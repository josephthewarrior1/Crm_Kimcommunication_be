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
        when(databases.findById(anyLong())).thenAnswer(call -> Optional.ofNullable(profiles.get(call.getArgument(0))));
        when(databases.findAll()).thenAnswer(call -> new ArrayList<>(profiles.values()));
        when(participants.findByEventId(anyLong())).thenAnswer(call -> links.stream()
                .filter(link -> link.getEvent().getId().equals(call.getArgument(0))).toList());
        when(participants.findAll()).thenAnswer(call -> new ArrayList<>(links));
        when(participants.findByEventIdAndDatabaseId(anyLong(), anyLong())).thenAnswer(call -> links.stream()
                .filter(link -> link.getEvent().getId().equals(call.getArgument(0)) && link.getDatabase() != null
                        && link.getDatabase().getId().equals(call.getArgument(1))).findFirst());
        when(participants.save(any())).thenAnswer(call -> {
            EventParticipant link = call.getArgument(0); link.setId(ids.incrementAndGet()); links.add(link); return link;
        });
        for (Object controller : List.of(eventsController, participantsController)) {
            ReflectionTestUtils.setField(controller, "eventRepository", events);
            ReflectionTestUtils.setField(controller, "databaseRepository", databases);
            ReflectionTestUtils.setField(controller, "eventParticipantRepository", participants);
            ReflectionTestUtils.setField(controller, "securityHelper", security);
        }
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
        for (String destination : List.of("company", "participant")) assertEquals(409,
                eventsController.setSyncDestination(3L, Map.of("destination", destination), "test").getStatusCode().value());
        assertNull(eventRecords.get(3L).getSyncDestination());
        event(4L, null); link(4L, null);
        assertEquals(409, eventsController.setSyncDestination(4L, Map.of("destination", "company"), "test").getStatusCode().value());
        event(5L, null);
        assertEquals(200, eventsController.setSyncDestination(5L, Map.of("destination", "participant"), "test").getStatusCode().value());
        event(6L, null); link(6L, participant); link(6L, both);
        assertEquals(200, eventsController.setSyncDestination(6L, Map.of("destination", "participant"), "test").getStatusCode().value());
    }

    @Test void manualAndBatchAddsPrevalidateAllMembershipsAndNeverPromoteProfiles() {
        event(1L, DatabaseDestination.COMPANY);
        Database company = profile(1L, DatabaseDestination.COMPANY, "Corporate");
        Database participant = profile(2L, DatabaseDestination.PARTICIPANT, "Participant");
        Database both = profile(3L, DatabaseDestination.BOTH, "Shared");
        var request = new EventParticipantController.EventParticipantRequest();
        request.setEventId(1L); request.setDatabaseIds(List.of(1L, 2L));
        assertEquals(400, participantsController.createEventParticipant(request, "test").getStatusCode().value());
        verify(participants, never()).save(any()); assertTrue(links.isEmpty());
        assertEquals(DatabaseDestination.COMPANY, company.getDatabaseDestination());
        assertEquals(DatabaseDestination.PARTICIPANT, participant.getDatabaseDestination());
        request.setDatabaseIds(List.of(1L, 3L, 1L));
        assertEquals(200, participantsController.createEventParticipant(request, "test").getStatusCode().value());
        assertEquals(2, links.size()); assertEquals(DatabaseDestination.BOTH, both.getDatabaseDestination());
        event(2L, DatabaseDestination.PARTICIPANT);
        request.setEventId(2L); request.setDatabaseIds(List.of(3L));
        assertEquals(200, participantsController.createEventParticipant(request, "test").getStatusCode().value());
        assertEquals(3, links.size());
        event(3L, null); request.setEventId(3L);
        assertEquals(409, participantsController.createEventParticipant(request, "test").getStatusCode().value());
        assertEquals(3, links.size());
    }

    @Test void availableContactCandidatesAndTheirFilterOptionsFollowTheStoredEventClassification() {
        Event event = event(1L, DatabaseDestination.COMPANY);
        profile(1L, DatabaseDestination.COMPANY, "Corporate Only");
        profile(2L, DatabaseDestination.PARTICIPANT, "Wrong Destination");
        profile(3L, DatabaseDestination.BOTH, "Shared");
        profile(4L, null, "Legacy Company");
        var response = eventsController.getAvailableDatabasesForEvent(1L, null, null, null, null, null, null, "test");
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(3, body.get("total"));
        assertEquals(Set.of(1L, 3L, 4L), ((List<?>) body.get("items")).stream()
                .map(item -> ((Database) ((Map<?, ?>) item).get("database")).getId()).collect(java.util.stream.Collectors.toSet()));
        assertFalse(((Map<?, ?>) body.get("filterOptions")).get("companies").toString().contains("Wrong Destination"));
        event.setSyncDestination(null); clearInvocations(databases);
        assertEquals(409, eventsController.getAvailableDatabasesForEvent(1L, null, null, null, null, null, null, "test").getStatusCode().value());
        verifyNoInteractions(databases);
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
