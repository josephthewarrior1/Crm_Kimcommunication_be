package com.crm.controller;

import com.crm.domain.Contact;
import com.crm.domain.RemovalReason;
import com.crm.domain.RemovalRequest;
import com.crm.domain.RemovalStatus;
import com.crm.repository.ContactRepository;
import com.crm.repository.RemovalRequestRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/removal-requests")
public class RemovalRequestController {

    @Autowired
    private RemovalRequestRepository removalRequestRepository;

    @Autowired
    private ContactRepository contactRepository;

    @GetMapping
    public List<RemovalRequest> getAllRemovalRequests() {
        return removalRequestRepository.findAll();
    }

    @PostMapping
    public ResponseEntity<?> createRemovalRequest(@RequestBody RemovalRequestDto request) {
        Contact contact = contactRepository.findById(request.getContactId()).orElse(null);
        if (contact == null) {
            return ResponseEntity.badRequest().body("Contact not found");
        }

        RemovalRequest removalRequest = RemovalRequest.builder()
                .contact(contact)
                .reason(request.getReason() != null ? RemovalReason.valueOf(request.getReason()) : RemovalReason.lainnya)
                .requestedBy(request.getRequestedBy())
                .sourceDb(request.getSourceDb())
                .notes(request.getNotes())
                .status(request.getStatus() != null ? RemovalStatus.valueOf(request.getStatus()) : RemovalStatus.pending)
                .build();

        RemovalRequest saved = removalRequestRepository.save(removalRequest);

        if (saved.getStatus() == RemovalStatus.done || saved.getStatus() == RemovalStatus.approved) {
            contact.setIsActive(false);
            contactRepository.save(contact);
        }

        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestParam String status) {
        return removalRequestRepository.findById(id).map(req -> {
            try {
                RemovalStatus newStatus = RemovalStatus.valueOf(status);
                req.setStatus(newStatus);
                RemovalRequest saved = removalRequestRepository.save(req);

                if (newStatus == RemovalStatus.done || newStatus == RemovalStatus.approved) {
                    Contact c = saved.getContact();
                    c.setIsActive(false);
                    contactRepository.save(c);
                }

                return ResponseEntity.ok(saved);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body("Invalid status");
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @lombok.Data
    public static class RemovalRequestDto {
        private Long contactId;
        private String reason;
        private String requestedBy;
        private String sourceDb;
        private String notes;
        private String status;
    }
}
