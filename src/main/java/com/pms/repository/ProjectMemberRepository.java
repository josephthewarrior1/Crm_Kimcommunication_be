package com.pms.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pms.domain.ProjectMember;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {
    // Check if user is already in project
    boolean existsByProjectIdAndUserId(Long projectId, Long userId);

    // Find a specific member record (useful for checking permissions)
    Optional<ProjectMember> findByProjectIdAndUserId(Long projectId, Long userId);

    // List all members of a project
    List<ProjectMember> findByProjectId(Long projectId);

    // Team-type-aware queries
    List<ProjectMember> findByProjectIdAndTeamType(Long projectId, String teamType);

    boolean existsByProjectIdAndUserIdAndTeamType(Long projectId, Long userId, String teamType);
}
