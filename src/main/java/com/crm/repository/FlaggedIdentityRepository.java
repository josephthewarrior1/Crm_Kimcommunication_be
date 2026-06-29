package com.crm.repository;

import com.crm.domain.FlaggedIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface FlaggedIdentityRepository extends JpaRepository<FlaggedIdentity, Long> {
}
