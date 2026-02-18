package com.pms.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pms.domain.ProjectRole;

public interface ProjectRoleRepository extends JpaRepository<ProjectRole, Long> {
    // Helper to find roles by name (e.g. findByName("ADMIN"))
    Optional<ProjectRole> findByName(String name);
}