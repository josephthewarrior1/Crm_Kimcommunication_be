package com.crm.controller;

import com.crm.domain.AppUser;
import com.crm.domain.Company;
import com.crm.domain.Database;
import com.crm.domain.DatabaseEmail;
import com.crm.domain.Group;
import com.crm.domain.PositionLevel;
import com.crm.domain.Role;
import com.crm.repository.CompanyRepository;
import com.crm.repository.DatabaseRepository;
import com.crm.repository.FlaggedIdentityRepository;
import com.crm.service.SecurityHelper;
import com.crm.service.SuspiciousIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabasePartialUpdateTest {
    @Test void incrementalEditsKeepMissingFieldsDirtyAndAcceptUnknownPositionAcrossViews() {
        var controller = new DatabaseController();
        var databases = mock(DatabaseRepository.class);
        var companies = mock(CompanyRepository.class);
        var flags = mock(FlaggedIdentityRepository.class);
        var security = mock(SecurityHelper.class);
        var user = AppUser.builder().id(7L).build();
        var contact = Database.builder().id(42L).isActive(true).build();
        var company = Company.builder().id(1L).name("Example Company").brandName("Example")
                .group(Group.builder().id(2L).name("Example Group").build())
                .address("Jl. Kantor").officePhone("021123").city("Jakarta")
                .website("https://example.test").industry("Technology").build();
        when(security.getAuthenticatedUser("test")).thenReturn(user);
        when(security.hasAnyRole(user, Role.ADMIN, Role.MANAGER)).thenReturn(true);
        when(databases.findById(42L)).thenReturn(Optional.of(contact));
        when(databases.findAll()).thenReturn(List.of(contact));
        when(databases.save(any())).thenAnswer(call -> call.getArgument(0));
        when(companies.findById(1L)).thenReturn(Optional.of(company));
        when(flags.findAll()).thenReturn(List.of());
        ReflectionTestUtils.setField(controller, "databaseRepository", databases);
        ReflectionTestUtils.setField(controller, "companyRepository", companies);
        ReflectionTestUtils.setField(controller, "flaggedIdentityRepository", flags);
        ReflectionTestUtils.setField(controller, "securityHelper", security);
        ReflectionTestUtils.setField(controller, "suspiciousIdentityService", mock(SuspiciousIdentityService.class));

        assertEquals(200, controller.updateDatabase(42L, Database.builder().firstName("Ayu").build(),
                null, null, false, "test").getStatusCode().value());
        assertEquals("Ayu", contact.getFirstName());
        assertNull(contact.getCompany());
        assertQuality(controller, contact, true);

        assertEquals(200, controller.updateDatabase(42L, Database.builder().lastName("Putri").build(),
                null, null, false, "test").getStatusCode().value());
        assertEquals("Ayu", contact.getFirstName());
        assertEquals("Putri", contact.getLastName());
        assertQuality(controller, contact, true);

        // The corporate email has its own endpoint; only its resulting stored row matters here.
        contact.setEmails(List.of(DatabaseEmail.builder().email("ayu@example.test").isCorporate(true).build()));
        assertEquals(200, controller.updateDatabase(42L, Database.builder().salutation("Ms")
                .jobTitle("Officer").mobilePhone("08123456789").build(),
                1L, null, false, "test").getStatusCode().value());
        assertSame(company, contact.getCompany());
        assertNull(contact.getPositionLevel());
        assertQuality(controller, contact, true);

        assertEquals(200, controller.updateDatabase(42L, Database.builder().positionLevel(PositionLevel.UNKNOWN).build(),
                null, null, false, "test").getStatusCode().value());
        assertEquals(PositionLevel.UNKNOWN, contact.getPositionLevel());
        assertQuality(controller, contact, false);

        assertEquals(200, controller.updateDatabase(42L, Database.builder().positionLevel(PositionLevel.STAFF).build(),
                null, null, false, "test").getStatusCode().value());
        assertEquals("Officer", contact.getJobTitle());
        assertSame(company, contact.getCompany());
        assertQuality(controller, contact, false);

        // Explicit empty strings clear a field; omitted fields preserve the saved values.
        assertEquals(200, controller.updateDatabase(42L, Database.builder().mobilePhone("").build(),
                null, null, false, "test").getStatusCode().value());
        assertEquals("", contact.getMobilePhone());
        assertEquals("Ayu", contact.getFirstName());
        assertQuality(controller, contact, true);
    }

    private static void assertQuality(DatabaseController controller, Database contact, boolean dirty) {
        Map<?, ?> all = list(controller, "all");
        Map<?, ?> summary = (Map<?, ?>) all.get("summary");
        Map<?, ?> analytics = (Map<?, ?>) all.get("analytics");
        assertEquals(dirty ? 1L : 0L, ((Number) summary.get("dirty")).longValue());
        assertEquals(dirty ? 0L : 1L, ((Number) summary.get("clean")).longValue());
        assertEquals(summary.get("dirty"), analytics.get("dirty"));
        assertEquals(summary.get("clean"), analytics.get("clean"));
        String matchingTab = dirty ? "dirty" : "clean";
        assertEquals(List.of(contact), list(controller, matchingTab).get("items"));
        assertEquals(List.of(), list(controller, dirty ? "clean" : "dirty").get("items"));
        Map<?, ?> exported = (Map<?, ?>) controller.exportDatabases(null, null, null, null,
                null, null, null, matchingTab, "id", "asc", "test").getBody();
        assertEquals(List.of(contact), exported.get("items"));
        Map<?, ?> oppositeExport = (Map<?, ?>) controller.exportDatabases(null, null, null, null,
                null, null, null, dirty ? "clean" : "dirty", "id", "asc", "test").getBody();
        assertEquals(List.of(), oppositeExport.get("items"));
    }

    private static Map<?, ?> list(DatabaseController controller, String tab) {
        return (Map<?, ?>) controller.getDatabasesList(null, null, null, null, null, null,
                null, tab, "id", "asc", 1, 10, "test").getBody();
    }
}
