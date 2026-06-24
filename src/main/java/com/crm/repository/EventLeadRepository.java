package com.crm.repository;

import com.crm.domain.EventLead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventLeadRepository extends JpaRepository<EventLead, UUID> {
    Optional<EventLead> findByEventIdAndContactId(UUID eventId, UUID contactId);
    List<EventLead> findByEventId(UUID eventId);
    List<EventLead> findByContactId(UUID contactId);
}
