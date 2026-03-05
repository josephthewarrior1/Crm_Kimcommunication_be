package com.pms.repository;

import com.pms.domain.ProjectTeam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectTeamRepository extends JpaRepository<ProjectTeam, Long> {
    List<ProjectTeam> findByProjectIdOrderBySortOrder(Long projectId);

    List<ProjectTeam> findByProjectId(Long projectId);

    Optional<ProjectTeam> findByProjectIdAndName(Long projectId, String name);
}
