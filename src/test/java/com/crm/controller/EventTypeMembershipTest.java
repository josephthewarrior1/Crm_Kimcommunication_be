package com.crm.controller;

import com.crm.domain.*;
import com.crm.domain.Database;
import com.crm.repository.*;
import com.crm.service.AuditLogService;
import com.crm.service.SecurityHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventTypeMembershipTest {
    private final EventController eventsController = new EventController();
    private final EventParticipantController participantsController = new EventParticipantController();
    private final EventRepository events = mock(EventRepository.class);
    private final DatabaseRepository databases = mock(DatabaseRepository.class);
    private final EventParticipantRepository participants = mock(EventParticipantRepository.class);
    private final DatabaseEmailRepository emails = mock(DatabaseEmailRepository.class);
    private final FlaggedIdentityRepository flags = mock(FlaggedIdentityRepository.class);
    private final RemovalRequestRepository removals = mock(RemovalRequestRepository.class);
    private final SecurityHelper security = spy(new SecurityHelper());
    private final Map<Long, Event> eventRecords = new LinkedHashMap<>();
    private final Map<Long, Database> profiles = new LinkedHashMap<>();
    private final List<EventParticipant> links = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach void setup() {
        doReturn(AppUser.builder().id(7L).roles(Set.of(Role.ADMIN)).allowedEventIds(Set.of(1L)).build())
                .when(security).getAuthenticatedUser("test");
        when(events.findById(anyLong())).thenAnswer(call -> Optional.ofNullable(eventRecords.get(call.getArgument(0))));
        when(events.findForSyncById(anyLong())).thenAnswer(call -> Optional.ofNullable(eventRecords.get(call.getArgument(0))));
        when(events.findAll()).thenAnswer(call -> new ArrayList<>(eventRecords.values()));
        when(events.save(any())).thenAnswer(call -> {
            Event event = call.getArgument(0);
            if (event.getId() == null) event.setId(ids.incrementAndGet());
            eventRecords.put(event.getId(), event); return event;
        });
        when(databases.save(any())).thenAnswer(call -> {
            Database contact = call.getArgument(0);
            if (contact.getId() == null) contact.setId(ids.incrementAndGet());
            profiles.put(contact.getId(), contact); return contact;
        });
        when(emails.save(any())).thenAnswer(call -> {
            DatabaseEmail address = call.getArgument(0); if (address.getId() == null) address.setId(ids.incrementAndGet()); return address;
        });
        when(databases.findById(anyLong())).thenAnswer(call -> Optional.ofNullable(profiles.get(call.getArgument(0))));
        when(databases.findAll()).thenAnswer(call -> new ArrayList<>(profiles.values()));
        when(participants.findByEventId(anyLong())).thenAnswer(call -> links.stream()
                .filter(link -> link.getEvent().getId().equals(call.getArgument(0))).toList());
        when(participants.findAll()).thenAnswer(call -> new ArrayList<>(links));
        when(participants.findByEventIdAndDatabaseId(anyLong(), anyLong())).thenAnswer(call -> links.stream()
                .filter(link -> link.getEvent().getId().equals(call.getArgument(0)) && link.getDatabase() != null
                        && link.getDatabase().getId().equals(call.getArgument(1))).findFirst());
        when(participants.save(any())).thenAnswer(call -> {
            EventParticipant link = call.getArgument(0);
            if (link.getId() == null) { link.setId(ids.incrementAndGet()); links.add(link); }
            return link;
        });
        for (Object controller : List.of(eventsController, participantsController)) {
            ReflectionTestUtils.setField(controller, "eventRepository", events);
            ReflectionTestUtils.setField(controller, "databaseRepository", databases);
            ReflectionTestUtils.setField(controller, "eventParticipantRepository", participants);
            ReflectionTestUtils.setField(controller, "securityHelper", security);
        }
        ReflectionTestUtils.setField(eventsController, "databaseEmailRepository", emails);
        ReflectionTestUtils.setField(eventsController, "flaggedIdentityRepository", flags);
        ReflectionTestUtils.setField(eventsController, "removalRequestRepository", removals);
        ReflectionTestUtils.setField(participantsController, "auditLogService", mock(AuditLogService.class));
    }

    @Test void creationRequiresOneEventClassificationEvenWithoutAnEmsMapping() {
        for (DatabaseDestination invalid : new DatabaseDestination[]{null, DatabaseDestination.BOTH}) {
            assertEquals(400, eventsController.createEvent(Event.builder().name("Invalid").syncDestination(invalid).build(), "test").getStatusCode().value());
        }
        verify(events, never()).save(any());
        for (DatabaseDestination selected : List.of(DatabaseDestination.COMPANY, DatabaseDestination.PARTICIPANT)) {
            Event event = Event.builder().name("Type " + selected).syncDestination(selected).build();
            assertEquals(200, eventsController.createEvent(event, "test").getStatusCode().value());
            assertEquals(selected, event.getSyncDestination()); assertNull(event.getEmsEventId());
        }
    }

    @Test void populatedEventsAreLockedButLegacyEventsCanBeClassifiedWhenAllLinkedProfilesMatch() {
        Event typed = event(1L, DatabaseDestination.COMPANY);
        Database company = profile(1L, DatabaseDestination.COMPANY, "Corporate Only");
        Database participant = profile(2L, DatabaseDestination.PARTICIPANT, "Participant Only");
        Database both = profile(3L, DatabaseDestination.BOTH, "Shared");
        Database legacy = profile(4L, null, "Legacy Company");
        link(1L, company);
        assertEquals(409, eventsController.setSyncDestination(1L, Map.of("destination", "participant"), "test").getStatusCode().value());
        assertEquals(409, eventsController.updateEvent(1L, Event.builder().name("Wrong rename").syncDestination(DatabaseDestination.PARTICIPANT).build(), "test").getStatusCode().value());
        assertEquals("Event 1", typed.getName()); assertEquals(DatabaseDestination.COMPANY, typed.getSyncDestination());
        assertEquals(200, eventsController.updateEvent(1L, Event.builder().name("Allowed rename").syncDestination(DatabaseDestination.COMPANY).build(), "test").getStatusCode().value());
        assertEquals(200, eventsController.setSyncDestination(1L, Map.of("destination", "company"), "test").getStatusCode().value());
        event(2L, null); link(2L, legacy); link(2L, both);
        assertEquals(200, eventsController.setSyncDestination(2L, Map.of("destination", "company"), "test").getStatusCode().value());
        event(3L, null); link(3L, company); link(3L, participant);
        Company retainedCompany = company.getCompany();
        assertEquals(DatabaseDestination.COMPANY, company.getDatabaseDestination());
        assertEquals(200, eventsController.setSyncDestination(3L, Map.of("destination", "participant"), "test").getStatusCode().value());
        assertEquals(DatabaseDestination.COMPANY, company.getDatabaseDestination());
        assertSame(retainedCompany, company.getCompany());
        Database copiedParticipant = links.stream().filter(row -> row.getEvent().getId().equals(3L) && row.getDatabase() != participant)
                .findFirst().orElseThrow().getDatabase();
        assertNotEquals(company.getId(), copiedParticipant.getId());
        assertEquals(DatabaseDestination.PARTICIPANT, copiedParticipant.getDatabaseDestination());
        assertNull(copiedParticipant.getCompany()); assertNull(copiedParticipant.getBranch());
        assertEquals(retainedCompany.getName(), copiedParticipant.getInstitutionName());
        assertEquals(DatabaseDestination.PARTICIPANT, eventRecords.get(3L).getSyncDestination());
        assertEquals(409, eventsController.setSyncDestination(3L, Map.of("destination", "company"), "test").getStatusCode().value());
        event(4L, null); link(4L, null);
        assertEquals(409, eventsController.setSyncDestination(4L, Map.of("destination", "company"), "test").getStatusCode().value());
        event(5L, null);
        assertEquals(200, eventsController.setSyncDestination(5L, Map.of("destination", "participant"), "test").getStatusCode().value());
        event(6L, null); link(6L, participant); link(6L, both);
        assertEquals(200, eventsController.setSyncDestination(6L, Map.of("destination", "participant"), "test").getStatusCode().value());
    }

    @Test void manualAndBatchAddsRequireMatchingDestinationAndRejectWholeMixedBatchBeforeWrites() {
        event(1L, DatabaseDestination.COMPANY);
        Database company = profile(1L, DatabaseDestination.COMPANY, "Corporate");
        Database participant = profile(2L, DatabaseDestination.PARTICIPANT, "Participant");
        Database both = profile(3L, DatabaseDestination.BOTH, "Shared");
        Database legacy = profile(4L, null, "Legacy Company");
        var request = new EventParticipantController.EventParticipantRequest();
        request.setEventId(1L); request.setDatabaseIds(List.of(1L, 2L));
        assertEquals(409, participantsController.createEventParticipant(request, "test").getStatusCode().value());
        assertTrue(links.isEmpty()); verify(participants, never()).save(any()); verify(databases, never()).save(any());
        assertEquals(DatabaseDestination.COMPANY, company.getDatabaseDestination());
        assertEquals(DatabaseDestination.PARTICIPANT, participant.getDatabaseDestination());
        request.setDatabaseIds(List.of(1L, 3L));
        assertEquals(409, participantsController.createEventParticipant(request, "test").getStatusCode().value());
        assertTrue(links.isEmpty()); assertEquals(DatabaseDestination.BOTH, both.getDatabaseDestination());
        request.setDatabaseIds(List.of(1L, 4L, 1L));
        assertEquals(200, participantsController.createEventParticipant(request, "test").getStatusCode().value());
        assertEquals(2, links.size()); assertNull(legacy.getDatabaseDestination());
        assertEquals(200, participantsController.createEventParticipant(request, "test").getStatusCode().value());
        assertEquals(2, links.size());
        event(2L, DatabaseDestination.PARTICIPANT);
        request.setEventId(2L); request.setDatabaseIds(List.of(1L));
        assertEquals(409, participantsController.createEventParticipant(request, "test").getStatusCode().value());
        request.setDatabaseIds(List.of(2L));
        assertEquals(200, participantsController.createEventParticipant(request, "test").getStatusCode().value());
        assertEquals(3, links.size());
        event(3L, null); request.setEventId(3L);
        assertEquals(409, participantsController.createEventParticipant(request, "test").getStatusCode().value());
        assertEquals(3, links.size()); verify(databases, never()).save(any());
    }

    @Test void legacyClassificationCopiesProfileAndRestrictionsWithoutChangingAttendanceOrOriginal() {
        event(1L, null);
        Database company = profile(1L, DatabaseDestination.COMPANY, "Corporate");
        company.setIsActive(false); company.setCreatedByUserId(19L); company.setEntryMethod("manual");
        company.setMobilePhone("081200001111, 081300002222");
        DatabaseEmail address = DatabaseEmail.builder().id(20L).database(company).email("same@campus.ac.id")
                .emailType("company").isCorporate(true).isPrimary(true).isVerified(true).build();
        company.setEmails(new ArrayList<>(List.of(address)));
        FlaggedIdentity flag = FlaggedIdentity.builder().id(21L).database(company).flagReason(FlagReason.duplicate_email)
                .status(FlagStatus.confirmed).evidenceNotes("Retain restriction").build();
        flag.setLinkedFlag(FlaggedIdentity.builder().id(99L).database(company).build());
        RemovalRequest removal = RemovalRequest.builder().id(22L).database(company).reason(RemovalReason.requested_takeout)
                .status(RemovalStatus.done).requestedBy("Synthetic reviewer").sourceDb("Legacy import").notes("Retain takeout").build();
        when(flags.findAll()).thenReturn(List.of(flag)); when(removals.findAll()).thenReturn(List.of(removal));
        link(1L, company); EventParticipant link = links.get(0);
        Long linkId = link.getId(); link.setNotes("Retain notes"); link.setConfirmationStatus("approve");
        assertEquals(200, eventsController.setSyncDestination(1L, Map.of("destination", "participant"), "test").getStatusCode().value());
        Database copy = link.getDatabase();
        assertNotSame(company, copy); assertNotEquals(company.getId(), copy.getId());
        assertEquals(linkId, link.getId()); assertEquals("Retain notes", link.getNotes()); assertEquals("approve", link.getConfirmationStatus());
        assertFalse(copy.getIsActive()); assertEquals(19L, copy.getCreatedByUserId()); assertEquals("manual", copy.getEntryMethod());
        assertEquals(company.getMobilePhone(), copy.getMobilePhone()); assertNull(copy.getCompany()); assertNull(copy.getBranch());
        assertEquals(DatabaseDestination.COMPANY, company.getDatabaseDestination()); assertNotNull(company.getCompany());
        assertSame(address, company.getEmails().get(0)); assertSame(company, address.getDatabase()); assertTrue(address.getIsCorporate());
        assertNotSame(address, copy.getEmails().get(0)); assertEquals(address.getEmail(), copy.getEmails().get(0).getEmail());
        assertSame(copy, copy.getEmails().get(0).getDatabase()); assertFalse(copy.getEmails().get(0).getIsCorporate());
        verify(flags).save(argThat(copied -> copied != flag && copied.getDatabase() == copy && copied.getStatus() == flag.getStatus()
                && copied.getLinkedFlag() == null && copied.getFlagReason() == flag.getFlagReason() && copied.getEvidenceNotes().equals(flag.getEvidenceNotes())));
        verify(removals).save(argThat(copied -> copied != removal && copied.getDatabase() == copy && copied.getStatus() == removal.getStatus()
                && copied.getReason() == removal.getReason() && copied.getSourceDb().equals(removal.getSourceDb()) && copied.getNotes().equals(removal.getNotes())));
        assertSame(company, flag.getDatabase()); assertSame(company, removal.getDatabase());
        assertEquals(200, eventsController.setSyncDestination(1L, Map.of("destination", "participant"), "test").getStatusCode().value());
        assertEquals(2, profiles.size()); assertSame(copy, link.getDatabase());
        verify(databases, times(1)).save(any()); verify(emails, times(1)).save(any());
    }

    @Test void availableContactCandidatesAndTheirFilterOptionsFollowTheStoredEventClassification() {
        Event event = event(1L, DatabaseDestination.COMPANY);
        profile(1L, DatabaseDestination.COMPANY, "Corporate Only");
        profile(2L, DatabaseDestination.PARTICIPANT, "Wrong Destination");
        profile(3L, DatabaseDestination.BOTH, "Shared");
        profile(4L, null, "Legacy Company");
        var response = eventsController.getAvailableDatabasesForEvent(1L, null, null, null, null, null, null, "test");
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(2, body.get("total"));
        assertEquals(Set.of(1L, 4L), ((List<?>) body.get("items")).stream()
                .map(item -> ((Database) ((Map<?, ?>) item).get("database")).getId()).collect(java.util.stream.Collectors.toSet()));
        assertFalse(((Map<?, ?>) body.get("filterOptions")).get("companies").toString().contains("Wrong Destination"));
        event.setSyncDestination(null); clearInvocations(databases);
        assertEquals(409, eventsController.getAvailableDatabasesForEvent(1L, null, null, null, null, null, null, "test").getStatusCode().value());
        verifyNoInteractions(databases);
    }

    @Test void classifyingTwoLegacyEventsReusesOneParticipantProfileAndKeepsBothAttendanceIds() {
        event(1L, null); event(2L, null);
        Database company = profile(1L, DatabaseDestination.COMPANY, "Original Company");
        company.setMobilePhone("081200001111, 081300002222");
        company.setEmails(new ArrayList<>(List.of(DatabaseEmail.builder().id(20L).database(company)
                .email("same@campus.ac.id").emailType("company").isCorporate(true).build())));
        link(1L, company); link(2L, company);
        List<Long> attendanceIds = links.stream().map(EventParticipant::getId).toList();
        for (long eventId : List.of(1L, 2L)) assertEquals(200, eventsController.setSyncDestination(eventId,
                Map.of("destination", "participant"), "test").getStatusCode().value());
        assertEquals(2, profiles.size()); assertEquals(2, links.size());
        assertSame(links.get(0).getDatabase(), links.get(1).getDatabase());
        assertEquals(DatabaseDestination.PARTICIPANT, links.get(0).getDatabase().getDatabaseDestination());
        assertEquals(attendanceIds, links.stream().map(EventParticipant::getId).toList());
        assertEquals(DatabaseDestination.COMPANY, company.getDatabaseDestination());
        assertSame(company, company.getEmails().get(0).getDatabase());
        verify(databases, times(1)).save(any()); verify(emails, times(1)).save(any());
    }

    @Test void ambiguousOrDifferentNameLegacyDestinationOwnersAreRejectedBeforeAnyWrites() {
        Event event = event(1L, null);
        Database company = profile(1L, DatabaseDestination.COMPANY, "Original Company");
        company.setFirstName("Alex");
        company.setEmails(List.of(DatabaseEmail.builder().database(company).email("same@campus.ac.id").build()));
        link(1L, company);
        for (long id : List.of(2L, 3L)) {
            Database participant = profile(id, DatabaseDestination.PARTICIPANT, "");
            participant.setCompany(null); participant.setFirstName("Alex");
            participant.setEmails(List.of(DatabaseEmail.builder().database(participant).email("same@campus.ac.id").build()));
        }
        var ambiguous = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> eventsController.setSyncDestination(1L, Map.of("destination", "participant"), "test"));
        assertEquals(409, ambiguous.getStatusCode().value());
        profiles.remove(3L); profiles.get(2L).setFirstName("Another Person");
        var conflict = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> eventsController.setSyncDestination(1L, Map.of("destination", "participant"), "test"));
        assertEquals(409, conflict.getStatusCode().value());
        assertNull(event.getSyncDestination()); assertSame(company, links.get(0).getDatabase());
        verify(databases, never()).save(any()); verify(emails, never()).save(any()); verify(participants, never()).save(any());
    }

    @Test void pmsEventBindingIsPositiveUniqueImmutableAndSurvivesOrdinaryResync() {
        Event existing = event(1L, DatabaseDestination.PARTICIPANT); existing.setPmsEventId(91L);
        when(events.findByPmsEventId(91L)).thenReturn(Optional.of(existing));
        for (long invalid : List.of(0L, -1L)) assertEquals(400, eventsController.createEvent(Event.builder()
                .name("Invalid PMS").syncDestination(DatabaseDestination.COMPANY).pmsEventId(invalid).build(), "test").getStatusCode().value());
        assertEquals(409, eventsController.createEvent(Event.builder().name("Duplicate PMS")
                .syncDestination(DatabaseDestination.COMPANY).pmsEventId(91L).build(), "test").getStatusCode().value());
        assertEquals(409, eventsController.updateEvent(1L, Event.builder().name("Retarget mapping").pmsEventId(92L).build(), "test").getStatusCode().value());
        assertEquals(91L, existing.getPmsEventId());
        assertEquals(200, eventsController.updateEvent(1L, Event.builder().name("PMS refreshed title").build(), "test").getStatusCode().value());
        assertEquals(91L, existing.getPmsEventId()); assertEquals(DatabaseDestination.PARTICIPANT, existing.getSyncDestination());
        Event legacy = event(2L, null);
        assertEquals(409, eventsController.updateEvent(2L, Event.builder().name("Collision").pmsEventId(91L).build(), "test").getStatusCode().value());
        assertNull(legacy.getPmsEventId());
        assertEquals(200, eventsController.updateEvent(2L, Event.builder().name("Bound once").pmsEventId(92L).syncDestination(DatabaseDestination.COMPANY).build(), "test").getStatusCode().value());
        assertEquals(92L, legacy.getPmsEventId()); assertEquals(DatabaseDestination.COMPANY, legacy.getSyncDestination());
    }

    private Event event(long id, DatabaseDestination destination) {
        Event event = Event.builder().id(id).name("Event " + id).syncDestination(destination).build();
        eventRecords.put(id, event); return event;
    }
    private Database profile(long id, DatabaseDestination destination, String companyName) {
        Database profile = Database.builder().id(id).firstName("Synthetic " + id).isActive(true).databaseDestination(destination)
                .company(Company.builder().id(id).name(companyName).build()).build();
        profiles.put(id, profile); return profile;
    }
    private void link(long eventId, Database profile) {
        links.add(EventParticipant.builder().id(ids.incrementAndGet()).event(eventRecords.get(eventId)).database(profile).build());
    }
}
