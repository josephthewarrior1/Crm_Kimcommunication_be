package com.pms.repository;

import com.pms.domain.ProjectFinanceDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectFinanceDocumentRepository extends JpaRepository<ProjectFinanceDocument, Long> {
    List<ProjectFinanceDocument> findByProjectIdOrderByCreatedAtDesc(Long projectId);
    long countByProjectId(Long projectId);
}
