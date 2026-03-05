package com.pms.repository;

import com.pms.domain.AppUser;
import com.pms.domain.ClientFolderShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ClientFolderShareRepository extends JpaRepository<ClientFolderShare, Long> {

    List<ClientFolderShare> findByFolderId(Long folderId);

    List<ClientFolderShare> findByUserId(Long userId);

    Optional<ClientFolderShare> findByFolderIdAndUserId(Long folderId, Long userId);

    boolean existsByFolderIdAndUserId(Long folderId, Long userId);

    void deleteByFolderIdAndUserId(Long folderId, Long userId);

    void deleteByFolderId(Long folderId);

    @Query("SELECT cfs FROM ClientFolderShare cfs WHERE cfs.folder.project.id = :projectId AND cfs.user.id = :userId")
    List<ClientFolderShare> findByProjectIdAndUserId(@Param("projectId") Long projectId, @Param("userId") Long userId);

    @Query("SELECT DISTINCT cfs.user FROM ClientFolderShare cfs WHERE cfs.folder.project.id = :projectId")
    List<AppUser> findClientUsersWithSharesInProject(@Param("projectId") Long projectId);
}
