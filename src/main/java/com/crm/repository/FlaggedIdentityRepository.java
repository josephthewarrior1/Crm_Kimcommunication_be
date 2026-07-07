package com.crm.repository;

import com.crm.domain.FlaggedIdentity;
import com.crm.domain.FlagReason;
import com.crm.domain.FlagStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FlaggedIdentityRepository extends JpaRepository<FlaggedIdentity, Long> {
    boolean existsByDatabaseIdAndFlagReasonAndStatusNot(Long databaseId, FlagReason flagReason, FlagStatus status);
    void deleteByDatabaseId(Long databaseId);
}

