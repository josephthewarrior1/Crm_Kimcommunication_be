package com.crm.controller;

import com.crm.domain.*;
import com.crm.domain.Database;
import com.crm.repository.*;
import com.crm.service.SecurityHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardPositionSummaryTest {
    private final DashboardController controller = new DashboardController();
    private final DatabaseRepository databases = mock(DatabaseRepository.class);
    private final FlaggedIdentityRepository flags = mock(FlaggedIdentityRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final GroupRepository groups = mock(GroupRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final EventParticipantRepository participants = mock(EventParticipantRepository.class);

    @BeforeEach void setup() {
        var security = mock(SecurityHelper.class);
        when(security.getAuthenticatedUser("test")).thenReturn(AppUser.builder().id(1L).build());
        ReflectionTestUtils.setField(controller, "securityHelper", security);
        ReflectionTestUtils.setField(controller, "databaseRepository", databases);
        ReflectionTestUtils.setField(controller, "flaggedIdentityRepository", flags);
        ReflectionTestUtils.setField(controller, "companyRepository", companies);
        ReflectionTestUtils.setField(controller, "groupRepository", groups);
        ReflectionTestUtils.setField(controller, "eventRepository", events);
        ReflectionTestUtils.setField(controller, "eventParticipantRepository", participants);
    }

    @Test void exactPositionEnumsClassifyRegardlessOfTitlesIndustryOrDatabaseType() {
        record Example(PositionLevel position, String title, String division, String category) {}
        var examples = List.of(
                new Example(PositionLevel.C_LEVEL_GM_DIRECTOR, "IT Director", null, "cLevel"),
                new Example(PositionLevel.C_LEVEL_GM_DIRECTOR, null, null, "cLevel"),
                new Example(PositionLevel.MANAGERIAL_HEAD, "ICT Manager", null, "managerial"),
                new Example(PositionLevel.MANAGERIAL_HEAD, null, null, "managerial"),
                new Example(PositionLevel.STAFF, "IT Support", null, "staff"),
                new Example(PositionLevel.STAFF, "non-IT staff", "non ICT", "staff"),
                new Example(PositionLevel.STAFF, "Administrator", "Operations", "staff"),
                new Example(PositionLevel.STAFF, "Director", null, "staff"),
                new Example(PositionLevel.STAFF, " ", "", "staff"),
                new Example(PositionLevel.STAFF, null, null, "staff"),
                new Example(PositionLevel.UNKNOWN, "IT Support", "Information Technology", "other"),
                new Example(PositionLevel.UNKNOWN, "Director", null, "other"),
                new Example(null, "IT Support", null, "other"));
        for (Example example : examples) {
            Database contact = contact(1, example.position(), example.title(), example.division(), true);
            contact.setCompany(Company.builder().id(1L).industry("Information Technology").build());
            contact.setDatabaseType(DatabaseType.partner_it);
            when(databases.findAll()).thenReturn(List.of(contact));
            Map<?, ?> response = response();
            Map<?, ?> summary = (Map<?, ?>) response.get("positionSummary");
            assertEquals(1L, number(summary, example.category()), example.toString());
            assertSummary(response, 1, example.category().equals("cLevel") ? 1 : 0,
                    example.category().equals("managerial") ? 1 : 0,
                    example.category().equals("staff") ? 1 : 0,
                    example.category().equals("other") ? 1 : 0);
            if (example.category().equals("other")) {
                assertEquals(1, otherPositions(response).size());
                assertEquals(example.position() == null ? null : example.position().getValue(),
                        otherPositions(response).get(0).get("position"));
            }
        }
        verify(databases, times(examples.size())).findAll();
        verifyNoMoreInteractions(databases);
    }

    @Test void otherPositionsKeepUnknownAndNullSeparateAndRankByCountThenNullLast() {
        for (int[] counts : new int[][]{{3, 2}, {2, 2}, {2, 3}}) {
            var records = new java.util.ArrayList<Database>();
            for (int i = 0; i < counts[1]; i++) records.add(contact(records.size() + 1, null, "IT", null, true));
            for (int i = 0; i < counts[0]; i++) records.add(contact(records.size() + 1, PositionLevel.UNKNOWN, "Director", null, true));
            when(databases.findAll()).thenReturn(records);
            Map<?, ?> response = response();
            assertSummary(response, records.size(), 0, 0, 0, records.size());
            List<Map<?, ?>> details = otherPositions(response);
            assertEquals(2, details.size());
            boolean nullFirst = counts[1] > counts[0];
            assertEquals(nullFirst ? null : "unknown", details.get(0).get("position"));
            assertEquals(nullFirst ? "unknown" : null, details.get(1).get("position"));
            assertEquals(Math.max(counts[0], counts[1]), number(details.get(0), "count"));
            assertEquals(Math.min(counts[0], counts[1]), number(details.get(1), "count"));
        }
        verify(databases, times(3)).findAll();
        verifyNoMoreInteractions(databases);
    }

    @Test void activeAndNullActiveRowsIncludeConfirmedFlagsAndShareTheExistingDatabaseRead() {
        Database flagged = contact(3, PositionLevel.STAFF, "IT Support", null, true);
        when(databases.findAll()).thenReturn(List.of(
                contact(1, PositionLevel.C_LEVEL_GM_DIRECTOR, null, null, true),
                contact(2, PositionLevel.MANAGERIAL_HEAD, null, null, null),
                flagged,
                contact(4, PositionLevel.STAFF, "ICT", null, false),
                contact(5, PositionLevel.C_LEVEL_GM_DIRECTOR, null, null, false),
                contact(6, PositionLevel.STAFF, "Facility", null, true)));
        when(flags.findAll()).thenReturn(List.of(FlaggedIdentity.builder().id(77L)
                .database(flagged).status(FlagStatus.confirmed).build()));

        Map<?, ?> response = response();

        assertSummary(response, 4, 1, 1, 2, 0);
        assertEquals(1, ((List<?>) response.get("flaggedAlerts")).size());
        verify(databases).findAll();
        verifyNoMoreInteractions(databases);
    }

    @Test void unauthenticatedRequestsDoNotReadAndEmptyDashboardHasZeroPositionCounts() {
        assertEquals(401, controller.getDashboardSummary(null).getStatusCode().value());
        verifyNoInteractions(databases, flags, companies, groups, events, participants);
        when(databases.findAll()).thenReturn(List.of());
        assertSummary(response(), 0, 0, 0, 0, 0);
        verify(databases).findAll();
        verifyNoMoreInteractions(databases);
    }

    private Map<?, ?> response() {
        var response = controller.getDashboardSummary("test");
        assertEquals(200, response.getStatusCode().value());
        return (Map<?, ?>) response.getBody();
    }

    private static void assertSummary(Map<?, ?> response, long total, long cLevel, long managerial, long staff, long other) {
        Map<?, ?> summary = (Map<?, ?>) response.get("positionSummary");
        assertNotNull(summary);
        assertEquals(total, number(summary, "total"));
        assertEquals(cLevel, number(summary, "cLevel"));
        assertEquals(managerial, number(summary, "managerial"));
        assertEquals(staff, number(summary, "staff"));
        assertEquals(other, number(summary, "other"));
        List<Map<?, ?>> details = otherPositions(response);
        assertNotNull(details);
        details.forEach(detail -> assertTrue(detail.containsKey("position")));
        assertEquals(other, details.stream().mapToLong(detail -> number(detail, "count")).sum());
        if (other == 0) assertEquals(List.of(), details);
        assertEquals(total, number(summary, "cLevel") + number(summary, "managerial")
                + number(summary, "staff") + number(summary, "other"));
        assertEquals(total, number((Map<?, ?>) response.get("metrics"), "totalDatabase"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> otherPositions(Map<?, ?> response) {
        return (List<Map<?, ?>>) ((Map<?, ?>) response.get("positionSummary")).get("otherPositions");
    }

    private static long number(Map<?, ?> values, String key) {
        return ((Number) values.get(key)).longValue();
    }

    private static Database contact(long id, PositionLevel position, String title, String division, Boolean active) {
        return Database.builder().id(id).firstName("Contact " + id).positionLevel(position)
                .jobTitle(title).specialityDivision(division).isActive(active).build();
    }
}
