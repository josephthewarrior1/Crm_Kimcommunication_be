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
@RequestMapping("/api/client-portal")
public class ClientPortalController {

    private final ProjectPermissionService permissionService;
    private final ClientFolderShareService shareService;
    private final ProjectRepository projectRepository;
    private final DocumentFolderRepository folderRepository;
    private final ProjectDocumentRepository documentRepository;

    public ClientPortalController(ProjectPermissionService permissionService,
                                   ClientFolderShareService shareService,
                                   ProjectRepository projectRepository,
                                   DocumentFolderRepository folderRepository,
                                   ProjectDocumentRepository documentRepository) {
        this.permissionService = permissionService;
        this.shareService = shareService;
        this.projectRepository = projectRepository;
        this.folderRepository = folderRepository;
        this.documentRepository = documentRepository;
    }

    @GetMapping("/shared-folders")
    public ResponseEntity<?> getSharedFolders(
            @RequestHeader(value = "Authorization", required = false) String auth) {

        AppUser u = permissionService.resolveUser(auth);
        if (u == null) return ResponseEntity.status(401).build();

        Map<Long, List<ClientFolderShare>> byProject = shareService.getSharedFoldersByProject(u.getId());

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, List<ClientFolderShare>> entry : byProject.entrySet()) {
            Long projectId = entry.getKey();
            Optional<Project> projectOpt = projectRepository.findById(projectId);
            if (projectOpt.isEmpty()) continue;

            Map<String, Object> group = new LinkedHashMap<>();
            group.put("projectId", projectId);
            group.put("projectName", projectOpt.get().getName());

            List<Map<String, Object>> folders = entry.getValue().stream().map(share -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("folderId", share.getFolder().getId());
                f.put("folderName", share.getFolder().getName());
                f.put("permission", share.getPermission().name());
                f.put("sharedAt", share.getCreatedAt());
                return f;
            }).collect(Collectors.toList());

            group.put("folders", folders);
            result.add(group);
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/projects/{projectId}/folders")
    public ResponseEntity<?> getClientFolderContents(
            @PathVariable Long projectId,
            @RequestParam(value = "parentId", required = false) Long parentId,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        AppUser u = permissionService.resolveUser(auth);
        if (u == null) return ResponseEntity.status(401).build();

        Optional<Project> projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty()) return ResponseEntity.notFound().build();

        // Get the set of directly shared folder IDs for this user
        Set<Long> sharedFolderIds = shareService.getDirectlySharedFolderIds(projectId, u.getId());
        if (sharedFolderIds.isEmpty()) return ResponseEntity.status(403).build();

        List<DocumentFolder> subfolders;
        List<ProjectDocument> docs;
        Long currentFolderId = null;
        String currentFolderName = null;
        ClientFolderPermission effectivePermission = null;

        if (parentId == null) {
            // Root level: show only directly shared folders
            subfolders = sharedFolderIds.stream()
                    .map(folderRepository::findById)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toList());
            docs = Collections.emptyList(); // No root-level docs for clients
        } else {
            // Verify the client has access to this folder (direct or inherited)
            if (!shareService.hasAccess(u.getId(), parentId))
                return ResponseEntity.status(403).build();

            Optional<DocumentFolder> folderOpt = folderRepository.findById(parentId);
            if (folderOpt.isEmpty() || !folderOpt.get().getProject().getId().equals(projectId))
                return ResponseEntity.notFound().build();

            currentFolderId = parentId;
            currentFolderName = folderOpt.get().getName();
            effectivePermission = shareService.getEffectivePermission(u.getId(), parentId);

            subfolders = folderRepository.findByProjectIdAndParentId(projectId, parentId);
            docs = documentRepository.findByProjectIdAndFolder_Id(projectId, parentId);
        }

        // Build breadcrumbs (starting from the shared folder, not from root)
        List<Map<String, Object>> breadcrumbs = buildClientBreadcrumbs(parentId, sharedFolderIds);

        // Build folder summaries
        List<Map<String, Object>> folderSummaries = subfolders.stream().map(f -> {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", f.getId());
            summary.put("name", f.getName());
            summary.put("createdAt", f.getCreatedAt());
            summary.put("childFolderCount", folderRepository.findByProjectIdAndParentId(projectId, f.getId()).size());
            summary.put("documentCount", documentRepository.findByProjectIdAndFolder_Id(projectId, f.getId()).size());
            return summary;
        }).collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("currentFolderId", currentFolderId);
        response.put("currentFolderName", currentFolderName);
        response.put("breadcrumbs", breadcrumbs);
        response.put("folders", folderSummaries);
        response.put("documents", docs);
        response.put("permission", effectivePermission != null ? effectivePermission.name() : null);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/projects/{projectId}/folders/tree")
    public ResponseEntity<?> getClientFolderTree(
            @PathVariable Long projectId,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        AppUser u = permissionService.resolveUser(auth);
        if (u == null) return ResponseEntity.status(401).build();

        Set<Long> sharedFolderIds = shareService.getDirectlySharedFolderIds(projectId, u.getId());
        if (sharedFolderIds.isEmpty()) return ResponseEntity.status(403).build();

        // Build a tree only for shared folders and their descendants
        List<DocumentFolder> allFolders = folderRepository.findByProjectId(projectId);

        // Filter to shared folders and all their descendants
        Set<Long> accessibleFolderIds = new HashSet<>(sharedFolderIds);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (DocumentFolder f : allFolders) {
                if (!accessibleFolderIds.contains(f.getId()) &&
                        f.getParent() != null &&
                        accessibleFolderIds.contains(f.getParent().getId())) {
                    accessibleFolderIds.add(f.getId());
                    changed = true;
                }
            }
        }

        Map<Long, List<DocumentFolder>> childrenMap = new HashMap<>();
        for (DocumentFolder f : allFolders) {
            if (!accessibleFolderIds.contains(f.getId())) continue;
            Long pid = f.getParent() != null ? f.getParent().getId() : null;
            if (pid == null || sharedFolderIds.contains(f.getId())) {
                // Treat shared folders as roots in client view
            } else {
                childrenMap.computeIfAbsent(pid, k -> new ArrayList<>()).add(f);
            }
        }

        List<Map<String, Object>> tree = sharedFolderIds.stream()
                .map(folderRepository::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(f -> buildTreeNode(f, childrenMap))
                .collect(Collectors.toList());

        return ResponseEntity.ok(tree);
    }

    private List<Map<String, Object>> buildClientBreadcrumbs(Long folderId, Set<Long> sharedFolderIds) {
        List<Map<String, Object>> crumbs = new ArrayList<>();
        Long currentId = folderId;
        while (currentId != null) {
            Optional<DocumentFolder> opt = folderRepository.findById(currentId);
            if (opt.isEmpty()) break;
            DocumentFolder f = opt.get();
            Map<String, Object> crumb = new LinkedHashMap<>();
            crumb.put("id", f.getId());
            crumb.put("name", f.getName());
            crumbs.add(0, crumb);
            // Stop if we've reached a directly shared folder
            if (sharedFolderIds.contains(f.getId())) break;
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
}
