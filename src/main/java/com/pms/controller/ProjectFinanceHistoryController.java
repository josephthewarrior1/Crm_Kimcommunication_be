package com.pms.controller;

import com.pms.domain.AppUser;
import com.pms.domain.ProjectFinanceHistory;
import com.pms.repository.ProjectFinanceHistoryRepository;
import com.pms.repository.ProjectRepository;
import com.pms.service.ProjectPermissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/finance-history")
public class ProjectFinanceHistoryController {

    private final ProjectFinanceHistoryRepository historyRepository;
    private final ProjectRepository projectRepository;
    private final ProjectPermissionService permissionService;

    public ProjectFinanceHistoryController(ProjectFinanceHistoryRepository historyRepository,
                                            ProjectRepository projectRepository,
                                            ProjectPermissionService permissionService) {
        this.historyRepository = historyRepository;
        this.projectRepository = projectRepository;
        this.permissionService = permissionService;
    }

    @GetMapping
    public ResponseEntity<?> getHistory(
            @PathVariable Long projectId,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        AppUser u = permissionService.resolveUser(auth);
        if (u == null || !permissionService.isAdminOrManager(u)) {
            // Also allow users with project read access (finance role users)
            if (u == null) return ResponseEntity.status(401).build();
            var projectOpt = projectRepository.findById(projectId);
            if (projectOpt.isEmpty()) return ResponseEntity.notFound().build();
            if (!permissionService.canRead(projectOpt.get(), u))
                return ResponseEntity.status(403).build();
        }

        List<ProjectFinanceHistory> entries = historyRepository.findByProjectIdOrderByChangedAtDesc(projectId);

        List<Map<String, Object>> result = entries.stream().map(e -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", e.getId());
            map.put("fieldName", e.getFieldName().name());
            map.put("oldValue", e.getOldValue());
            map.put("newValue", e.getNewValue());
            map.put("changedByName", e.getChangedBy() != null ? e.getChangedBy().getName() : null);
            map.put("changedAt", e.getChangedAt());
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }
}
