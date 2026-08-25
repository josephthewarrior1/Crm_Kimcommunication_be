package com.crm.service;

import com.crm.domain.AuditLog;
import com.crm.repository.AuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class AuditLogService {

    @Autowired
    private AuditLogRepository auditLogRepository;

    public List<AuditLog> getAuditLogs(String username, String module, String actionType, String search) {
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

        return auditLogRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    public AuditLog recordLog(AuditLog log) {
        if (log.getCreatedAt() == null) {
            log.setCreatedAt(Instant.now());
        }
        return auditLogRepository.save(log);
    }
}
