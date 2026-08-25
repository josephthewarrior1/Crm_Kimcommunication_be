package com.crm.controller;

import com.crm.domain.AuditLog;
import com.crm.domain.AppUser;
import com.crm.domain.Role;
import com.crm.service.AuditLogService;
import com.crm.service.SecurityHelper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can view audit logs");
        }

        List<AuditLog> logs = auditLogService.getAuditLogs(username, module, actionType, search);
        return ResponseEntity.ok(logs);
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
