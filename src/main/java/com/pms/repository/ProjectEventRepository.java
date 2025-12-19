package com.pms.repository;

import com.pms.domain.ProjectEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectEventRepository extends JpaRepository<ProjectEvent, Long> {
    List<ProjectEvent> findByProjectId(Long projectId);
}

