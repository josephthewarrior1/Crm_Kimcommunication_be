package com.crm.controller;

import com.crm.domain.Event;
import com.crm.domain.Role;
import com.crm.domain.AppUser;
import com.crm.repository.EventRepository;
import com.crm.repository.EventParticipantRepository;
import com.crm.service.SecurityHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/events")
public class EventController {

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventParticipantRepository eventParticipantRepository;

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired
    private com.crm.service.EmsService emsService;

    @GetMapping("/ems-upcoming")
    public ResponseEntity<?> getEmsUpcomingEvents(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        return ResponseEntity.ok(emsService.getUpcomingEvents());
    }

    @GetMapping("/ems-participants/{emsEventId}")
    public ResponseEntity<?> getEmsEventParticipants(
            @PathVariable Long emsEventId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        return ResponseEntity.ok(emsService.getEventParticipants(emsEventId));
    }

    @GetMapping
    public ResponseEntity<?> getAllEvents(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (isViewer(currentUser)) {
            return ResponseEntity.ok(eventRepository.findAll().stream()
                    .filter(event -> currentUser.getAllowedEventIds().contains(event.getId()))
                    .toList());
        }
        return ResponseEntity.ok(eventRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<?> createEvent(
            @RequestBody Event event,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can create events");
        }

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
    public ResponseEntity<?> getEventById(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        return eventRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateEvent(
            @PathVariable Long id, 
            @RequestBody Event eventDetails,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can update events");
        }

        if (eventDetails.getName() == null || eventDetails.getName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Event name is required");
        }
        String cleanName = eventDetails.getName().trim();

        Event existing = eventRepository.findById(id).orElseGet(() -> {
            java.util.Optional<Event> duplicate = eventRepository.findByNameIgnoreCase(cleanName);
            if (duplicate.isPresent()) {
                return duplicate.get();
            }
            return new Event();
        });

        existing.setName(cleanName);
        existing.setEventType(eventDetails.getEventType());
        existing.setClientName(eventDetails.getClientName());
        existing.setDateStart(eventDetails.getDateStart());
        existing.setDateEnd(eventDetails.getDateEnd());
        existing.setNotes(eventDetails.getNotes());
        existing.setTargetParticipants(eventDetails.getTargetParticipants());
        existing.setEmsEventId(eventDetails.getEmsEventId());

        Event saved = eventRepository.save(existing);
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/{id}/sync-ems")
    public ResponseEntity<?> syncEmsParticipants(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can sync EMS participants");
        }
        Event event = eventRepository.findById(id).orElse(null);
        if (event == null) {
            event = eventRepository.findByEmsEventId(id).orElse(null);
        }
        if (event == null) {
            event = eventRepository.findAll().stream()
                    .filter(e -> e.getEmsEventId() != null && e.getEmsEventId() > 0)
                    .findFirst().orElse(null);
        }
        if (event == null) {
            return ResponseEntity.notFound().build();
        }
        int count = emsService.syncParticipantsForEvent(event);
        return ResponseEntity.ok(java.util.Map.of("message", "Synced EMS participants", "count", count));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteEvent(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN can delete events");
        }

        if (eventRepository.existsById(id)) {
            // Delete associated event participants
            List<com.crm.domain.EventParticipant> participants = eventParticipantRepository.findByEventId(id);
            eventParticipantRepository.deleteAll(participants);

            eventRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private boolean isViewer(AppUser user) {
        return securityHelper.hasRole(user, Role.USER) && !securityHelper.hasAnyRole(user, Role.ADMIN, Role.MANAGER);
    }
}
