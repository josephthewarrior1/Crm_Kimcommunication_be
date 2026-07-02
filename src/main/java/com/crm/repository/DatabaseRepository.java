package com.crm.repository;

import com.crm.domain.Database;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface DatabaseRepository extends JpaRepository<Database, Long> {
    List<Database> findByFirstNameIgnoreCaseAndLastNameIgnoreCase(String firstName, String lastName);
    List<Database> findByMobilePhone(String mobilePhone);
    List<Database> findByNormalizedPhone(String normalizedPhone);
}
