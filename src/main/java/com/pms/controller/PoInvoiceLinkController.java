package com.pms.controller;

import com.pms.domain.*;
import com.pms.repository.PoInvoiceLinkRepository;
import com.pms.repository.ProjectRepository;
import com.pms.service.ProjectPermissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/po-invoice-links")
public class PoInvoiceLinkController {

    private final PoInvoiceLinkRepository linkRepository;
    private final ProjectRepository projectRepository;
    private final ProjectPermissionService permissionService;

    public PoInvoiceLinkController(PoInvoiceLinkRepository linkRepository,
                                    ProjectRepository projectRepository,
                                    ProjectPermissionService permissionService) {
        this.linkRepository = linkRepository;
        this.projectRepository = projectRepository;
        this.permissionService = permissionService;
    }

    @GetMapping
    public ResponseEntity<?> list(@PathVariable Long projectId,
                                   @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = permissionService.resolveUser(auth);
        Optional<Project> projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty()) return ResponseEntity.notFound().build();

        List<PoInvoiceLink> links = linkRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        List<Map<String, Object>> result = links.stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("poNo", l.getPoNo());
            m.put("invoiceNo", l.getInvoiceNo());
            m.put("notes", l.getNotes());
            m.put("createdByName", l.getCreatedBy() != null ? l.getCreatedBy().getName() : null);
            m.put("createdAt", l.getCreatedAt());
            return m;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable Long projectId,
                                     @RequestBody Map<String, String> body,
                                     @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = permissionService.resolveUser(auth);
        Optional<Project> projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty()) return ResponseEntity.notFound().build();

        String poNo = body.get("poNo");
        String invoiceNo = body.get("invoiceNo");
        if (poNo == null || poNo.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "PO No. is required"));

        PoInvoiceLink link = PoInvoiceLink.builder()
                .project(projectOpt.get())
                .poNo(poNo.trim())
                .invoiceNo(invoiceNo != null && !invoiceNo.isBlank() ? invoiceNo.trim() : null)
                .notes(body.get("notes"))
                .createdBy(u)
                .build();

        PoInvoiceLink saved = linkRepository.save(link);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", saved.getId());
        result.put("poNo", saved.getPoNo());
        result.put("invoiceNo", saved.getInvoiceNo());
        result.put("notes", saved.getNotes());
        result.put("createdByName", u != null ? u.getName() : null);
        result.put("createdAt", saved.getCreatedAt());

        return ResponseEntity.created(
                URI.create("/api/projects/" + projectId + "/po-invoice-links/" + saved.getId()))
                .body(result);
    }

    @DeleteMapping("/{linkId}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId,
                                        @PathVariable Long linkId,
                                        @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = permissionService.resolveUser(auth);
        Optional<Project> projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty()) return ResponseEntity.notFound().build();

        return linkRepository.findById(linkId).map(link -> {
            if (!link.getProject().getId().equals(projectId))
                return ResponseEntity.notFound().<Void>build();
            linkRepository.deleteById(linkId);
            return ResponseEntity.noContent().<Void>build();
        }).orElse(ResponseEntity.notFound().build());
    }
}
