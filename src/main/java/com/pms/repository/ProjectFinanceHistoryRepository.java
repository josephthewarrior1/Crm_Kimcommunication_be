package com.pms.repository;

import com.pms.domain.ProjectFinanceHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectFinanceHistoryRepository extends JpaRepository<ProjectFinanceHistory, Long> {

    List<ProjectFinanceHistory> findByProjectIdOrderByChangedAtDesc(Long projectId);
}
