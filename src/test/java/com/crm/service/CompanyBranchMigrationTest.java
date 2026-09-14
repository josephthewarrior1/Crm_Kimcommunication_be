package com.crm.service;

import liquibase.changelog.ChangeLogParameters;
import liquibase.database.core.PostgresDatabase;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompanyBranchMigrationTest {
    @Test void masterIncludesAdditiveNullableBranchMigrationAndUniqueCompanyNameIndex() throws Exception {
        String master = "db/changelog/db.changelog-master.yaml";
        try (var resources = new ClassLoaderResourceAccessor()) {
            var log = ChangeLogParserFactory.getInstance().getParser(master, resources)
                    .parse(master, new ChangeLogParameters(), resources);
            var changes = log.getChangeSets().stream().filter(change -> change.getId().equals("crm-company-branches")).toList();
            assertEquals(1, changes.size());
            var database = new PostgresDatabase();
            StringBuilder sql = new StringBuilder();
            for (var change : changes.get(0).getChanges()) {
                for (var statement : change.generateStatements(database)) {
                    for (var generated : SqlGeneratorFactory.getInstance().generateSql(statement, database)) {
                        sql.append(generated.toSql().toLowerCase()).append(';');
                    }
                }
            }
            String migration = sql.toString();
            assertTrue(migration.contains("create table company_branches"));
            assertTrue(migration.contains("company_id bigint not null"));
            assertTrue(migration.contains("lower(trim(name))"));
            assertTrue(migration.contains("alter table databases add branch_id bigint"));
            assertFalse(migration.contains("branch_id bigint not null"));
            assertTrue(migration.contains("references company_branches (id)"));
            assertFalse(migration.contains("delete "));
            assertFalse(migration.contains("update "));
        }
    }
}
