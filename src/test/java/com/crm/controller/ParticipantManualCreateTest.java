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
        for (Database incoming : List.of(missing, unnamed, invalid, duplicate, storedId)) {
            assertEquals(400, controller.createDatabase(incoming, null, null, false, "test").getStatusCode().value());
        }
        verify(databases, never()).save(any());
        verify(emails, never()).save(any());
    }

    @Test void anotherNameOrAmbiguousOrInactiveOwnerCannotLeaveAnOrphanProfile() {
        Database owner = Database.builder().id(1L).firstName("Other Person").isActive(true).build();
        DatabaseEmail known = email("known@campus.ac.id"); known.setDatabase(owner);
        when(emails.findAllByEmailIgnoreCase("known@campus.ac.id")).thenReturn(List.of(known));
        assertEquals(400, controller.createDatabase(incoming("Student", "known@campus.ac.id"), null, null, false, "test").getStatusCode().value());
        assertNull(owner.getDatabaseDestination());

        Database second = Database.builder().id(2L).firstName("Student").isActive(true).build();
        DatabaseEmail otherKnown = email("known@campus.ac.id"); otherKnown.setDatabase(second);
        when(emails.findAllByEmailIgnoreCase("known@campus.ac.id")).thenReturn(List.of(known, otherKnown));
        assertEquals(400, controller.createDatabase(incoming("Student", "known@campus.ac.id"), null, null, false, "test").getStatusCode().value());
        owner.setFirstName("Student"); owner.setIsActive(false);
        when(emails.findAllByEmailIgnoreCase("known@campus.ac.id")).thenReturn(List.of(known));
        assertEquals(400, controller.createDatabase(incoming("Student", "known@campus.ac.id"), null, null, false, "test").getStatusCode().value());
        assertEquals(false, owner.getIsActive());
        verify(databases, never()).save(any());
    }

    @Test void samePersonReusesOneProfileAddsParticipantMembershipAndPreservesCompanyAndEmailOwnership() {
        Company company = Company.builder().id(11L).name("Existing employer").build();
        CompanyBranch branch = CompanyBranch.builder().id(12L).companyId(11L).name("Existing office").build();
        Database existing = Database.builder().id(8L).firstName("Student").company(company).branch(branch)
                .createdByUserId(99L).entryMethod("ems_sync").isActive(true).build();
        DatabaseEmail old = email("known@employer.example"); old.setId(44L); old.setEmailType("company");
        old.setIsCorporate(true); old.setIsPrimary(true); old.setDatabase(existing);
        existing.setEmails(List.of(old));
        when(emails.findAllByEmailIgnoreCase("known@employer.example")).thenReturn(List.of(old));
        Database request = incoming(" student ", "known@employer.example");
        request.setEmails(List.of(email("known@employer.example"), email("new@campus.ac.id")));
        request.setInstitutionName("Example Campus"); request.setParticipantCategory("mahasiswa");
        var response = controller.createDatabase(request, null, null, false, "test");
        assertEquals(200, response.getStatusCode().value());
        assertSame(existing, response.getBody());
        assertEquals(8L, existing.getId());
        assertEquals(DatabaseDestination.BOTH, existing.getDatabaseDestination());
        assertSame(company, existing.getCompany()); assertSame(branch, existing.getBranch());
        assertEquals(99L, existing.getCreatedByUserId()); assertEquals("ems_sync", existing.getEntryMethod());
        assertEquals("Example Campus", existing.getInstitutionName());
        assertEquals(2, existing.getEmails().size());
        assertSame(old, existing.getEmails().get(0));
        assertEquals("company", old.getEmailType()); assertTrue(old.getIsCorporate());
        DatabaseEmail added = existing.getEmails().get(1);
        assertSame(existing, added.getDatabase()); assertEquals("personal", added.getEmailType());
        assertEquals("new@campus.ac.id", added.getEmail());
        verify(databases).save(existing);
        verify(emails, never()).save(any());
        verify(companies, never()).save(any());
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

    private static Database incoming(String name, String address) {
        return Database.builder().databaseDestination(DatabaseDestination.PARTICIPANT).firstName(name)
                .emails(List.of(email(address))).build();
    }

    private static DatabaseEmail email(String address) {
        return DatabaseEmail.builder().email(address).emailType("personal").isCorporate(false).isVerified(true).build();
    }
}
