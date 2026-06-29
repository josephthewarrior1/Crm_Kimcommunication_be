package com.crm.repository;

import com.crm.domain.PersonalEmailDomain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PersonalEmailDomainRepository extends JpaRepository<PersonalEmailDomain, Long> {
    Optional<PersonalEmailDomain> findByDomain(String domain);
}
