package com.crm.repository;

import com.crm.domain.Contact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface ContactRepository extends JpaRepository<Contact, UUID> {
    List<Contact> findByFirstNameIgnoreCaseAndLastNameIgnoreCase(String firstName, String lastName);
}
