package com.pms.controller;

import com.pms.domain.*;
import com.pms.repository.DocumentFolderRepository;
import com.pms.repository.ProjectDocumentRepository;
import com.pms.repository.ProjectRepository;
import com.pms.service.ClientFolderShareService;
import com.pms.service.ProjectPermissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/folders")
public class DocumentFolderController {

    private final ProjectRepository projectRepository;
    private final DocumentFolderRepository folderRepository;
    private final ProjectDocumentRepository documentRepository;
    private final ProjectPermissionService permissionService;
    private final ClientFolderShareService shareService;

    public DocumentFolderController(ProjectRepository projectRepository,
                                     DocumentFolderRepository folderRepository,
                                     ProjectDocumentRepository documentRepository,
                                     ProjectPermissionService permissionService,
                                     ClientFolderShareService shareService) {
        this.projectRepository = projectRepository;
        this.folderRepository = folderRepository;
        this.documentRepository = documentRepository;
        this.permissionService = permissionService;
        this.shareService = shareService;
    }

    @GetMapping
    public ResponseEntity<?> listContents(
            @PathVariable Long projectId,
            @RequestParam(value = "parentId", required = false) Long parentId,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        Optional<Project> projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty()) return ResponseEntity.notFound().build();

        AppUser u = permissionService.resolveUser(auth);
        boolean isClient = permissionService.isClientUser(u);

        // For client users, check folder share access instead of project-level read
        if (u != null && !isClient && !permissionService.canRead(projectOpt.get(), u))
            return ResponseEntity.status(403).build();
        if (isClient && !shareService.hasAnyShareInProject(projectId, u.getId()))
            return ResponseEntity.status(403).build();

        List<DocumentFolder> subfolders;
        List<ProjectDocument> docs;
        Long currentFolderId = null;
        String currentFolderName = null;
        String effectivePermission = null;

