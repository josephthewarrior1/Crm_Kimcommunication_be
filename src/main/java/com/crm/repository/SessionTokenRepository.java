package com.crm.repository;

import com.crm.domain.SessionToken;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SessionTokenRepository extends JpaRepository<SessionToken, Long> {
    @EntityGraph(attributePaths = {"user", "user.roles", "user.allowedEventIds"})
    Optional<SessionToken> findByTokenHash(String hash);
    List<SessionToken> findByTagtogIssuerAndTagtogSubject(String issuer, String subject);
}
