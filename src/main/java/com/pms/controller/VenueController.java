package com.pms.controller;

import com.pms.domain.Venue;
import com.pms.repository.VenueRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/venues")
public class VenueController {

    private final VenueRepository venueRepository;

    public VenueController(VenueRepository venueRepository) {
        this.venueRepository = venueRepository;
    }

    @GetMapping
    public List<Venue> list() {
        return venueRepository.findAllByOrderByNameAsc();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Venue> get(@PathVariable Long id) {
        return venueRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Venue> create(@RequestBody Venue venue) {
        venue.setId(null);
        Venue saved = venueRepository.save(venue);
        return ResponseEntity.created(URI.create("/api/venues/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Venue> update(@PathVariable Long id, @RequestBody Venue venue) {
        return venueRepository.findById(id).map(existing -> {
            if (venue.getName() != null) existing.setName(venue.getName());
            if (venue.getCity() != null) existing.setCity(venue.getCity());
            if (venue.getDocumentData() != null) existing.setDocumentData(venue.getDocumentData());
            return ResponseEntity.ok(venueRepository.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!venueRepository.existsById(id))
            return ResponseEntity.notFound().build();
        venueRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
