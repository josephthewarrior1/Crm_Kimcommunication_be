package com.pms.repository;

import com.pms.domain.Country;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CountryRepository extends JpaRepository<Country, Long> {
    Optional<Country> findByName(String name);
    Optional<Country> findByCode(String code);
    List<Country> findAllByOrderByNameAsc();
}
