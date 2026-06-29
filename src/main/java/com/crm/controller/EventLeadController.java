package com.crm.controller;

import com.crm.domain.*;
import com.crm.repository.*;
import com.crm.service.SecurityHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/event-leads")
public class EventLeadController {

    @Autowired
    private EventLeadRepository eventLeadRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private SecurityHelper securityHelper;

    @GetMapping
    public ResponseEntity<?> getAllEventLeads(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        return ResponseEntity.ok(eventLeadRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<?> createEventLead(
            @RequestBody EventLeadRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        Event event = eventRepository.findById(request.getEventId()).orElse(null);
        if (event == null) {
            return ResponseEntity.badRequest().body("Event not found");
        }

        java.util.List<Long> contactsToProcess = new java.util.ArrayList<>();
        if (request.getContactIds() != null && !request.getContactIds().isEmpty()) {
            contactsToProcess.addAll(request.getContactIds());
        } else if (request.getContactId() != null) {
            contactsToProcess.add(request.getContactId());
        }

        if (contactsToProcess.isEmpty()) {
            return ResponseEntity.badRequest().body("No Contact IDs provided");
        }

        java.util.List<EventLead> savedLeads = new java.util.ArrayList<>();
        for (Long contactId : contactsToProcess) {
            Contact contact = contactRepository.findById(contactId).orElse(null);
            if (contact == null) {
                continue;
            }

            if (eventLeadRepository.findByEventIdAndContactId(request.getEventId(), contactId).isPresent()) {
                continue;
            }

            EventLead eventLead = EventLead.builder()
                    .event(event)
                    .contact(contact)
                    .leadStatus(request.getLeadStatus() != null ? LeadStatus.valueOf(request.getLeadStatus()) : LeadStatus.white)
                    .attendanceStatus(request.getAttendanceStatus() != null ? AttendanceStatus.valueOf(request.getAttendanceStatus()) : AttendanceStatus.invited)
                    .notes(request.getNotes())
                    .build();
            savedLeads.add(eventLeadRepository.save(eventLead));
        }

        return ResponseEntity.ok(savedLeads);
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable Long id,
            @RequestParam(required = false) String leadStatus,
            @RequestParam(required = false) String attendanceStatus,
            @RequestParam(required = false) String notes,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        return eventLeadRepository.findById(id).map(lead -> {
            if (leadStatus != null) {
                try {
                    lead.setLeadStatus(LeadStatus.valueOf(leadStatus));
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body("Invalid leadStatus. Must be white, yellow, green, or red.");
                }
            }
            if (attendanceStatus != null) {
                try {
                    lead.setAttendanceStatus(AttendanceStatus.valueOf(attendanceStatus));
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body("Invalid attendanceStatus. Must be invited, registered, attended, no_show, or cancelled.");
                }
            }
            if (notes != null) {
                lead.setNotes(notes);
            }
            return ResponseEntity.ok(eventLeadRepository.save(lead));
        }).orElse(ResponseEntity.notFound().build());
    }

    @lombok.Data
    public static class EventLeadRequest {
        private Long eventId;
        private Long contactId;
        private List<Long> contactIds;
        private String leadStatus;
        private String attendanceStatus;
        private String notes;
    }
}
