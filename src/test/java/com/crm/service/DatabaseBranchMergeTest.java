package com.crm.service;

import com.crm.domain.AppUser;
import com.crm.domain.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseBranchMergeTest {
    @Test void differingBranchAssignmentsBlockMergeBeforeAnyDataWrites() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> target = new LinkedHashMap<>(Map.of("id", 1L, "company_id", 1L, "branch_id", 10L,
                "first_name", "Budi", "last_name", "Santoso", "mobile_phone", "08123456789"));
        Map<String, Object> source = new LinkedHashMap<>(target);
        source.put("id", 2L); source.put("branch_id", 20L);
        when(jdbc.query(eq("SELECT row_to_json(row_data)::text FROM (SELECT * FROM databases WHERE id IN (?,?) ORDER BY id) row_data"),
                org.mockito.ArgumentMatchers.<RowMapper<Map<String, Object>>>any(), eq(1L), eq(2L)))
                .thenReturn(List.of(target, source));
        var service = new DatabaseMergeService(jdbc, new ObjectMapper());
        var actor = AppUser.builder().id(7L).roles(Set.of(Role.ADMIN)).build();
        var request = new DatabaseMergeService.MergeRequest(1L, 2L, Map.of(), Map.of(), null);
        var error = assertThrows(ResponseStatusException.class, () -> service.merge(request, actor, false));
        assertEquals(409, error.getStatusCode().value());
        assertTrue(error.getReason().contains("Different branches"));
        source.remove("branch_id");
        var unassigned = assertThrows(ResponseStatusException.class, () -> service.merge(request, actor, false));
        assertEquals(409, unassigned.getStatusCode().value());
        assertTrue(unassigned.getReason().contains("Different branches"));
        source.put("branch_id", 10L); source.put("company_id", 2L);
        var wrongCompany = assertThrows(ResponseStatusException.class, () -> service.merge(request, actor, false));
        assertEquals(409, wrongCompany.getStatusCode().value());
        assertTrue(wrongCompany.getReason().contains("Branch company mismatch"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
