package com.pms.repository;

import com.pms.domain.WorkflowStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowStageRepository extends JpaRepository<WorkflowStage, Long> {
    List<WorkflowStage> findByProjectId(Long projectId);
}

