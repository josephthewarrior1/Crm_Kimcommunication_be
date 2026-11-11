package com.crm.controller;

import com.crm.domain.*;
import com.crm.repository.*;
import com.crm.service.AuditLogService;
import com.crm.service.SecurityHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class EmsApprovalWorkflowTest {
    private final EventController controller = new EventController();
    private final EventParticipantController editor = new EventParticipantController();
    private final EventRepository events = mock(EventRepository.class);
    private final EventParticipantRepository participants = mock(EventParticipantRepository.class);
    private final DatabaseRepository databases = mock(DatabaseRepository.class);
    private final EventParticipantActivityRepository activities = mock(EventParticipantActivityRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SecurityHelper security = spy(new SecurityHelper());
    private final ObjectMapper json = new ObjectMapper();
    private final Event event = Event.builder().id(1L).name("EMS authority fixture")
            .emsEventId(14L).syncDestination(DatabaseDestination.COMPANY).build();
    private final List<EventParticipant> rows = new ArrayList<>();
    private MockMvc mvc;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(controller, "eventRepository", events);
        ReflectionTestUtils.setField(controller, "eventParticipantRepository", participants);
        ReflectionTestUtils.setField(controller, "userRepository", users);
        ReflectionTestUtils.setField(controller, "securityHelper", security);
        ReflectionTestUtils.setField(editor, "eventRepository", events);
        ReflectionTestUtils.setField(editor, "eventParticipantRepository", participants);
        ReflectionTestUtils.setField(editor, "databaseRepository", databases);
        ReflectionTestUtils.setField(editor, "eventParticipantActivityRepository", activities);
        ReflectionTestUtils.setField(editor, "securityHelper", security);
        ReflectionTestUtils.setField(editor, "auditLogService", mock(AuditLogService.class));
        when(events.findById(1L)).thenReturn(Optional.of(event));
        when(events.findForSyncById(1L)).thenReturn(Optional.of(event));
        when(events.save(any())).thenAnswer(call -> call.getArgument(0));
        when(users.findAll()).thenReturn(List.of());
        when(participants.findByEventId(1L)).thenReturn(rows);
        when(participants.findById(anyLong())).thenAnswer(call -> rows.stream()
                .filter(row -> row.getId().equals(call.getArgument(0))).findFirst());
        when(participants.save(any())).thenAnswer(call -> call.getArgument(0));
        when(activities.findByEventParticipantEventId(1L)).thenReturn(List.of());
        doReturn(AppUser.builder().id(7L).username("operator").fullName("Operator")
                .roles(Set.of(Role.ADMIN)).allowedEventIds(Set.of(1L)).build())
                .when(security).getAuthenticatedUser("Bearer test");
        mvc = standaloneSetup(controller, editor).build();
    }

    @Test void acceptedEmsApprovalMovesToReminderWhileClientDeclineStillVetoes() throws Exception {
        rows.add(participant(1, "approve", "pending"));
        rows.add(participant(2, "approve", "decline"));
        rows.add(participant(3, "pending", "approve"));
        rows.add(participant(4, "decline", "approve"));
        EventParticipant legacy = participant(5, null, "approve");
        legacy.setPreEventApprovalStatus("approve");
        legacy.setNotes("[Origin: Request] [PreEventApproval: approve]");
        rows.add(legacy);
        EventParticipant manualRegistered = participant(6, null, "pending");
        manualRegistered.setNotes("[Origin: Request]");
        rows.add(manualRegistered);

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), ids(response("participants", "request")));
        assertEquals(List.of(1L, 3L, 5L), ids(response("participants", "pre_event")));
        assertEquals(List.of(1L, 5L), ids(response("participants", "reminder")));
        assertEquals(List.of(1L, 5L), ids(response("participants", "reminder_dday")));
        assertEquals(List.of(2L, 4L), ids(response("participants", "declined")));
        assertEquals(2, response("statistics", "reminder").at("/scopes/all/approvedRegisterTotal").asInt());
        assertEquals(2, response("statistics", "pre_event").at("/scopes/all/approve").asInt());
        assertEquals(1, response("statistics", "pre_event").at("/scopes/all/pending").asInt());
        assertEquals("pending", rows.get(0).getConfirmationStatus(), "EMS approval does not overwrite Client Approval");
        assertEquals("approve", legacy.getPreEventApprovalStatus(), "Stored manual decisions stay effective until the next sync");
        assertEquals("approve", response("participants/export", "pre_event").path("rows").get(0).path("Pre Event Approval").asText());
        verify(participants, never()).save(any());
    }

    @Test void manualRegisteredOrAttendanceDoesNotPromoteUnknownEmsApproval() throws Exception {
        EventParticipant row = participant(1, null, "pending");
        row.setNotes("[Origin: Request]");
        rows.add(row);
        assertEquals(0, response("participants", "pre_event").path("total").asInt(),
                "Attendance registered alone is not evidence that an EMS registration exists");
        mvc.perform(put("/api/event-participants/1/status")
                        .param("participantStatus", "registered").header("Authorization", "Bearer test"))
                .andExpect(status().isOk());
        assertNull(row.getEmsApprovalStatus());
        assertEquals("pending", row.getPreEventApprovalStatus());
        assertEquals(0, response("participants", "reminder").path("total").asInt());
        assertEquals(0, response("participants", "reminder_dday").path("total").asInt());
    }

    @Test void acceptedRegistrationWithAHistoricalClientDeclineReviewCannotEnterOperationalTabsOrTheirExports() throws Exception {
        EventParticipant exact = participant(1, "approve", "pending");
        exact.setEmsIdentityReviewMessage("HP juga dipakai profil lama yang ditolak client; perlu ditinjau.");
        exact.setEmsClientDeclineReviewRequired(true);
        EventParticipant historical = participant(2, "pending", "decline");
        rows.addAll(List.of(exact, historical));
        assertEquals(List.of(1L, 2L), ids(response("participants", "request")));
        for (String tab : List.of("pre_event", "reminder", "reminder_dday")) {
            assertEquals(0, response("participants", tab).path("total").asInt(), tab);
            assertEquals(0, response("participants/export", tab).path("rows").size(), tab + " export");
        }
        assertEquals(List.of(1L, 2L), ids(response("participants", "declined")));
        assertEquals(0, response("statistics", "reminder").at("/scopes/all/approvedRegisterTotal").asInt());
        assertEquals("pending", exact.getConfirmationStatus()); assertEquals("decline", historical.getConfirmationStatus());
        assertEquals("approve", exact.getEmsApprovalStatus()); assertEquals("approve", exact.getPreEventApprovalStatus());
        verify(participants, never()).save(any());
    }

    @Test void manualEventsRetainManualApprovalAndExistingReminderRules() throws Exception {
        event.setEmsEventId(null);
        rows.add(participant(1, null, "approve"));
        EventParticipant declined = participant(2, null, "approve");
        declined.setPreEventApprovalStatus("decline");
        rows.add(declined);
        assertEquals(List.of(1L), ids(response("participants", "reminder")));
        mvc.perform(put("/api/event-participants/1/status").param("preEventApprovalStatus", "approve")
                        .header("Authorization", "Bearer test")).andExpect(status().isOk());
        assertEquals("approve", rows.get(0).getPreEventApprovalStatus());
        assertEquals("crm", rows.get(0).getApprovalSource());
    }

    @Test void explicitManualApprovalChangesCurrentDecisionWithoutChangingLastSyncOrClientApproval() throws Exception {
        EventParticipant row = participant(1, "pending", "pending");
        rows.add(row);
        mvc.perform(put("/api/event-participants/1/status").param("callStatus", "CONNECTED")
                        .param("preEventApprovalStatus", "approve")
                        .header("Authorization", "Bearer test"))
                .andExpect(status().isOk());
        assertEquals(ParticipantStatus.registered, row.getParticipantStatus());
        assertEquals("pending", row.getConfirmationStatus());
        assertTrue(row.getNotes().contains("[PreEventApproval: approve]"));
        assertEquals("approve", row.getPreEventApprovalStatus());
        assertEquals("pending", row.getEmsApprovalStatus());
        assertEquals("CONNECTED", row.getCallStatus());
        assertEquals(List.of(1L), ids(response("participants", "reminder")));

        mvc.perform(put("/api/event-participants/1/status").param("confirmationStatus", "decline")
                        .header("Authorization", "Bearer test")).andExpect(status().isOk());
        assertEquals("decline", row.getConfirmationStatus(), "CRM can still reject the client decision");
        assertEquals("pending", row.getEmsApprovalStatus());
        assertEquals(0, response("participants", "reminder").path("total").asInt());
    }

    @Test void notesEditingRetainsCurrentApprovalUntilAnExplicitManualChange() throws Exception {
        EventParticipant row = participant(1, "pending", "pending");
        rows.add(row);
        mvc.perform(put("/api/event-participants/1/status")
                        .param("notes", "[PreEventApproval: approve] Keep this CRM note")
                        .param("preEventApprovalStatus", "pending").param("callStatus", "CONNECTED")
                        .header("Authorization", "Bearer test"))
                .andExpect(status().isOk());
        assertEquals("pending", row.getEmsApprovalStatus());
        assertEquals("pending", row.getPreEventApprovalStatus());
        assertEquals("[PreEventApproval: pending] Keep this CRM note", row.getNotes());
        assertEquals("CONNECTED", row.getCallStatus());
        assertEquals(0, response("participants", "reminder").path("total").asInt());

        mvc.perform(put("/api/event-participants/1/status").param("preEventApprovalStatus", "decline")
                        .param("notes", "Updated CRM note").header("Authorization", "Bearer test"))
                .andExpect(status().isOk());
        assertEquals("decline", row.getPreEventApprovalStatus());
        assertEquals("pending", row.getEmsApprovalStatus());
        assertEquals("[PreEventApproval: decline] Updated CRM note", row.getNotes());
    }

    @Test void bulkEditsCurrentApprovalsWithoutRewritingSnapshotsOrClientDecisions() throws Exception {
        EventParticipant accepted = participant(1, "approve", "pending");
        EventParticipant pending = participant(2, "pending", "pending");
        rows.addAll(List.of(accepted, pending));
        mvc.perform(patch("/api/event-participants/bulk").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantIds\":[1,2],\"preEventApprovalStatus\":\"decline\"}")
                        .header("Authorization", "Bearer test"))
                .andExpect(status().isOk());
        assertEquals(ParticipantStatus.registered, accepted.getParticipantStatus());
        assertEquals(ParticipantStatus.registered, pending.getParticipantStatus());
        assertEquals("approve", accepted.getEmsApprovalStatus());
        assertEquals("pending", pending.getEmsApprovalStatus());
        assertEquals("decline", accepted.getPreEventApprovalStatus());
        assertEquals("decline", pending.getPreEventApprovalStatus());
        assertEquals("pending", accepted.getConfirmationStatus());
        assertEquals("pending", pending.getConfirmationStatus());
        assertTrue(pending.getNotes().contains("[PreEventApproval: decline]"));
        assertEquals(0, response("participants", "reminder").path("total").asInt());
        verify(participants, times(2)).save(any());
    }

    @Test void addingManualParticipantAllowsCurrentApprovalButCannotSeedAnEmsSnapshot() throws Exception {
        Database database = contact(1);
        when(databases.findById(1L)).thenReturn(Optional.of(database));
        String created = mvc.perform(post("/api/event-participants").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":1,\"databaseId\":1,\"preEventApprovalStatus\":\"approve\"}")
                        .header("Authorization", "Bearer test")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode manual = json.readTree(created).get(0);
        assertEquals("approve", manual.path("preEventApprovalStatus").asText());
        assertTrue(manual.path("emsApprovalStatus").isNull());

        String response = mvc.perform(post("/api/event-participants").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":1,\"databaseId\":1,\"emsApprovalStatus\":\"approve\",\"emsCompanyName\":\"Forged company\",\"emsCompanyReviewRequired\":true,\"emsIdentityReviewMessage\":\"Forged review\",\"emsClientDeclineReviewRequired\":true,\"notes\":\"[PreEventApproval: approve] CRM note\"}")
                        .header("Authorization", "Bearer test")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode saved = json.readTree(response).get(0);
        assertEquals("ems", saved.path("approvalSource").asText());
        assertTrue(saved.path("emsApprovalStatus").isNull());
        assertTrue(saved.path("emsCompanyName").isNull());
        assertTrue(saved.path("emsCompanyReviewRequired").isNull());
        assertTrue(saved.path("emsIdentityReviewMessage").isNull());
        assertTrue(saved.path("emsClientDeclineReviewRequired").isNull());
        assertEquals("pending", saved.path("preEventApprovalStatus").asText());
        assertEquals("[PreEventApproval: pending] CRM note", saved.path("notes").asText());
    }

    @Test void manualApproveEntersPreEventButStillRequiresRegistrationBeforeReminder() throws Exception {
        EventParticipant row = participant(1, null, "pending");
        row.setParticipantStatus(ParticipantStatus.white);
        row.setAttendanceStatus(AttendanceStatus.invited);
        rows.add(row);
        mvc.perform(put("/api/event-participants/1/status").param("preEventApprovalStatus", "approve")
                        .header("Authorization", "Bearer test")).andExpect(status().isOk());
        assertEquals(List.of(1L), ids(response("participants", "pre_event")));
        assertEquals(0, response("participants", "reminder").path("total").asInt());
        assertEquals(ParticipantStatus.white, row.getParticipantStatus());
        assertEquals(AttendanceStatus.invited, row.getAttendanceStatus());
        assertNull(row.getEmsApprovalStatus());

        mvc.perform(put("/api/event-participants/1/status").param("participantStatus", "registered")
                        .header("Authorization", "Bearer test")).andExpect(status().isOk());
        assertEquals(List.of(1L), ids(response("participants", "reminder")));
        assertEquals(List.of(1L), ids(response("participants", "reminder_dday")));
        assertEquals("pending", row.getConfirmationStatus());
    }

    @Test void currentManualApprovalCanOverrideHistoricalSourceDeclineWithoutErasingIt() throws Exception {
        EventParticipant row = participant(1, "decline", "pending");
        row.setParticipantStatus(ParticipantStatus.unable_to_attend);
        row.setAttendanceStatus(AttendanceStatus.cancelled);
        row.setNotes("[EMS Declined] [Origin: EMS Sync] [PreEventApproval: decline]");
        rows.add(row);
        mvc.perform(put("/api/event-participants/1/status").param("preEventApprovalStatus", "approve")
                        .header("Authorization", "Bearer test")).andExpect(status().isOk());
        assertEquals(List.of(1L), ids(response("participants", "pre_event")));
        assertEquals(0, response("participants", "reminder").path("total").asInt());
        mvc.perform(put("/api/event-participants/1/status").param("participantStatus", "registered")
                        .header("Authorization", "Bearer test")).andExpect(status().isOk());
        assertEquals(List.of(1L), ids(response("participants", "reminder")));
        assertEquals(0, response("participants", "declined").path("total").asInt());
        assertEquals("decline", row.getEmsApprovalStatus());
        assertEquals(AttendanceStatus.cancelled, row.getAttendanceStatus());

        mvc.perform(put("/api/event-participants/1/status").param("confirmationStatus", "decline")
                        .header("Authorization", "Bearer test")).andExpect(status().isOk());
        assertEquals(0, response("participants", "reminder").path("total").asInt());
        assertEquals(List.of(1L), ids(response("participants", "declined")));
    }

    @Test void allowingManualApprovalsDoesNotGiveViewerPermissionToEditThem() throws Exception {
        EventParticipant row = participant(1, "pending", "pending");
        rows.add(row);
        doReturn(AppUser.builder().id(8L).username("viewer").fullName("Viewer")
                .roles(Set.of(Role.USER)).allowedEventIds(Set.of(1L)).build())
                .when(security).getAuthenticatedUser("Bearer test");
        mvc.perform(put("/api/event-participants/1/status").param("preEventApprovalStatus", "approve")
                        .header("Authorization", "Bearer test")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/event-participants/bulk").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantIds\":[1],\"preEventApprovalStatus\":\"approve\"}")
                        .header("Authorization", "Bearer test")).andExpect(status().isForbidden());
        assertEquals("pending", row.getPreEventApprovalStatus());
        verify(participants, never()).save(any());
    }

    @Test void legacyWhiteRowsAreIncludedInNotRespondYetStatistics() throws Exception {
        event.setEmsEventId(null);
        EventParticipant row = participant(1, null, "approve");
        row.setParticipantStatus(ParticipantStatus.white);
        row.setAttendanceStatus(AttendanceStatus.invited);
        rows.add(row);
        assertEquals(1, response("participants", "pre_event", "remarks", "not_respon_yet").path("total").asInt());
        assertEquals(1, response("statistics", "pre_event", "remarks", "not_respon_yet").at("/scopes/all/notRespondYet").asInt());
    }

    @Test void changingEmsBindingInvalidatesApprovalWithoutChangingClientDataOrReminders() throws Exception {
        event.setEmsSource(EmsSource.V2);
        EventParticipant row = participant(1, "approve", "pending");
        row.setNotes("[Origin: EMS Sync] [PreEventApproval: approve] Keep CRM note");
        row.setEmsCompanyName("Source company");row.setEmsCompanyReviewRequired(true);
        row.setReminderH7("confirm");
        row.setReminderHariH("on_location");
        rows.add(row);
        String profileBefore = json.writeValueAsString(row.getDatabase());
        assertEquals(1, response("participants", "reminder").path("total").asInt());

        mvc.perform(put("/api/events/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed fixture\",\"emsEventId\":14,\"emsSource\":\"V2\"}")
                        .header("Authorization", "Bearer test")).andExpect(status().isOk());
        assertEquals("approve", row.getEmsApprovalStatus(), "Normal edits preserve the current EMS snapshot");
        assertEquals("Source company",row.getEmsCompanyName());assertEquals(true,row.getEmsCompanyReviewRequired());
        verify(participants, never()).saveAll(any());

        for (String nextBinding : List.of("\"emsEventId\":15,\"emsSource\":\"V2\"",
                "\"emsEventId\":15,\"emsSource\":\"LEGACY\"",
                "\"emsEventId\":null,\"emsSource\":\"LEGACY\"")) {
            row.setEmsApprovalStatus("approve");
            row.setPreEventApprovalStatus("approve");
            row.setEmsCompanyName("Source company");row.setEmsCompanyReviewRequired(true);
            mvc.perform(put("/api/events/1").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Renamed fixture\"," + nextBinding + "}")
                            .header("Authorization", "Bearer test")).andExpect(status().isOk());
            assertNull(row.getEmsApprovalStatus());
            assertNull(row.getEmsCompanyName());assertNull(row.getEmsCompanyReviewRequired());
            assertEquals("pending", row.getPreEventApprovalStatus());
            assertEquals("[Origin: EMS Sync] [PreEventApproval: pending] Keep CRM note", row.getNotes());
            assertEquals("pending", row.getConfirmationStatus());
            assertEquals("confirm", row.getReminderH7());
            assertEquals("on_location", row.getReminderHariH());
            assertEquals(profileBefore, json.writeValueAsString(row.getDatabase()));
            assertEquals(0, response("participants", "reminder").path("total").asInt());
        }
    }

    @Test void emsRegisteredStatisticsFollowRemarksWithoutChangingManualCountsOrReminderEligibility() throws Exception {
        EventParticipant legacy = participant(1, null, "approve");
        legacy.setParticipantStatus(ParticipantStatus.white);
        rows.add(legacy);
        assertEquals(0, response("statistics", "pre_event").at("/scopes/all/totalRegister").asInt());
        assertEquals(1, response("statistics", "pre_event").at("/scopes/all/notRespondYet").asInt());
        assertEquals(0, activitySummary().at("/participantsSummary/registered").asInt());
        assertEquals(1, activitySummary().at("/participantsSummary/notRespond").asInt());

        legacy.setEmsApprovalStatus("approve");
        legacy.setPreEventApprovalStatus("approve");
        assertEquals(List.of(1L), ids(response("participants", "reminder")), "Statistics must not change the operational attendance gate");
        legacy.setEmsApprovalStatus(null);
        rows.add(participant(2, "pending", "pending"));
        assertEquals(1, response("statistics", "pre_event").at("/scopes/all/totalRegister").asInt(), "Pending EMS registrations are still Registered");
        assertEquals(1, activitySummary().at("/participantsSummary/registered").asInt());

        event.setEmsEventId(null);
        assertEquals(2, response("statistics", "pre_event").at("/scopes/all/totalRegister").asInt(), "Manual events retain their attendance fallback");
        assertEquals(2, activitySummary().at("/participantsSummary/registered").asInt());
    }

    private JsonNode activitySummary() throws Exception {
        return json.readTree(mvc.perform(get("/api/event-participants/event/1/activity-summary")
                        .header("Authorization", "Bearer test")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private EventParticipant participant(long id, String emsApproval, String clientApproval) {
        return EventParticipant.builder().id(id).event(event).database(contact(id))
                .participantStatus(ParticipantStatus.registered).attendanceStatus(AttendanceStatus.registered)
                .confirmationStatus(clientApproval).preEventApprovalStatus(emsApproval == null ? "pending" : emsApproval).emsApprovalStatus(emsApproval)
                .notes("[Origin: Request] [PIC: Operator]").build();
    }

    private Database contact(long id) {
        return Database.builder().id(id).firstName("Contact" + id).lastName("Example")
                .company(Company.builder().id(1L).name("Fixture company").build())
                .databaseDestination(DatabaseDestination.COMPANY).source(DatabaseSource.excel_import).build();
    }

    private JsonNode response(String endpoint, String tab, String... filters) throws Exception {
        var request = get("/api/events/1/" + endpoint).param("tab", tab).header("Authorization", "Bearer test");
        for (int index = 0; index < filters.length; index += 2) request.param(filters[index], filters[index + 1]);
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private List<Long> ids(JsonNode response) {
        List<Long> result = new ArrayList<>();
        response.path("items").forEach(item -> result.add(item.path("id").asLong()));
        return result;
    }
}
