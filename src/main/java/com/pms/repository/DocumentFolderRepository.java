package com.pms.repository;

import com.pms.domain.DocumentFolder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentFolderRepository extends JpaRepository<DocumentFolder, Long> {

    List<DocumentFolder> findByProjectIdAndParentIsNull(Long projectId);

    List<DocumentFolder> findByProjectIdAndParentId(Long projectId, Long parentId);

    List<DocumentFolder> findByProjectId(Long projectId);
}
