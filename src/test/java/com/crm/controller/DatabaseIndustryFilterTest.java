package com.crm.controller;

import com.crm.domain.AppUser;
import com.crm.domain.Company;
import com.crm.domain.Database;
import com.crm.domain.FlaggedIdentity;
import com.crm.domain.FlagStatus;
import com.crm.repository.DatabaseRepository;
import com.crm.repository.FlaggedIdentityRepository;
import com.crm.service.SecurityHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseIndustryFilterTest {
    private final DatabaseController controller = new DatabaseController();
    private final DatabaseRepository databases = mock(DatabaseRepository.class);
    private final FlaggedIdentityRepository flags = mock(FlaggedIdentityRepository.class);

    @BeforeEach void setup() {
        SecurityHelper security = mock(SecurityHelper.class);
        when(security.getAuthenticatedUser("test")).thenReturn(AppUser.builder().id(1L).build());
        when(flags.findAll()).thenReturn(List.of());
        ReflectionTestUtils.setField(controller, "databaseRepository", databases);
        ReflectionTestUtils.setField(controller, "flaggedIdentityRepository", flags);
        ReflectionTestUtils.setField(controller, "securityHelper", security);
    }

    private Database contact(long id, String industry) {
        return Database.builder().id(id).firstName("Contact " + id).isActive(true)
                .company(Company.builder().id(id).name("Company " + id).industry(industry).build()).build();
    }

    @SuppressWarnings("unchecked")
    private List<Database> exported(String industry) {
        Map<String, Object> body = (Map<String, Object>) controller.exportDatabases(
                null, null, null, null, industry, null, "all", "id", "asc", "test").getBody();
        return (List<Database>) body.get("items");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> options(String industry, Long companyId, String tab) {
        return (Map<String, Object>) controller.getDatabaseFilterOptions(
                null, null, companyId, null, industry, null, tab, "test").getBody();
    }

    @Test void distinctCategoriesNeverMatchBySubstring() {
        Database school = contact(1, "Education"), college = contact(2, "Education - Higher");
        Database tech = contact(3, "Technology"), hybrid = contact(4, "E-Commerce & Technology");
        when(databases.findAll()).thenReturn(List.of(school, college, tech, hybrid));
        assertEquals(List.of(school), exported("Education"));
        assertEquals(List.of(college), exported("Education - Higher"));
        assertEquals(List.of(tech), exported("Technology"));
        assertEquals(List.of(hybrid), exported("E-Commerce & Technology"));
        assertTrue(exported("Tech").isEmpty());
    }

    @Test void knownAliasesShareOneOptionAndMatchWithoutChangingStoredLabels() {
        for (String[] aliases : List.of(
                new String[]{"Financial Services (Banking / Insurance / Multifinance / Fintech)", "Financial Services", "Financial Service"},
                new String[]{"Telecommunication", "Telecommunications"},
                new String[]{"Mining/Oil/Gas", " Mining / Oil / Gas ", " MINING\u00a0/ OIL / GAS "},
                new String[]{"Manufacturing", "Manufaktur"})) {
            List<Database> records = java.util.stream.IntStream.range(0, aliases.length)
                    .mapToObj(i -> contact(i + 1, aliases[i])).toList();
            when(databases.findAll()).thenReturn(records);
            assertEquals(List.of(aliases[0]), options(null, null, "all").get("industries"));
            for (String alias : aliases) assertEquals(records, exported(alias));
            for (int i = 0; i < aliases.length; i++) assertEquals(aliases[i], records.get(i).getCompany().getIndustry());
        }
        verify(databases, never()).save(any());
    }

    @Test void unknownLabelsArePreservedAndCaseWhitespaceDuplicatesCollapse() {
        Database first = contact(1, "Government / Public Institution");
        Database second = contact(2, " government/ public   institution ");
        when(databases.findAll()).thenReturn(List.of(first, second, contact(3, "Education - Etc.")));
        assertEquals(List.of("Education - Etc.", "Government/Public Institution"), options(null, null, "all").get("industries"));
        assertEquals(List.of(first, second), exported("GOVERNMENT / PUBLIC INSTITUTION"));
        assertTrue(exported("Government - Federal/National").isEmpty());
    }

    @Test void listExportAndFacetsAgreeAndStillRespectCompanyAndTabFilters() {
        Database first = contact(1, "Mining / Oil / Gas"), second = contact(2, "Mining/Oil/Gas");
        when(databases.findAll()).thenReturn(List.of(first, second, contact(3, "Education")));
        Map<?, ?> page = (Map<?, ?>) controller.getDatabasesList(null, null, null, null,
                "Mining/Oil/Gas", null, "all", "id", "asc", 2, 1, "test").getBody();
        assertEquals(2, page.get("total"));
        assertEquals(2, page.get("totalPages"));
        assertEquals(List.of(second), page.get("items"));
        assertEquals(List.of(first, second), exported("Mining/Oil/Gas"));
        assertEquals(2, ((List<?>) options("Mining/Oil/Gas", null, "all").get("companies")).size());
        assertEquals(List.of("Education", "Mining/Oil/Gas"), options("Mining/Oil/Gas", null, "all").get("industries"));
        assertEquals(List.of("Mining/Oil/Gas"), options(null, 1L, "all").get("industries"));
        assertEquals(List.of(), options(null, 999L, "all").get("industries"));
        assertEquals(List.of(), options(null, null, "clean").get("industries"));
    }

    @Test void allIncludesBlankIndustryButSpecificFilterExcludesBlankInactiveAndFlagged() {
        Database active = contact(1, "Education"), inactive = contact(2, "Education"), flagged = contact(3, "Education");
        inactive.setIsActive(false);
        Database blank = contact(4, null), noCompany = Database.builder().id(5L).isActive(true).build();
        when(databases.findAll()).thenReturn(List.of(active, inactive, flagged, blank, noCompany));
        when(flags.findAll()).thenReturn(List.of(FlaggedIdentity.builder().database(flagged).status(FlagStatus.confirmed).build()));
        assertEquals(List.of(active), exported("Education"));
        assertEquals(List.of(active, blank, noCompany), exported(null));
        assertEquals(List.of(active, blank, noCompany), exported(" "));
        assertEquals(List.of("Education"), options(null, null, "all").get("industries"));
    }
}
