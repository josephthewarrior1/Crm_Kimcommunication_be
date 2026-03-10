package com.pms.controller;

import com.pms.domain.AppUser;
import com.pms.domain.Project;
import com.pms.domain.Task;
import com.pms.repository.ProjectRepository;
import com.pms.repository.TaskRepository;
import com.pms.service.ProjectPermissionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/tasks")
public class TaskCrudController {

    private final TaskRepository tasks;
    private final ProjectRepository projects;
    private final ProjectPermissionService permissionService;

    public TaskCrudController(TaskRepository tasks, ProjectRepository projects,
                              ProjectPermissionService permissionService) {
        this.tasks = tasks;
        this.projects = projects;
        this.permissionService = permissionService;
    }

    @GetMapping
    public List<Task> list() {
        return tasks.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Task> get(@PathVariable Long id) {
        return tasks.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.status(404).body((Task) null));
    }

    @PostMapping
    public ResponseEntity<Task> create(@Valid @RequestBody Task task,
                                       @RequestParam(name = "projectId", required = false) Long projectId) {
        if (projectId != null) {
            Optional<Project> opt = projects.findById(projectId);
            if (opt.isEmpty()) return ResponseEntity.status(400).body((Task) null);
            task.setProject(opt.get());
        }
        task.setId(null);
        Task saved = tasks.save(task);
        return ResponseEntity.created(URI.create("/api/tasks/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Task> update(@PathVariable Long id, @Valid @RequestBody Task task,
                                       @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = permissionService.resolveUser(auth);
        return tasks.findById(id).map(existing -> {
            if (u != null && !permissionService.canUpdate(existing.getProject(), u)) {
                return ResponseEntity.status(403).body((Task) null);
            }
            task.setId(existing.getId());
            if (task.getProject() == null) {
                task.setProject(existing.getProject());
            }
            // Preserve relatedDocuments if not provided
            if (task.getRelatedDocuments() == null || task.getRelatedDocuments().isEmpty()) {
                task.setRelatedDocuments(existing.getRelatedDocuments());
            }
            // Preserve pics if not provided
            if (task.getPics() == null || task.getPics().isEmpty()) {
                task.setPics(existing.getPics());
            }
            // Preserve approval fields on regular edits
            if (task.getApprovedBy() == null) {
                task.setApprovedBy(existing.getApprovedBy());
                task.setApprovedAt(existing.getApprovedAt());
            }
            return ResponseEntity.ok(tasks.save(task));
        }).orElseGet(() -> ResponseEntity.status(404).body((Task) null));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Task> approve(@PathVariable Long id,
                                        @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser user = permissionService.resolveUser(auth);
        if (user == null) return ResponseEntity.status(401).body(null);

        return tasks.findById(id).map(task -> {
            if (!permissionService.canUpdate(task.getProject(), user)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body((Task) null);
            }
            task.setApprovedBy(user);
            task.setApprovedAt(java.time.LocalDateTime.now());
            return ResponseEntity.ok(tasks.save(task));
        }).orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(null));
    }

    @PostMapping("/{id}/unapprove")
    public ResponseEntity<Task> unapprove(@PathVariable Long id,
                                          @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser user = permissionService.resolveUser(auth);
        if (user == null) return ResponseEntity.status(401).body(null);

        return tasks.findById(id).map(task -> {
            if (!permissionService.canUpdate(task.getProject(), user)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body((Task) null);
            }
            task.setApprovedBy(null);
            task.setApprovedAt(null);
            return ResponseEntity.ok(tasks.save(task));
        }).orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(null));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = permissionService.resolveUser(auth);
        var opt = tasks.findById(id);
        if (opt.isEmpty()) return new ResponseEntity<Void>(HttpStatus.NOT_FOUND);
        var existing = opt.get();
        if (u != null && !permissionService.canDelete(existing.getProject(), u)) {
            return new ResponseEntity<Void>(HttpStatus.FORBIDDEN);
        }
        tasks.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
