package com.pms.controller;

import com.pms.domain.ProjectDocument;
import com.pms.domain.WorkflowStage;
import com.pms.repository.ProjectDocumentRepository;
import com.pms.repository.ProjectRepository;
import com.pms.repository.WorkflowStageRepository;
import com.pms.service.DocumentStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Handles callbacks from OnlyOffice Document Server.
 *
 * When a user edits a document in OnlyOffice and saves/closes,
 * OnlyOffice POSTs a callback with the edited document URL.
 * This controller downloads the edited file and saves it as a new ProjectDocument.
 */
@RestController
@RequestMapping("/api/documents")
public class OnlyOfficeCallbackController {

    private static final Logger log = LoggerFactory.getLogger(OnlyOfficeCallbackController.class);

    private final DocumentStorageService storageService;
    private final ProjectDocumentRepository documentRepository;
    private final ProjectRepository projectRepository;
    private final WorkflowStageRepository stageRepository;

    public OnlyOfficeCallbackController(
            DocumentStorageService storageService,
            ProjectDocumentRepository documentRepository,
            ProjectRepository projectRepository,
            WorkflowStageRepository stageRepository) {
        this.storageService = storageService;
        this.documentRepository = documentRepository;
        this.projectRepository = projectRepository;
        this.stageRepository = stageRepository;
    }

    /**
     * OnlyOffice callback endpoint.
     *
     * OnlyOffice sends status codes:
     *   1 = document being edited
     *   2 = document ready for saving (all editors closed)
     *   4 = document closed with no changes
     *   6 = document being edited, but force save requested
     *   7 = error force saving
     *
     * On status 2 or 6, we download the edited file and store it as a new document.
     */
    @PostMapping("/onlyoffice-callback")
    public ResponseEntity<Map<String, Object>> handleCallback(
            @RequestParam(value = "projectId", required = false) Long projectId,
            @RequestParam(value = "stageId", required = false) Long stageId,
            @RequestParam(value = "fileName", required = false, defaultValue = "edited_document.docx") String fileName,
            @RequestBody Map<String, Object> body) {

        int status = body.get("status") instanceof Number
                ? ((Number) body.get("status")).intValue()
                : 0;

        log.info("OnlyOffice callback received: status={}, projectId={}, stageId={}, fileName={}",
                status, projectId, stageId, fileName);

        // Status 2 = document ready for saving, 6 = force save
        if (status == 2 || status == 6) {
            String downloadUrl = (String) body.get("url");
            if (downloadUrl == null || downloadUrl.isBlank()) {
                log.warn("OnlyOffice callback status {} but no download URL provided", status);
                return ResponseEntity.ok(Map.of("error", 0));
            }

            try {
                // Download the edited document from OnlyOffice
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(downloadUrl))
                        .GET()
                        .build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    log.error("Failed to download edited document from OnlyOffice: HTTP {}", response.statusCode());
                    return ResponseEntity.ok(Map.of("error", 0));
                }

                byte[] fileBytes = response.body().readAllBytes();

                // Determine content type based on file extension
                String contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                if (fileName.endsWith(".xlsx")) {
                    contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                } else if (fileName.endsWith(".pptx")) {
                    contentType = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                }

                // Store the file using the existing storage service
                final String ct = contentType;
                MultipartFile multipartFile = new ByteArrayMultipartFile(fileBytes, "file", fileName, ct);
                DocumentStorageService.StoredFile stored = storageService.store(multipartFile);

                log.info("Stored edited document: {} -> {}", fileName, stored.url);

                // Create a new ProjectDocument if projectId is provided
                if (projectId != null) {
                    projectRepository.findById(projectId).ifPresent(project -> {
                        ProjectDocument newDoc = ProjectDocument.builder()
                                .name(fileName)
                                .url(stored.url)
                                .type("document")
                                .status("pending")
                                .uploadedAt(LocalDateTime.now())
                                .project(project)
                                .build();
                        ProjectDocument savedDoc = documentRepository.save(newDoc);
                        log.info("Created new ProjectDocument id={} for project id={}", savedDoc.getId(), projectId);

                        // Link to stage if stageId is provided
                        if (stageId != null) {
                            stageRepository.findById(stageId).ifPresent(stage -> {
                                if (!stage.getRelatedDocuments().contains(savedDoc)) {
                                    stage.getRelatedDocuments().add(savedDoc);
                                    stageRepository.save(stage);
                                    log.info("Linked document id={} to stage id={}", savedDoc.getId(), stageId);
                                }
                            });
                        }
                    });
                }
            } catch (Exception e) {
                log.error("Error processing OnlyOffice callback", e);
            }
        }

        // OnlyOffice expects {"error": 0} to acknowledge receipt
        return ResponseEntity.ok(Map.of("error", 0));
    }

    /** Simple MultipartFile backed by a byte array (avoids test-scope MockMultipartFile). */
    private static class ByteArrayMultipartFile implements MultipartFile {
        private final byte[] content;
        private final String name;
        private final String originalFilename;
        private final String contentType;

        ByteArrayMultipartFile(byte[] content, String name, String originalFilename, String contentType) {
            this.content = content;
            this.name = name;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
        }

        @Override public String getName() { return name; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return content.length == 0; }
        @Override public long getSize() { return content.length; }
        @Override public byte[] getBytes() { return content; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
        @Override public void transferTo(File dest) throws java.io.IOException {
            java.nio.file.Files.write(dest.toPath(), content);
        }
    }
}
