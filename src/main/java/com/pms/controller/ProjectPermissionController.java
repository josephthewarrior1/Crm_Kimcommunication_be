package com.pms.controller;

import com.pms.domain.AppUser;
import com.pms.domain.Project;
import com.pms.repository.ProjectRepository;
import com.pms.service.ProjectPermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class ProjectPermissionController {

    private static final Logger log = LoggerFactory.getLogger(ProjectPermissionController.class);

    private final ProjectRepository projectRepository;
    private final ProjectPermissionService permissionService;

    public ProjectPermissionController(ProjectRepository projectRepository,
                                       ProjectPermissionService permissionService) {
        this.projectRepository = projectRepository;
        this.permissionService = permissionService;
    }

    @GetMapping("/my-permissions")
    public ResponseEntity<?> getMyPermissions(
            @PathVariable Long projectId,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        AppUser user = permissionService.resolveUser(auth);
        if (user == null) {
            log.warn("[my-permissions] Could not resolve user from token");
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        log.info("[my-permissions] User resolved: id={}, name={}, systemRoles={}",
                user.getId(), user.getName(), user.getRoles());

        var projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Project not found"));
        }
        Project project = projectOpt.get();

        log.info("[my-permissions] Project resolved: id={}, name={}", project.getId(), project.getName());

        boolean canCreate = permissionService.canCreate(project, user);
        boolean canRead   = permissionService.canRead(project, user);
        boolean canUpdate = permissionService.canUpdate(project, user);
        boolean canDelete = permissionService.canDelete(project, user);
        String projectRoleName = permissionService.getProjectRoleName(project, user);
        boolean isSystemAdmin = permissionService.isAdminOrManager(user);

        log.info("[my-permissions] RESULT: canCreate={}, canRead={}, canUpdate={}, canDelete={}, projectRoleName={}, isSystemAdmin={}",
                canCreate, canRead, canUpdate, canDelete, projectRoleName, isSystemAdmin);

        var result = new java.util.HashMap<String, Object>();
        result.put("canCreate", canCreate);
        result.put("canRead", canRead);
        result.put("canUpdate", canUpdate);
        result.put("canDelete", canDelete);
        result.put("projectRoleName", projectRoleName);
        result.put("isSystemAdmin", isSystemAdmin);
        return ResponseEntity.ok(result);
    }
}
