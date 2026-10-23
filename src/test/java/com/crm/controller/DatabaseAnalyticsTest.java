package com.crm.controller;

import com.crm.domain.*;
import com.crm.domain.Database;
import com.crm.repository.DatabaseRepository;
import com.crm.repository.FlaggedIdentityRepository;
import com.crm.service.SecurityHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseAnalyticsTest {
    private final DatabaseController controller = new DatabaseController();
    private final DatabaseRepository databases = mock(DatabaseRepository.class);
    private final FlaggedIdentityRepository flags = mock(FlaggedIdentityRepository.class);

    @BeforeEach void setup() {
        var security = mock(SecurityHelper.class);
        when(security.getAuthenticatedUser("test")).thenReturn(AppUser.builder().id(1L).build());
        when(flags.findAll()).thenReturn(List.of());
        ReflectionTestUtils.setField(controller, "databaseRepository", databases);
        ReflectionTestUtils.setField(controller, "flaggedIdentityRepository", flags);
        ReflectionTestUtils.setField(controller, "securityHelper", security);
    }

    @Test void analyticsUseAllFilteredVisibleContactsBeforePaginationAndLeaveSummaryGlobal() {
        Company acme = company(100, "Mining/Oil/Gas"), other = company(200, "Manufacturing");
        other.setGroup(Group.builder().id(20L).name("Other group").build());
        other.setCity("Semarang");
        var branch = CompanyBranch.builder().id(1000L).companyId(100L).name("Bandung branch")
                .city("Bandung").address("Jl. Cabang").officePhone("022123").build();
        var records = new ArrayList<Database>();
        for (long id = 1; id <= 9; id++) {
            Database contact = contact(id, "Technology");
            if (List.of(1L, 2L, 3L, 5L, 6L, 7L).contains(id)) {
                contact.setCompany(acme);
                if (id != 3) contact.setBranch(branch);
            } else if (id == 4 || id == 9) {
                contact.setCompany(other);
                contact.setPositionLevel(PositionLevel.MANAGERIAL_HEAD);
            }
            records.add(contact);
        }
        records.get(0).setFirstName("Ayu"); records.get(1).setFirstName("Ayu");
        records.get(1).setJobTitle(null);
        records.get(4).setIsActive(false);
        records.get(7).setIsActive(null);
        when(databases.findAll()).thenReturn(records);
        when(flags.findAll()).thenReturn(List.of(
                FlaggedIdentity.builder().database(records.get(5)).status(FlagStatus.confirmed).build(),
                FlaggedIdentity.builder().database(records.get(6)).status(FlagStatus.suspected).build(),
                FlaggedIdentity.builder().database(records.get(8)).status(FlagStatus.cleared).build()));

        Map<?, ?> firstPage = list(null, null, null, null, null, null, null, "all", 1, 1);
        Map<?, ?> secondPage = list(null, null, null, null, null, null, null, "all", 2, 1);
        assertEquals(List.of(records.get(0)), firstPage.get("items"));
        assertEquals(List.of(records.get(1)), secondPage.get("items"));
        assertEquals(firstPage.get("analytics"), secondPage.get("analytics"));
        assertCounts(firstPage, 7, 6, 1);
        assertEquals(List.of(), otherIndustries(firstPage));
        Map<?, ?> summary = (Map<?, ?>) firstPage.get("summary");
        assertEquals(7L, number(summary, "all"));
        assertEquals(6L, number(summary, "clean"));
        assertEquals(1L, number(summary, "dirty"));

        List<Map<?, ?>> filtered = List.of(
                list("Ayu", null, null, null, null, null, null, "all", 1, 1),
                list(null, 10L, null, null, null, null, null, "all", 1, 1),
                list(null, null, 100L, null, null, null, null, "all", 1, 1),
                list(null, null, null, 1000L, null, null, null, "all", 1, 1),
                list(null, null, null, null, "Manajerial/Head", null, null, "all", 1, 1),
                list(null, null, null, null, null, " Mining / Oil / Gas ", null, "all", 1, 1),
                list(null, null, null, null, null, null, "Kota Bandung", "all", 1, 1),
                list(null, null, null, null, null, null, null, "clean", 1, 1),
                list(null, null, null, null, null, null, null, "dirty", 1, 1),
                list("Ayu", 10L, 100L, 1000L, "Staff", "Mining/Oil/Gas", "Bandung", "all", 2, 1));
        long[][] expected = {{2, 1, 1}, {5, 4, 1}, {4, 3, 1}, {3, 2, 1}, {2, 2, 0},
                {4, 3, 1}, {3, 2, 1}, {6, 6, 0}, {1, 0, 1}, {2, 1, 1}};
        for (int i = 0; i < filtered.size(); i++) {
            assertCounts(filtered.get(i), expected[i][0], expected[i][1], expected[i][2]);
            assertEquals(summary, filtered.get(i).get("summary"));
        }
        verify(databases, never()).save(any());
    }

    @Test void industryBucketsCanonicalizeAliasesIncludeBlanksAndKeepDeterministicTopSixPlusRemainder() {
        String[] industries = {"Manufaktur", "Manufacturing", " MANUFACTURING ",
                "Mining/Oil/Gas", " Mining / Oil / Gas ", " MINING\u00a0/ OIL / GAS ",
                null, "  ", null, "Telecommunications", "Telecommunication", "Financial Service",
                "Financial Services", "Alpha", " alpha ", "Beta", "Gamma", "Zulu"};
        var records = new ArrayList<Database>();
        for (int i = 0; i < industries.length; i++) records.add(contact(i + 1, industries[i]));
        records.get(8).setCompany(null);
        when(databases.findAll()).thenReturn(records);

        Map<?, ?> result = list(null, null, null, null, null, null, null, "all", 1, 2);
        assertCounts(result, 18, 15, 3);
        List<Map<?, ?>> buckets = industries(result);
        assertEquals(List.of("Belum diisi", "Manufacturing", "Mining/Oil/Gas", "Alpha",
                "Financial Services (Banking / Insurance / Multifinance / Fintech)", "Telecommunication", "Lainnya"),
                buckets.stream().map(bucket -> bucket.get("name")).toList());
        assertEquals(List.of(3L, 3L, 3L, 2L, 2L, 2L, 3L),
                buckets.stream().map(bucket -> number(bucket, "count")).toList());
        assertEquals(List.of("Beta", "Gamma", "Zulu"),
                otherIndustries(result).stream().map(bucket -> bucket.get("name")).toList());
        assertEquals(List.of(1L, 1L, 1L),
                otherIndustries(result).stream().map(bucket -> number(bucket, "count")).toList());
        Collections.reverse(records);
        Map<?, ?> descending = (Map<?, ?>) controller.getDatabasesList(null, null, null, null, null,
                null, null, "all", "id", "desc", 3, 2, "test").getBody();
        assertEquals(result.get("analytics"), descending.get("analytics"));
        assertEquals("Manufaktur", records.get(records.size() - 1).getCompany().getIndustry());
        for (long id = 19; id <= 22; id++) records.add(contact(id, "LAINNYA"));
        Map<?, ?> existingOtherBucket = list(null, null, null, null, null, null, null, "all", 1, 2);
        assertCounts(existingOtherBucket, 22, 19, 3);
        List<Map<?, ?>> otherBuckets = industries(existingOtherBucket);
        assertEquals(6, otherBuckets.size());
        assertEquals(1L, otherBuckets.stream().filter(bucket -> "Lainnya".equals(bucket.get("name"))).count());
        assertEquals(9L, number(otherBuckets.get(0), "count"));
        assertEquals("Lainnya", otherBuckets.get(0).get("name"));
        assertEquals(List.of("Lainnya", "Telecommunication", "Beta", "Gamma", "Zulu"),
                otherIndustries(existingOtherBucket).stream().map(bucket -> bucket.get("name")).toList());
        assertEquals(List.of(4L, 2L, 1L, 1L, 1L),
                otherIndustries(existingOtherBucket).stream().map(bucket -> number(bucket, "count")).toList());
        verify(databases, never()).save(any());
    }

    @Test void noMatchesAndEmptyDatasetReturnZeroAnalyticsWithoutInventingAnIndustryBucket() {
        when(databases.findAll()).thenReturn(List.of(contact(1, "Technology")));
        Map<?, ?> noMatch = list("no-such-contact", null, null, null, null, null, null, "all", 1, 10);
        assertCounts(noMatch, 0, 0, 0);
        assertEquals(List.of(), noMatch.get("items"));
        assertEquals(List.of(), industries(noMatch));
        assertEquals(List.of(), otherIndustries(noMatch));
        assertEquals(1L, number((Map<?, ?>) noMatch.get("summary"), "all"));
        when(databases.findAll()).thenReturn(List.of());
        Map<?, ?> empty = list(null, null, null, null, null, null, null, "all", 1, 10);
        assertCounts(empty, 0, 0, 0);
        assertEquals(List.of(), industries(empty));
        assertEquals(List.of(), otherIndustries(empty));
        assertEquals(0L, number((Map<?, ?>) empty.get("summary"), "all"));
    }

    private Map<?, ?> list(String search, Long group, Long company, Long branch, String position,
                          String industry, String city, String tab, int page, int size) {
        return (Map<?, ?>) controller.getDatabasesList(search, group, company, branch, position, industry,
                city, tab, "id", "asc", page, size, "test").getBody();
    }

    private static void assertCounts(Map<?, ?> result, long total, long clean, long dirty) {
        Map<?, ?> analytics = (Map<?, ?>) result.get("analytics");
        assertNotNull(analytics);
        assertEquals(total, number(result, "total"));
        assertEquals(total, number(analytics, "total"));
        assertEquals(clean, number(analytics, "clean"));
        assertEquals(dirty, number(analytics, "dirty"));
        assertEquals(total, industries(result).stream().mapToLong(bucket -> number(bucket, "count")).sum());
        List<Map<?, ?>> details = otherIndustries(result);
        assertNotNull(details);
        if (!details.isEmpty()) {
            Map<?, ?> aggregate = industries(result).stream().filter(bucket -> "Lainnya".equals(bucket.get("name")))
                    .findFirst().orElseThrow();
            assertEquals(number(aggregate, "count"), details.stream().mapToLong(bucket -> number(bucket, "count")).sum());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> industries(Map<?, ?> result) {
        return (List<Map<?, ?>>) ((Map<?, ?>) result.get("analytics")).get("industries");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> otherIndustries(Map<?, ?> result) {
        return (List<Map<?, ?>>) ((Map<?, ?>) result.get("analytics")).get("otherIndustries");
    }

    private static long number(Map<?, ?> values, String key) {
        return ((Number) values.get(key)).longValue();
    }

    private static Company company(long id, String industry) {
        return Company.builder().id(id).name("Company " + id).brandName("Brand " + id)
                .group(Group.builder().id(10L).name("Main group").build()).industry(industry)
                .city("Jakarta").address("Jl. Kantor").officePhone("021123").website("https://company.example").build();
    }

    private static Database contact(long id, String industry) {
        return Database.builder().id(id).company(company(id, industry)).salutation("Mr").firstName("Contact " + id)
                .lastName("Santoso").positionLevel(PositionLevel.STAFF).jobTitle("Officer").mobilePhone("08123456789")
                .emails(List.of(DatabaseEmail.builder().email("contact@company.example").isCorporate(true).build())).isActive(true).build();
    }
}
