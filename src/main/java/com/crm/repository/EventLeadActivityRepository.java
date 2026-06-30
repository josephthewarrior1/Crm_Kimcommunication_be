package com.crm.repository;

import com.crm.domain.EventLeadActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface EventLeadActivityRepository extends JpaRepository<EventLeadActivity, Long> {
    List<EventLeadActivity> findByEventLeadIdOrderByCreatedAtDesc(Long eventLeadId);
    
    List<EventLeadActivity> findByEventLeadEventId(Long eventId);

    @Query("SELECT a FROM EventLeadActivity a WHERE a.eventLead.event.id = :eventId AND a.createdAt >= :startDate AND a.createdAt <= :endDate")
    List<EventLeadActivity> findByEventIdAndDateRange(Long eventId, LocalDateTime startDate, LocalDateTime endDate);
}
