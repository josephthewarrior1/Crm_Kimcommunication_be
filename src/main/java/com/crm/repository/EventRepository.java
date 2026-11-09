package com.crm.repository;

import com.crm.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {
    Optional<Event> findByNameIgnoreCase(String name);
    Optional<Event> findByEmsEventId(Long emsEventId);
    Optional<Event> findByPmsEventId(Long pmsEventId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select event from Event event where event.id = :id")
    Optional<Event> findForSyncById(@org.springframework.data.repository.query.Param("id") Long id);
}
