package com.crm.controller;

import com.crm.domain.AuditLog;
import com.crm.domain.AppUser;
import com.crm.domain.Role;
import com.crm.service.AuditLogService;
import com.crm.service.SecurityHelper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private SecurityHelper securityHelper;

    @GetMapping
    public ResponseEntity<?> getAuditLogs(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "15") Integer size,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can view audit logs");
        }

        String scopedUsername = securityHelper.hasRole(currentUser, Role.ADMIN) ? username : currentUser.getUsername();
        int safePage = page == null || page < 1 ? 1 : page;
        int safeSize = size == null || size < 1 ? 15 : Math.min(size, 1000);
        Page<AuditLog> logs = auditLogService.getAuditLogsPage(scopedUsername, module, actionType, search, startDate, endDate, safePage - 1, safeSize);
        return ResponseEntity.ok(Map.of(
                "items", logs.getContent(),
                "page", safePage,
                "size", safeSize,
                "total", logs.getTotalElements(),
                "totalPages", Math.max(logs.getTotalPages(), 1)
        ));
    }

    @GetMapping("/summary")
    public ResponseEntity<?> getAuditLogsSummary(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can view audit log summary");
        }

        String scopedUsername = securityHelper.hasRole(currentUser, Role.ADMIN) ? username : currentUser.getUsername();
        Map<String, Object> summary = auditLogService.getAuditLogsSummary(scopedUsername, module, actionType, search, startDate, endDate);
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/filter-options")
    public ResponseEntity<?> getAuditLogFilterOptions(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can view audit log filters");
        }

        String scopedUsername = securityHelper.hasRole(currentUser, Role.ADMIN) ? null : currentUser.getUsername();
        List<AuditLog> logs = auditLogService.getAuditLogs(scopedUsername, null, null, null, null, null);
        return ResponseEntity.ok(Map.of(
                "users", logs.stream().map(AuditLog::getUsername).filter(value -> value != null && !value.isBlank()).distinct().sorted().toList(),
                "modules", logs.stream().map(AuditLog::getModule).filter(value -> value != null && !value.isBlank()).distinct().sorted().toList(),
                "actionTypes", logs.stream().map(AuditLog::getActionType).filter(value -> value != null && !value.isBlank()).distinct().sorted().toList()
        ));
    }

    @PostMapping
    public ResponseEntity<?> recordLog(
            @RequestBody AuditLog log,
            HttpServletRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser != null) {
            if (log.getUserId() == null) log.setUserId(currentUser.getId());
            if (log.getUsername() == null) log.setUsername(currentUser.getUsername());
            if (log.getUserFullName() == null) log.setUserFullName(currentUser.getFullName());
        }

        if (log.getIpAddress() == null || log.getIpAddress().isBlank()) {
            String clientIp = request.getHeader("X-Forwarded-For");
            if (clientIp == null || clientIp.isEmpty() || "unknown".equalsIgnoreCase(clientIp)) {
                clientIp = request.getRemoteAddr();
            }
            log.setIpAddress(clientIp);
        }

        AuditLog saved = auditLogService.recordLog(log);
        return ResponseEntity.ok(saved);
    }
}
