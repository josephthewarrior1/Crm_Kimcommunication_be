package com.pms.repository;

import com.pms.domain.VenueRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VenueRoomRepository extends JpaRepository<VenueRoom, Long> {
    List<VenueRoom> findByVenue_Id(Long venueId);
    List<VenueRoom> findByVenue_IdOrderByIdAsc(Long venueId);
}
