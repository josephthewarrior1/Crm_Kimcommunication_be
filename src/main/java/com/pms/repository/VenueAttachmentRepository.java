package com.pms.repository;

import com.pms.domain.VenueAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VenueAttachmentRepository extends JpaRepository<VenueAttachment, Long> {
    List<VenueAttachment> findByVenueIdOrderByCreatedAtDesc(Long venueId);
    List<VenueAttachment> findByVenueIdAndRoomIsNullOrderByCreatedAtDesc(Long venueId);
    List<VenueAttachment> findByRoomIdOrderByCreatedAtDesc(Long roomId);
    List<VenueAttachment> findByVenueIdAndCategory(Long venueId, String category);
    long countByVenueId(Long venueId);
    long countByVenueIdAndRoomIsNull(Long venueId);
    long countByRoomId(Long roomId);
}
