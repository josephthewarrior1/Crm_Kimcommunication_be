package com.pms.repository;

import com.pms.domain.ChecklistItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChecklistItemRepository extends JpaRepository<ChecklistItem, Long> {
    List<ChecklistItem> findByStageIdOrderByOrderPositionAsc(Long stageId);
}
