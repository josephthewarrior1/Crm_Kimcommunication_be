package com.pms.controller;

import com.pms.domain.AppUser;
import com.pms.domain.Project;
import com.pms.domain.WorkflowStage;
import com.pms.repository.ProjectRepository;
import com.pms.repository.WorkflowStageRepository;
import com.pms.service.ProjectPermissionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/stages")
public class WorkflowStageCrudController {

    private final WorkflowStageRepository stages;
    private final ProjectRepository projects;
    private final ProjectPermissionService permissionService;

    public WorkflowStageCrudController(WorkflowStageRepository stages, ProjectRepository projects,
                                       ProjectPermissionService permissionService) {
        this.stages = stages;
        this.projects = projects;
        this.permissionService = permissionService;
    }

    @GetMapping
    public List<WorkflowStage> list() {
        return stages.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkflowStage> get(@PathVariable Long id) {
        return stages.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.status(404).body((WorkflowStage) null));
    }

    @PostMapping
    public ResponseEntity<WorkflowStage> create(@Valid @RequestBody WorkflowStage stage,
                                                @RequestParam(name = "projectId", required = false) Long projectId) {
        if (projectId != null) {
            Optional<Project> opt = projects.findById(projectId);
            if (opt.isEmpty()) return ResponseEntity.status(400).body((WorkflowStage) null);
            stage.setProject(opt.get());
        }
        stage.setId(null);
        WorkflowStage saved = stages.save(stage);
        return ResponseEntity.created(URI.create("/api/stages/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<WorkflowStage> update(@PathVariable Long id, @Valid @RequestBody WorkflowStage stage,
                                                @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = permissionService.resolveUser(auth);
        return stages.findById(id).map(existing -> {
            if (u != null && !permissionService.canUpdate(existing.getProject(), u)) {
                return ResponseEntity.status(403).body((WorkflowStage) null);
            }
            stage.setId(existing.getId());
            if (stage.getProject() == null) {
                stage.setProject(existing.getProject());
            }
            // Preserve relatedDocuments if not provided
            if (stage.getRelatedDocuments() == null || stage.getRelatedDocuments().isEmpty()) {
                stage.setRelatedDocuments(existing.getRelatedDocuments());
            }
            return ResponseEntity.ok(stages.save(stage));
        }).orElseGet(() -> ResponseEntity.status(404).body((WorkflowStage) null));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = permissionService.resolveUser(auth);
        var opt = stages.findById(id);
        if (opt.isEmpty()) return new ResponseEntity<Void>(HttpStatus.NOT_FOUND);
        var existing = opt.get();
        if (u != null && !permissionService.canDelete(existing.getProject(), u)) {
            return new ResponseEntity<Void>(HttpStatus.FORBIDDEN);
        }
        stages.deleteById(id);
        return ResponseEntity.noContent().build();
    }

}
