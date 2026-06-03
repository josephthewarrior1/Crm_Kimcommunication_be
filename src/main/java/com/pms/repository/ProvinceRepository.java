package com.pms.repository;

import com.pms.domain.Province;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProvinceRepository extends JpaRepository<Province, Long> {
    List<Province> findAllByOrderByNameAsc();
    List<Province> findByCountryIdOrderByNameAsc(Long countryId);
    Optional<Province> findByNameAndCountryId(String name, Long countryId);
}
