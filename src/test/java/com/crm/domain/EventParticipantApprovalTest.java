package com.crm.domain;

import com.crm.util.EventApprovalPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import liquibase.changelog.ChangeLogParameters;
import liquibase.database.core.PostgresDatabase;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

class EventParticipantApprovalTest {
    @Test void linkedEventsRequireAnEmsSnapshotInsteadOfLegacyManualApprovalOrNotes() {
        for (EmsSource source : EmsSource.values()) {
            Event event = Event.builder().emsEventId(42L).emsSource(source).build();
            EventParticipant participant = EventParticipant.builder().event(event)
                    .confirmationStatus("decline").preEventApprovalStatus("approve")
                    .notes("[PreEventApproval: approve] Staff note").build();
            assertEquals("ems", participant.getApprovalSource());
            assertEquals("pending", participant.getPreEventApprovalStatus());
            for (String status : List.of("approve", "pending", "decline")) {
                participant.setEmsApprovalStatus(status);
                assertEquals(status, participant.getPreEventApprovalStatus());
                assertEquals("decline", participant.getConfirmationStatus());
            }
        }
    }

    @Test void manualEventsKeepTheirExistingPreEventDecision() {
        for (Long externalId : new Long[]{null, 0L, -1L}) {
            Event event = Event.builder().emsEventId(externalId).build();
            EventParticipant participant = EventParticipant.builder().event(event)
                    .preEventApprovalStatus("decline").emsApprovalStatus("approve").build();
            assertFalse(EventApprovalPolicy.isEmsManaged(event));
            assertEquals("crm", participant.getApprovalSource());
            assertEquals("decline", participant.getPreEventApprovalStatus());
        }
    }

    @Test void jsonCannotInjectAnEmsSnapshotAndSerializesTheCanonicalPreEventStatus() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        EventParticipant participant = mapper.readValue("{\"emsApprovalStatus\":\"approve\",\"approvalSource\":\"crm\","
                + "\"preEventApprovalStatus\":\"approve\",\"notes\":\"[PreEventApproval: approve]\","
                + "\"event\":{\"emsEventId\":42}}", EventParticipant.class);
        assertNull(participant.getEmsApprovalStatus());
        assertEquals("pending", participant.getPreEventApprovalStatus());
        var json = mapper.readTree(mapper.writeValueAsString(participant));
        assertEquals("ems", json.get("approvalSource").asText());
        assertEquals("pending", json.get("preEventApprovalStatus").asText());
        assertTrue(json.get("emsApprovalStatus").isNull());
    }

    @Test void migrationAddsOnlyNullableSnapshotWithoutPromotingOldManualRows() throws Exception {
        String master = "db/changelog/db.changelog-master.yaml";
        try (var resources = new ClassLoaderResourceAccessor()) {
            var log = ChangeLogParserFactory.getInstance().getParser(master, resources)
                    .parse(master, new ChangeLogParameters(), resources);
            var changes = log.getChangeSets().stream()
                    .filter(change -> change.getId().equals("crm-ems-approval-source-20261111")).toList();
            assertEquals(1, changes.size());
            var database = new PostgresDatabase();
            StringBuilder generatedSql = new StringBuilder();
            for (var change : changes.get(0).getChanges()) for (var statement : change.generateStatements(database)) {
                for (var sql : SqlGeneratorFactory.getInstance().generateSql(statement, database)) generatedSql.append(sql.toSql()).append(';');
            }
            String migration = generatedSql.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            assertTrue(migration.contains("alter table"));
            assertTrue(migration.contains("event_participants add ems_approval_status varchar(20)"));
            assertFalse(migration.contains("not null"));
            assertFalse(migration.contains("default"));
            assertFalse(migration.contains("update "));
            assertFalse(migration.contains("delete "));
            assertFalse(migration.contains("drop "));
        }
    }
}
