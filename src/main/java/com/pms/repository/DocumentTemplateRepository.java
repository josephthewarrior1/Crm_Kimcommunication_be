package com.pms.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pms.domain.DocumentTemplate;

public interface DocumentTemplateRepository extends JpaRepository<DocumentTemplate, Long> {
    List<DocumentTemplate> findByIsActiveTrue();
}