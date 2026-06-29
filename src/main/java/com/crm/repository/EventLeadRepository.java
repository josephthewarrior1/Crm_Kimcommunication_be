package com.crm.repository;

import com.crm.domain.EventLead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventLeadRepository extends JpaRepository<EventLead, Long> {
    Optional<EventLead> findByEventIdAndContactId(Long eventId, Long contactId);
    List<EventLead> findByEventId(Long eventId);
    List<EventLead> findByContactId(Long contactId);
}
