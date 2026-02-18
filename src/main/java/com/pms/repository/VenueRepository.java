package com.pms.repository;

import com.pms.domain.Venue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VenueRepository extends JpaRepository<Venue, Long> {
    Optional<Venue> findByNameAndCity(String name, String city);
    List<Venue> findByCity(String city);
    List<Venue> findAllByOrderByNameAsc();
}