        if (isClient) {
            // Client users: filtered view
            Set<Long> sharedFolderIds = shareService.getDirectlySharedFolderIds(projectId, u.getId());
            if (parentId == null) {
                // Root: show only directly shared folders
                subfolders = sharedFolderIds.stream()
                        .map(folderRepository::findById)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toList());
                docs = Collections.emptyList();
            } else {
                if (!shareService.hasAccess(u.getId(), parentId))
                    return ResponseEntity.status(403).build();
                Optional<DocumentFolder> folderOpt = folderRepository.findById(parentId);
                if (folderOpt.isEmpty() || !folderOpt.get().getProject().getId().equals(projectId))
                    return ResponseEntity.notFound().build();
                currentFolderId = parentId;
                currentFolderName = folderOpt.get().getName();
                ClientFolderPermission perm = shareService.getEffectivePermission(u.getId(), parentId);
                effectivePermission = perm != null ? perm.name() : null;
                subfolders = folderRepository.findByProjectIdAndParentId(projectId, parentId);
                docs = documentRepository.findByProjectIdAndFolder_Id(projectId, parentId);
            }
        } else {
            // Internal users: normal view
            if (parentId == null) {
                subfolders = folderRepository.findByProjectIdAndParentIsNull(projectId);
                docs = documentRepository.findByProjectIdAndFolderIsNull(projectId);
            } else {
                Optional<DocumentFolder> folderOpt = folderRepository.findById(parentId);
                if (folderOpt.isEmpty() || !folderOpt.get().getProject().getId().equals(projectId))
                    return ResponseEntity.notFound().build();
                currentFolderId = parentId;
                currentFolderName = folderOpt.get().getName();
                subfolders = folderRepository.findByProjectIdAndParentId(projectId, parentId);
                docs = documentRepository.findByProjectIdAndFolder_Id(projectId, parentId);
            }
        }

        // Build breadcrumbs
        List<Map<String, Object>> breadcrumbs = buildBreadcrumbs(parentId);

        // Build folder summaries
        List<Map<String, Object>> folderSummaries = subfolders.stream().map(f -> {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", f.getId());
            summary.put("name", f.getName());
            summary.put("createdAt", f.getCreatedAt());
            summary.put("childFolderCount", folderRepository.findByProjectIdAndParentId(projectId, f.getId()).size());
            summary.put("documentCount", documentRepository.findByProjectIdAndFolder_Id(projectId, f.getId()).size());
            // Include share count for admin/manager users
            if (!isClient) {
                summary.put("shareCount", shareService.getSharesForFolder(f.getId()).size());
            }
            return summary;
        }).collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("currentFolderId", currentFolderId);
        response.put("currentFolderName", currentFolderName);
        response.put("breadcrumbs", breadcrumbs);
        response.put("folders", folderSummaries);
        response.put("documents", docs);
        if (effectivePermission != null) {
            response.put("permission", effectivePermission);
        }

        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<?> createFolder(
            @PathVariable Long projectId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        Optional<Project> projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty()) return ResponseEntity.notFound().build();

        AppUser u = permissionService.resolveUser(auth);
        if (permissionService.isClientUser(u)) {
            // Client users need FULL_CRUD on the parent folder to create subfolders
            Object parentIdObj2 = body.get("parentId");
            if (parentIdObj2 == null) return ResponseEntity.status(403).build();
            Long pid = Long.valueOf(parentIdObj2.toString());
            if (!shareService.canFullCrud(u.getId(), pid))
                return ResponseEntity.status(403).build();
        } else if (u != null && !permissionService.canCreate(projectOpt.get(), u)) {
            return ResponseEntity.status(403).build();
        }

        String name = (String) body.get("name");
        if (name == null || name.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Folder name is required"));

        DocumentFolder folder = DocumentFolder.builder()
                .name(name.trim())
                .project(projectOpt.get())
                .build();

        // Set parent if provided
        Object parentIdObj = body.get("parentId");
        if (parentIdObj != null) {
            Long parentId = Long.valueOf(parentIdObj.toString());
            Optional<DocumentFolder> parentOpt = folderRepository.findById(parentId);
            if (parentOpt.isEmpty() || !parentOpt.get().getProject().getId().equals(projectId))
                return ResponseEntity.badRequest().body(Map.of("error", "Parent folder not found"));
            folder.setParent(parentOpt.get());
        }

        DocumentFolder saved = folderRepository.save(folder);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", saved.getId());
        response.put("name", saved.getName());
        response.put("parentId", saved.getParent() != null ? saved.getParent().getId() : null);
        response.put("createdAt", saved.getCreatedAt());

        return ResponseEntity.status(201).body(response);
    }

    @PutMapping("/{folderId}")
    public ResponseEntity<?> renameFolder(
            @PathVariable Long projectId,
            @PathVariable Long folderId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        Optional<Project> projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty()) return ResponseEntity.notFound().build();

        AppUser u = permissionService.resolveUser(auth);
        if (permissionService.isClientUser(u)) {
            if (!shareService.canFullCrud(u.getId(), folderId))
                return ResponseEntity.status(403).build();
        } else if (u != null && !permissionService.canUpdate(projectOpt.get(), u)) {
            return ResponseEntity.status(403).build();
        }

        Optional<DocumentFolder> folderOpt = folderRepository.findById(folderId);
        if (folderOpt.isEmpty() || !folderOpt.get().getProject().getId().equals(projectId))
            return ResponseEntity.notFound().build();

        String name = body.get("name");
        if (name == null || name.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Folder name is required"));

        DocumentFolder folder = folderOpt.get();
        folder.setName(name.trim());
        folderRepository.save(folder);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", folder.getId());
        response.put("name", folder.getName());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{folderId}")
    public ResponseEntity<?> deleteFolder(
            @PathVariable Long projectId,
            @PathVariable Long folderId,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        Optional<Project> projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty()) return ResponseEntity.notFound().build();

        AppUser u = permissionService.resolveUser(auth);
        if (permissionService.isClientUser(u)) {
            if (!shareService.canFullCrud(u.getId(), folderId))
                return ResponseEntity.status(403).build();
        } else if (u != null && !permissionService.canDelete(projectOpt.get(), u)) {
            return ResponseEntity.status(403).build();
        }

        Optional<DocumentFolder> folderOpt = folderRepository.findById(folderId);
        if (folderOpt.isEmpty() || !folderOpt.get().getProject().getId().equals(projectId))
            return ResponseEntity.notFound().build();

        // Recursively unset folder_id for all documents in this folder and descendants
        unsetFolderForDescendants(folderOpt.get());

        folderRepository.delete(folderOpt.get());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{folderId}/move")
    public ResponseEntity<?> moveFolder(
            @PathVariable Long projectId,
            @PathVariable Long folderId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        Optional<Project> projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty()) return ResponseEntity.notFound().build();

        AppUser u = permissionService.resolveUser(auth);
        if (permissionService.isClientUser(u)) {
            if (!shareService.canFullCrud(u.getId(), folderId))
                return ResponseEntity.status(403).build();
        } else if (u != null && !permissionService.canUpdate(projectOpt.get(), u)) {
            return ResponseEntity.status(403).build();
        }

        Optional<DocumentFolder> folderOpt = folderRepository.findById(folderId);
        if (folderOpt.isEmpty() || !folderOpt.get().getProject().getId().equals(projectId))
            return ResponseEntity.notFound().build();

        DocumentFolder folder = folderOpt.get();
        Object newParentIdObj = body.get("parentId");

        if (newParentIdObj == null) {
            // Move to root
            folder.setParent(null);
        } else {
            Long newParentId = Long.valueOf(newParentIdObj.toString());

            // Prevent moving into itself
            if (newParentId.equals(folderId))
                return ResponseEntity.badRequest().body(Map.of("error", "Cannot move a folder into itself"));

            // Prevent circular reference — check if newParentId is a descendant of folderId
            if (isDescendant(folderId, newParentId))
                return ResponseEntity.badRequest().body(Map.of("error", "Cannot move a folder into one of its own subfolders"));

            Optional<DocumentFolder> newParentOpt = folderRepository.findById(newParentId);
            if (newParentOpt.isEmpty() || !newParentOpt.get().getProject().getId().equals(projectId))
                return ResponseEntity.badRequest().body(Map.of("error", "Target folder not found"));

            folder.setParent(newParentOpt.get());
        }

        folderRepository.save(folder);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", folder.getId());
        response.put("name", folder.getName());
        response.put("parentId", folder.getParent() != null ? folder.getParent().getId() : null);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/tree")
    public ResponseEntity<?> getFolderTree(
            @PathVariable Long projectId,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        Optional<Project> projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty()) return ResponseEntity.notFound().build();

        AppUser u = permissionService.resolveUser(auth);
        if (u != null && !permissionService.canRead(projectOpt.get(), u))
            return ResponseEntity.status(403).build();

        List<DocumentFolder> allFolders = folderRepository.findByProjectId(projectId);

        // Build tree from flat list
        Map<Long, List<DocumentFolder>> childrenMap = new HashMap<>();
        List<DocumentFolder> roots = new ArrayList<>();

        for (DocumentFolder f : allFolders) {
            Long parentId = f.getParent() != null ? f.getParent().getId() : null;
            if (parentId == null) {
                roots.add(f);
            } else {
                childrenMap.computeIfAbsent(parentId, k -> new ArrayList<>()).add(f);
            }
        }

        List<Map<String, Object>> tree = roots.stream()
                .map(f -> buildTreeNode(f, childrenMap))
                .collect(Collectors.toList());

        return ResponseEntity.ok(tree);
    }

    // --- Helper methods ---

    private List<Map<String, Object>> buildBreadcrumbs(Long folderId) {
        List<Map<String, Object>> crumbs = new ArrayList<>();
        Long currentId = folderId;
        while (currentId != null) {
            Optional<DocumentFolder> opt = folderRepository.findById(currentId);
            if (opt.isEmpty()) break;
            DocumentFolder f = opt.get();
            Map<String, Object> crumb = new LinkedHashMap<>();
            crumb.put("id", f.getId());
            crumb.put("name", f.getName());
            crumbs.add(0, crumb); // prepend
            currentId = f.getParent() != null ? f.getParent().getId() : null;
        }
        return crumbs;
    }

    private Map<String, Object> buildTreeNode(DocumentFolder folder, Map<Long, List<DocumentFolder>> childrenMap) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", folder.getId());
        node.put("name", folder.getName());

        List<DocumentFolder> children = childrenMap.getOrDefault(folder.getId(), Collections.emptyList());
        node.put("children", children.stream()
                .map(c -> buildTreeNode(c, childrenMap))
                .collect(Collectors.toList()));

        return node;
    }

    private boolean isDescendant(Long ancestorId, Long candidateId) {
        Long currentId = candidateId;
        while (currentId != null) {
            if (currentId.equals(ancestorId)) return true;
            Optional<DocumentFolder> opt = folderRepository.findById(currentId);
            if (opt.isEmpty()) break;
            currentId = opt.get().getParent() != null ? opt.get().getParent().getId() : null;
        }
        return false;
    }

    private void unsetFolderForDescendants(DocumentFolder folder) {
        // Unset folder for documents directly in this folder
        List<ProjectDocument> docs = documentRepository.findByProjectIdAndFolder_Id(
                folder.getProject().getId(), folder.getId());
        for (ProjectDocument doc : docs) {
            doc.setFolder(null);
            documentRepository.save(doc);
        }

        // Recurse into child folders
        List<DocumentFolder> children = folderRepository.findByProjectIdAndParentId(
                folder.getProject().getId(), folder.getId());
        for (DocumentFolder child : children) {
            unsetFolderForDescendants(child);
        }
    }
}
