package com.pms.service;

import com.pms.domain.*;
import com.pms.repository.ClientFolderShareRepository;
import com.pms.repository.DocumentFolderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ClientFolderShareService {

    private final ClientFolderShareRepository shareRepository;
    private final DocumentFolderRepository folderRepository;

    public ClientFolderShareService(ClientFolderShareRepository shareRepository,
                                     DocumentFolderRepository folderRepository) {
        this.shareRepository = shareRepository;
        this.folderRepository = folderRepository;
    }

    /**
     * Check if a client user has access to a specific folder (direct or inherited from ancestor).
     */
    public boolean hasAccess(Long userId, Long folderId) {
        Long currentId = folderId;
        while (currentId != null) {
            if (shareRepository.existsByFolderIdAndUserId(currentId, userId)) {
                return true;
            }
            Optional<DocumentFolder> folderOpt = folderRepository.findById(currentId);
            if (folderOpt.isEmpty()) break;
            DocumentFolder folder = folderOpt.get();
            currentId = folder.getParent() != null ? folder.getParent().getId() : null;
        }
        return false;
    }

    /**
     * Get the effective permission for a client user on a folder.
     * Walks up the tree and returns the nearest ancestor's permission.
     * A share on a child folder overrides a share on a parent folder.
     */
    public ClientFolderPermission getEffectivePermission(Long userId, Long folderId) {
        Long currentId = folderId;
        while (currentId != null) {
            Optional<ClientFolderShare> shareOpt = shareRepository.findByFolderIdAndUserId(currentId, userId);
            if (shareOpt.isPresent()) {
                return shareOpt.get().getPermission();
            }
            Optional<DocumentFolder> folderOpt = folderRepository.findById(currentId);
            if (folderOpt.isEmpty()) break;
            currentId = folderOpt.get().getParent() != null
                    ? folderOpt.get().getParent().getId() : null;
        }
        return null; // no access
    }

    /**
     * Check if user can upload to a folder (VIEW_UPLOAD or FULL_CRUD).
     */
    public boolean canUpload(Long userId, Long folderId) {
        ClientFolderPermission perm = getEffectivePermission(userId, folderId);
        return perm == ClientFolderPermission.VIEW_UPLOAD || perm == ClientFolderPermission.FULL_CRUD;
    }

    /**
     * Check if user has full CRUD on a folder.
     */
    public boolean canFullCrud(Long userId, Long folderId) {
        return getEffectivePermission(userId, folderId) == ClientFolderPermission.FULL_CRUD;
    }

    /**
     * Share a folder with a client user.
     */
    @Transactional
    public ClientFolderShare shareFolder(DocumentFolder folder, AppUser user,
                                          ClientFolderPermission permission, AppUser sharedBy) {
        // Check if share already exists
        Optional<ClientFolderShare> existing = shareRepository.findByFolderIdAndUserId(folder.getId(), user.getId());
        if (existing.isPresent()) {
            // Update existing share
            ClientFolderShare share = existing.get();
            share.setPermission(permission);
            share.setSharedBy(sharedBy);
            return shareRepository.save(share);
        }

        ClientFolderShare share = ClientFolderShare.builder()
                .folder(folder)
                .user(user)
                .permission(permission)
                .sharedBy(sharedBy)
                .build();
        return shareRepository.save(share);
    }

    /**
     * Revoke a share by its ID.
     */
    @Transactional
    public void revokeShare(Long shareId) {
        shareRepository.deleteById(shareId);
    }

    /**
     * Update permission on an existing share.
     */
    @Transactional
    public ClientFolderShare updatePermission(Long shareId, ClientFolderPermission newPermission) {
        ClientFolderShare share = shareRepository.findById(shareId)
                .orElseThrow(() -> new IllegalArgumentException("Share not found"));
        share.setPermission(newPermission);
        return shareRepository.save(share);
    }

    /**
     * Get all shares for a folder (for the share dialog listing).
     */
    public List<ClientFolderShare> getSharesForFolder(Long folderId) {
        return shareRepository.findByFolderId(folderId);
    }

    /**
     * Get all shares for a user in a specific project.
     */
    public List<ClientFolderShare> getSharesForProjectAndUser(Long projectId, Long userId) {
        return shareRepository.findByProjectIdAndUserId(projectId, userId);
    }

    /**
     * Check if a client user has any folder share in a project.
     */
    public boolean hasAnyShareInProject(Long projectId, Long userId) {
        return !shareRepository.findByProjectIdAndUserId(projectId, userId).isEmpty();
    }

    /**
     * Get all shared folders for a client user, grouped by project.
     * Returns a map of projectId -> list of shares.
     */
    public Map<Long, List<ClientFolderShare>> getSharedFoldersByProject(Long userId) {
        List<ClientFolderShare> allShares = shareRepository.findByUserId(userId);
        return allShares.stream()
                .collect(Collectors.groupingBy(
                        s -> s.getFolder().getProject().getId()));
    }

    /**
     * Get the set of directly shared folder IDs for a user in a project.
     * Used to filter root-level folder listing for client users.
     */
    public Set<Long> getDirectlySharedFolderIds(Long projectId, Long userId) {
        return shareRepository.findByProjectIdAndUserId(projectId, userId).stream()
                .map(s -> s.getFolder().getId())
                .collect(Collectors.toSet());
    }
}
