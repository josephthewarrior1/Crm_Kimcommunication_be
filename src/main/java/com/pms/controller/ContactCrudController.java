package com.pms.controller;

import com.pms.domain.AppUser;
import com.pms.domain.Contact;
import com.pms.domain.Project;
import com.pms.repository.ContactRepository;
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
@RequestMapping("/api/contacts")
public class ContactCrudController {

    private final ContactRepository contacts;
    private final ProjectRepository projects;
    private final ProjectPermissionService permissionService;

    public ContactCrudController(ContactRepository contacts, ProjectRepository projects,
                                 ProjectPermissionService permissionService) {
        this.contacts = contacts;
        this.projects = projects;
        this.permissionService = permissionService;
    }

    @GetMapping
    public List<Contact> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = permissionService.resolveUser(auth);
        if (u == null || permissionService.isAdminOrManager(u)) return contacts.findAll();
        return contacts.findAll().stream().filter(c -> permissionService.canRead(c.getProject(), u)).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Contact> get(@PathVariable Long id, @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = permissionService.resolveUser(auth);
        return contacts.findById(id).map(c -> {
            if (u != null && !permissionService.canRead(c.getProject(), u)) return ResponseEntity.status(403).body((Contact) null);
            return ResponseEntity.ok(c);
        }).orElseGet(() -> ResponseEntity.status(404).body((Contact) null));
    }

    @PostMapping
    public ResponseEntity<Contact> create(@Valid @RequestBody Contact contact,
                                          @RequestParam(name = "projectId", required = false) Long projectId,
                                          @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = permissionService.resolveUser(auth);
        if (projectId != null) {
            Optional<Project> opt = projects.findById(projectId);
            if (opt.isEmpty()) return ResponseEntity.status(400).<Contact>build();
            if (u != null && !permissionService.canCreate(opt.get(), u)) return ResponseEntity.status(403).body((Contact) null);
            contact.setProject(opt.get());
        }
        contact.setId(null);
        Contact saved = contacts.save(contact);
        return ResponseEntity.created(URI.create("/api/contacts/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Contact> update(@PathVariable Long id, @Valid @RequestBody Contact contact,
                                          @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = permissionService.resolveUser(auth);
        return contacts.findById(id).map(existing -> {
            if (u != null && !permissionService.canUpdate(existing.getProject(), u)) return ResponseEntity.status(403).body((Contact) null);
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
        AppUser u = permissionService.resolveUser(auth);
        var opt = contacts.findById(id);
        if (opt.isEmpty()) return new ResponseEntity<Void>(HttpStatus.NOT_FOUND);
        var existing = opt.get();
        if (u != null && !permissionService.canDelete(existing.getProject(), u)) return new ResponseEntity<Void>(HttpStatus.FORBIDDEN);
        contacts.deleteById(id);
        return ResponseEntity.noContent().build();
    }

}
