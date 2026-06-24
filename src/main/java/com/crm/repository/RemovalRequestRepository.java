package com.crm.repository;

import com.crm.domain.RemovalRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface RemovalRequestRepository extends JpaRepository<RemovalRequest, UUID> {
}
