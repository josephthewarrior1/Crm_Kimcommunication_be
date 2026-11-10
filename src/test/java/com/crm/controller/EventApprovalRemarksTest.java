package com.crm.controller;

import com.crm.domain.AppUser;
import com.crm.domain.AttendanceStatus;
import com.crm.domain.Company;
import com.crm.domain.Database;
import com.crm.domain.DatabaseSource;
import com.crm.domain.Event;
import com.crm.domain.EventParticipant;
import com.crm.domain.ParticipantStatus;
import com.crm.domain.Role;
import com.crm.repository.EventParticipantRepository;
import com.crm.repository.EventRepository;
import com.crm.repository.UserRepository;
import com.crm.service.AuditLogService;
import com.crm.service.SecurityHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class EventApprovalRemarksTest {
    private final EventController controller = new EventController();
    private final EventRepository events = mock(EventRepository.class);
    private final EventParticipantRepository participants = mock(EventParticipantRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SecurityHelper security = spy(new SecurityHelper());
    private final ObjectMapper json = new ObjectMapper();
    private final Event event = Event.builder().id(1L).name("Local review event").build();
    private List<EventParticipant> rows;
    private MockMvc mvc;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(controller, "eventRepository", events);
        ReflectionTestUtils.setField(controller, "eventParticipantRepository", participants);
        ReflectionTestUtils.setField(controller, "userRepository", users);
        ReflectionTestUtils.setField(controller, "securityHelper", security);
        when(events.findById(1L)).thenReturn(Optional.of(event));
        when(users.findAll()).thenReturn(List.of());
        when(participants.findByEventId(1L)).thenAnswer(invocation -> rows);
        authenticate(Role.ADMIN, Set.of(1L));
        rows = new ArrayList<>();
        mvc = standaloneSetup(controller).build();
    }

    @Test void clientDeclineRemainsDeclinedForEveryFormerActiveEmsOverride() throws Exception {
        for (int index = 0; index < 5; index++) {
            EventParticipant declined = participant(index + 1, ParticipantStatus.registered);
            declined.setConfirmationStatus("decline");
            declined.setNotes("[Origin: Request] [Origin: EMS Sync] [PIC: Operator]");
            declined.getDatabase().setSource(DatabaseSource.event_registration);
            switch (index) {
                case 0 -> declined.setAttendanceStatus(AttendanceStatus.registered);
                case 1 -> declined.setAttendanceStatus(AttendanceStatus.attended);
                case 2 -> declined.setParticipantStatus(ParticipantStatus.green);
                case 3 -> declined.setParticipantStatus(ParticipantStatus.confirm);
                default -> declined.setReminderHariH("on_location");
            }
            rows.add(declined);
        }
        assertEquals(0, response("participants", "pre_event").path("total").asInt());
        assertEquals(0, response("participants", "reminder").path("total").asInt());
        assertEquals(0, response("participants", "reminder_dday").path("total").asInt());
        assertEquals(5, response("participants", "declined").path("total").asInt());
        assertEquals(5, response("statistics", "request").at("/scopes/all/takenOut").asInt());
        assertEquals(5, response("statistics", "declined").at("/scopes/all/totalDeclined").asInt());
        JsonNode exported = response("participants/export", "declined");
        for (JsonNode row : exported.path("rows")) assertEquals("decline", row.path("Client Approval").asText());
        for (EventParticipant row : rows) {
            assertEquals("decline", row.getConfirmationStatus());
            assertEquals("pending", row.getPreEventApprovalStatus());
            assertEquals("Company " + row.getId() % 2, row.getDatabase().getCompany().getName());
        }
        verify(participants, never()).save(any());
    }

    @Test void preEventAndEmsDeclinesAlsoLeaveOperationalTabsWithoutChangingClientApproval() throws Exception {
        EventParticipant preEventDeclined = participant(1, ParticipantStatus.registered);
        preEventDeclined.setPreEventApprovalStatus("decline");
        EventParticipant cancelled = participant(2, ParticipantStatus.registered);
        cancelled.setAttendanceStatus(AttendanceStatus.cancelled);
        EventParticipant emsDeclined = participant(3, ParticipantStatus.registered);
        emsDeclined.setNotes("[Origin: Request] [EMS Declined]");
        rows.addAll(List.of(preEventDeclined, cancelled, emsDeclined));
        for (String tab : List.of("pre_event", "reminder", "reminder_dday")) {
            assertEquals(0, response("participants", tab).path("total").asInt(), tab);
        }
        assertEquals(3, response("participants", "declined").path("total").asInt());
        assertEquals(3, response("statistics", "declined").at("/scopes/all/totalDeclined").asInt());
        for (EventParticipant row : rows) assertEquals("approve", row.getConfirmationStatus());
    }

    @Test void normalManualAndEmsPendingAndApprovedMembershipIsPreserved() throws Exception {
        EventParticipant pendingRequest = participant(1, ParticipantStatus.not_respon_yet);
        pendingRequest.setConfirmationStatus("pending");
        EventParticipant approvedRequest = participant(2, ParticipantStatus.not_respon_yet);
        EventParticipant emsPending = participant(3, ParticipantStatus.registered);
        emsPending.setConfirmationStatus("pending");
        emsPending.setNotes("[Origin: EMS Sync]");
        rows.addAll(List.of(pendingRequest, approvedRequest, emsPending));
        assertEquals(List.of(1L, 2L, 3L), ids(response("participants", "request")));
        assertEquals(List.of(2L, 3L), ids(response("participants", "pre_event")));
        assertEquals(0, response("participants", "declined").path("total").asInt());
    }

    @Test void emsRegistrantsRemainAvailableForClientDecisionsAndRestorationInDataList() throws Exception {
        EventParticipantController statusController = new EventParticipantController();
        ReflectionTestUtils.setField(statusController, "eventParticipantRepository", participants);
        ReflectionTestUtils.setField(statusController, "securityHelper", security);
        ReflectionTestUtils.setField(statusController, "auditLogService", mock(AuditLogService.class));
        mvc = standaloneSetup(controller, statusController).build();
        when(participants.findById(anyLong())).thenAnswer(invocation -> rows.stream()
                .filter(row -> row.getId().equals(invocation.getArgument(0))).findFirst());
        when(participants.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        authenticate(Role.USER, Set.of(1L));

        for (String origin : List.of("[Origin: EMS Sync]", "[Origin: Request] [Origin: EMS Sync]")) {
            EventParticipant registration = participant(1, ParticipantStatus.registered);
            registration.setConfirmationStatus("pending");
            registration.setNotes(origin + " [PIC: Operator]");
            registration.getDatabase().setSource(DatabaseSource.event_registration);
            rows.clear();
            rows.add(registration);
            assertEquals(List.of(1L), ids(response("participants", "request")));
            assertEquals(List.of(1L), ids(response("participants", "pre_event")));
            assertEquals(1, response("statistics", "request").at("/scopes/all/pendingApproval").asInt());

            for (String decision : List.of("approve", "decline", "approve")) {
                mvc.perform(put("/api/event-participants/1/status").param("confirmationStatus", decision)
                                .header("Authorization", "Bearer test"))
                        .andExpect(status().isOk());
                assertEquals(List.of(1L), ids(response("participants", "request")), "The decision must remain editable");
                assertEquals("decline".equals(decision) ? List.of() : List.of(1L), ids(response("participants", "pre_event")));
                assertEquals("decline".equals(decision) ? List.of(1L) : List.of(), ids(response("participants", "declined")));
                assertEquals(decision, registration.getConfirmationStatus());
                assertEquals("pending", registration.getPreEventApprovalStatus());
                assertEquals(origin + " [PIC: Operator]", registration.getNotes());
                assertEquals(DatabaseSource.event_registration, registration.getDatabase().getSource());
                assertEquals("Company 1", registration.getDatabase().getCompany().getName());
            }
        }

        authenticate(Role.USER, Set.of(99L));
        mvc.perform(get("/api/events/1/participants").param("tab", "request").header("Authorization", "Bearer test"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/event-participants/1/status").param("confirmationStatus", "decline").header("Authorization", "Bearer test"))
                .andExpect(status().isForbidden());
        authenticate(Role.USER, Set.of(1L));
        rows.get(0).setNotes("[Origin: EMS Sync] [PIC: Other operator]");
        mvc.perform(put("/api/event-participants/1/status").param("confirmationStatus", "decline").header("Authorization", "Bearer test"))
                .andExpect(status().isForbidden());
        assertEquals("approve", rows.get(0).getConfirmationStatus());
    }

    @Test void legacyPreEventDecisionIsRespectedUntilAValidColumnOverridesIt() throws Exception {
        EventParticipant legacy = participant(1, ParticipantStatus.registered);
        legacy.setPreEventApprovalStatus(null);
        legacy.setNotes("[Origin: Request] [PreEventApproval: decline]");
        EventParticipant validPending = participant(2, ParticipantStatus.registered);
        validPending.setNotes("[Origin: Request] [PreEventApproval: decline]");
        EventParticipant legacyAlias = participant(3, ParticipantStatus.registered);
        legacyAlias.setPreEventApprovalStatus("declined");
        rows.addAll(List.of(legacy, validPending, legacyAlias));
        assertEquals(List.of(2L), ids(response("participants", "pre_event")));
        assertEquals(List.of(2L), ids(response("participants", "reminder")));
        assertEquals(List.of(2L), ids(response("participants", "reminder_dday")));
        assertEquals(List.of(1L, 3L), ids(response("participants", "declined")));
        assertEquals(2, response("statistics", "declined").at("/scopes/all/totalDeclined").asInt());
        assertEquals(1, response("participants", "pre_event", "confirmationStatus", "pending").path("total").asInt());
        assertNull(legacy.getPreEventApprovalStatus());
        assertEquals("[Origin: Request] [PreEventApproval: decline]", legacy.getNotes());
    }

    @Test void remarksAreFilteredBeforePaginationAndAllReadEndpointsUseTheSameScope() throws Exception {
        for (long id = 70; id >= 1; id--) {
            rows.add(participant(id, id % 2 == 0 ? ParticipantStatus.not_respond_2x : ParticipantStatus.registered));
        }
        JsonNode secondPage = response("participants", "pre_event", "remarks", "not_respond_2x", "page", "2", "size", "25");
        assertEquals(35, secondPage.path("total").asInt());
        assertEquals(2, secondPage.path("totalPages").asInt());
        assertEquals(LongStream.rangeClosed(26, 35).map(i -> i * 2).boxed().toList(), ids(secondPage));
        assertEquals(35, response("participants/summary", "pre_event", "remarks", "not_respond_2x").path("totalParticipants").asInt());
        assertEquals(35, response("participants/summary-by-pic", "pre_event", "remarks", "not_respond_2x").path("totalParticipants").asInt());
        assertEquals(35, response("statistics", "pre_event", "remarks", "not_respond_2x").at("/scopes/all/pending").asInt());
        JsonNode exported = response("participants/export", "pre_event", "remarks", "not_respond_2x");
        assertEquals(35, exported.path("total").asInt());
        assertEquals("Contact2", exported.path("rows").get(0).path("First Name").asText());
        assertEquals("Contact70", exported.path("rows").get(34).path("First Name").asText());
        assertEquals(List.of("Company 0"), json.convertValue(response("participants/filter-options", "pre_event", "remarks", "not_respond_2x").path("companies"), List.class));
        assertEquals(70, response("participants", "request", "remarks", "not_respond_2x", "size", "100").path("total").asInt());
        verify(participants, never()).save(any());
    }

    @Test void legacyRemarksAliasesMatchTheDisplayedCategoryButRespondCountsStayDistinct() throws Exception {
        ParticipantStatus[] statuses = {null, ParticipantStatus.white, ParticipantStatus.not_respon_yet,
                ParticipantStatus.green, ParticipantStatus.confirm, ParticipantStatus.registered,
                ParticipantStatus.yellow, ParticipantStatus.tentative, ParticipantStatus.red,
                ParticipantStatus.not_interest, ParticipantStatus.unable_to_attend,
                ParticipantStatus.not_respond_2x, ParticipantStatus.not_respond_3x, ParticipantStatus.not_respond_9x};
        for (int index = 0; index < statuses.length; index++) rows.add(participant(index + 1, statuses[index]));
        assertEquals(List.of(1L, 2L, 3L), ids(response("participants", "pre_event", "remarks", "not_respon_yet")));
        assertEquals(List.of(1L, 2L, 3L), ids(response("participants", "pre_event", "remarks", "not_respond_yet")));
        assertEquals(List.of(4L, 5L, 6L), ids(response("participants", "pre_event", "remarks", "registered")));
        assertEquals(List.of(7L, 8L), ids(response("participants", "pre_event", "remarks", "tentative")));
        assertEquals(List.of(9L, 10L), ids(response("participants", "pre_event", "remarks", "not_interest")));
        assertEquals(List.of(11L), ids(response("participants", "pre_event", "remarks", "unable_to_attend")));
        for (int[] countAndId : new int[][]{{2, 12}, {3, 13}, {9, 14}}) {
            assertEquals(List.of((long) countAndId[1]), ids(response("participants", "pre_event", "remarks", "not_respond_" + countAndId[0] + "x")));
        }
        assertEquals(0, response("participants", "pre_event", "remarks", "unknown-value").path("total").asInt());
    }

    @Test void remarksCombineWithSearchCompanyAndPicAndCannotWidenAssignedAccess() throws Exception {
        rows.add(participant(2, ParticipantStatus.tentative));
        EventParticipant assignedElsewhere = participant(4, ParticipantStatus.tentative);
        assignedElsewhere.setNotes("[Origin: Request] [PIC: Other operator]");
        rows.add(assignedElsewhere);
        rows.add(participant(6, ParticipantStatus.registered));
        authenticate(Role.MANAGER, Set.of(1L));
        JsonNode result = response("participants", "pre_event", "remarks", "tentative", "pic", "Other operator", "company", "Company 0", "search", "Contact2");
        assertEquals(List.of(2L), ids(result));
        authenticate(Role.MANAGER, Set.of(99L));
        mvc.perform(get("/api/events/1/participants").param("tab", "pre_event").param("remarks", "registered")
                        .header("Authorization", "Bearer test"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/events/1/participants").param("remarks", "registered"))
                .andExpect(status().isUnauthorized());
    }

    private EventParticipant participant(long id, ParticipantStatus remarks) {
        Company company = Company.builder().id(id % 2).name("Company " + id % 2).build();
        Database contact = Database.builder().id(id).firstName("Contact" + id).lastName("Example")
                .company(company).source(DatabaseSource.excel_import).build();
        return EventParticipant.builder().id(id).event(event).database(contact).participantStatus(remarks)
                .attendanceStatus(AttendanceStatus.invited).confirmationStatus("approve").preEventApprovalStatus("pending")
                .notes("[Origin: Request] [PIC: Operator]").build();
    }

    private void authenticate(Role role, Set<Long> allowedEvents) {
        doReturn(AppUser.builder().id(7L).username("operator").fullName("Operator").roles(Set.of(role)).allowedEventIds(allowedEvents).build())
                .when(security).getAuthenticatedUser("Bearer test");
    }

    private JsonNode response(String endpoint, String tab, String... filters) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/events/1/" + endpoint).param("tab", tab).header("Authorization", "Bearer test");
        for (int index = 0; index < filters.length; index += 2) request.param(filters[index], filters[index + 1]);
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private List<Long> ids(JsonNode response) {
        List<Long> ids = new ArrayList<>();
        response.path("items").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }
}
