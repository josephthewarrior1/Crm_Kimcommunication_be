package com.crm.controller;

import com.crm.domain.*;
import com.crm.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/event-leads")
public class EventLeadController {

    @Autowired
    private EventLeadRepository eventLeadRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ContactRepository contactRepository;

    @GetMapping
    public List<EventLead> getAllEventLeads() {
        return eventLeadRepository.findAll();
    }

    @PostMapping
    public ResponseEntity<?> createEventLead(@RequestBody EventLeadRequest request) {
        Event event = eventRepository.findById(request.getEventId()).orElse(null);
        Contact contact = contactRepository.findById(request.getContactId()).orElse(null);

        if (event == null || contact == null) {
            return ResponseEntity.badRequest().body("Event or Contact not found");
        }

        // Check if already exists
        if (eventLeadRepository.findByEventIdAndContactId(request.getEventId(), request.getContactId()).isPresent()) {
            return ResponseEntity.badRequest().body("Contact is already a lead for this event");
        }

        EventLead eventLead = EventLead.builder()
                .event(event)
                .contact(contact)
                .leadStatus(request.getLeadStatus() != null ? LeadStatus.valueOf(request.getLeadStatus()) : LeadStatus.white)
                .attendanceStatus(request.getAttendanceStatus() != null ? AttendanceStatus.valueOf(request.getAttendanceStatus()) : AttendanceStatus.invited)
                .notes(request.getNotes())
                .build();

        return ResponseEntity.ok(eventLeadRepository.save(eventLead));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable UUID id,
            @RequestParam(required = false) String leadStatus,
            @RequestParam(required = false) String attendanceStatus,
            @RequestParam(required = false) String notes) {
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
        private UUID eventId;
        private UUID contactId;
        private String leadStatus;
        private String attendanceStatus;
        private String notes;
    }
}
