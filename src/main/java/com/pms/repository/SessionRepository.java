package com.pms.repository;

import com.pms.domain.SessionToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SessionRepository extends JpaRepository<SessionToken, Long> {
    Optional<SessionToken> findByTokenAndRevokedFalse(String token);
}

