package com.crm.service;

import com.crm.domain.AuditLog;
import com.crm.domain.AppUser;
import com.crm.repository.AuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AuditLogService {
    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Jakarta");

    @Autowired
    private AuditLogRepository auditLogRepository;

    public List<AuditLog> getAuditLogs(String username, String module, String actionType, String search, String startDate, String endDate) {
        return auditLogRepository.findAll(buildSpec(username, module, actionType, search, startDate, endDate), Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    public Page<AuditLog> getAuditLogsPage(String username, String module, String actionType, String search, String startDate, String endDate, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 1000));
        return auditLogRepository.findAll(
                buildSpec(username, module, actionType, search, startDate, endDate),
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        );
    }

    private Specification<AuditLog> buildSpec(String username, String module, String actionType, String search, String startDate, String endDate) {
        Specification<AuditLog> spec = Specification.where(null);

        if (username != null && !username.trim().isEmpty() && !username.equalsIgnoreCase("ALL")) {
            spec = spec.and((root, query, cb) -> cb.equal(cb.lower(root.get("username")), username.trim().toLowerCase()));
        }
        if (module != null && !module.trim().isEmpty() && !module.equalsIgnoreCase("ALL")) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("module"), module.trim()));
        }
        if (actionType != null && !actionType.trim().isEmpty() && !actionType.equalsIgnoreCase("ALL")) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("actionType"), actionType.trim()));
        }
        if (search != null && !search.trim().isEmpty()) {
            String pattern = "%" + search.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("description")), pattern),
                cb.like(cb.lower(root.get("targetName")), pattern),
                cb.like(cb.lower(root.get("username")), pattern),
                cb.like(cb.lower(root.get("module")), pattern)
            ));
        }
        if (startDate != null && !startDate.trim().isEmpty()) {
            Instant start = LocalDate.parse(startDate.trim()).atStartOfDay(APP_ZONE).toInstant();
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), start));
        }
        if (endDate != null && !endDate.trim().isEmpty()) {
            Instant endExclusive = LocalDate.parse(endDate.trim()).plusDays(1).atStartOfDay(APP_ZONE).toInstant();
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("createdAt"), endExclusive));
        }

        return spec;
    }

    public Map<String, Object> getAuditLogsSummary(String username, String module, String actionType, String search, String startDate, String endDate) {
        List<AuditLog> logs = getAuditLogs(username, module, actionType, search, startDate, endDate);
        LocalDate today = LocalDate.now(APP_ZONE);

        long todayCount = logs.stream()
                .filter(log -> log.getCreatedAt() != null && log.getCreatedAt().atZone(APP_ZONE).toLocalDate().equals(today))
                .count();
        long criticalCount = logs.stream()
                .filter(log -> List.of("DELETE", "APPROVE_TAKEOUT", "REJECT_TAKEOUT", "FLAG_TIKUS").contains(log.getActionType()))
                .count();
        Map.Entry<String, Long> topUser = logs.stream()
                .collect(Collectors.groupingBy(AuditLog::getUsername, Collectors.counting()))
                .entrySet()
                .stream()
                .max(Comparator.comparingLong(Map.Entry::getValue))
                .orElse(null);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", logs.size());
        summary.put("today", todayCount);
        summary.put("topUser", topUser != null ? topUser.getKey() : "-");
        summary.put("topUserCount", topUser != null ? topUser.getValue() : 0);
        summary.put("critical", criticalCount);
        return summary;
    }

    public AuditLog recordLog(AuditLog log) {
        if (log.getCreatedAt() == null) {
            log.setCreatedAt(Instant.now());
        }
        return auditLogRepository.save(log);
    }

    public AuditLog recordUserAction(
            AppUser user,
            String module,
            String actionType,
            Long targetId,
            String targetName,
            String description) {
        AuditLog log = AuditLog.builder()
                .userId(user != null ? user.getId() : null)
                .username(user != null ? user.getUsername() : "system")
                .userFullName(user != null ? user.getFullName() : "System")
                .userRole(user != null && user.getRoles() != null
                        ? user.getRoles().stream().map(Enum::name).sorted().collect(Collectors.joining(","))
                        : "SYSTEM")
                .module(module)
                .actionType(actionType)
                .targetId(targetId)
                .targetName(targetName)
                .description(description)
                .createdAt(Instant.now())
                .build();
        return recordLog(log);
    }
}
