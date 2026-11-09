package com.crm.service;

import liquibase.changelog.ChangeLogParameters;
import liquibase.database.core.PostgresDatabase;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseDestinationMigrationTest {
    @Test void masterGeneratesOnlyAdditivePostgresDestinationColumnsWithoutEmailBackfill() throws Exception {
        String master = "db/changelog/db.changelog-master.yaml";
        try (var resources = new ClassLoaderResourceAccessor()) {
            var log = ChangeLogParserFactory.getInstance().getParser(master, resources)
                    .parse(master, new ChangeLogParameters(), resources);
            var changes = log.getChangeSets().stream()
                    .filter(change -> change.getId().equals("crm-explicit-database-destinations")).toList();
            assertEquals(1, changes.size(), "The master must load this migration exactly once");
            assertTrue(changes.get(0).getFilePath().endsWith("changes/changelog-crm-database-destinations.yml"));

            var database = new PostgresDatabase();
            var generator = SqlGeneratorFactory.getInstance();
            List<String> statements = new ArrayList<>();
            for (var change : changes.get(0).getChanges()) {
                for (var statement : change.generateStatements(database)) {
                    var errors = generator.validate(statement, database);
                    assertFalse(errors.hasErrors(), errors.toString());
                    for (var generated : generator.generateSql(statement, database)) {
                        statements.add(generated.toSql().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim());
                    }
                }
            }

            assertEquals(4, statements.size(), "One additive statement per new column");
            String membership = columnSql(statements, "database_destination");
            assertTrue(membership.startsWith("alter table databases add "), membership);
            assertTrue(membership.contains("database_destination varchar(16)"), membership);
            assertTrue(membership.contains("default 'company'"), "Legacy contacts keep their company destination: " + membership);
            assertTrue(membership.contains("not null"), membership);

            String event = columnSql(statements, "sync_destination");
            assertTrue(event.startsWith("alter table events add "), event);
            assertTrue(event.contains("sync_destination varchar(16)"), event);
            assertFalse(event.contains("not null"), "Existing events must wait for an explicit sync choice");
            assertFalse(event.contains("default"), "Do not silently choose a sync destination for existing events");
            for (String field : List.of("institution_name", "participant_category")) {
                String metadata = columnSql(statements, field);
                assertTrue(metadata.startsWith("alter table databases add "), metadata);
                assertFalse(metadata.contains("not null"), metadata);
                assertFalse(metadata.contains("default"), metadata);
            }
            for (String statement : statements) {
                assertTrue(statement.startsWith("alter table "), statement);
                assertFalse(statement.matches(".*\\b(update|delete|drop|truncate)\\b.*"), statement);
                assertFalse(statement.contains("email"), "Destination must not be inferred from historical email data");
            }
        }
    }

    private static String columnSql(List<String> statements, String column) {
        var matches = statements.stream().filter(sql -> sql.contains(column + " ")).toList();
        assertEquals(1, matches.size(), column + " must be generated exactly once");
        return matches.get(0);
    }
}
