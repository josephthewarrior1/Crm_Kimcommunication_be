package com.crm.service;

import liquibase.changelog.ChangeLogParameters;
import liquibase.database.core.PostgresDatabase;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ParticipantOrganizationMigrationTest {
    @Test void nullableOrganizationColumnsAndKnownLineageBackfillNeverUpdateCompanyMasters() throws Exception {
        try(var resources=new ClassLoaderResourceAccessor()){
            String master="db/changelog/db.changelog-master.yaml";
            var log=ChangeLogParserFactory.getInstance().getParser(master,resources).parse(master,new ChangeLogParameters(),resources);
            var sets=log.getChangeSets().stream().filter(change->change.getId().equals("crm-participant-organization-fields-20261109")).toList();assertEquals(1,sets.size());
            List<String> sql=new ArrayList<>();var database=new PostgresDatabase();var generator=SqlGeneratorFactory.getInstance();
            for(var change:sets.get(0).getChanges())for(var statement:change.generateStatements(database)){
                assertFalse(generator.validate(statement,database).hasErrors());
                for(var generated:generator.generateSql(statement,database))sql.add(generated.toSql().toLowerCase(Locale.ROOT));
            }
            var columns=sql.stream().filter(value->value.startsWith("alter table databases add ")).toList();assertEquals(10,columns.size());
            for(String column:columns){assertFalse(column.contains("not null"));assertFalse(column.contains("default"));assertTrue(column.contains("organization_"));}
            String backfill=sql.stream().filter(value->value.contains("update databases participant set")).findFirst().orElseThrow();
            assertTrue(backfill.contains("database_destination_splits"));assertTrue(backfill.contains("participant.database_destination='participant'"));
            assertTrue(backfill.contains("participant.institution_name"));assertTrue(backfill.contains("company.name"));
            assertFalse(backfill.contains("update companies"));assertFalse(backfill.contains("update company_branches"));assertFalse(backfill.contains("delete from"));
        }
    }
}
