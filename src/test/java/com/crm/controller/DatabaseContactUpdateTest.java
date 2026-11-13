package com.crm.controller;

import com.crm.domain.*;
import com.crm.domain.Database;
import com.crm.repository.*;
import com.crm.service.SecurityHelper;
import com.crm.service.SuspiciousIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatabaseContactUpdateTest {
    private final DatabaseController controller = new DatabaseController();
    private final DatabaseRepository databases = mock(DatabaseRepository.class);
    private final DatabaseEmailRepository emails = mock(DatabaseEmailRepository.class);
    private final EventParticipantRepository participants = mock(EventParticipantRepository.class);
    private final SecurityHelper security = mock(SecurityHelper.class);
    private final SuspiciousIdentityService identities = mock(SuspiciousIdentityService.class);
    private final AppUser user = AppUser.builder().id(7L).build();
    private final Company company = Company.builder().id(4L).name("Original Company").build();
    private final Database profile = Database.builder().id(42L).firstName("Original").lastName("Person")
            .databaseDestination(DatabaseDestination.COMPANY).company(company).isActive(false)
            .mobilePhone("0811111111").normalizedPhone("62811111111").jobTitle("Original Title").build();

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(controller, "databaseRepository", databases);
        ReflectionTestUtils.setField(controller, "databaseEmailRepository", emails);
        ReflectionTestUtils.setField(controller, "eventParticipantRepository", participants);
        ReflectionTestUtils.setField(controller, "securityHelper", security);
        ReflectionTestUtils.setField(controller, "suspiciousIdentityService", identities);
        when(security.getAuthenticatedUser("test")).thenReturn(user);
        when(security.hasAnyRole(user, Role.ADMIN, Role.MANAGER)).thenReturn(true);
        when(databases.findById(42L)).thenReturn(Optional.of(profile));
        when(databases.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(emails.save(any())).thenAnswer(call -> call.getArgument(0));
        profile.setEmails(new ArrayList<>());
    }

    @Test void invalidAndDuplicateEmailAreRejectedBeforeAnyProfileOrEmailMutation() {
        Database owner = Database.builder().id(99L).databaseDestination(DatabaseDestination.COMPANY).build();
        when(emails.findAllByEmailIgnoreCase("taken@gmail.com")).thenReturn(List.of(
                DatabaseEmail.builder().id(9L).database(owner).email("taken@gmail.com").emailType("personal").build()));
        for (String address : List.of("bad-address", "taken@gmail.com")) {
            assertEquals(400, controller.updateDatabaseContact(42L,
                    request("Changed", "0812222222", List.of(entry(null, address, "personal"))), "test")
                    .getStatusCode().value());
            assertEquals("Original", profile.getFirstName());
            assertEquals("0811111111", profile.getMobilePhone());
            assertTrue(profile.getEmails().isEmpty());
        }
        verify(databases, never()).saveAndFlush(any());
        verify(emails, never()).save(any());
        verifyNoInteractions(identities, participants);
    }

    @Test void twoPhoneNumbersStaySeparateAndContactChangesNeverChangeCompanyOrApproval() {
        Event event = Event.builder().id(1L).emsEventId(14L).syncDestination(DatabaseDestination.COMPANY).build();
        EventParticipant selected = EventParticipant.builder().id(1L).database(profile).event(event)
                .confirmationStatus("pending").preEventApprovalStatus("approve").emsApprovalStatus("accepted")
                .emsIdentityReviewMessage("Old alias review").emsClientDeclineReviewRequired(true).build();
        EventParticipant alias = EventParticipant.builder().id(2L).event(event).confirmationStatus("decline")
                .database(Database.builder().id(99L).databaseDestination(DatabaseDestination.COMPANY)
                        .firstName("Other").mobilePhone("0811111111").build()).build();
        when(participants.findByDatabaseId(42L)).thenReturn(List.of(selected, selected));
        when(participants.findByEventId(1L)).thenReturn(List.of(selected, alias));
        DatabaseEmail retained = DatabaseEmail.builder().id(8L).database(profile)
                .email("old@company.example").emailType("company").isCorporate(true).build();
        profile.getEmails().add(retained);

        var response = controller.updateDatabaseContact(42L,
                request(" New Name ", "0812-345-678 / +65 8123 4567", List.of(entry(null, "new@gmail.com", "personal"))), "test");

        assertEquals(200, response.getStatusCode().value());
        assertSame(profile, response.getBody());
        assertEquals("New Name", profile.getFirstName());
        assertEquals("Person", profile.getLastName());
        assertEquals("0812345678, +6581234567", profile.getMobilePhone());
        assertEquals("62812345678", profile.getNormalizedPhone());
        assertSame(company, profile.getCompany());
        assertEquals("Original Company", company.getName());
        assertFalse(profile.getIsActive());
        assertEquals(DatabaseDestination.COMPANY, profile.getDatabaseDestination());
        assertEquals(List.of("old@company.example", "new@gmail.com"),
                profile.getEmails().stream().map(DatabaseEmail::getEmail).toList());
        assertFalse(selected.getRawEmsClientDeclineReviewRequired());
        assertNull(selected.getRawEmsIdentityReviewMessage());
        assertEquals("pending", selected.getConfirmationStatus());
        assertEquals("approve", selected.getPreEventApprovalStatus());
        assertEquals("decline", alias.getConfirmationStatus());
        verify(participants, times(1)).findByEventId(1L);
    }

    @Test void blankPhoneClearsDerivedPhoneAndOmittedEmailsAndFieldsArePreserved() {
        var email = DatabaseEmail.builder().id(8L).database(profile).email("retained@gmail.com").build();
        profile.getEmails().add(email);
        var request = new DatabaseController.ContactUpdateRequest("", null, null, "", null, "", "", List.of());
        assertEquals(200, controller.updateDatabaseContact(42L, request, "test").getStatusCode().value());
        assertEquals("", profile.getSalutation());
        assertEquals("", profile.getJobTitle());
        assertEquals("", profile.getMobilePhone());
        assertEquals("", profile.getNormalizedPhone());
        assertNull(profile.getPositionLevel());
        assertEquals("Original", profile.getFirstName());
        assertEquals(List.of(email), profile.getEmails());
        verifyNoInteractions(emails);
    }

    @Test void participantEmailMayMatchCompanyButCannotTakeAnotherParticipantEmail() {
        profile.setDatabaseDestination(DatabaseDestination.PARTICIPANT);
        profile.setCompany(null);
        Database companyOwner = Database.builder().id(100L).databaseDestination(DatabaseDestination.COMPANY).build();
        when(emails.findAllByEmailIgnoreCase("same@gmail.com")).thenReturn(List.of(DatabaseEmail.builder()
                .id(7L).database(companyOwner).email("same@gmail.com").emailType("personal").build()));
        assertEquals(200, controller.updateDatabaseContact(42L,
                request(null, null, List.of(entry(null, "same@gmail.com", "personal"))), "test").getStatusCode().value());
        assertNull(profile.getCompany());
        assertEquals(DatabaseDestination.PARTICIPANT, profile.getDatabaseDestination());
        Database participantOwner = Database.builder().id(101L).databaseDestination(DatabaseDestination.PARTICIPANT).build();
        when(emails.findAllByEmailIgnoreCase("taken@example.edu")).thenReturn(List.of(DatabaseEmail.builder()
                .id(9L).database(participantOwner).email("taken@example.edu").emailType("company").build()));
        assertEquals(400, controller.updateDatabaseContact(42L,
                request("Rejected", null, List.of(entry(null, "taken@example.edu", "personal"))), "test").getStatusCode().value());
        assertEquals("Original", profile.getFirstName());
    }

    @Test void missingLoginViewerAndInvalidPhoneCannotWriteContacts() {
        when(security.getAuthenticatedUser("test")).thenReturn(null);
        assertEquals(401, controller.updateDatabaseContact(42L, request("Changed", null, null), "test").getStatusCode().value());
        when(security.getAuthenticatedUser("test")).thenReturn(user);
        when(security.hasAnyRole(user, Role.ADMIN, Role.MANAGER)).thenReturn(false);
        assertEquals(403, controller.updateDatabaseContact(42L, request("Changed", null, null), "test").getStatusCode().value());
        when(security.hasAnyRole(user, Role.ADMIN, Role.MANAGER)).thenReturn(true);
        assertEquals(400, controller.updateDatabaseContact(42L, request("Changed", "081234, invalid", null), "test").getStatusCode().value());
        assertEquals(400, controller.updateDatabaseContact(42L, request("x".repeat(256), null, null), "test").getStatusCode().value());
        assertEquals("Original", profile.getFirstName());
        verify(databases, never()).saveAndFlush(any());
        verifyNoInteractions(emails, participants, identities);
    }

    @Test void lateConstraintFailureThrowsSanitizedErrorForTransactionalRollback() throws Exception {
        assertNotNull(DatabaseController.class.getMethod("updateDatabaseContact", Long.class,
                DatabaseController.ContactUpdateRequest.class, String.class).getAnnotation(Transactional.class));
        when(databases.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("SQL private@example.com constraint"));
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> controller.updateDatabaseContact(42L, request("Changed", null, null), "test"));
        assertEquals(409, failure.getStatusCode().value());
        assertFalse(failure.getReason().contains("private@example.com"));
        verifyNoInteractions(participants, identities);
    }

    private static DatabaseController.ContactUpdateRequest request(String name, String phone,
            List<DatabaseController.EmailBatchEntry> entries) {
        return new DatabaseController.ContactUpdateRequest(null, name, null, null, null, null, phone, entries);
    }

    private static DatabaseController.EmailBatchEntry entry(Long id, String address, String type) {
        return new DatabaseController.EmailBatchEntry(id, address, type, null, null, null, null);
    }
}
