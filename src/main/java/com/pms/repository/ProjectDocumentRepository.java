package com.pms.repository;

import com.pms.domain.ProjectDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectDocumentRepository extends JpaRepository<ProjectDocument, Long> {
    List<ProjectDocument> findByProjectId(Long projectId);

    List<ProjectDocument> findByProjectIdAndFolderIsNull(Long projectId);

    List<ProjectDocument> findByProjectIdAndFolder_Id(Long projectId, Long folderId);

    boolean existsByProjectIdAndNameAndFolderIsNull(Long projectId, String name);

    boolean existsByProjectIdAndNameAndFolder_Id(Long projectId, String name, Long folderId);

    List<ProjectDocument> findByProjectIdAndNameStartingWithAndFolder_Id(Long projectId, String namePrefix, Long folderId);

    List<ProjectDocument> findByProjectIdAndNameStartingWithAndFolderIsNull(Long projectId, String namePrefix);
}

