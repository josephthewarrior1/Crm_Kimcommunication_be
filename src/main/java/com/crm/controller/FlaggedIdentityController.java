package com.crm.controller;

import com.crm.domain.Contact;
import com.crm.domain.FlaggedIdentity;
import com.crm.domain.FlagStatus;
import com.crm.domain.Role;
import com.crm.domain.AppUser;
import com.crm.repository.ContactRepository;
import com.crm.repository.FlaggedIdentityRepository;
import com.crm.service.SecurityHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/flagged-identities")
public class FlaggedIdentityController {

    @Autowired
    private FlaggedIdentityRepository flaggedIdentityRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private SecurityHelper securityHelper;

    @GetMapping
    public ResponseEntity<?> getAllFlaggedIdentities(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        return ResponseEntity.ok(flaggedIdentityRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<?> createFlaggedIdentity(
            @RequestBody FlaggedIdentity flaggedIdentity,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN can manually flag identities");
        }

        // Save FlaggedIdentity
        FlaggedIdentity saved = flaggedIdentityRepository.save(flaggedIdentity);

        // Auto update contact isActive status
        if (saved.getContact() != null) {
            updateContactActiveStatus(saved.getContact(), saved.getStatus());
        }

        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateFlaggedIdentity(
            @PathVariable Long id, 
            @RequestBody FlaggedIdentity details,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN can edit flagged identities status");
        }

        return flaggedIdentityRepository.findById(id).map(existing -> {
            Contact oldContact = existing.getContact();

            existing.setNameUsed(details.getNameUsed());
            existing.setEmailUsed(details.getEmailUsed());
            existing.setPhoneUsed(details.getPhoneUsed());
            existing.setFlagReason(details.getFlagReason());
            existing.setEvidenceNotes(details.getEvidenceNotes());
            existing.setStatus(details.getStatus());

            Contact newContact = null;
            if (details.getContact() != null && details.getContact().getId() != null) {
                newContact = contactRepository.findById(details.getContact().getId()).orElse(null);
                existing.setContact(newContact);
            } else {
                existing.setContact(null);
            }

            if (details.getEvent() != null) {
                existing.setEvent(details.getEvent());
            } else {
                existing.setEvent(null);
            }

            FlaggedIdentity saved = flaggedIdentityRepository.save(existing);

            // Update active status for both old and new contact
            if (newContact != null) {
                updateContactActiveStatus(newContact, existing.getStatus());
            }
            if (oldContact != null && (newContact == null || !oldContact.getId().equals(newContact.getId()))) {
                updateContactActiveStatus(oldContact, FlagStatus.cleared); // Reset old contact's state
            }

            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteFlaggedIdentity(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN can delete flagged records");
        }

        return flaggedIdentityRepository.findById(id).map(flag -> {
            Contact contact = flag.getContact();
            flaggedIdentityRepository.delete(flag);

            // After deleting the flag, evaluate if the contact has other confirmed flags
            if (contact != null) {
                updateContactActiveStatus(contact, FlagStatus.cleared);
            }

            return ResponseEntity.noContent().build();
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Updates the active status (isActive) of a contact based on flag status.
     * If the flag is confirmed, contact is soft-deleted (isActive = false).
     * If suspected/cleared, it is restored, provided there are no other confirmed flags.
     */
    private void updateContactActiveStatus(Contact contact, FlagStatus status) {
        if (contact == null || contact.getId() == null) {
            return;
        }
        contactRepository.findById(contact.getId()).ifPresent(c -> {
            if (status == FlagStatus.confirmed) {
                c.setIsActive(false);
            } else {
                // Check if there are any other CONFIRMED flags for this contact
                boolean hasOtherConfirmed = flaggedIdentityRepository.findAll().stream()
                        .anyMatch(f -> f.getContact() != null 
                                       && f.getContact().getId().equals(c.getId()) 
                                       && f.getStatus() == FlagStatus.confirmed);
                if (!hasOtherConfirmed) {
                    c.setIsActive(true);
                }
            }
            contactRepository.save(c);
        });
    }
}
