package com.pms.repository;

import com.pms.domain.City;
import com.pms.domain.Venue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VenueRepository extends JpaRepository<Venue, Long> {
    Optional<Venue> findByNameAndCity(String name, City city);
    List<Venue> findByCityId(Long cityId);
    List<Venue> findAllByOrderByNameAsc();
}
