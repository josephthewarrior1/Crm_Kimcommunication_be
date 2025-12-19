package com.pms.controller;

import com.pms.domain.Project;
import com.pms.domain.AppUser;
import com.pms.domain.Role;
import com.pms.domain.ProjectEvent;
import com.pms.repository.ProjectEventRepository;
import com.pms.repository.ProjectRepository;
import com.pms.repository.SessionRepository;
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
    private final SessionRepository sessions;

    public ProjectEventCrudController(ProjectEventRepository events, ProjectRepository projects, SessionRepository sessions) {
        this.events = events;
        this.projects = projects;
        this.sessions = sessions;
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
                                               @RequestParam(name = "projectId", required = false) Long projectId) {
        if (projectId != null) {
            Optional<Project> opt = projects.findById(projectId);
            if (opt.isEmpty()) return ResponseEntity.status(400).<ProjectEvent>build();
            event.setProject(opt.get());
        }
        event.setId(null);
        ProjectEvent saved = events.save(event);
        return ResponseEntity.created(URI.create("/api/events/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProjectEvent> update(@PathVariable Long id, @Valid @RequestBody ProjectEvent event,
                                               @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        return events.findById(id).map(existing -> {
            if (u != null && !isAdminOrManager(u) && !hasAccess(existing.getProject(), u)) {
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
        AppUser u = currentUser(auth);
        var opt = events.findById(id);
        if (opt.isEmpty()) return new ResponseEntity<Void>(HttpStatus.NOT_FOUND);
        var existing = opt.get();
        if (u != null && !isAdminOrManager(u) && !hasAccess(existing.getProject(), u)) {
            return new ResponseEntity<Void>(HttpStatus.FORBIDDEN);
        }
        events.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private AppUser currentUser(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        String token = auth.substring(7);
        return sessions.findByTokenAndRevokedFalse(token)
                .filter(st -> st.getExpiresAt().isAfter(java.time.Instant.now()))
                .map(st -> st.getUser())
                .orElse(null);
    }
    private boolean isAdminOrManager(AppUser u) {
        return u != null && u.getRoles() != null && (u.getRoles().contains(Role.ADMIN) || u.getRoles().contains(Role.MANAGER));
    }
    private boolean hasAccess(Project p, AppUser u) {
        if (isAdminOrManager(u)) return true;
        if (p.getUsers() != null && u != null && u.getId() != null) {
            Long uid = u.getId();
            if (p.getUsers().stream().anyMatch(x -> uid.equals(x.getId()))) return true;
        }
        String email = u != null ? u.getEmail() : null;
        if (email != null && p.getTeamMembers() != null) {
            return p.getTeamMembers().stream().anyMatch(m -> email.equalsIgnoreCase(m.getEmail()));
        }
        return false;
    }
}
