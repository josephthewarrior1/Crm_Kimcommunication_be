package com.pms.repository;

import com.pms.domain.City;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CityRepository extends JpaRepository<City, Long> {
    Optional<City> findByName(String name);
    Optional<City> findByNameAndCountryId(String name, Long countryId);
    List<City> findByCountryId(Long countryId);
    List<City> findByCountryIdOrderByNameAsc(Long countryId);
    List<City> findByProvinceIdOrderByNameAsc(Long provinceId);
    List<City> findAllByOrderByNameAsc();
}
