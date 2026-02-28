package com.pms.controller;

import com.pms.domain.AppUser;
import com.pms.domain.Project;
import com.pms.domain.ProjectFinanceDocument;
import com.pms.repository.ProjectFinanceDocumentRepository;
import com.pms.repository.ProjectRepository;
import com.pms.service.ProjectPermissionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/finance-documents")
public class ProjectFinanceDocumentController {

    private static final int MAX_FINANCE_DOCS = 20;

    private final ProjectRepository projectRepository;
    private final ProjectFinanceDocumentRepository financeDocRepository;
    private final ProjectPermissionService permissionService;
    private final Path financeUploadDir;

    public ProjectFinanceDocumentController(ProjectRepository projectRepository,
                                            ProjectFinanceDocumentRepository financeDocRepository,
                                            ProjectPermissionService permissionService,
                                            @Value("${storage.local-dir:uploads}") String storageLocalDir) throws IOException {
        this.projectRepository = projectRepository;
        this.financeDocRepository = financeDocRepository;
        this.permissionService = permissionService;
        this.financeUploadDir = Paths.get(storageLocalDir, "finance_documents").toAbsolutePath().normalize();
        Files.createDirectories(this.financeUploadDir);
    }

    @GetMapping
    public ResponseEntity<?> list(@PathVariable Long projectId,
                                  @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser user = permissionService.resolveUser(auth);
        if (user == null) return ResponseEntity.status(401).build();

        return projectRepository.findById(projectId).map(project -> {
            if (!hasFinanceAccess(project, user))
                return ResponseEntity.status(403).body((Object) Map.of("error", "Access denied"));
            List<ProjectFinanceDocument> docs = financeDocRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
            return ResponseEntity.ok((Object) docs);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/count")
    public ResponseEntity<?> count(@PathVariable Long projectId,
                                   @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser user = permissionService.resolveUser(auth);
        if (user == null) return ResponseEntity.status(401).build();

        return projectRepository.findById(projectId).map(project -> {
            if (!hasFinanceAccess(project, user))
                return ResponseEntity.status(403).body((Object) Map.of("error", "Access denied"));
            long count = financeDocRepository.countByProjectId(projectId);
            return ResponseEntity.ok((Object) Map.of(
                    "count", count,
                    "max", MAX_FINANCE_DOCS,
                    "remaining", MAX_FINANCE_DOCS - count));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@PathVariable Long projectId,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "label", required = false) String label,
                                    @RequestParam(value = "description", required = false) String description,
                                    @RequestParam(value = "category", required = false) String category,
                                    @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser user = permissionService.resolveUser(auth);
        if (user == null) return ResponseEntity.status(401).build();

        return projectRepository.findById(projectId).map(project -> {
            if (!hasFinanceAccess(project, user))
                return ResponseEntity.status(403).body((Object) Map.of("error", "Access denied"));

            long count = financeDocRepository.countByProjectId(projectId);
            if (count >= MAX_FINANCE_DOCS) {
                return ResponseEntity.badRequest().body(
                        (Object) Map.of("error", "Maximum of " + MAX_FINANCE_DOCS + " finance documents per project reached."));
            }

            try {
                String original = StringUtils
                        .cleanPath(file.getOriginalFilename() == null ? "file" : file.getOriginalFilename());
                String ext = "";
                int dot = original.lastIndexOf('.');
                if (dot > 0 && dot < original.length() - 1) {
                    ext = original.substring(dot);
                }
                String storedName = UUID.randomUUID().toString().replace("-", "") + ext;
                Path target = financeUploadDir.resolve(storedName);
                Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

                ProjectFinanceDocument doc = new ProjectFinanceDocument();
                doc.setProject(project);
                doc.setLabel(label);
                doc.setDescription(description);
                doc.setFileName(original);
                doc.setFileUrl("/files/finance_documents/" + storedName);
                doc.setFileType(file.getContentType());
                doc.setFileSize(file.getSize());
                doc.setCategory(category);
                ProjectFinanceDocument saved = financeDocRepository.save(doc);
                return ResponseEntity.created(
                        URI.create("/api/projects/" + projectId + "/finance-documents/" + saved.getId()))
                        .body((Object) saved);
            } catch (IOException e) {
                return ResponseEntity.internalServerError().body(
                        (Object) Map.of("error", "Failed to store file: " + e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long projectId,
                                    @PathVariable Long id,
                                    @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser user = permissionService.resolveUser(auth);
        if (user == null) return ResponseEntity.status(401).build();

        return projectRepository.findById(projectId).map(project -> {
            if (!hasFinanceAccess(project, user))
                return ResponseEntity.status(403).body((Object) Map.of("error", "Access denied"));

            return financeDocRepository.findById(id).map(doc -> {
                if (doc.getFileUrl() != null && doc.getFileUrl().startsWith("/files/finance_documents/")) {
                    String storedName = doc.getFileUrl().replace("/files/finance_documents/", "");
                    try { Files.deleteIfExists(financeUploadDir.resolve(storedName)); } catch (IOException ignored) {}
                }
                financeDocRepository.deleteById(id);
                return ResponseEntity.noContent().<Void>build();
            }).orElse(ResponseEntity.notFound().<Void>build());
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping(value = "/{id}/replace", consumes = "multipart/form-data")
    public ResponseEntity<?> replace(@PathVariable Long projectId,
                                     @PathVariable Long id,
                                     @RequestParam("file") MultipartFile file,
                                     @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser user = permissionService.resolveUser(auth);
        if (user == null) return ResponseEntity.status(401).build();

        return projectRepository.findById(projectId).map(project -> {
            if (!hasFinanceAccess(project, user))
                return ResponseEntity.status(403).body((Object) Map.of("error", "Access denied"));

            return financeDocRepository.findById(id).map(doc -> {
                try {
                    // Delete old physical file
                    if (doc.getFileUrl() != null && doc.getFileUrl().startsWith("/files/finance_documents/")) {
                        String oldName = doc.getFileUrl().replace("/files/finance_documents/", "");
                        try { Files.deleteIfExists(financeUploadDir.resolve(oldName)); } catch (IOException ignored) {}
                    }

                    // Store new file
                    String original = StringUtils
                            .cleanPath(file.getOriginalFilename() == null ? "file" : file.getOriginalFilename());
                    String ext = "";
                    int dot = original.lastIndexOf('.');
                    if (dot > 0 && dot < original.length() - 1) {
                        ext = original.substring(dot);
                    }
                    String storedName = UUID.randomUUID().toString().replace("-", "") + ext;
                    Path target = financeUploadDir.resolve(storedName);
                    Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

                    // Update entity
                    doc.setFileName(original);
                    doc.setFileUrl("/files/finance_documents/" + storedName);
                    doc.setFileType(file.getContentType());
                    doc.setFileSize(file.getSize());
                    ProjectFinanceDocument saved = financeDocRepository.save(doc);
                    return ResponseEntity.ok((Object) saved);
                } catch (IOException e) {
                    return ResponseEntity.internalServerError().body(
                            (Object) Map.of("error", "Failed to replace file: " + e.getMessage()));
                }
            }).orElse(ResponseEntity.notFound().build());
        }).orElse(ResponseEntity.notFound().build());
    }

    private boolean hasFinanceAccess(Project project, AppUser user) {
        if (permissionService.isAdminOrManager(user)) return true;
        String roleName = permissionService.getProjectRoleName(project, user);
        return "FINANCE".equals(roleName) || "PROJECT_ADMIN".equals(roleName);
    }
}
