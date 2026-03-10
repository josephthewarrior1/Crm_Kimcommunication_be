package com.pms.repository;

import com.pms.domain.TaskChecklistItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskChecklistItemRepository extends JpaRepository<TaskChecklistItem, Long> {
    List<TaskChecklistItem> findByTaskIdOrderByOrderPositionAsc(Long taskId);
}
