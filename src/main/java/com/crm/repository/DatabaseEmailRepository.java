package com.crm.repository;

import com.crm.domain.DatabaseEmail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface DatabaseEmailRepository extends JpaRepository<DatabaseEmail, Long> {
    List<DatabaseEmail> findAllByEmailIgnoreCase(String email);
    boolean existsByDatabaseIdAndEmailIgnoreCase(Long databaseId, String email);
}
