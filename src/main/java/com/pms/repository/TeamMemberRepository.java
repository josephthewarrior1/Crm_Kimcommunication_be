package com.pms.repository;

import com.pms.domain.TeamMember;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {
    // // This now works because 'project' is a field in the entity
    // boolean existsByProjectIdAndUserId(Long projectId, Long userId);

    // // Optional: Useful for finding the team of a specific project
    // List<TeamMember> findByProjectId(Long projectId);
}
