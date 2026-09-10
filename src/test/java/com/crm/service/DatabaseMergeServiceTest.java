package com.crm.service;

import com.crm.domain.AppUser;
import com.crm.domain.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only a disposable PostgreSQL database named merge_test; does not start the CRM application. */
@EnabledIfEnvironmentVariable(named="CRM_MERGE_TEST_URL", matches="jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/merge_test")
class DatabaseMergeServiceTest {
    JdbcTemplate db;
    DatabaseMergeService service;
    TransactionTemplate tx;
    final AppUser actor = AppUser.builder().id(19L).roles(Set.of(Role.ADMIN)).build();

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(System.getenv("CRM_MERGE_TEST_URL"), "merge_test", "merge_test_local_only");
        db = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        service = new DatabaseMergeService(db,new ObjectMapper());
        assertEquals("merge_test",db.queryForObject("select current_database()",String.class));
        db.execute("DROP SCHEMA IF EXISTS merge_test_case CASCADE");
        db.execute("CREATE SCHEMA merge_test_case");
        Properties properties = new Properties();properties.setProperty("currentSchema","merge_test_case");ds.setConnectionProperties(properties);
        db.execute("CREATE TABLE companies(id bigint primary key,name text)");
        db.execute("CREATE TABLE databases(id bigint primary key,company_id bigint references companies(id), salutation text,first_name text,last_name text,position_level varchar(100),speciality_division text,job_title text,mobile_phone text,normalized_phone text,linkedin_url text,database_type text,source text,created_by_user_id bigint,entry_method text,is_active boolean not null default true,created_at timestamp not null default current_timestamp,updated_at timestamp not null default current_timestamp)");
        db.execute("CREATE TABLE database_emails(id bigserial primary key,database_id bigint not null references databases(id),email text not null,email_type text,is_primary boolean not null default false,is_verified boolean not null default false,is_corporate boolean not null default false,domain text,created_at timestamp not null default current_timestamp)");
        db.execute("CREATE UNIQUE INDEX unique_database_email_lower ON database_emails(database_id,lower(email))");
        db.execute("CREATE UNIQUE INDEX unique_personal_email_lower ON database_emails(lower(email)) WHERE lower(coalesce(email_type,''))='personal'");
        db.execute("CREATE TABLE event_participants(id bigint primary key,event_id bigint not null,database_id bigint not null references databases(id),notes text,unique(event_id,database_id))");
        db.execute("CREATE TABLE event_participant_activities(id bigint primary key,event_participant_id bigint not null references event_participants(id),notes text)");
        db.execute("CREATE TABLE flagged_identities(id bigint primary key,database_id bigint references databases(id),status text,evidence_notes text)");
        db.execute("CREATE TABLE removal_requests(id bigint primary key,database_id bigint references databases(id),status text,notes text)");
        db.execute("CREATE TABLE database_merge_audits(id bigserial primary key,actor_id bigint not null,target_id bigint not null,source_id bigint not null,preview_token varchar(64) unique not null,before_snapshot jsonb not null,result jsonb not null,created_at timestamp default current_timestamp)");
        db.update("INSERT INTO companies VALUES(1,'PT Example')");
        db.update("INSERT INTO databases(id,company_id,first_name,last_name,mobile_phone,position_level) VALUES (1,1,'Budi','Santoso','08123456789','unknown'),(2,1,'Budi','Santoso','628123456789','Staff')");
    }
    DatabaseMergeService.MergeRequest request(Map<String,String> choices,Map<String,String> types,String token) {
        return new DatabaseMergeService.MergeRequest(1L,2L,choices,types,token);
    }
    Map<String,Object> run(DatabaseMergeService.MergeRequest req, boolean apply) { return tx.execute(s->service.merge(req,actor,apply)); }
    Map<String,Object> preview() { return run(request(Map.of(),Map.of(),null),false); }
    Map<String,Object> apply(Map<String,Object> preview) { return run(request(Map.of(),Map.of(),(String)preview.get("previewToken")),true); }
    long count(String table) { return db.queryForObject("SELECT count(*) FROM "+table,Long.class); }

    @Test void previewMergeBackupAndIdempotentRetryPreserveAttribution() {
        db.update("UPDATE databases SET created_by_user_id=19,entry_method='excel_import' WHERE id=1");
        Object created=db.queryForObject("SELECT created_at FROM databases WHERE id=1",Object.class);
        var p=preview(); assertEquals(2,count("databases"));assertEquals(0,count("database_merge_audits"));
        assertEquals("merged",apply(p).get("status"));assertEquals(1,count("databases"));
        assertEquals("Staff",db.queryForObject("SELECT position_level FROM databases",String.class));
        assertEquals(19,db.queryForObject("SELECT created_by_user_id FROM databases",Integer.class));
        assertEquals(created,db.queryForObject("SELECT created_at FROM databases",Object.class));
        assertEquals("merged",apply(p).get("status"));assertEquals(1,count("database_merge_audits"));
        assertEquals(2,db.queryForObject("SELECT jsonb_array_length(before_snapshot->'contacts') FROM database_merge_audits",Integer.class));
    }
    @Test void preservesEventsActivitiesFlagsAndOptOut() {
        db.update("INSERT INTO event_participants VALUES(10,5,2,'attendance history')");
        db.update("INSERT INTO event_participant_activities VALUES(20,10,'called twice')");
        db.update("INSERT INTO flagged_identities VALUES(30,2,'confirmed','keep evidence')");
        db.update("INSERT INTO removal_requests VALUES(40,2,'approved','opt out')");
        apply(preview());
        for(String table:List.of("event_participants","flagged_identities","removal_requests")) assertEquals(1,db.queryForObject("SELECT database_id FROM "+table,Integer.class));
        assertEquals("called twice",db.queryForObject("SELECT notes FROM event_participant_activities",String.class));
        assertEquals(false,db.queryForObject("SELECT is_active FROM databases",Boolean.class));
    }
    @Test void splitsAndUnionsEmailsPreservingVerificationAndPersonalType() {
        db.update("INSERT INTO database_emails(database_id,email,email_type,is_corporate) VALUES(1,'budi@company.id, budi@gmail.com','company',true),(2,'budi@gmail.com','personal',false)");
        db.update("UPDATE database_emails SET is_verified=true WHERE database_id=2");
        apply(preview());assertEquals(2,count("database_emails"));
        assertEquals("personal",db.queryForObject("SELECT email_type FROM database_emails WHERE email='budi@gmail.com'",String.class));
        assertEquals(true,db.queryForObject("SELECT is_verified FROM database_emails WHERE email='budi@gmail.com'",Boolean.class));
        assertEquals(false,db.queryForObject("SELECT is_corporate FROM database_emails WHERE email='budi@gmail.com'",Boolean.class));
    }
    @Test void rejectsThirdPartyPersonalEmailInLegacyCompanyField() {
        db.update("INSERT INTO databases(id,first_name) VALUES(3,'Someone')");
        db.update("INSERT INTO database_emails(database_id,email,email_type) VALUES(1,'budi@gmail.com','personal'),(3,'other@company.id; budi@gmail.com','company')");
        assertEquals(409,assertThrows(ResponseStatusException.class,this::preview).getStatusCode().value());assertEquals(3,count("databases"));
    }
    @Test void rejectsStaleContactAndRelationPreview() {
        var p=preview();db.update("UPDATE databases SET job_title='Changed' WHERE id=1");
        assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(p)).getStatusCode().value());
        var second=preview();db.update("INSERT INTO event_participants VALUES(10,5,2,'new registration')");
        assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(second)).getStatusCode().value());assertEquals(2,count("databases"));
    }
    @Test void fieldConflictsRequireChoiceAndFreshPreview() {
        db.update("UPDATE databases SET job_title=CASE WHEN id=1 THEN 'Head' ELSE 'Staff' END");
        var p=preview();assertFalse(((List<?>)p.get("conflicts")).isEmpty());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(p)).getStatusCode().value());
        var resolved=run(request(Map.of("job_title","target"),Map.of(),null),false);
        run(request(Map.of("job_title","target"),Map.of(),(String)resolved.get("previewToken")),true);
        assertEquals("Head",db.queryForObject("SELECT job_title FROM databases",String.class));
    }
    @Test void refusesEventCollision() {
        db.update("INSERT INTO event_participants VALUES(10,5,1,'one'),(11,5,2,'two')");
        assertEquals(409,assertThrows(ResponseStatusException.class,this::preview).getStatusCode().value());assertEquals(2,count("event_participants"));
    }
    @Test void refusesCreditedSourceAndDifferentPeople() {
        db.update("UPDATE databases SET created_by_user_id=19 WHERE id=2");
        assertEquals(409,assertThrows(ResponseStatusException.class,this::preview).getStatusCode().value());
        db.update("UPDATE databases SET created_by_user_id=null,first_name='Other' WHERE id=2");
        assertEquals(409,assertThrows(ResponseStatusException.class,this::preview).getStatusCode().value());
    }
    @Test void refusesDifferentCompaniesAndUnknownDependencies() {
        db.update("INSERT INTO companies VALUES(2,'Different Business')");db.update("UPDATE databases SET company_id=2 WHERE id=2");
        assertEquals(409,assertThrows(ResponseStatusException.class,this::preview).getStatusCode().value());
        db.update("UPDATE databases SET company_id=1 WHERE id=2");
        db.execute("CREATE TABLE future_relation(id bigint primary key,database_id bigint references databases(id) ON DELETE CASCADE)");
        assertEquals(409,assertThrows(ResponseStatusException.class,this::preview).getStatusCode().value());
    }
    @Test void auditFailureRollsBackAllWrites() {
        db.update("INSERT INTO database_emails(database_id,email,email_type) VALUES(2,'budi@gmail.com','personal')");
        db.update("INSERT INTO event_participants VALUES(10,5,2,'keep')");var p=preview();
        db.execute("ALTER TABLE database_merge_audits ADD CONSTRAINT simulate_failure CHECK(actor_id < 0)");
        assertThrows(Exception.class,()->apply(p));assertEquals(2,count("databases"));
        assertEquals(2,db.queryForObject("SELECT database_id FROM database_emails",Integer.class));
        assertEquals(2,db.queryForObject("SELECT database_id FROM event_participants",Integer.class));assertEquals(0,count("database_merge_audits"));
    }
    @Test void invalidRequestCannotWrite() {
        assertThrows(ResponseStatusException.class,()->run(new DatabaseMergeService.MergeRequest(1L,1L,null,null,null),false));
        assertThrows(ResponseStatusException.class,()->run(request(Map.of("created_by_user_id","source"),Map.of(),null),false));
        assertThrows(ResponseStatusException.class,()->run(request(Map.of(),Map.of("not-present@gmail.com","personal"),null),false));
        assertThrows(ResponseStatusException.class,()->run(request(Map.of(),Map.of(),null),true));assertEquals(2,count("databases"));
    }
    @Test void newLiquibaseMigrationCreatesDurableJsonBackups() throws Exception {
        db.execute("DROP TABLE database_merge_audits");
        var migration = new liquibase.integration.spring.SpringLiquibase();
        migration.setDataSource(db.getDataSource());
        migration.setDefaultSchema("merge_test_case");
        migration.setChangeLog("classpath:db/changelog/changes/changelog-crm-database-merge-audits.yml");
        migration.afterPropertiesSet();
        apply(preview());assertEquals(1,count("database_merge_audits"));
    }
    @Test void sharedOfficePhoneWithoutSameNameCannotMerge() {
        db.update("UPDATE databases SET first_name='Different' WHERE id=2");
        assertThrows(ResponseStatusException.class,this::preview);
    }
    @Test void duplicateCompanyNamesRequireExplicitSurvivingCompany() {
        db.update("INSERT INTO companies VALUES(2,'Example, PT')");db.update("UPDATE databases SET company_id=2 WHERE id=2");
        var p=preview();assertFalse(((List<?>)p.get("conflicts")).isEmpty());
        assertThrows(ResponseStatusException.class,()->apply(p));
        var resolved=run(request(Map.of("company_id","target"),Map.of(),null),false);
        run(request(Map.of("company_id","target"),Map.of(),(String)resolved.get("previewToken")),true);
        assertEquals(2,count("companies"));assertEquals(1,db.queryForObject("SELECT company_id FROM databases",Integer.class));
    }
    @Test void concurrentRetriesCommitOnlyOneMerge() throws Exception {
        var p=preview();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Map<String,Object>> work=()->{start.await();return apply(p);};
            var one=pool.submit(work);var two=pool.submit(work);start.countDown();
            assertEquals("merged",one.get(15,java.util.concurrent.TimeUnit.SECONDS).get("status"));
            assertEquals("merged",two.get(15,java.util.concurrent.TimeUnit.SECONDS).get("status"));
            assertEquals(1,count("databases"));assertEquals(1,count("database_merge_audits"));
        } finally { pool.shutdownNow(); }
    }
}
