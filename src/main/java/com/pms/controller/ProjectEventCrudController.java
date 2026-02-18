package com.pms.controller;

import com.pms.domain.AppUser;
import com.pms.domain.Project;
import com.pms.domain.ProjectEvent;
import com.pms.repository.ProjectEventRepository;
import com.pms.repository.ProjectRepository;
import com.pms.service.ProjectPermissionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/events")
public class ProjectEventCrudController {

    private final ProjectEventRepository events;
    private final ProjectRepository projects;
    private final ProjectPermissionService permissionService;

    public ProjectEventCrudController(ProjectEventRepository events, ProjectRepository projects,
                                      ProjectPermissionService permissionService) {
        this.events = events;
        this.projects = projects;
        this.permissionService = permissionService;
    }

    @GetMapping
    public List<ProjectEvent> list() {
        return events.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectEvent> get(@PathVariable Long id) {
        return events.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.status(404).body((ProjectEvent) null));
    }

    @PostMapping
    public ResponseEntity<ProjectEvent> create(@Valid @RequestBody ProjectEvent event,
                                               @RequestParam(name = "projectId", required = false) Long projectId,
                                               @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = permissionService.resolveUser(auth);
        if (projectId != null) {
            Optional<Project> opt = projects.findById(projectId);
            if (opt.isEmpty()) return ResponseEntity.status(400).<ProjectEvent>build();
            if (u != null && !permissionService.canCreate(opt.get(), u)) return ResponseEntity.status(403).<ProjectEvent>build();
            event.setProject(opt.get());
        }
        event.setId(null);
        ProjectEvent saved = events.save(event);
        return ResponseEntity.created(URI.create("/api/events/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProjectEvent> update(@PathVariable Long id, @Valid @RequestBody ProjectEvent event,
                                               @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = permissionService.resolveUser(auth);
        return events.findById(id).map(existing -> {
            if (u != null && !permissionService.canUpdate(existing.getProject(), u)) {
                return ResponseEntity.status(403).body((ProjectEvent) null);
            }
            event.setId(existing.getId());
            if (event.getProject() == null) {
                event.setProject(existing.getProject());
            }
            return ResponseEntity.ok(events.save(event));
        }).orElseGet(() -> ResponseEntity.status(404).body((ProjectEvent) null));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = permissionService.resolveUser(auth);
        var opt = events.findById(id);
        if (opt.isEmpty()) return new ResponseEntity<Void>(HttpStatus.NOT_FOUND);
        var existing = opt.get();
        if (u != null && !permissionService.canDelete(existing.getProject(), u)) {
            return new ResponseEntity<Void>(HttpStatus.FORBIDDEN);
        }
        events.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
