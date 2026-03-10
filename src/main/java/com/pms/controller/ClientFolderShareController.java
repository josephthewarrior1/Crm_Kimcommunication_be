package com.pms.controller;

import com.pms.domain.*;
import com.pms.repository.DocumentFolderRepository;
import com.pms.repository.UserRepository;
import com.pms.service.ClientFolderShareService;
import com.pms.service.ProjectPermissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/folders/{folderId}/shares")
public class ClientFolderShareController {

    private final DocumentFolderRepository folderRepository;
    private final UserRepository userRepository;
    private final ClientFolderShareService shareService;
    private final ProjectPermissionService permissionService;

    public ClientFolderShareController(DocumentFolderRepository folderRepository,
                                        UserRepository userRepository,
                                        ClientFolderShareService shareService,
                                        ProjectPermissionService permissionService) {
        this.folderRepository = folderRepository;
        this.userRepository = userRepository;
        this.shareService = shareService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public ResponseEntity<?> listShares(
            @PathVariable Long projectId,
            @PathVariable Long folderId,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        AppUser u = permissionService.resolveUser(auth);
        if (u == null || !permissionService.isAdminOrManager(u))
            return ResponseEntity.status(403).build();

        Optional<DocumentFolder> folderOpt = folderRepository.findById(folderId);
        if (folderOpt.isEmpty() || !folderOpt.get().getProject().getId().equals(projectId))
            return ResponseEntity.notFound().build();

        List<ClientFolderShare> shares = shareService.getSharesForFolder(folderId);
        List<Map<String, Object>> result = shares.stream().map(s -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", s.getId());
            map.put("userId", s.getUser().getId());
            map.put("userName", s.getUser().getName());
            map.put("userEmail", s.getUser().getEmail());
            map.put("permission", s.getPermission().name());
            map.put("sharedByName", s.getSharedBy() != null ? s.getSharedBy().getName() : null);
            map.put("createdAt", s.getCreatedAt());
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @GetMapping("/inherited")
    public ResponseEntity<?> listInheritedShares(
            @PathVariable Long projectId,
            @PathVariable Long folderId,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        AppUser u = permissionService.resolveUser(auth);
        if (u == null || !permissionService.isAdminOrManager(u))
            return ResponseEntity.status(403).build();

        Optional<DocumentFolder> folderOpt = folderRepository.findById(folderId);
        if (folderOpt.isEmpty() || !folderOpt.get().getProject().getId().equals(projectId))
            return ResponseEntity.notFound().build();

        List<ClientFolderShare> inherited = shareService.getInheritedShares(folderId);
        // Deduplicate by user (closest ancestor wins)
        Map<Long, ClientFolderShare> byUser = new LinkedHashMap<>();
        for (ClientFolderShare s : inherited) {
            byUser.putIfAbsent(s.getUser().getId(), s);
        }
        List<Map<String, Object>> result = byUser.values().stream().map(s -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("userId", s.getUser().getId());
            map.put("userName", s.getUser().getName());
            map.put("userEmail", s.getUser().getEmail());
            map.put("permission", s.getPermission().name());
            map.put("folderName", s.getFolder().getName());
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<?> createShare(
            @PathVariable Long projectId,
            @PathVariable Long folderId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        AppUser u = permissionService.resolveUser(auth);
        if (u == null || !permissionService.isAdminOrManager(u))
            return ResponseEntity.status(403).build();

        Optional<DocumentFolder> folderOpt = folderRepository.findById(folderId);
        if (folderOpt.isEmpty() || !folderOpt.get().getProject().getId().equals(projectId))
            return ResponseEntity.notFound().build();

        Object userIdObj = body.get("userId");
        String permissionStr = (String) body.get("permission");
        if (userIdObj == null || permissionStr == null)
            return ResponseEntity.badRequest().body(Map.of("error", "userId and permission are required"));

        Long targetUserId = Long.valueOf(userIdObj.toString());
        Optional<AppUser> targetUserOpt = userRepository.findById(targetUserId);
        if (targetUserOpt.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "User not found"));

        ClientFolderPermission permission;
        try {
            permission = ClientFolderPermission.valueOf(permissionStr);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid permission. Use VIEW_ONLY, VIEW_UPLOAD, or FULL_CRUD"));
        }

        ClientFolderShare share = shareService.shareFolder(
                folderOpt.get(), targetUserOpt.get(), permission, u);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", share.getId());
        result.put("userId", share.getUser().getId());
        result.put("userName", share.getUser().getName());
        result.put("permission", share.getPermission().name());
        result.put("createdAt", share.getCreatedAt());

        return ResponseEntity.status(201).body(result);
    }

    @PutMapping("/{shareId}")
    public ResponseEntity<?> updatePermission(
            @PathVariable Long projectId,
            @PathVariable Long folderId,
            @PathVariable Long shareId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        AppUser u = permissionService.resolveUser(auth);
        if (u == null || !permissionService.isAdminOrManager(u))
            return ResponseEntity.status(403).build();

        String permissionStr = body.get("permission");
        if (permissionStr == null)
            return ResponseEntity.badRequest().body(Map.of("error", "permission is required"));

        ClientFolderPermission permission;
        try {
            permission = ClientFolderPermission.valueOf(permissionStr);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid permission"));
        }

        try {
            ClientFolderShare updated = shareService.updatePermission(shareId, permission);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", updated.getId());
            result.put("permission", updated.getPermission().name());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{shareId}")
    public ResponseEntity<?> revokeShare(
            @PathVariable Long projectId,
            @PathVariable Long folderId,
            @PathVariable Long shareId,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        AppUser u = permissionService.resolveUser(auth);
        if (u == null || !permissionService.isAdminOrManager(u))
            return ResponseEntity.status(403).build();

        shareService.revokeShare(shareId);
        return ResponseEntity.noContent().build();
    }
}
