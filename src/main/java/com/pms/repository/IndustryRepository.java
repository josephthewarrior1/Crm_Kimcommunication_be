package com.pms.repository;

import com.pms.domain.Industry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IndustryRepository extends JpaRepository<Industry, Long> {
    Optional<Industry> findByName(String name);
    List<Industry> findAllByOrderByNameAsc();
}
