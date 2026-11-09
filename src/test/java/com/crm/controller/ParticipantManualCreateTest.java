package com.crm.controller;

import com.crm.domain.AppUser;
import com.crm.domain.Company;
import com.crm.domain.CompanyBranch;
import com.crm.domain.Database;
import com.crm.domain.DatabaseDestination;
import com.crm.domain.DatabaseEmail;
import com.crm.domain.Role;
import com.crm.repository.CompanyRepository;
import com.crm.repository.DatabaseEmailRepository;
import com.crm.repository.DatabaseRepository;
import com.crm.repository.FlaggedIdentityRepository;
import com.crm.service.SecurityHelper;
import com.crm.service.SuspiciousIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ParticipantManualCreateTest {
    private final DatabaseController controller = new DatabaseController();
    private final DatabaseRepository databases = mock(DatabaseRepository.class);
    private final DatabaseEmailRepository emails = mock(DatabaseEmailRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);

    @BeforeEach void setup() {
        var security = mock(SecurityHelper.class);
        var user = AppUser.builder().id(7L).build();
        when(security.getAuthenticatedUser("test")).thenReturn(user);
        when(security.hasAnyRole(user, Role.ADMIN, Role.MANAGER)).thenReturn(true);
        when(databases.save(any())).thenAnswer(call -> {
            Database contact = call.getArgument(0);
            if (contact.getId() == null) contact.setId(42L);
            return contact;
        });
        ReflectionTestUtils.setField(controller, "databaseRepository", databases);
        ReflectionTestUtils.setField(controller, "databaseEmailRepository", emails);
        ReflectionTestUtils.setField(controller, "companyRepository", companies);
        ReflectionTestUtils.setField(controller, "flaggedIdentityRepository", mock(FlaggedIdentityRepository.class));
        ReflectionTestUtils.setField(controller, "securityHelper", security);
        ReflectionTestUtils.setField(controller, "suspiciousIdentityService", mock(SuspiciousIdentityService.class));
    }

    @Test void profileAndCampusOrEmployerEmailAreSavedTogetherWithServerOwnedEmailRows() {
        for (String address : List.of("student@gmail.com", "student@campus.ac.id", "student@employer.example")) {
            clearInvocations(databases);
            Database incoming = incoming("Student", address);
            incoming.setInstitutionName("Example Campus");
            incoming.setParticipantCategory("mahasiswa");
            incoming.getEmails().get(0).setDatabase(Database.builder().id(999L).build());
            var response = controller.createDatabase(incoming, null, null, false, "test");
            assertEquals(200, response.getStatusCode().value());
            Database saved = (Database) response.getBody();
            assertSame(incoming, saved);
            assertEquals(DatabaseDestination.PARTICIPANT, saved.getDatabaseDestination());
            assertNull(saved.getCompany());
            assertEquals("Example Campus", saved.getInstitutionName());
            assertEquals("mahasiswa", saved.getParticipantCategory());
            assertEquals(7L, saved.getCreatedByUserId());
            assertEquals("manual", saved.getEntryMethod());
            DatabaseEmail stored = saved.getEmails().get(0);
            assertSame(saved, stored.getDatabase());
            assertNull(stored.getId());
            assertEquals(address, stored.getEmail());
            assertEquals("personal", stored.getEmailType());
            assertEquals(false, stored.getIsCorporate());
            assertEquals(true, stored.getIsPrimary());
            verify(databases).save(saved);
        }
        verify(emails, never()).save(any());
        verify(companies, never()).save(any());
    }

    @Test void invalidOrDuplicatedPayloadAndStoredEmailIdsFailBeforeAnyProfileIsSaved() {
        Database missing = incoming("Student", "student@campus.ac.id"); missing.setEmails(List.of());
        Database unnamed = incoming(" ", "student@campus.ac.id");
        Database invalid = incoming("Student", "broken-email");
        Database duplicate = incoming("Student", "same@gmail.com");
        duplicate.setEmails(List.of(email("same@gmail.com"), email(" SAME@GMAIL.COM ")));
        Database storedId = incoming("Student", "student@campus.ac.id"); storedId.getEmails().get(0).setId(88L);
        for (Database incoming : List.of(missing, invalid, duplicate, storedId)) {
            assertEquals(400, controller.createDatabase(incoming, null, null, false, "test").getStatusCode().value());
        }
        verify(databases, never()).save(any());
        verify(emails, never()).save(any());
    }

    @Test void anotherNameOrAmbiguousOrInactiveOwnerCannotLeaveAnOrphanProfile() {
        Database owner = Database.builder().id(1L).databaseDestination(DatabaseDestination.PARTICIPANT).firstName("Other Person").isActive(true).build();
        DatabaseEmail known = email("known@campus.ac.id"); known.setDatabase(owner);
        when(emails.findAllByEmailIgnoreCase("known@campus.ac.id")).thenReturn(List.of(known));
        assertEquals(400, controller.createDatabase(incoming("Student", "known@campus.ac.id"), null, null, false, "test").getStatusCode().value());
        assertEquals(DatabaseDestination.PARTICIPANT, owner.getDatabaseDestination());

        Database second = Database.builder().id(2L).databaseDestination(DatabaseDestination.PARTICIPANT).firstName("Student").isActive(true).build();
        DatabaseEmail otherKnown = email("known@campus.ac.id"); otherKnown.setDatabase(second);
        when(emails.findAllByEmailIgnoreCase("known@campus.ac.id")).thenReturn(List.of(known, otherKnown));
        assertEquals(400, controller.createDatabase(incoming("Student", "known@campus.ac.id"), null, null, false, "test").getStatusCode().value());
        owner.setFirstName("Student"); owner.setIsActive(false);
        when(emails.findAllByEmailIgnoreCase("known@campus.ac.id")).thenReturn(List.of(known));
        assertEquals(400, controller.createDatabase(incoming("Student", "known@campus.ac.id"), null, null, false, "test").getStatusCode().value());
        assertEquals(false, owner.getIsActive());
        verify(databases, never()).save(any());
    }

    @Test void samePersonCreatesAnIndependentParticipantEvenWithCompanyEmailAndPhoneAlreadyStored() {
        Company company = Company.builder().id(11L).name("Existing employer").build();
        CompanyBranch branch = CompanyBranch.builder().id(12L).companyId(11L).name("Existing office").build();
        Database existing = Database.builder().id(8L).firstName("Student").company(company).branch(branch)
                .databaseDestination(DatabaseDestination.COMPANY).createdByUserId(99L).entryMethod("ems_sync")
                .mobilePhone("081200001111").isActive(true).build();
        DatabaseEmail old = email("known@employer.example"); old.setId(44L); old.setEmailType("personal");
        old.setIsPrimary(true); old.setDatabase(existing); existing.setEmails(List.of(old));
        when(emails.findAllByEmailIgnoreCase("known@employer.example")).thenReturn(List.of(old));
        when(databases.findAll()).thenReturn(List.of(existing));
        Database request = incoming("Student", "known@employer.example"); request.setMobilePhone("081200001111");
        request.setInstitutionName("Example Campus"); request.setParticipantCategory("mahasiswa");
        var response = controller.createDatabase(request, null, null, false, "test");
        assertEquals(200, response.getStatusCode().value()); assertSame(request, response.getBody());
        assertEquals(42L, request.getId()); assertNotEquals(existing.getId(), request.getId());
        assertEquals(DatabaseDestination.PARTICIPANT, request.getDatabaseDestination()); assertNull(request.getCompany()); assertNull(request.getBranch());
        assertEquals(DatabaseDestination.COMPANY, existing.getDatabaseDestination());
        assertSame(company, existing.getCompany()); assertSame(branch, existing.getBranch());
        assertEquals(99L, existing.getCreatedByUserId()); assertEquals("ems_sync", existing.getEntryMethod());
        assertEquals("081200001111", existing.getMobilePhone()); assertNull(existing.getInstitutionName());
        assertEquals(1, existing.getEmails().size()); assertSame(old, existing.getEmails().get(0));
        assertSame(existing, old.getDatabase()); assertEquals("personal", old.getEmailType());
        assertNotSame(old, request.getEmails().get(0)); assertSame(request, request.getEmails().get(0).getDatabase());
        verify(databases).save(request); verify(databases, never()).save(existing); verify(companies, never()).save(any());
    }

    @Test void companyAndBothCreateCannotBypassEmailOwnershipRulesThroughBodyCascade() {
        for (DatabaseDestination destination : List.of(DatabaseDestination.COMPANY, DatabaseDestination.BOTH)) {
            Database body = Database.builder().databaseDestination(destination).firstName("Company")
                    .emails(List.of(email("company@gmail.com"))).build();
            body.getEmails().get(0).setIsCorporate(true);
            assertEquals(400, controller.createDatabase(body, null, null, false, "test").getStatusCode().value());
            body.getEmails().get(0).setEmail("company@institution.example");
            body.getEmails().get(0).setId(77L);
            assertEquals(400, controller.createDatabase(body, null, null, false, "test").getStatusCode().value());
        }
        verify(databases, never()).save(any());
        verify(emails, never()).save(any());
    }

    @Test void unnamedOrPhoneOnlyParticipantsSaveWithoutFakeNamesAndReuseSecondaryPhone() {
        Database blankName = incoming("", "nameless@example.invalid");
        assertEquals(200, controller.createDatabase(blankName, null, null, false, "test").getStatusCode().value());
        assertEquals("", blankName.getFirstName());
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(controller, "participantIncomplete", blankName));
        Database phoneOnly = Database.builder().databaseDestination(DatabaseDestination.PARTICIPANT).firstName("Phone Person")
                .mobilePhone("081200001111, 081300002222").build();
        assertEquals(200, controller.createDatabase(phoneOnly, null, null, false, "test").getStatusCode().value());
        assertEquals("081200001111, 081300002222", phoneOnly.getMobilePhone());
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(controller, "participantIncomplete", phoneOnly));
        when(databases.findAll()).thenReturn(List.of(phoneOnly));
        Database repeat = Database.builder().databaseDestination(DatabaseDestination.PARTICIPANT).mobilePhone("+6281300002222").build();
        assertSame(phoneOnly, controller.createDatabase(repeat, null, null, false, "test").getBody());
        assertEquals("Phone Person", phoneOnly.getFirstName());
        assertEquals("081200001111, 081300002222", phoneOnly.getMobilePhone());
        Database invalid = Database.builder().databaseDestination(DatabaseDestination.PARTICIPANT).mobilePhone("081200001111, call 123").build();
        assertEquals(400, controller.createDatabase(invalid, null, null, false, "test").getStatusCode().value());
    }

    @Test void participantCannotLinkCompanyOrChangeDestinationThroughCreateOrEdit() {
        Database participant = incoming("Student", "student@campus.ac.id");
        assertEquals(400, controller.createDatabase(participant, 11L, null, false, "test").getStatusCode().value());
        participant.setCompany(Company.builder().id(11L).build());
        assertEquals(400, controller.createDatabase(participant, null, null, false, "test").getStatusCode().value());
        participant.setCompany(null); participant.setId(8L); when(databases.findById(8L)).thenReturn(java.util.Optional.of(participant));
        assertEquals(409, controller.updateDatabase(8L, Database.builder().databaseDestination(DatabaseDestination.COMPANY).firstName("Wrong").build(), null,null,false,"test").getStatusCode().value());
        assertEquals("Student", participant.getFirstName()); assertEquals(DatabaseDestination.PARTICIPANT, participant.getDatabaseDestination());
        assertEquals(400, controller.updateDatabase(8L, Database.builder().company(Company.builder().id(11L).build()).build(), null,null,false,"test").getStatusCode().value());
        assertEquals(200, controller.updateDatabase(8L, Database.builder().firstName("Edited").build(), null,null,true,"test").getStatusCode().value());
        assertNull(participant.getCompany()); assertNull(participant.getBranch());
        Database both = Database.builder().databaseDestination(DatabaseDestination.BOTH).firstName("Invalid").build();
        assertEquals(400, controller.createDatabase(both,null,null,false,"test").getStatusCode().value());
    }

    private static Database incoming(String name, String address) {
        return Database.builder().databaseDestination(DatabaseDestination.PARTICIPANT).firstName(name)
                .emails(List.of(email(address))).build();
    }

    private static DatabaseEmail email(String address) {
        return DatabaseEmail.builder().email(address).emailType("personal").isCorporate(false).isVerified(true).build();
    }
}
