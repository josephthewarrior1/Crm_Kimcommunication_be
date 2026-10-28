package com.crm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class EmsV2ClientTest {
    private static final String API = "https://ems-api.example.invalid";
    private static final String IAM = "https://ems-iam.example.invalid";
    private static final String CREATED = "2026-10-21T02:00:00Z";
    private static final String UPDATED = "2026-10-21T03:00:00Z";
    private static final ObjectMapper JSON = new ObjectMapper();
    private RestTemplate http;
    private MockRestServiceServer server;
    private EmsV2Client client;

    private void setup(boolean staticToken) {
        http = new RestTemplate();
        server = MockRestServiceServer.bindTo(http).build();
        client = new EmsV2Client(http);
        ReflectionTestUtils.setField(client, "apiUrl", API);
        ReflectionTestUtils.setField(client, "iamUrl", IAM);
        ReflectionTestUtils.setField(client, "adminEmail", "integration@example.invalid");
        ReflectionTestUtils.setField(client, "adminPassword", "anonymous-test-password");
        ReflectionTestUtils.setField(client, "configuredToken", staticToken ? "fixture-token" : "");
    }

    @Test
    void joinsByRegistrationIdAndPreservesMissingFieldsAndActualAttendance() throws Exception {
        setup(true);
        Map<String, Object> accepted = registration(11L, "ACCEPTED", "Alex Example");
        Map<String, Object> form = form(accepted);
        form.put("salutation", "Dr");
        form.put("phone", "+628111111111");
        form.put("company_name", "Example Company");
        form.put("job_title", "Engineer");
        Map<String, Object> pending = registration(12L, "PENDING", "Casey Example");
        Map<String, Object> declined = registration(13L, "DECLINED", "Taylor Example");
        Map<String, Object> checkout = registration(14L, "ACCEPTED", "Morgan Example");
        Map<String, Object> manualAbsent = attendance(12L, "ABSENT", UPDATED);
        manualAbsent.put("source", "MANUAL"); // EMS retains history even after an ABSENT override.
        // Same email deliberately: this adapter must join IDs, never deduplicate by email.
        expectGet("/api/events/9/registrations", List.of(accepted, pending, declined, checkout));
        expectGet("/api/events/9/attendance", List.of(
                attendance(14L, "CHECKED_OUT", UPDATED), manualAbsent,
                attendance(11L, "PRESENT", UPDATED)));

        List<Map<String, Object>> result = client.getEventParticipants(9L);
        assertEquals(List.of(11L, 12L, 13L, 14L), result.stream().map(row -> row.get("id")).toList());
        assertEquals(UPDATED, result.get(0).get("checked_in_at"));
        assertEquals(UPDATED, result.get(0).get("verified_at"));
        Map<?, ?> profile = (Map<?, ?>) result.get(0).get("user_profile");
        assertEquals("Alex Example", profile.get("registered_name"));
        assertEquals("Dr", profile.get("registered_salutation"));
        assertEquals("Example Company", profile.get("company"));
        assertEquals("Engineer", profile.get("job_title"));
        assertFalse(profile.containsKey("registered_last_name"));
        assertFalse(result.get(1).containsKey("checked_in_at"));
        assertFalse(result.get(1).containsKey("verified_at"));
        assertFalse(((Map<?, ?>) result.get(1).get("user_profile")).containsKey("phone"));
        assertFalse(((Map<?, ?>) result.get(1).get("user_profile")).containsKey("company"));
        assertEquals(UPDATED, result.get(2).get("declined_at"));
        assertFalse(result.get(2).containsKey("checked_in_at"));
        assertEquals(UPDATED, result.get(3).get("checked_in_at"));
        server.verify();
    }

    @Test
    void industryUsesTheWinningFieldAndExactOptionValueWithoutGuessing() throws Exception {
        setup(true);
        List<Map<String, Object>> registrations = new ArrayList<>();
        for (long id = 11; id <= 16; id++) registrations.add(registration(id, "ACCEPTED", "Fixture " + id));
        form(registrations.get(0)).putAll(Map.of("industry", "technology", "company_industry", "technology"));
        form(registrations.get(1)).putAll(Map.of("industry", " ", "company_industry", "technology"));
        form(registrations.get(2)).put("business_sector", "education_higher");
        form(registrations.get(3)).put("industry", "unknown_code");
        form(registrations.get(4)).put("industry", "Technology");
        form(registrations.get(5)).put("industry", "TECHNOLOGY");
        expectGet("/api/events/9/registrations", registrations);
        expectGet("/api/events/9/attendance", registrations.stream()
                .map(row -> attendance((Long) row.get("id"), "ABSENT", null)).toList());
        expectGet("/api/events/9/page", Map.of("formFields", List.of(
                industryField("industry", "DROPDOWN", "technology", "Technology"),
                industryField("company_industry", "DROPDOWN", "technology", "Company-specific label"),
                industryField("business_sector", "DROPDOWN", "education_higher", "Higher Education"))));

        List<Map<String, Object>> profiles = client.getEventParticipants(9L).stream()
                .map(row -> (Map<String, Object>) row.get("user_profile")).toList();
        assertEquals(List.of("Technology", "Company-specific label", "Higher Education", "unknown_code", "Technology", "TECHNOLOGY"),
                profiles.stream().map(profile -> profile.get("industry")).toList());
        assertEquals("technology", profiles.get(0).get("ems_v2_industry_raw"));
        assertEquals("technology", profiles.get(1).get("ems_v2_industry_raw"));
        assertEquals("education_higher", profiles.get(2).get("ems_v2_industry_raw"));
        for (int i = 3; i < profiles.size(); i++) assertFalse(profiles.get(i).containsKey("ems_v2_industry_raw"));
        server.verify();
    }

    @Test
    void industryOptionsBelongToEachEventAndTextFieldsArePreserved() throws Exception {
        setup(true);
        for (long eventId = 9; eventId <= 11; eventId++) {
            Map<String, Object> row = registration(11L, "ACCEPTED", "Fixture");
            form(row).put("industry", "technology");
            expectGet("/api/events/" + eventId + "/registrations", List.of(row));
            expectGet("/api/events/" + eventId + "/attendance", List.of(attendance(11L, "ABSENT", null)));
            expectGet("/api/events/" + eventId + "/page", Map.of("formFields", List.of(
                    industryField("industry", eventId == 11 ? "TEXT" : "DROPDOWN", "technology", "Label " + eventId))));
        }
        for (long eventId = 9; eventId <= 11; eventId++) {
            Map<?, ?> profile = (Map<?, ?>) client.getEventParticipants(eventId).get(0).get("user_profile");
            assertEquals(eventId == 11 ? "technology" : "Label " + eventId, profile.get("industry"));
            assertEquals(eventId != 11, profile.containsKey("ems_v2_industry_raw"));
        }
        server.verify();
    }

    @Test
    void malformedOrUnavailableIndustryMetadataFailsInsteadOfReturningRawPartialData() throws Exception {
        Map<String, Object> duplicateOptions = new LinkedHashMap<>(industryField("industry", "DROPDOWN", "technology", "Technology"));
        duplicateOptions.put("options", List.of(Map.of("value", "technology", "label", "Technology"),
                Map.of("value", "technology", "label", "Conflicting label")));
        for (Object page : List.of(Map.of(), Map.of("formFields", "invalid"),
                Map.of("formFields", List.of(industryField("industry", "DROPDOWN", "technology", " "))),
                Map.of("formFields", List.of(duplicateOptions)))) {
            setup(true);
            Map<String, Object> row = registration(11L, "ACCEPTED", "Fixture");
            form(row).put("industry", "technology");
            expectGet("/api/events/9/registrations", List.of(row));
            expectGet("/api/events/9/attendance", List.of(attendance(11L, "ABSENT", null)));
            expectGet("/api/events/9/page", page);
            assertGateway(() -> client.getEventParticipants(9L));
            server.verify();
        }
        setup(true);
        Map<String, Object> row = registration(11L, "ACCEPTED", "Fixture");
        form(row).put("industry", "technology");
        expectGet("/api/events/9/registrations", List.of(row));
        expectGet("/api/events/9/attendance", List.of(attendance(11L, "ABSENT", null)));
        server.expect(requestTo(API + "/api/events/9/page")).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertGateway(() -> client.getEventParticipants(9L));
        server.verify();
    }

    @Test
    void displayNameFallbackRemovesOnlyTheExplicitRecordedSalutation() throws Exception {
        setup(true);
        Map<String, Object> row = registration(11L, "ACCEPTED", "unused");
        form(row).remove("full_name");
        form(row).put("salutation", "Ms");
        row.put("displayName", "Ms Example Person");
        row.remove("updatedAt");
        expectGet("/api/events/9/registrations", List.of(row));
        expectGet("/api/events/9/attendance", List.of(attendance(11L, "ABSENT", null)));
        Map<String, Object> normalized = client.getEventParticipants(9L).get(0);
        assertEquals("Example Person", ((Map<?, ?>) normalized.get("user_profile")).get("registered_name"));
        assertEquals(CREATED, normalized.get("verified_at"));
        server.verify();
    }

    @Test
    void rejectsDuplicateRegistrationBeforeReadingAttendance() throws Exception {
        setup(true);
        expectGet("/api/events/9/registrations", List.of(registration(11L, "PENDING", "Example"), registration(11L, "ACCEPTED", "Other")));
        assertGateway(() -> client.getEventParticipants(9L));
        server.verify();
    }

    @Test
    void rejectsOrphanDuplicateMissingAndUnknownAttendanceInsteadOfReturningPartialData() throws Exception {
        List<List<Map<String, Object>>> bad = List.of(
                List.of(attendance(999L, "PRESENT", UPDATED)),
                List.of(attendance(11L, "ABSENT", null), attendance(11L, "ABSENT", null)),
                List.of(), List.of(attendance(11L, "MYSTERY", null)));
        for (List<Map<String, Object>> rows : bad) {
            setup(true);
            expectGet("/api/events/9/registrations", List.of(registration(11L, "PENDING", "Example")));
            expectGet("/api/events/9/attendance", rows);
            assertGateway(() -> client.getEventParticipants(9L));
            server.verify();
        }
    }

    @Test
    void malformedIdentityStatusAndTimestampNeverBecomePlaceholderContacts() throws Exception {
        for (String defect : List.of("name", "date", "id", "status", "source")) {
            setup(true);
            Map<String, Object> row = registration(11L, "PENDING", "Example");
            switch (defect) {
                case "name" -> { form(row).remove("full_name"); row.remove("displayName"); }
                case "date" -> row.put("createdAt", "not-a-date");
                case "id" -> row.put("id", 11.5);
                case "status" -> row.put("status", "UNKNOWN");
                case "source" -> row.put("source", "UNKNOWN");
            }
            expectGet("/api/events/9/registrations", List.of(row));
            if (defect.equals("name") || defect.equals("date")) {
                expectGet("/api/events/9/attendance", List.of(attendance(11L, "ABSENT", null)));
            }
            assertGateway(() -> client.getEventParticipants(9L));
            server.verify();
        }
    }

    @Test
    void automaticIamLoginUsesStrictEnvelopeAndReusesToken() throws Exception {
        setup(false);
        expectLogin("issued-token");
        expectEvents("issued-token");
        expectEvents("issued-token");
        assertEquals(List.of(Map.of("id", 9L, "name", "Example Event")), client.getEvents());
        client.getEvents();
        server.verify();
    }

    @Test
    void expiredUpstreamSessionRelogsOnceAndRetriesOnlyTheGet() throws Exception {
        setup(false);
        expectLogin("first-token");
        server.expect(requestTo(API + "/api/events?archived=false")).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectLogin("second-token");
        expectEvents("second-token");
        assertEquals(1, client.getEvents().size());
        server.verify();
    }

    @Test
    void failsClosedOnApiErrorsAndRedirectsWithoutExposingResponseBody() throws Exception {
        setup(true);
        server.expect(requestTo(API + "/api/events?archived=false"))
                .andRespond(withSuccess("{\"data\":[],\"error\":{\"message\":\"private fixture detail\"}}", MediaType.APPLICATION_JSON));
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, client::getEvents);
        assertEquals(502, exception.getStatusCode().value());
        assertFalse(exception.getReason().contains("private fixture detail"));
        server.verify();
        setup(true);
        server.expect(requestTo(API + "/api/events?archived=false"))
                .andRespond(withStatus(HttpStatus.FOUND).location(URI.create("https://unexpected.example.invalid")));
        assertGateway(client::getEvents);
        server.verify();
        HttpURLConnection connection = mock(HttpURLConnection.class);
        ReflectionTestUtils.invokeMethod(EmsV2Client.requestFactory(), "prepareConnection", connection, "GET");
        verify(connection).setInstanceFollowRedirects(false);
    }

    @Test
    void unconfiguredClientAndInvalidEventIdDoNotMakeRequests() {
        setup(true);
        ReflectionTestUtils.setField(client, "apiUrl", "");
        assertEquals(503, assertThrows(ResponseStatusException.class, client::getEvents).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> client.getEventParticipants(0L)).getStatusCode().value());
        server.verify();
    }

    @Test
    void configuredTokenAcceptsBearerPrefixButRejectsHeaderInjectionBeforeAnyRequest() throws Exception {
        setup(true);
        ReflectionTestUtils.setField(client, "configuredToken", "  bEaReR fixture-token  ");
        expectEvents("fixture-token");
        client.getEvents();
        server.verify();
        for (String malformed : List.of("Bearer", "Bearer  ", "fixture\r\nInjected: value", "two tokens")) {
            setup(true);
            ReflectionTestUtils.setField(client, "configuredToken", malformed);
            assertEquals(503, assertThrows(ResponseStatusException.class, client::getEvents).getStatusCode().value());
            server.verify();
        }
    }

    private void expectLogin(String token) throws Exception {
        server.expect(requestTo(IAM + "/api/auth/login")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json(JSON.writeValueAsString(Map.of("email", "integration@example.invalid", "password", "anonymous-test-password"))))
                .andRespond(withSuccess(envelope(Map.of("accessToken", token, "tokenType", "Bearer", "expiresIn", 900)), MediaType.APPLICATION_JSON));
    }

    private void expectEvents(String token) throws Exception {
        server.expect(requestTo(API + "/api/events?archived=false")).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andRespond(withSuccess(envelope(List.of(Map.of("id", 9, "name", "Example Event", "status", "PUBLISHED"))), MediaType.APPLICATION_JSON));
    }

    private void expectGet(String path, Object data) throws Exception {
        server.expect(requestTo(API + path)).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fixture-token"))
                .andRespond(withSuccess(envelope(data), MediaType.APPLICATION_JSON));
    }

    private static String envelope(Object data) throws Exception {
        return JSON.writeValueAsString(Map.of("data", data, "timestamp", CREATED));
    }

    private static Map<String, Object> registration(long id, String status, String name) {
        return new LinkedHashMap<>(Map.of("id", id, "referenceCode", "R-" + id, "status", status,
                "email", "person@example.invalid", "displayName", name, "formData", new LinkedHashMap<>(Map.of("full_name", name)),
                "source", "PUBLIC", "createdAt", CREATED, "updatedAt", UPDATED));
    }

    private static Map<String, Object> industryField(String key, String type, String value, String label) {
        return Map.of("fieldKey", key, "fieldType", type, "label", "Industry",
                "options", List.of(Map.of("value", value, "label", label)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> form(Map<String, Object> registration) {
        return (Map<String, Object>) registration.get("formData");
    }

    private static Map<String, Object> attendance(long id, String status, String checkIn) {
        Map<String, Object> row = new LinkedHashMap<>(Map.of("registrationId", id, "status", status, "source", "SCAN"));
        if (checkIn != null) row.put("checkedInAt", checkIn);
        if (status.equals("CHECKED_OUT")) row.put("checkedOutAt", UPDATED);
        return row;
    }

    private static void assertGateway(org.junit.jupiter.api.function.Executable call) {
        assertEquals(502, assertThrows(ResponseStatusException.class, call).getStatusCode().value());
    }
}
