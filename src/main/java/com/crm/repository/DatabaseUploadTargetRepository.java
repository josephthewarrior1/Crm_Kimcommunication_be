package com.crm.repository;

import com.crm.domain.DatabaseUploadTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface DatabaseUploadTargetRepository extends JpaRepository<DatabaseUploadTarget, Long> {
    List<DatabaseUploadTarget> findByTargetMonthAndUserIdIn(LocalDate month, Collection<Long> userIds);

    // Atomic upsert also handles two admins assigning the first target concurrently.
    @Modifying
    @Transactional
    @Query(value = """
            insert into database_upload_targets (user_id, target_month, target_count, target_mode)
            values (:userId, :month, :targetCount, :targetMode)
            on conflict (user_id, target_month) do update
                set target_count = excluded.target_count, target_mode = excluded.target_mode
            """, nativeQuery = true)
    void setTarget(Long userId, LocalDate month, int targetCount, String targetMode);
}
