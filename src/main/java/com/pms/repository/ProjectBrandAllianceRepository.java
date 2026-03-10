package com.pms.repository;

import com.pms.domain.ProjectBrandAlliance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectBrandAllianceRepository extends JpaRepository<ProjectBrandAlliance, Long> {
    List<ProjectBrandAlliance> findByProjectId(Long projectId);
}
