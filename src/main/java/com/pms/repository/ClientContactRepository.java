package com.pms.repository;

import com.pms.domain.ClientContact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClientContactRepository extends JpaRepository<ClientContact, Long> {
    List<ClientContact> findByClientId(Long clientId);
    List<ClientContact> findByClientIdOrderByName(Long clientId);
}
