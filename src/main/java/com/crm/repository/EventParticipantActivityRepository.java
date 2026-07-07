package com.crm.repository;

import com.crm.domain.EventParticipantActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface EventParticipantActivityRepository extends JpaRepository<EventParticipantActivity, Long> {
    List<EventParticipantActivity> findByEventParticipantIdOrderByCreatedAtDesc(Long eventParticipantId);
    
    List<EventParticipantActivity> findByEventParticipantEventId(Long eventId);

    @Query("SELECT a FROM EventParticipantActivity a WHERE a.eventParticipant.event.id = :eventId AND a.createdAt >= :startDate AND a.createdAt <= :endDate")
    List<EventParticipantActivity> findByEventIdAndDateRange(Long eventId, LocalDateTime startDate, LocalDateTime endDate);
}
