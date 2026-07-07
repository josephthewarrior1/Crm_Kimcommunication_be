package com.crm.repository;

import com.crm.domain.EventParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface EventParticipantRepository extends JpaRepository<EventParticipant, Long> {
    Optional<EventParticipant> findByEventIdAndDatabaseId(Long eventId, Long databaseId);

    List<EventParticipant> findByEventId(Long eventId);

    List<EventParticipant> findByDatabaseId(Long databaseId);

    void deleteByDatabaseId(Long databaseId);
}
