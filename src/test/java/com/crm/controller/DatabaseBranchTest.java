package com.crm.controller;

import com.crm.domain.*;
import com.crm.repository.*;
import com.crm.service.SecurityHelper;
import com.crm.service.SuspiciousIdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseBranchTest {
    private final DatabaseController controller = new DatabaseController();
    private final DatabaseRepository databases = mock(DatabaseRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final CompanyBranchRepository branches = mock(CompanyBranchRepository.class);
    private final Company company = Company.builder().id(1L).name("Bank Central Asia PT").brandName("BCA")
            .group(Group.builder().id(1L).name("BCA Group").build()).city("Jakarta").address("Head office")
            .officePhone("021100").website("https://bca.example").industry("Banking").build();
    private final CompanyBranch branch = CompanyBranch.builder().id(10L).companyId(1L).name("Pademangan")
            .city("Bandung").address("Jl. Cabang").officePhone("022200").build();

    @BeforeEach void setup() {
        SecurityHelper security = mock(SecurityHelper.class);
        AppUser user = AppUser.builder().id(7L).build();
        when(security.getAuthenticatedUser("test")).thenReturn(user);
        when(security.hasAnyRole(user, Role.ADMIN, Role.MANAGER)).thenReturn(true);
        when(companies.findById(1L)).thenReturn(Optional.of(company));
        when(branches.findById(10L)).thenReturn(Optional.of(branch));
        when(databases.save(any())).thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.setField(controller, "securityHelper", security);
        ReflectionTestUtils.setField(controller, "databaseRepository", databases);
        ReflectionTestUtils.setField(controller, "companyRepository", companies);
        ReflectionTestUtils.setField(controller, "companyBranchRepository", branches);
        ReflectionTestUtils.setField(controller, "flaggedIdentityRepository", mock(FlaggedIdentityRepository.class));
        ReflectionTestUtils.setField(controller, "suspiciousIdentityService", mock(SuspiciousIdentityService.class));
    }

    @Test void validatesAssignmentBeforeChangingContactAndDoesNotTrustBodyBranch() throws Exception {
        Database existing = contact(1L, branch);
        when(databases.findById(1L)).thenReturn(Optional.of(existing));
        Company other = Company.builder().id(2L).name("Other").build();
        when(companies.findById(2L)).thenReturn(Optional.of(other));
        Database changed = Database.builder().firstName("Changed").build();
        assertEquals(400, controller.updateDatabase(1L, changed, 2L, 10L, false, "test").getStatusCode().value());
        assertEquals("Budi", existing.getFirstName());
        assertSame(company, existing.getCompany());
        assertSame(branch, existing.getBranch());
        assertEquals(400, controller.createDatabase(new Database(), 2L, 10L, false, "test").getStatusCode().value());
        assertEquals(400, controller.updateDatabase(1L, changed, 1L, 999L, false, "test").getStatusCode().value());
        assertEquals(400, controller.updateDatabase(1L, changed, 1L, 10L, true, "test").getStatusCode().value());
        verify(databases, never()).save(any());
        assertNull(new ObjectMapper().readValue("{\"branch\":{\"id\":10,\"companyId\":2}}", Database.class).getBranch());
    }

    @Test void createAssignsAndUpdatePreservesClearsOrMovesWithoutBreakingOldClients() {
        Database existing = contact(1L, branch);
        existing.setId(null);
        assertEquals(200, controller.createDatabase(existing, 1L, 10L, false, "test").getStatusCode().value());
        assertSame(branch, existing.getBranch());
        existing.setId(1L);
        when(databases.findById(1L)).thenReturn(Optional.of(existing));
        assertEquals(200, controller.updateDatabase(1L, new Database(), null, null, false, "test").getStatusCode().value());
        assertSame(branch, existing.getBranch());
        assertEquals(200, controller.updateDatabase(1L, new Database(), 1L, null, true, "test").getStatusCode().value());
        assertNull(existing.getBranch());
        controller.updateDatabase(1L, new Database(), 1L, 10L, false, "test");
        Company other = Company.builder().id(2L).build();
        when(companies.findById(2L)).thenReturn(Optional.of(other));
        controller.updateDatabase(1L, new Database(), 2L, null, false, "test");
        assertSame(other, existing.getCompany());
        assertNull(existing.getBranch());
        existing.setCompany(company);
        existing.setBranch(branch);
        existing.setCompany(Company.builder().id(1L).build());
        assertSame(branch, existing.getBranch());
        existing.setCompany(other); // Excel, EMS and event writers share this setter.
        assertNull(existing.getBranch());
    }

    @Test void searchFacetsExportAndCompletenessUseBranchLocationWithoutCompanyFallback() {
        Database assigned = contact(1L, branch), headquarters = contact(2L, null);
        when(databases.findAll()).thenReturn(List.of(assigned, headquarters));
        Map<?, ?> page = (Map<?, ?>) controller.getDatabasesList("BCA Pademangan", null, null, null, null, null,
                null, "all", "id", "asc", 1, 10, "test").getBody();
        assertEquals(List.of(assigned), page.get("items"));
        Map<?, ?> exported = (Map<?, ?>) controller.exportDatabases("BCA 022200 Cabang", null, null, 10L,
                null, null, "Bandung", "all", "id", "asc", "test").getBody();
        assertEquals(List.of(assigned), exported.get("items"));
        Map<?, ?> options = (Map<?, ?>) controller.getDatabaseFilterOptions("BCA Pademangan", null, null, null,
                null, null, null, "all", "test").getBody();
        assertEquals(List.of(Map.of("value", "BANDUNG", "label", "Bandung")), options.get("cities"));
        assertEquals(List.of(Map.of("id", 10L, "companyId", 1L, "name", "Pademangan")), options.get("branches"));
        assertEquals(false, ReflectionTestUtils.invokeMethod(controller, "isIncomplete", assigned));
        branch.setCity(null);
        assertEquals(true, ReflectionTestUtils.invokeMethod(controller, "isIncomplete", assigned));
        assertEquals(false, ReflectionTestUtils.invokeMethod(controller, "matchesCity", assigned, "Jakarta"));
        assertEquals(false, ReflectionTestUtils.invokeMethod(controller, "matchesSearch", assigned, "Head office"));
        branch.setCity("Bandung"); branch.setAddress("");
        assertEquals(true, ReflectionTestUtils.invokeMethod(controller, "isIncomplete", assigned));
        branch.setAddress("Jl. Cabang"); branch.setOfficePhone(null);
        assertEquals(true, ReflectionTestUtils.invokeMethod(controller, "isIncomplete", assigned));
        assertEquals(false, ReflectionTestUtils.invokeMethod(controller, "isIncomplete", headquarters));
    }

    @Test void eventExportAndCitySelectionUseSameBranchLocation() {
        var events = new EventController();
        Database assigned = contact(1L, branch), headquarters = contact(2L, null);
        assertEquals(true, ReflectionTestUtils.invokeMethod(events, "matchesAvailableDatabaseCity", assigned, "Bandung"));
        assertEquals(false, ReflectionTestUtils.invokeMethod(events, "matchesAvailableDatabaseCity", assigned, "Jakarta"));
        assertEquals("022200", ReflectionTestUtils.invokeMethod(events, "contactOfficePhone", assigned));
        branch.setCity(null); branch.setOfficePhone(null);
        assertEquals("", ReflectionTestUtils.invokeMethod(events, "contactCity", assigned));
        assertEquals("", ReflectionTestUtils.invokeMethod(events, "contactOfficePhone", assigned));
        assertEquals("Jakarta", ReflectionTestUtils.invokeMethod(events, "contactCity", headquarters));
        assertEquals("021100", ReflectionTestUtils.invokeMethod(events, "contactOfficePhone", headquarters));
    }

    private Database contact(Long id, CompanyBranch branch) {
        return Database.builder().id(id).company(company).branch(branch).salutation("Mr").firstName("Budi")
                .lastName("Santoso").positionLevel(PositionLevel.STAFF).jobTitle("Officer").mobilePhone("08123456789")
                .emails(List.of(DatabaseEmail.builder().email("budi@bca.example").isCorporate(true).build())).isActive(true).build();
    }
}
