package com.pms.controller;

import com.pms.domain.*;
import com.pms.repository.DocumentFolderRepository;
import com.pms.repository.ProjectDocumentRepository;
import com.pms.repository.ProjectRepository;
import com.pms.service.ClientFolderShareService;
import com.pms.service.DocumentStorageService;
import com.pms.service.ProjectPermissionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/documents")
public class ProjectDocumentCrudController {

    private final ProjectDocumentRepository documents;
    private final ProjectRepository projects;
    private final DocumentStorageService storageService;
    private final ProjectPermissionService permissionService;
    private final DocumentFolderRepository folderRepository;
    private final ClientFolderShareService shareService;

    public ProjectDocumentCrudController(ProjectDocumentRepository documents, ProjectRepository projects,
                                         DocumentStorageService storageService,
                                         ProjectPermissionService permissionService,
                                         DocumentFolderRepository folderRepository,
                                         ClientFolderShareService shareService) {
        this.documents = documents;
        this.projects = projects;
        this.storageService = storageService;
        this.permissionService = permissionService;
        this.folderRepository = folderRepository;
        this.shareService = shareService;
    }

    @GetMapping
    public List<ProjectDocument> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = permissionService.resolveUser(auth);
        if (u == null || permissionService.isAdminOrManager(u)) return documents.findAll();
        return documents.findAll().stream().filter(d -> permissionService.canRead(d.getProject(), u)).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectDocument> get(@PathVariable Long id,
                                               @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = permissionService.resolveUser(auth);
        return documents.findById(id).map(doc -> {
            if (u != null && !permissionService.canRead(doc.getProject(), u)) return ResponseEntity.status(403).body((ProjectDocument) null);
            return ResponseEntity.ok(doc);
        }).orElseGet(() -> ResponseEntity.status(404).body((ProjectDocument) null));
    }

    @PostMapping
    public ResponseEntity<ProjectDocument> create(@Valid @RequestBody ProjectDocument doc,
                                                  @RequestParam(name = "projectId", required = false) Long projectId,
                                                  @RequestParam(name = "folderId", required = false) Long folderId,
                                                  @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = permissionService.resolveUser(auth);
        if (projectId != null) {
            Optional<Project> opt = projects.findById(projectId);
            if (opt.isEmpty()) return ResponseEntity.status(400).body((ProjectDocument) null);
            if (permissionService.isClientUser(u)) {
                if (folderId == null || !shareService.canUpload(u.getId(), folderId))
                    return ResponseEntity.status(403).body((ProjectDocument) null);
            } else if (u != null && !permissionService.canCreate(opt.get(), u)) {
                return ResponseEntity.status(403).body((ProjectDocument) null);
            }
            doc.setProject(opt.get());
            if (folderId != null) {
                folderRepository.findById(folderId).ifPresent(folder -> {
                    if (folder.getProject().getId().equals(projectId)) {
                        doc.setFolder(folder);
                    }
                });
            }
        }
        doc.setId(null);
        ProjectDocument saved = documents.save(doc);
        return ResponseEntity.created(URI.create("/api/documents/" + saved.getId())).body(saved);
    }

    @GetMapping("/check-duplicate")
    public ResponseEntity<?> checkDuplicate(@RequestParam("projectId") Long projectId,
                                             @RequestParam("name") String name,
                                             @RequestParam(value = "folderId", required = false) Long folderId) {
        boolean exists;
        if (folderId != null) {
            exists = documents.existsByProjectIdAndNameAndFolder_Id(projectId, name, folderId);
        } else {
            exists = documents.existsByProjectIdAndNameAndFolderIsNull(projectId, name);
        }
        return ResponseEntity.ok(Map.of("exists", exists));
    }

