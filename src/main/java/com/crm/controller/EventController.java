package com.crm.controller;

import com.crm.domain.Event;
import com.crm.repository.EventRepository;
import com.crm.repository.EventLeadRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/events")
public class EventController {

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventLeadRepository eventLeadRepository;

    @GetMapping
    public List<Event> getAllEvents() {
        return eventRepository.findAll();
    }

    @PostMapping
    public ResponseEntity<?> createEvent(@RequestBody Event event) {
        if (event.getName() == null || event.getName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Event name is required");
        }

        String cleanName = event.getName().trim();
        if (eventRepository.findByNameIgnoreCase(cleanName).isPresent()) {
            return ResponseEntity.badRequest().body("Event name already exists");
        }

        event.setName(cleanName);
        return ResponseEntity.ok(eventRepository.save(event));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Event> getEventById(@PathVariable UUID id) {
        return eventRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateEvent(@PathVariable UUID id, @RequestBody Event eventDetails) {
        return eventRepository.findById(id).map(existing -> {
            if (eventDetails.getName() == null || eventDetails.getName().trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Event name is required");
            }
            String cleanName = eventDetails.getName().trim();
            java.util.Optional<Event> duplicate = eventRepository.findByNameIgnoreCase(cleanName);
            if (duplicate.isPresent() && !duplicate.get().getId().equals(id)) {
                return ResponseEntity.badRequest().body("Event name already exists");
            }

            existing.setName(cleanName);
            existing.setEventType(eventDetails.getEventType());
            existing.setClientName(eventDetails.getClientName());
            existing.setDateStart(eventDetails.getDateStart());
            existing.setDateEnd(eventDetails.getDateEnd());
            existing.setNotes(eventDetails.getNotes());

            return ResponseEntity.ok(eventRepository.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteEvent(@PathVariable UUID id) {
        if (eventRepository.existsById(id)) {
            // Delete associated event leads
            List<com.crm.domain.EventLead> leads = eventLeadRepository.findByEventId(id);
            eventLeadRepository.deleteAll(leads);

            eventRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
