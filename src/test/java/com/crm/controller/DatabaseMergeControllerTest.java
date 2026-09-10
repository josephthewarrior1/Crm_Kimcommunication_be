package com.crm.controller;

import com.crm.domain.AppUser;
import com.crm.domain.Role;
import com.crm.service.DatabaseMergeService;
import com.crm.service.SecurityHelper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseMergeControllerTest {
    @Test void httpRoutesKeepAuthAndConflictStatusDespiteGlobalHandler() throws Exception {
        var service=mock(DatabaseMergeService.class);
        var security=mock(SecurityHelper.class);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new DatabaseMergeController(service,security))
                .setControllerAdvice(new com.crm.config.GlobalExceptionHandler()).build();
        String body="{\"targetId\":1,\"sourceId\":2}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/databases/merge/preview")
                .contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        var actor=AppUser.builder().id(19L).roles(java.util.Set.of(Role.ADMIN)).build();
        when(security.getAuthenticatedUser("admin")).thenReturn(actor);when(security.hasRole(actor,Role.ADMIN)).thenReturn(true);
        when(service.merge(any(),eq(actor),eq(true))).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Preview stale"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/databases/merge")
                .header("Authorization","admin").contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error").value("Preview stale"));
    }
    @Test void onlyAdminCanPreviewOrApply() {
        var service = mock(DatabaseMergeService.class);
        var security = mock(SecurityHelper.class);
        var controller = new DatabaseMergeController(service, security);
        var request = new DatabaseMergeService.MergeRequest(1L,2L,Map.of(),Map.of(),null);
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> controller.preview(request,null)).getStatusCode().value());
        var manager = AppUser.builder().id(7L).build();
        when(security.getAuthenticatedUser("manager")).thenReturn(manager);
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller.merge(request,"manager")).getStatusCode().value());
        verifyNoInteractions(service);
        when(security.getAuthenticatedUser("admin")).thenReturn(manager);
        when(security.hasRole(manager, Role.ADMIN)).thenReturn(true);
        controller.preview(request,"admin"); controller.merge(request,"admin");
        verify(service).merge(request,manager,false); verify(service).merge(request,manager,true);
    }
}
