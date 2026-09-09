package com.crm.controller;

import com.crm.domain.*;
import com.crm.repository.*;
import com.crm.service.SecurityHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseUploadTargetControllerTest {
    private DatabaseUploadTargetController controller;
    private SecurityHelper security;
    private UserRepository users;
    private DatabaseRepository databases;
    private DatabaseUploadTargetRepository targets;
    private final AppUser admin = AppUser.builder().id(1L).username("admin").roles(Set.of(Role.ADMIN)).build();
    private final AppUser manager = AppUser.builder().id(2L).username("nabila").roles(Set.of(Role.MANAGER)).build();
    private final AppUser viewer = AppUser.builder().id(3L).username("viewer").roles(Set.of(Role.USER)).build();

    @BeforeEach void setup() {
        controller = new DatabaseUploadTargetController();
        security = mock(SecurityHelper.class);
        users = mock(UserRepository.class);
        databases = mock(DatabaseRepository.class);
        targets = mock(DatabaseUploadTargetRepository.class);
        ReflectionTestUtils.setField(controller, "securityHelper", security);
        ReflectionTestUtils.setField(controller, "userRepository", users);
        ReflectionTestUtils.setField(controller, "databaseRepository", databases);
        ReflectionTestUtils.setField(controller, "targetRepository", targets);
        when(security.getAuthenticatedUser("admin")).thenReturn(admin);
        when(security.getAuthenticatedUser("manager")).thenReturn(manager);
        when(security.getAuthenticatedUser("viewer")).thenReturn(viewer);
        when(security.hasRole(admin, Role.ADMIN)).thenReturn(true);
        when(security.hasAnyRole(admin, Role.ADMIN, Role.MANAGER)).thenReturn(true);
        when(security.hasAnyRole(manager, Role.ADMIN, Role.MANAGER)).thenReturn(true);
        when(users.findAll()).thenReturn(List.of(admin, manager, viewer));
        when(users.findById(2L)).thenReturn(Optional.of(manager));
        when(users.findById(3L)).thenReturn(Optional.of(viewer));
    }

    @Test void authAndAdminPermissionAreEnforced() {
        var input = new DatabaseUploadTargetController.TargetRequest(100);
        assertEquals(401, controller.list("2026-09", null, null).getStatusCode().value());
        assertEquals(401, controller.myProgress(null).getStatusCode().value());
        assertEquals(401, controller.setTarget(2L,"2026-09",input,null).getStatusCode().value());
        assertEquals(403, controller.setTarget(2L,"2026-09",input,"manager").getStatusCode().value());
        assertEquals(403, controller.setTarget(2L,"2026-09",input,"viewer").getStatusCode().value());
        verifyNoInteractions(targets, databases, users);
    }

    @Test void adminCanSetChangeAndClearMonthlyTargets() {
        for (int count : List.of(100, 200, 0)) {
            assertEquals(200, controller.setTarget(2L,"2026-09",new DatabaseUploadTargetController.TargetRequest(count),"admin").getStatusCode().value());
            verify(targets).setTarget(2L,LocalDate.of(2026,9,1),count,"MONTHLY");
        }
    }

    @Test void invalidPeriodsCountsAndViewerAssignmentsAreRejected() {
        for (String month : List.of("2026-13", "2026-00", "2026-9", "", "0000-01", "2026-09-01")) {
            assertEquals(400,controller.list(month,null,"admin").getStatusCode().value());
            assertEquals(400,controller.setTarget(2L,month,new DatabaseUploadTargetController.TargetRequest(10),"admin").getStatusCode().value());
        }
        assertEquals(400,controller.setTarget(2L,"2026-09",new DatabaseUploadTargetController.TargetRequest(-1),"admin").getStatusCode().value());
        assertEquals(400,controller.setTarget(2L,"2026-09",new DatabaseUploadTargetController.TargetRequest(null),"admin").getStatusCode().value());
        assertEquals(400,controller.setTarget(3L,"2026-09",new DatabaseUploadTargetController.TargetRequest(10),"admin").getStatusCode().value());
        assertEquals(404,controller.setTarget(999L,"2026-09",new DatabaseUploadTargetController.TargetRequest(10),"admin").getStatusCode().value());
        verifyNoInteractions(targets);
    }

    @Test void fractionalAndOverflowTargetsAreNotSilentlyCoercedByJson() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String value : List.of("1.5", "2147483648", "-0.5")) {
            var request = mapper.readValue("{\"targetCount\":" + value + "}", DatabaseUploadTargetController.TargetRequest.class);
            assertEquals(400, controller.setTarget(2L,"2026-09",request,"admin").getStatusCode().value());
        }
        verifyNoInteractions(targets);
    }

    @Test void managersOnlyReadTheirOwnProgressAndMonthBoundariesAreExclusive() {
        var uploaded = List.of(count(2L,"excel_import",70),count(2L,"manual",10));
        when(databases.countUploads(eq(List.of(2L)),any(),any())).thenReturn(uploaded);
        DatabaseUploadTarget target = new DatabaseUploadTarget();
        target.setUserId(2L);target.setTargetCount(100);
        when(targets.findByTargetMonthAndUserIdIn(LocalDate.of(2024,2,1),List.of(2L))).thenReturn(List.of(target));
        Map<?,?> response = (Map<?,?>) controller.list("2024-02",null,"manager").getBody();
        List<?> items = (List<?>) response.get("items");
        assertEquals(1,items.size());
        Map<?,?> row = (Map<?,?>)items.get(0);
        assertEquals(2L,row.get("userId"));assertEquals(80L,row.get("totalCount"));
        assertEquals(70L,row.get("excelCount"));assertEquals(10L,row.get("manualCount"));assertEquals(100,row.get("targetCount"));
        verify(databases).countUploads(List.of(2L),LocalDate.of(2024,2,1).atStartOfDay(),LocalDate.of(2024,3,1).atStartOfDay());
        verifyNoInteractions(users);
    }

    @Test void adminSeesAllAssignablePeopleIncludingThoseWithoutTargets() {
        Map<?,?> response = (Map<?,?>)controller.list("2026-12",null,"admin").getBody();
        List<?> items = (List<?>)response.get("items");
        assertEquals(2,items.size());
        for(Object item:items) {
            Map<?,?> row=(Map<?,?>)item;
            assertEquals(0,row.get("targetCount"));assertEquals(0L,row.get("totalCount"));assertFalse(row.containsKey("password"));
        }
        verify(databases).countUploads(List.of(1L,2L),LocalDate.of(2026,12,1).atStartOfDay(),LocalDate.of(2027,1,1).atStartOfDay());
    }

    @Test void dailyModeCanBeSavedAndInvalidModeOrDateIsRejected() {
        var daily = new DatabaseUploadTargetController.TargetRequest(java.math.BigDecimal.valueOf(20), "DAILY");
        assertEquals(200, controller.setTarget(2L,"2024-02",daily,"admin").getStatusCode().value());
        verify(targets).setTarget(2L,LocalDate.of(2024,2,1),20,"DAILY");
        for (String mode : List.of("WEEKLY", "daily", "")) {
            assertEquals(400, controller.setTarget(2L,"2024-02",
                    new DatabaseUploadTargetController.TargetRequest(java.math.BigDecimal.TEN,mode),"admin").getStatusCode().value());
        }
        for (String date : List.of("2024-03-01", "2024-02-30", "invalid")) {
            assertEquals(400, controller.list("2024-02",date,"admin").getStatusCode().value());
        }
    }

    @Test void dailyProgressUsesOnlySelectedDayEvenWhenMonthTotalExceedsTarget() {
        var target = new DatabaseUploadTarget();
        target.setUserId(2L);target.setTargetCount(20);target.setTargetMode("DAILY");
        var monthStart = LocalDate.of(2024,2,1);
        var day = LocalDate.of(2024,2,29);
        when(targets.findByTargetMonthAndUserIdIn(monthStart,List.of(2L))).thenReturn(List.of(target));
        var monthUploads = List.of(count(2,"excel_import",100),count(2,"manual",10));
        var dayUploads = List.of(count(2,"excel_import",10),count(2,"manual",2));
        when(databases.countUploads(List.of(2L),monthStart.atStartOfDay(),LocalDate.of(2024,3,1).atStartOfDay()))
                .thenReturn(monthUploads);
        when(databases.countUploads(List.of(2L),day.atStartOfDay(),day.plusDays(1).atStartOfDay()))
                .thenReturn(dayUploads);
        Map<?,?> response = (Map<?,?>)controller.list("2024-02","2024-02-29","manager").getBody();
        Map<?,?> row = (Map<?,?>)((List<?>)response.get("items")).get(0);
        assertEquals(110L,row.get("totalCount"));assertEquals(12L,row.get("dailyCount"));
        assertEquals(12L,row.get("progressCount"));assertEquals(8L,row.get("remainingCount"));
        assertEquals(580L,row.get("monthlyTargetCount"));assertEquals(29,response.get("daysInMonth"));
        verify(databases).countUploads(List.of(2L),day.atStartOfDay(),day.plusDays(1).atStartOfDay());

        target.setTargetMode("MONTHLY");
        response = (Map<?,?>)controller.list("2024-02","2024-02-29","manager").getBody();
        row = (Map<?,?>)((List<?>)response.get("items")).get(0);
        assertEquals(110L,row.get("progressCount"));assertEquals(0L,row.get("remainingCount"));
        assertEquals(20L,row.get("monthlyTargetCount"));
    }

    @Test void ownEndpointUsesServerDateAndNeverListsOtherUsersEvenForAdmin() {
        ReflectionTestUtils.setField(controller,"clock",java.time.Clock.fixed(
                java.time.Instant.parse("2024-02-29T17:30:00Z"),java.time.ZoneId.of("Asia/Jakarta")));
        Map<?,?> response = (Map<?,?>)controller.myProgress("admin").getBody();
        assertEquals("2024-03",response.get("month"));assertEquals("2024-03-01",response.get("date"));
        List<?> items = (List<?>)response.get("items");
        assertEquals(1,items.size());assertEquals(1L,((Map<?,?>)items.get(0)).get("userId"));
        verifyNoInteractions(users);
        verify(targets).findByTargetMonthAndUserIdIn(LocalDate.of(2024,3,1),List.of(1L));
        verify(databases).countUploads(List.of(1L),LocalDate.of(2024,3,1).atStartOfDay(),LocalDate.of(2024,3,2).atStartOfDay());
    }

    private DatabaseRepository.UploadCount count(long userId,String method,long total) {
        DatabaseRepository.UploadCount count=mock(DatabaseRepository.UploadCount.class);
        when(count.getUserId()).thenReturn(userId);when(count.getMethod()).thenReturn(method);when(count.getTotal()).thenReturn(total);
        return count;
    }
}
