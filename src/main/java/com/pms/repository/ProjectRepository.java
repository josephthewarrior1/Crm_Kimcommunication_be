package com.pms.repository;

import com.pms.domain.Project;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    // Frontend listing only needs stages for current-stage computation; avoid multiple bag fetches.
    @EntityGraph(attributePaths = {"stages"})
    List<Project> findAll();

    // Use default (lazy) for details; nested endpoints load collections separately.
    Optional<Project> findById(Long id);
    
    // Fetch project with teamMembers for team management endpoints
    @EntityGraph(attributePaths = {"teamMembers"})
    Optional<Project> findWithTeamMembersById(Long id);
}
