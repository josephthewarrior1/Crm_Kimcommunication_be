package com.pms.controller;

import com.pms.domain.AppUser;
import com.pms.domain.Contact;
import com.pms.domain.Project;
import com.pms.domain.Role;
import com.pms.repository.ContactRepository;
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
@RequestMapping("/api/contacts")
public class ContactCrudController {

    private final ContactRepository contacts;
    private final ProjectRepository projects;
    private final SessionRepository sessions;

    public ContactCrudController(ContactRepository contacts, ProjectRepository projects, SessionRepository sessions) {
        this.contacts = contacts;
        this.projects = projects;
        this.sessions = sessions;
    }

    @GetMapping
    public List<Contact> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null || isAdminOrManager(u)) return contacts.findAll();
        return contacts.findAll().stream().filter(c -> hasAccess(c.getProject(), u)).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Contact> get(@PathVariable Long id, @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        return contacts.findById(id).map(c -> {
            if (u != null && !isAdminOrManager(u) && !hasAccess(c.getProject(), u)) return ResponseEntity.status(403).body((Contact) null);
            return ResponseEntity.ok(c);
        }).orElseGet(() -> ResponseEntity.status(404).body((Contact) null));
    }

    @PostMapping
    public ResponseEntity<Contact> create(@Valid @RequestBody Contact contact,
                                          @RequestParam(name = "projectId", required = false) Long projectId,
                                          @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (projectId != null) {
            Optional<Project> opt = projects.findById(projectId);
            if (opt.isEmpty()) return ResponseEntity.status(400).<Contact>build();
            if (u != null && !isAdminOrManager(u) && !hasAccess(opt.get(), u)) return ResponseEntity.status(403).body((Contact) null);
            contact.setProject(opt.get());
        }
        contact.setId(null);
        Contact saved = contacts.save(contact);
        return ResponseEntity.created(URI.create("/api/contacts/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Contact> update(@PathVariable Long id, @Valid @RequestBody Contact contact,
                                          @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        return contacts.findById(id).map(existing -> {
            if (u != null && !isAdminOrManager(u) && !hasAccess(existing.getProject(), u)) return ResponseEntity.status(403).body((Contact) null);
            contact.setId(existing.getId());
            if (contact.getProject() == null) {
                contact.setProject(existing.getProject());
            }
            return ResponseEntity.ok(contacts.save(contact));
        }).orElseGet(() -> ResponseEntity.status(404).body((Contact) null));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        var opt = contacts.findById(id);
        if (opt.isEmpty()) return new ResponseEntity<Void>(HttpStatus.NOT_FOUND);
        var existing = opt.get();
        if (u != null && !isAdminOrManager(u) && !hasAccess(existing.getProject(), u)) return new ResponseEntity<Void>(HttpStatus.FORBIDDEN);
        contacts.deleteById(id);
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
