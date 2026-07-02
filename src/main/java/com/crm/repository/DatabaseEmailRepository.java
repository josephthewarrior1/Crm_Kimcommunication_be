package com.crm.repository;

import com.crm.domain.DatabaseEmail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface DatabaseEmailRepository extends JpaRepository<DatabaseEmail, Long> {
    Optional<DatabaseEmail> findByEmail(String email);
}
