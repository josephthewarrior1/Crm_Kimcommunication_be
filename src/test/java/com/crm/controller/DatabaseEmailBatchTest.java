package com.crm.controller;

import com.crm.domain.AppUser;
import com.crm.domain.Company;
import com.crm.domain.Database;
import com.crm.domain.DatabaseDestination;
import com.crm.domain.DatabaseEmail;
import com.crm.domain.Group;
import com.crm.domain.PositionLevel;
import com.crm.domain.Role;
import com.crm.repository.CompanyRepository;
import com.crm.repository.DatabaseEmailRepository;
import com.crm.repository.DatabaseRepository;
import com.crm.repository.FlaggedIdentityRepository;
import com.crm.service.SecurityHelper;
import com.crm.service.SuspiciousIdentityService;
import com.crm.util.ContactEmailCategory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatabaseEmailBatchTest {
    private final DatabaseController controller = new DatabaseController();
    private final DatabaseRepository databases = mock(DatabaseRepository.class);
    private final DatabaseEmailRepository emails = mock(DatabaseEmailRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final SecurityHelper security = mock(SecurityHelper.class);
    private final SuspiciousIdentityService identities = mock(SuspiciousIdentityService.class);
    private final AppUser user = AppUser.builder().id(1L).build();
    private final List<DatabaseEmail> stored = new ArrayList<>();
    private final Database profile = company(42);

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(controller, "databaseRepository", databases);
        ReflectionTestUtils.setField(controller, "databaseEmailRepository", emails);
        ReflectionTestUtils.setField(controller, "companyRepository", companies);
        ReflectionTestUtils.setField(controller, "flaggedIdentityRepository", mock(FlaggedIdentityRepository.class));
        ReflectionTestUtils.setField(controller, "securityHelper", security);
        ReflectionTestUtils.setField(controller, "suspiciousIdentityService", identities);
        when(security.getAuthenticatedUser("test")).thenReturn(user);
        when(security.hasAnyRole(user, Role.ADMIN, Role.MANAGER)).thenReturn(true);
        when(databases.findById(42L)).thenReturn(Optional.of(profile));
        when(companies.findById(10L)).thenReturn(Optional.of(profile.getCompany()));
        when(databases.save(any())).thenAnswer(call -> call.getArgument(0));
        when(emails.findAllByEmailIgnoreCase(anyString())).thenAnswer(call -> stored.stream()
                .filter(email -> email.getEmail().equalsIgnoreCase(call.getArgument(0))).toList());
        when(emails.findById(anyLong())).thenAnswer(call -> stored.stream()
                .filter(email -> email.getId().equals(call.getArgument(0))).findFirst());
        AtomicLong ids = new AtomicLong(100);
        when(emails.save(any())).thenAnswer(call -> {
            DatabaseEmail email = call.getArgument(0);
            if (email.getId() == null) { email.setId(ids.incrementAndGet()); stored.add(email); }
            return email;
        });
        profile.setEmails(new ArrayList<>());
    }

    @Test void splitDedupAndRepeatDoNotChangeUnrepresentedEmailsOrDestinations() {
        DatabaseEmail personal = retained(profile, 9, "private@gmail.com", "personal", false);
        var request = request(entry(null, " A@company.example ; a@company.example\u00a0b@company.example\nc@company.example ", "company", true, true, false));
        Map<?, ?> result = body(controller.saveDatabaseEmails(42L, request, "test"));
        assertEquals(3, result.get("createdCount"));
        assertEquals(0, result.get("updatedCount"));
        assertEquals(List.of("a@company.example", "b@company.example", "c@company.example"),
                ((List<DatabaseEmail>) result.get("emails")).stream().map(DatabaseEmail::getEmail).toList());
        assertEquals(4, profile.getEmails().size());
        assertEquals(1, profile.getEmails().stream().filter(email -> email.getIsPrimary()).count());
        assertEquals("private@gmail.com", personal.getEmail());
        assertEquals("personal", personal.getEmailType());
        assertFalse(personal.getIsCorporate());
        clearInvocations(emails, identities);
        result = body(controller.saveDatabaseEmails(42L, request, "test"));
        assertEquals(0, result.get("createdCount"));
        assertEquals(3, result.get("existingCount"));
        verify(emails, never()).save(any());
        verify(identities, never()).checkAndFlagDatabase(any());
        assertEquals(DatabaseDestination.COMPANY, profile.getDatabaseDestination());
    }

    @Test void legacyJoinedRowsRepairInPlaceAndOnlyAddressAndDerivedDomainChange() throws Exception {
        String joined = "first@institution.example second@institution.example";
        DatabaseEmail first = retained(profile, 11, joined, "company", true);
        first.setIsPrimary(true); first.setIsVerified(true); first.setDomain("old malformed domain");
        DatabaseEmail second = retained(profile, 12, joined.replace(" ", "  "), "company", true);
        DatabaseEmail personal = retained(profile, 13, "private@gmail.com", "personal", false);
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(controller, "isIncomplete", profile));
        String beforePersonal = emailState(personal);
        DatabaseController.EmailBatchRequest supplied = new ObjectMapper().readValue("""
                {"emails":[{"id":11,"email":"first@institution.example"},
                {"id":12,"email":"second@institution.example"}]}
                """, DatabaseController.EmailBatchRequest.class);
        assertNull(supplied.emails().get(0).isCorporate());
        Map<?, ?> result = body(controller.saveDatabaseEmails(42L, supplied, "test"));
        assertEquals(2, result.get("updatedCount")); assertEquals(0, result.get("createdCount"));
        assertEquals(List.of(11L,12L,13L), profile.getEmails().stream().map(DatabaseEmail::getId).toList());
        assertTrue(first.getIsCorporate()); assertTrue(first.getIsPrimary()); assertTrue(first.getIsVerified());
        assertFalse(second.getIsPrimary()); assertFalse(second.getIsVerified());
        assertEquals("institution.example", first.getDomain());
        assertEquals(beforePersonal, emailState(personal));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(controller, "isIncomplete", profile));
        verify(databases, never()).save(any()); verify(emails, never()).deleteById(anyLong());
    }

    @Test void anExistingEntryCanExpandWithoutReassigningItsPrimaryFlagToEveryToken() {
        DatabaseEmail old = retained(profile, 11, "old@company.example", "company", true);
        old.setIsPrimary(true); old.setIsVerified(true);
        Map<?, ?> result = body(controller.saveDatabaseEmails(42L,
                request(entry(11L, "new@company.example;second@company.example", null,null,null,null)), "test"));
        assertEquals(1, result.get("updatedCount")); assertEquals(1, result.get("createdCount"));
        assertEquals("new@company.example", old.getEmail()); assertEquals(11L, old.getId());
        DatabaseEmail added = profile.getEmails().get(1);
        assertFalse(added.getIsPrimary()); assertTrue(added.getIsVerified()); assertTrue(added.getIsCorporate());
        assertEquals("company", added.getEmailType());
    }

    @Test void laterMalformedOrConflictingEntriesCannotMutateEarlierManagedRows() {
        DatabaseEmail old = retained(profile,11,"old@company.example","company",true);
        old.setIsPrimary(true); old.setIsVerified(true); old.setDomain("company.example");
        Database other = company(50);
        retained(other,20,"owned@gmail.com","personal",false);
        for (var last : List.of(entry(null,"not-an-email","company",true,false,false),
                entry(null,"owned@gmail.com","personal",false,false,false),
                entry(null,"public@gmail.com","company",true,false,false))) {
            var response = controller.saveDatabaseEmails(42L,
                    request(entry(11L,"changed@company.example",null,null,null,null),last), "test");
            assertEquals(400,response.getStatusCode().value());
            assertEquals("old@company.example",old.getEmail());
            assertEquals("company.example",old.getDomain());
            assertTrue(old.getIsPrimary()); assertTrue(old.getIsCorporate()); assertTrue(old.getIsVerified());
        }
        verify(emails,never()).save(any()); verify(databases,never()).save(any());
        verifyNoInteractions(identities);
    }

    @Test void scopedOwnershipSharingPermissionsAndExplicitIdsStayGuarded() throws Exception {
        retained(company(50),10,"shared@company.example","company",true);
        assertEquals(200,controller.saveDatabaseEmails(42L,
                request(entry(null,"shared@company.example","company",true,false,false)),"test").getStatusCode().value());
        clearInvocations(emails);
        assertEquals(400,controller.saveDatabaseEmails(42L,
                request(entry(10L,"changed@company.example",null,null,null,null)),"test").getStatusCode().value());
        assertEquals(400,controller.saveDatabaseEmails(42L,
                request(new DatabaseController.EmailBatchEntry(null,"test@company.example","company",true,false,false,Map.of("id",50))),"test").getStatusCode().value());
        assertEquals(401,controller.saveDatabaseEmails(42L,request(entry(null,"a@company.example","company",true,false,false)),"missing").getStatusCode().value());
        when(security.hasAnyRole(user,Role.ADMIN,Role.MANAGER)).thenReturn(false);
        assertEquals(403,controller.saveDatabaseEmails(42L,request(entry(null,"a@company.example","company",true,false,false)),"test").getStatusCode().value());
        verify(emails,never()).save(any());
        assertNotNull(DatabaseController.class.getMethod("saveDatabaseEmails",Long.class,
                DatabaseController.EmailBatchRequest.class,String.class).getAnnotation(Transactional.class));
    }

    @Test void participantDestinationAcceptsAllDomainsButCannotBorrowAnotherParticipantEmail() {
        profile.setDatabaseDestination(DatabaseDestination.PARTICIPANT);
        Database companyOwner = company(50);
        retained(companyOwner,10,"shared@company.example","company",true);
        assertEquals(200,controller.saveDatabaseEmails(42L,
                request(entry(null,"shared@company.example;student@campus.ac.id;private@gmail.com","personal",false,false,false)),"test").getStatusCode().value());
        assertEquals(DatabaseDestination.COMPANY,companyOwner.getDatabaseDestination());
        Database another = Database.builder().id(51L).databaseDestination(DatabaseDestination.PARTICIPANT).build();
        retained(another,20,"owned@campus.ac.id","personal",false);
        clearInvocations(emails);
        assertEquals(400,controller.saveDatabaseEmails(42L,
                request(entry(null,"fresh@campus.ac.id;owned@campus.ac.id","personal",false,false,false)),"test").getStatusCode().value());
        verify(emails,never()).save(any());
    }

    @Test void onlyGenuineCompanyEmailOwnersCanShareAnAddressAcrossCompanyProfiles() {
        Database owner=company(50);
        DatabaseEmail privateWork=retained(owner,10,"private@company.example","personal",false);
        retained(owner,11,"other@company.example","other",false);
        retained(owner,12,"legacy@gmail.com","company",true);
        for(var address:List.of(entry(null,"private@company.example","company",true,false,false),
                entry(null,"other@company.example","other",false,false,false),
                entry(null,"legacy@gmail.com","other",false,false,false))) {
            assertEquals(400,controller.saveDatabaseEmails(42L,
                    request(entry(null,"fresh@company.example","company",true,false,false),address),"test").getStatusCode().value());
            Database incoming=company(0);incoming.setId(null);
            incoming.setEmails(List.of(DatabaseEmail.builder().email("fresh@company.example").emailType("company").isCorporate(true).build(),
                    DatabaseEmail.builder().email(address.email()).emailType(address.emailType()).isCorporate(address.isCorporate()).build()));
            assertEquals(400,controller.createDatabase(incoming,null,null,false,"test").getStatusCode().value());
        }
        // A personal label remains personal even if a legacy corporate flag is contradictory.
        privateWork.setIsCorporate(true);
        assertEquals(400,controller.saveDatabaseEmails(42L,
                request(entry(null,"private@company.example","company",true,false,false)),"test").getStatusCode().value());
        assertEquals(0,profile.getEmails().size());
        assertEquals(3,owner.getEmails().size());
        verify(databases,never()).save(any());verify(emails,never()).save(any());verifyNoInteractions(identities);
    }

    @Test void companyEmbeddedCreateValidatesAllEmailsBeforeItsSingleProfileSave() {
        Database incoming = company(0); incoming.setId(null);
        incoming.setEmails(List.of(DatabaseEmail.builder().email("First@company.example, second@company.example;FIRST@company.example")
                        .emailType("company").isCorporate(true).isPrimary(true).isVerified(true).build(),
                DatabaseEmail.builder().email("private@gmail.com").emailType("personal").isCorporate(false).build()));
        var response = controller.createDatabase(incoming,null,null,false,"test");
        assertEquals(200,response.getStatusCode().value());
        assertEquals(3,incoming.getEmails().size());
        assertEquals(List.of("first@company.example","second@company.example","private@gmail.com"),
                incoming.getEmails().stream().map(DatabaseEmail::getEmail).toList());
        assertTrue(incoming.getEmails().get(0).getIsPrimary()); assertFalse(incoming.getEmails().get(1).getIsPrimary());
        for(DatabaseEmail email:incoming.getEmails()) assertSame(incoming,email.getDatabase());
        verify(databases).save(incoming); verify(emails,never()).save(any());
    }

    @Test void embeddedCreateCannotSmuggleIdsOwnersOrLeaveAProfileForInvalidEmail() {
        DatabaseEmail storedId=DatabaseEmail.builder().id(88L).email("valid@company.example").emailType("company").build();
        DatabaseEmail owner=DatabaseEmail.builder().database(company(99)).email("valid@company.example").emailType("company").build();
        for(DatabaseEmail last:List.of(storedId,owner,DatabaseEmail.builder().email("invalid").build(),
                DatabaseEmail.builder().email("private@gmail.com").emailType("company").isCorporate(true).build())) {
            Database incoming=company(0);incoming.setId(null);
            incoming.setEmails(List.of(DatabaseEmail.builder().email("valid@company.example").emailType("company").isCorporate(true).build(),last));
            assertEquals(400,controller.createDatabase(incoming,null,null,false,"test").getStatusCode().value());
        }
        DatabaseEmail unknown=DatabaseEmail.builder().id(90L).email("unknown@gmail.com").build(); stored.add(unknown);
        Database incoming=company(0);incoming.setId(null);
        incoming.setEmails(List.of(DatabaseEmail.builder().email("unknown@gmail.com").emailType("personal").build()));
        assertEquals(400,controller.createDatabase(incoming,null,null,false,"test").getStatusCode().value());
        verify(databases,never()).save(any()); verify(emails,never()).save(any());
    }

    @Test void participantEmbeddedCreateUsesSharedTokensAndRetainsItsIdentityRules() {
        Database incoming=Database.builder().firstName("Student").databaseDestination(DatabaseDestination.PARTICIPANT)
                .emails(List.of(DatabaseEmail.builder().email("one@campus.ac.id\u00a0two@gmail.com;ONE@CAMPUS.AC.ID").build())).build();
        Database suppliedOwner=company(99);
        incoming.getEmails().get(0).setDatabase(suppliedOwner);
        assertEquals(200,controller.createDatabase(incoming,null,null,false,"test").getStatusCode().value());
        assertEquals(2,incoming.getEmails().size());
        assertTrue(incoming.getEmails().stream().allMatch(email -> "personal".equals(email.getEmailType())&&!email.getIsCorporate()));
        assertTrue(incoming.getEmails().stream().allMatch(email -> email.getDatabase()==incoming));
        assertEquals(DatabaseDestination.COMPANY,suppliedOwner.getDatabaseDestination());
        assertEquals(List.of("one@campus.ac.id","two@gmail.com"),incoming.getEmails().stream().map(DatabaseEmail::getEmail).toList());
        assertEquals(List.of("one@campus.ac.id","two@gmail.com"),ExcelImportController.splitEmailTokens("ONE@campus.ac.id, two@gmail.com one@campus.ac.id"));
        assertTrue(ContactEmailCategory.isValidEmail(incoming.getEmails().get(0).getEmail()));
    }

    private DatabaseEmail retained(Database owner,long id,String address,String type,boolean corporate) {
        DatabaseEmail email=DatabaseEmail.builder().id(id).database(owner).email(address).emailType(type).isCorporate(corporate).build();
        stored.add(email);
        if(owner.getEmails()==null)owner.setEmails(new ArrayList<>());
        owner.getEmails().add(email);
        return email;
    }
    private static String emailState(DatabaseEmail email) {
        return List.of(email.getId(),email.getEmail(),email.getEmailType(),email.getIsCorporate(),
                email.getIsPrimary(),email.getIsVerified(),String.valueOf(email.getDomain()),String.valueOf(email.getCreatedAt())).toString();
    }
    private static DatabaseController.EmailBatchEntry entry(Long id,String email,String type,Boolean corporate,Boolean primary,Boolean verified) {
        return new DatabaseController.EmailBatchEntry(id,email,type,corporate,primary,verified,null);
    }
    private static DatabaseController.EmailBatchRequest request(DatabaseController.EmailBatchEntry... entries) {
        return new DatabaseController.EmailBatchRequest(List.of(entries));
    }
    private static Map<?,?> body(org.springframework.http.ResponseEntity<?> response) {
        assertEquals(200,response.getStatusCode().value(),String.valueOf(response.getBody()));return (Map<?,?>)response.getBody();
    }
    private static Database company(long id) {
        Company company=Company.builder().id(10L).name("Example Company").brandName("Example")
                .group(Group.builder().id(1L).name("Example Group").build()).address("Example Address")
                .officePhone("021123").city("Jakarta").industry("Technology").website("https://company.example").build();
        return Database.builder().id(id).isActive(true).databaseDestination(DatabaseDestination.COMPANY)
                .company(company).salutation("Mr").firstName("Corporate").lastName("Member")
                .positionLevel(PositionLevel.STAFF).jobTitle("Officer").mobilePhone("08123456789").build();
    }
}
