package com.crm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.crm.domain.EmsSource;
import com.crm.domain.Event;
import com.crm.domain.Company;
import com.crm.domain.Database;
import com.crm.domain.DatabaseEmail;
import com.crm.domain.EventParticipant;
import com.crm.domain.EventParticipantActivity;
import com.crm.repository.*;
import liquibase.changelog.ChangeLogParameters;
import liquibase.database.core.PostgresDatabase;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class EmsServiceRoutingTest {
    private final EmsService service = new EmsService();
    private final EmsV2Client v2 = mock(EmsV2Client.class);
    private final EventParticipantRepository participants = mock(EventParticipantRepository.class);
    private final DatabaseRepository databases = mock(DatabaseRepository.class);
    private final DatabaseEmailRepository emails = mock(DatabaseEmailRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final EventParticipantActivityRepository activities = mock(EventParticipantActivityRepository.class);
    private MockRestServiceServer legacy;

    @BeforeEach void setup() {
        var rest = new RestTemplate();
        legacy = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
        ReflectionTestUtils.setField(service, "restTemplate", rest);
        ReflectionTestUtils.setField(service, "emsApiUrl", "https://legacy.invalid");
        ReflectionTestUtils.setField(service, "emsAdminEmail", "fixture@example.invalid");
        ReflectionTestUtils.setField(service, "emsAdminPassword", "fixture-only");
        ReflectionTestUtils.setField(service, "emsV2Client", v2);
        ReflectionTestUtils.setField(service, "eventParticipantRepository", participants);
        ReflectionTestUtils.setField(service, "databaseRepository", databases);
        ReflectionTestUtils.setField(service, "databaseEmailRepository", emails);
        ReflectionTestUtils.setField(service, "companyRepository", companies);
        ReflectionTestUtils.setField(service, "eventParticipantActivityRepository", activities);
    }

    @Test void originalOverloadsExplicitLegacyAndNullSourceUseOnlyTheLegacyTransport() {
        expectLegacyLogin(6);
        expectLegacyGet("/api/admin/v1/events_upcoming", 3, "[{\"id\":42}]");
        expectLegacyGet("/api/admin/v1/events/42/registers", 3, "[{\"id\":43}]");
        var expectedEvents = List.of(Map.of("id", 42));
        var expectedParticipants = List.of(Map.of("id", 43));

        assertEquals(expectedEvents, service.getUpcomingEvents());
        assertEquals(expectedEvents, service.getUpcomingEvents(EmsSource.LEGACY));
        assertEquals(expectedEvents, service.getUpcomingEvents(null));
        assertEquals(expectedParticipants, service.getEventParticipants(42L));
        assertEquals(expectedParticipants, service.getEventParticipants(42L, EmsSource.LEGACY));
        assertEquals(expectedParticipants, service.getEventParticipants(42L, null));

        legacy.verify();
        verifyNoInteractions(v2, participants, databases, emails, companies, activities);
    }

    @Test void v2UsesTheDedicatedClientAndPropagatesFailuresBeforeAnyCrmWrites() {
        List<Map<String, Object>> result = List.of(Map.of("id", 42L));
        when(v2.getEvents()).thenReturn(result);
        when(v2.getEventParticipants(42L)).thenReturn(result);
        assertSame(result, service.getUpcomingEvents(EmsSource.V2));
        assertSame(result, service.getEventParticipants(42L, EmsSource.V2));
        var failure = new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Fixture gateway failure");
        when(v2.getEvents()).thenThrow(failure);
        when(v2.getEventParticipants(42L)).thenThrow(failure);
        assertSame(failure, assertThrows(ResponseStatusException.class, () -> service.getUpcomingEvents(EmsSource.V2)));
        assertSame(failure, assertThrows(ResponseStatusException.class, () -> service.getEventParticipants(42L, EmsSource.V2)));
        assertSame(failure, assertThrows(ResponseStatusException.class, () -> service.syncParticipantsForEvent(
                Event.builder().id(1L).emsEventId(42L).emsSource(EmsSource.V2).build())));
        legacy.verify();
        verifyNoInteractions(participants, databases, emails, companies, activities);
    }

    @Test void syncUsesEachRecordsSourceAndItsCrmIdWhenLegacyAndV2ExternalIdsMatch() {
        expectLegacyLogin(1);
        expectLegacyGet("/api/admin/v1/events/42/registers", 1, "[]");
        when(v2.getEventParticipants(42L)).thenReturn(List.of());
        assertEquals(0, service.syncParticipantsForEvent(Event.builder().id(1L).emsEventId(42L).build()));
        assertEquals(0, service.syncParticipantsForEvent(Event.builder().id(2L).emsEventId(42L).emsSource(EmsSource.V2).build()));
        legacy.verify();
        verify(v2).getEventParticipants(42L);
        verify(participants).findByEventId(1L);
        verify(participants).findByEventId(2L);
        verifyNoMoreInteractions(v2, participants);
        verifyNoInteractions(databases, emails, companies, activities);
    }

    @Test void repeatedCorporateRegistrationInOneBatchReusesTheNewContactAndParticipant() {
        var event = Event.builder().id(1L).emsEventId(42L).emsSource(EmsSource.V2).build();
        var company = Company.builder().id(50L).name("Fixture Company").industry("Technology").build();
        Map<String, Object> registration = Map.of("id", 70L, "created_at", "2026-10-22T03:04:05Z",
                "registration_code", "FIXTURE", "user_profile", Map.of(
                        "registered_name", "Fixture", "registered_last_name", "Contact",
                        "registered_email", "team@corp.example", "phone", "0800000000",
                        "company", "Fixture Company", "position", "Staff", "salutation", "Ms"));
        when(v2.getEventParticipants(42L)).thenReturn(List.of(registration, registration));
        when(companies.findByNameIgnoreCase("Fixture Company")).thenReturn(Optional.of(company));
        // This repository result is intentionally immutable; the service owns its mutable batch cache.
        when(participants.findByEventId(1L)).thenReturn(List.of());
        var createdDatabases = new ArrayList<Database>();
        var savedEmails = new HashMap<Long, DatabaseEmail>();
        var savedParticipants = new HashMap<Long, EventParticipant>();
        var savedActivities = new HashMap<Long, EventParticipantActivity>();
        var createdParticipants = new AtomicInteger();
        when(databases.save(any())).thenAnswer(invocation -> {
            Database database = invocation.getArgument(0);
            if (database.getId() == null) {
                database.setId(101L + createdDatabases.size());
                createdDatabases.add(database);
            }
            return database;
        });
        when(emails.existsByDatabaseIdAndEmailIgnoreCase(anyLong(), anyString())).thenAnswer(invocation -> {
            DatabaseEmail email = savedEmails.get(invocation.getArgument(0, Long.class));
            return email != null && email.getEmail().equalsIgnoreCase(invocation.getArgument(1, String.class));
        });
        when(emails.save(any())).thenAnswer(invocation -> {
            DatabaseEmail email = invocation.getArgument(0);
            email.setId(301L + savedEmails.size());
            savedEmails.put(email.getDatabase().getId(), email);
            return email;
        });
        when(participants.findByEventIdAndDatabaseId(eq(1L), anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(savedParticipants.get(invocation.getArgument(1, Long.class))));
        when(participants.save(any())).thenAnswer(invocation -> {
            EventParticipant participant = invocation.getArgument(0);
            if (participant.getId() == null) participant.setId(200L + createdParticipants.incrementAndGet());
            savedParticipants.put(participant.getDatabase().getId(), participant);
            return participant;
        });
        when(activities.save(any())).thenAnswer(invocation -> {
            EventParticipantActivity activity = invocation.getArgument(0);
            savedActivities.put(activity.getEventParticipant().getId(), activity);
            return activity;
        });
        when(activities.findByEventParticipantIdOrderByCreatedAtDesc(anyLong())).thenAnswer(invocation -> {
            var activity = savedActivities.get(invocation.getArgument(0, Long.class));
            return activity == null ? List.of() : List.of(activity);
        });

        assertEquals(2, service.syncParticipantsForEvent(event));

        assertEquals(1, createdDatabases.size());
        assertEquals(1, createdParticipants.get());
        assertEquals(1, savedParticipants.size());
        Database database = createdDatabases.get(0);
        EventParticipant participant = savedParticipants.get(database.getId());
        assertSame(database, participant.getDatabase());
        assertSame(database, savedEmails.get(database.getId()).getDatabase());
        assertSame(company, database.getCompany());
        assertSame(event, participant.getEvent());
        assertEquals(LocalDateTime.of(2026, 10, 22, 10, 4, 5), participant.getCreatedAt());
        assertEquals(participant.getCreatedAt(), participant.getRequestedAt());
        verify(databases).save(database);
        verify(emails).save(any(DatabaseEmail.class));
        verify(participants, times(2)).save(participant);
        verify(activities).save(any(EventParticipantActivity.class));
        verify(emails, never()).findAllByEmailIgnoreCase(anyString());
        legacy.verify();
    }

    @Test void v2ResyncRepairsOnlyTheExactOriginalIndustryCode() throws Exception {
        assertCompanyIndustryAfterSync(EmsSource.V2, "technology", "technology", "Technology");
    }

    @Test void v2ResyncPreservesAnExistingHumanIndustry() throws Exception {
        assertCompanyIndustryAfterSync(EmsSource.V2, "Technology Services", "technology", "Technology Services");
    }

    @Test void v2ResyncRequiresRawValueProvenanceToReplaceExistingIndustry() throws Exception {
        assertCompanyIndustryAfterSync(EmsSource.V2, "technology", null, "technology");
    }

    @Test void legacyResyncCannotUseV2IndustryRepairMetadata() throws Exception {
        assertCompanyIndustryAfterSync(EmsSource.LEGACY, "technology", "technology", "technology");
    }

    @Test void industryStillHydratesAnEmptyCompanyValue() throws Exception {
        assertCompanyIndustryAfterSync(EmsSource.V2, "", "technology", "Technology");
    }

    private void assertCompanyIndustryAfterSync(EmsSource source, String current, String raw, String expected) throws Exception {
        var event = Event.builder().id(1L).emsEventId(42L).emsSource(source).build();
        var company = Company.builder().id(50L).name("Fixture Company").industry(current).build();
        var database = Database.builder().id(101L).firstName("Fixture").lastName("Contact").company(company).build();
        var participant = EventParticipant.builder().id(201L).database(database).event(event).build();
        Map<String, Object> profile = new HashMap<>(Map.of("registered_name", "Fixture", "registered_last_name", "Contact",
                "company", "Fixture Company", "industry", "Technology"));
        if (raw != null) profile.put("ems_v2_industry_raw", raw);
        List<Map<String, Object>> rows = List.of(Map.of("id", 70L, "user_profile", profile));
        if (source == EmsSource.V2) {
            when(v2.getEventParticipants(42L)).thenReturn(rows);
        } else {
            expectLegacyLogin(2);
            expectLegacyGet("/api/admin/v1/events/42/registers", 2, new ObjectMapper().writeValueAsString(rows));
        }
        when(companies.findByNameIgnoreCase("Fixture Company")).thenReturn(Optional.of(company));
        when(companies.save(company)).thenReturn(company);
        when(participants.findByEventId(1L)).thenReturn(List.of(participant));
        when(participants.findByEventIdAndDatabaseId(1L, 101L)).thenReturn(Optional.of(participant));
        when(activities.findByEventParticipantIdOrderByCreatedAtDesc(201L))
                .thenReturn(List.of(EventParticipantActivity.builder().id(301L).build()));

        assertEquals(1, service.syncParticipantsForEvent(event));
        assertEquals(expected, company.getIndustry());
        assertEquals(1, service.syncParticipantsForEvent(event));
        assertEquals(expected, company.getIndustry());
        verify(companies, times(current.equals(expected) ? 0 : 1)).save(company);
        verify(databases, never()).save(any());
        verify(activities, never()).save(any());
        legacy.verify();
    }

    @Test void v2InstantsUseJakartaTimeWhileLegacyTimestampsRetainTheirExistingInterpretation() {
        String timestamp = "2026-10-22T03:04:05Z";
        assertEquals(LocalDateTime.of(2026, 10, 22, 10, 4, 5),
                ReflectionTestUtils.invokeMethod(service, "parseEmsDateTime", timestamp, EmsSource.V2));
        assertEquals(LocalDateTime.of(2026, 10, 22, 3, 4, 5),
                ReflectionTestUtils.invokeMethod(service, "parseEmsDateTime", timestamp, EmsSource.LEGACY));
        assertEquals(LocalDateTime.of(2026, 10, 22, 3, 4, 5),
                ReflectionTestUtils.invokeMethod(service, "parseEmsDateTime", timestamp, (EmsSource) null));
    }

    @Test void migrationAddsNullableLegacySourceWithoutRewritingEmsIdsOrParticipants() throws Exception {
        String master = "db/changelog/db.changelog-master.yaml";
        try (var resources = new ClassLoaderResourceAccessor()) {
            var log = ChangeLogParserFactory.getInstance().getParser(master, resources)
                    .parse(master, new ChangeLogParameters(), resources);
            var changes = log.getChangeSets().stream().filter(change -> change.getId().equals("add-ems-source-to-events")).toList();
            assertEquals(1, changes.size());
            var database = new PostgresDatabase();
            var sql = new StringBuilder();
            for (var change : changes.get(0).getChanges()) {
                for (var statement : change.generateStatements(database)) {
                    for (var generated : SqlGeneratorFactory.getInstance().generateSql(statement, database)) {
                        sql.append(generated.toSql()).append(';');
                    }
                }
            }
            String migration = sql.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            assertTrue(migration.contains("ems_source varchar(16)"));
            assertTrue(migration.contains("default 'legacy'"));
            assertTrue(migration.contains("set ems_source = 'legacy'"));
            assertTrue(migration.contains("where ems_source is null"));
            assertFalse(migration.contains("not null"));
            assertFalse(migration.contains("ems_event_id"));
            assertFalse(migration.contains("event_participants"));
            assertFalse(migration.contains("delete "));
            assertFalse(migration.contains("drop "));
        }
    }

    private void expectLegacyLogin(int times) {
        legacy.expect(ExpectedCount.times(times), requestTo("https://legacy.invalid/api/admin/v1/login"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"data\":{\"access_token\":\"fixture-token\"}}", MediaType.APPLICATION_JSON));
    }

    private void expectLegacyGet(String route, int times, String data) {
        legacy.expect(ExpectedCount.times(times), requestTo("https://legacy.invalid" + route))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer fixture-token"))
                .andRespond(withSuccess("{\"data\":" + data + "}", MediaType.APPLICATION_JSON));
    }
}
