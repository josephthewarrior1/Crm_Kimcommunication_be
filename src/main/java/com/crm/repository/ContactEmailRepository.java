package com.crm.repository;

import com.crm.domain.ContactEmail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContactEmailRepository extends JpaRepository<ContactEmail, UUID> {
    Optional<ContactEmail> findByEmail(String email);
}
