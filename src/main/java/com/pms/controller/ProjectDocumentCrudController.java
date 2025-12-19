package com.pms.controller;

import com.pms.domain.AppUser;
import com.pms.domain.Project;
import com.pms.domain.ProjectDocument;
import com.pms.domain.Role;
import com.pms.repository.ProjectDocumentRepository;
import com.pms.repository.ProjectRepository;
import com.pms.repository.SessionRepository;
import com.pms.service.DocumentStorageService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/documents")
public class ProjectDocumentCrudController {

    private final ProjectDocumentRepository documents;
    private final ProjectRepository projects;
    private final DocumentStorageService storageService;
    private final SessionRepository sessions;

    public ProjectDocumentCrudController(ProjectDocumentRepository documents, ProjectRepository projects, DocumentStorageService storageService, SessionRepository sessions) {
        this.documents = documents;
        this.projects = projects;
        this.storageService = storageService;
        this.sessions = sessions;
    }

    @GetMapping
    public List<ProjectDocument> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null || isAdminOrManager(u)) return documents.findAll();
        return documents.findAll().stream().filter(d -> hasAccess(d.getProject(), u)).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectDocument> get(@PathVariable Long id,
                                               @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        return documents.findById(id).map(doc -> {
            if (u != null && !isAdminOrManager(u) && !hasAccess(doc.getProject(), u)) return ResponseEntity.status(403).body((ProjectDocument) null);
            return ResponseEntity.ok(doc);
        }).orElseGet(() -> ResponseEntity.status(404).body((ProjectDocument) null));
    }

    @PostMapping
    public ResponseEntity<ProjectDocument> create(@Valid @RequestBody ProjectDocument doc,
                                                  @RequestParam(name = "projectId", required = false) Long projectId,
                                                  @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (projectId != null) {
            Optional<Project> opt = projects.findById(projectId);
            if (opt.isEmpty()) return ResponseEntity.status(400).body((ProjectDocument) null);
            if (u != null && !isAdminOrManager(u) && !hasAccess(opt.get(), u)) return ResponseEntity.status(403).body((ProjectDocument) null);
            doc.setProject(opt.get());
        }
        doc.setId(null);
        ProjectDocument saved = documents.save(doc);
        return ResponseEntity.created(URI.create("/api/documents/" + saved.getId())).body(saved);
    }

    @PostMapping(value = "/upload", consumes = {"multipart/form-data"})
    public ResponseEntity<ProjectDocument> upload(@RequestParam("projectId") Long projectId,
                                                  @RequestPart("file") MultipartFile file,
                                                  @RequestParam(value = "name", required = false) String name,
                                                  @RequestParam(value = "type", required = false) String type,
                                                  @RequestParam(value = "description", required = false) String description,
                                                  @RequestHeader(value = "Authorization", required = false) String auth) throws Exception {
        Optional<Project> opt = projects.findById(projectId);
        if (opt.isEmpty()) return ResponseEntity.status(404).body((ProjectDocument) null);
        AppUser u = currentUser(auth);
        if (u != null && !isAdminOrManager(u) && !hasAccess(opt.get(), u)) return ResponseEntity.status(403).body((ProjectDocument) null);
        var stored = storageService.store(file);
        ProjectDocument doc = ProjectDocument.builder()
                .name(name != null ? name : file.getOriginalFilename())
                .description(description != null ? description : "")
                .type(type != null ? type : "upload")
                .url(stored.url)
                .uploadedAt(java.time.LocalDateTime.now())
                .status("pending")
                .project(opt.get())
                .build();
        ProjectDocument saved = documents.save(doc);
        return ResponseEntity.created(URI.create("/api/documents/" + saved.getId())).body(saved);
    }

    @PostMapping(value = "/{id}/upload", consumes = {"multipart/form-data"})
    public ResponseEntity<ProjectDocument> replace(@PathVariable Long id,
                                                   @RequestPart("file") MultipartFile file,
                                                   @RequestParam(value = "name", required = false) String name,
                                                   @RequestParam(value = "type", required = false) String type,
                                                   @RequestParam(value = "description", required = false, defaultValue = "") String description,
                                                   @RequestHeader(value = "Authorization", required = false) String auth) throws Exception {
        AppUser u = currentUser(auth);
        return documents.findById(id).map(existing -> {
            if (u != null && !isAdminOrManager(u) && !hasAccess(existing.getProject(), u)) return ResponseEntity.status(403).body((ProjectDocument) null);
            try {
                System.out.println("=== REPLACE FILE DEBUG ===");
                System.out.println("Received description parameter: '" + description + "'");
                System.out.println("Description is null: " + (description == null));
                System.out.println("Description is empty: " + (description != null && description.isEmpty()));
                System.out.println("Description equals 'null' string: " + ("null".equals(description)));
                System.out.println("Description length: " + (description != null ? description.length() : 0));
                
                var stored = storageService.store(file);
                existing.setUrl(stored.url);
                if (name != null && !name.isBlank()) existing.setName(name);
                else if (file.getOriginalFilename() != null) existing.setName(file.getOriginalFilename());
                if (type != null && !type.isBlank()) existing.setType(type);
                
                // Handle description - check for null or the string "null"
                if (description != null && !description.equals("null")) {
                    String trimmedDescription = description.trim();
                    existing.setDescription(trimmedDescription);
                    System.out.println("Setting description in replace to: '" + trimmedDescription + "'");
                } else {
                    // If null or "null" string, keep existing description
                    System.out.println("Description is null or 'null' string, keeping existing: '" + existing.getDescription() + "'");
                }
                
                existing.setUploadedAt(java.time.LocalDateTime.now());
                ProjectDocument saved = documents.saveAndFlush(existing);
                System.out.println("Saved document description after replace: '" + saved.getDescription() + "'");
                System.out.println("==========================");
                return ResponseEntity.ok(saved);
            } catch (Exception e) {
                return ResponseEntity.status(500).body((ProjectDocument) null);
            }
        }).orElseGet(() -> ResponseEntity.status(404).body((ProjectDocument) null));
    }

    @PostMapping("/{id}/review")
    public ResponseEntity<ProjectDocument> review(@PathVariable Long id,
                                                  @RequestParam("status") String status,
                                                  @RequestParam(value = "notes", required = false) String notes,
                                                  @RequestParam(value = "reviewedBy", required = false) String reviewedBy,
                                                  @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        return documents.findById(id).map(doc -> {
            if (u != null && !isAdminOrManager(u) && !hasAccess(doc.getProject(), u)) return ResponseEntity.status(403).body((ProjectDocument) null);
            doc.setStatus(status);
            doc.setReviewNotes(notes);
            doc.setReviewedBy(reviewedBy);
            doc.setReviewedAt(java.time.LocalDateTime.now());
            return ResponseEntity.ok(documents.save(doc));
        }).orElseGet(() -> ResponseEntity.status(404).body((ProjectDocument) null));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProjectDocument> update(@PathVariable Long id, @Valid @RequestBody ProjectDocument doc,
                                                  @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        return documents.findById(id).map(existing -> {
            if (u != null && !isAdminOrManager(u) && !hasAccess(existing.getProject(), u)) return ResponseEntity.status(403).body((ProjectDocument) null);
            
            System.out.println("Updating document " + id);
            System.out.println("Incoming description: " + doc.getDescription());
            System.out.println("Existing description: " + existing.getDescription());
            
            // Update all fields from the incoming document
            existing.setName(doc.getName());
            existing.setType(doc.getType());
            existing.setUrl(doc.getUrl() != null ? doc.getUrl() : existing.getUrl());
            existing.setStatus(doc.getStatus());
            // Explicitly set description - handle null as empty string
            String newDescription = doc.getDescription() != null ? doc.getDescription() : "";
            existing.setDescription(newDescription);
            
            System.out.println("Setting description to: " + newDescription);
            
            // Preserve the project relationship and other fields that shouldn't change
            // uploadedAt, reviewedAt, reviewedBy, reviewNotes are preserved from existing
            
            ProjectDocument saved = documents.saveAndFlush(existing);
            System.out.println("Saved document description: " + saved.getDescription());
            
            return ResponseEntity.ok(saved);
        }).orElseGet(() -> ResponseEntity.status(404).body((ProjectDocument) null));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        var opt = documents.findById(id);
        if (opt.isEmpty()) return new ResponseEntity<Void>(HttpStatus.NOT_FOUND);
        var existing = opt.get();
        if (u != null && !isAdminOrManager(u) && !hasAccess(existing.getProject(), u)) return new ResponseEntity<Void>(HttpStatus.FORBIDDEN);
        documents.deleteById(id);
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