    @PostMapping(value = "/upload", consumes = {"multipart/form-data"})
    public ResponseEntity<ProjectDocument> upload(@RequestParam("projectId") Long projectId,
                                                  @RequestPart("file") MultipartFile file,
                                                  @RequestParam(value = "name", required = false) String name,
                                                  @RequestParam(value = "type", required = false) String type,
                                                  @RequestParam(value = "description", required = false) String description,
                                                  @RequestParam(value = "folderId", required = false) Long folderId,
                                                  @RequestHeader(value = "Authorization", required = false) String auth) throws Exception {
        Optional<Project> opt = projects.findById(projectId);
        if (opt.isEmpty()) return ResponseEntity.status(404).body((ProjectDocument) null);
        AppUser u = permissionService.resolveUser(auth);
        if (permissionService.isClientUser(u)) {
            // Client users need VIEW_UPLOAD or FULL_CRUD on the target folder
            if (folderId == null || !shareService.canUpload(u.getId(), folderId))
                return ResponseEntity.status(403).body((ProjectDocument) null);
        } else if (u != null && !permissionService.canCreate(opt.get(), u)) {
            return ResponseEntity.status(403).body((ProjectDocument) null);
        }

        // Determine final document name, adding _copy suffix if duplicate exists
        String docName = name != null ? name : file.getOriginalFilename();
        docName = ensureUniqueName(docName, projectId, folderId);

        var stored = storageService.store(file);
        ProjectDocument doc = ProjectDocument.builder()
                .name(docName)
                .description(description != null ? description : "")
                .type(type != null ? type : "upload")
                .url(stored.url)
                .uploadedAt(java.time.LocalDateTime.now())
                .status("pending")
                .project(opt.get())
                .build();

        if (folderId != null) {
            folderRepository.findById(folderId).ifPresent(folder -> {
                if (folder.getProject().getId().equals(projectId)) {
                    doc.setFolder(folder);
                }
            });
        }

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
        AppUser u = permissionService.resolveUser(auth);
        return documents.findById(id).map(existing -> {
            if (u != null && !permissionService.canUpdate(existing.getProject(), u)) return ResponseEntity.status(403).body((ProjectDocument) null);
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
        AppUser u = permissionService.resolveUser(auth);
        return documents.findById(id).map(doc -> {
            if (u != null && !permissionService.canUpdate(doc.getProject(), u)) return ResponseEntity.status(403).body((ProjectDocument) null);
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
        AppUser u = permissionService.resolveUser(auth);
        return documents.findById(id).map(existing -> {
            if (permissionService.isClientUser(u)) {
                Long docFolderId = existing.getFolder() != null ? existing.getFolder().getId() : null;
                if (docFolderId == null || !shareService.canFullCrud(u.getId(), docFolderId))
                    return ResponseEntity.status(403).body((ProjectDocument) null);
            } else if (u != null && !permissionService.canUpdate(existing.getProject(), u)) {
                return ResponseEntity.status(403).body((ProjectDocument) null);
            }
            
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

    @PutMapping("/{id}/move")
    public ResponseEntity<?> moveToFolder(@PathVariable Long id,
                                           @RequestBody Map<String, Object> body,
                                           @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = permissionService.resolveUser(auth);
        return documents.findById(id).map(doc -> {
            if (u != null && !permissionService.canUpdate(doc.getProject(), u))
                return ResponseEntity.status(403).build();

            Object folderIdObj = body.get("folderId");
            if (folderIdObj == null) {
                doc.setFolder(null);
            } else {
                Long folderId = Long.valueOf(folderIdObj.toString());
                Optional<DocumentFolder> folderOpt = folderRepository.findById(folderId);
                if (folderOpt.isEmpty() || !folderOpt.get().getProject().getId().equals(doc.getProject().getId()))
                    return ResponseEntity.badRequest().body(Map.of("error", "Target folder not found"));
                doc.setFolder(folderOpt.get());
            }
            return ResponseEntity.ok(documents.save(doc));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = permissionService.resolveUser(auth);
        var opt = documents.findById(id);
        if (opt.isEmpty()) return new ResponseEntity<Void>(HttpStatus.NOT_FOUND);
        var existing = opt.get();
        if (permissionService.isClientUser(u)) {
            // Client users are never allowed to delete documents, even with FULL_CRUD
            return new ResponseEntity<Void>(HttpStatus.FORBIDDEN);
        } else if (u != null && !permissionService.canDelete(existing.getProject(), u)) {
            return new ResponseEntity<Void>(HttpStatus.FORBIDDEN);
        }
        documents.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Ensures the document name is unique within the project+folder scope.
     * If a duplicate exists, appends _copy (or _copy2, _copy3, etc.) before the file extension.
     */
    private String ensureUniqueName(String docName, Long projectId, Long folderId) {
        boolean exists;
        if (folderId != null) {
            exists = documents.existsByProjectIdAndNameAndFolder_Id(projectId, docName, folderId);
        } else {
            exists = documents.existsByProjectIdAndNameAndFolderIsNull(projectId, docName);
        }
        if (!exists) return docName;

        // Split name into base and extension
        String base = docName;
        String ext = "";
        int dot = docName.lastIndexOf('.');
        if (dot > 0) {
            base = docName.substring(0, dot);
            ext = docName.substring(dot);
        }

        // Try _copy, _copy2, _copy3, etc.
        for (int i = 1; i <= 100; i++) {
            String suffix = i == 1 ? "_copy" : "_copy" + i;
            String candidate = base + suffix + ext;
            boolean candidateExists;
            if (folderId != null) {
                candidateExists = documents.existsByProjectIdAndNameAndFolder_Id(projectId, candidate, folderId);
            } else {
                candidateExists = documents.existsByProjectIdAndNameAndFolderIsNull(projectId, candidate);
            }
            if (!candidateExists) return candidate;
        }
        // Fallback: append timestamp
        return base + "_copy_" + System.currentTimeMillis() + ext;
    }
}
