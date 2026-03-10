package com.pms.repository;

import com.pms.domain.PoInvoiceLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PoInvoiceLinkRepository extends JpaRepository<PoInvoiceLink, Long> {
    List<PoInvoiceLink> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
