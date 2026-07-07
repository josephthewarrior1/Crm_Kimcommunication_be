package com.crm.repository;

import com.crm.domain.RemovalRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RemovalRequestRepository extends JpaRepository<RemovalRequest, Long> {
    void deleteByDatabaseId(Long databaseId);
}

