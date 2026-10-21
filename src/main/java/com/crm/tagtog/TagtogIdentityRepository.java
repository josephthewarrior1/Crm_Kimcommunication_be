package com.crm.tagtog;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TagtogIdentityRepository extends JpaRepository<TagtogIdentityLink, Long> {
    Optional<TagtogIdentityLink> findByIssuerAndSubject(String issuer, String subject);
    Optional<TagtogIdentityLink> findByCrmUserId(Long userId);
}
