package com.crm.controller;

import com.crm.domain.Company;
import com.crm.domain.Contact;
import com.crm.domain.ContactEmail;
import com.crm.domain.EventLead;
import com.crm.domain.RemovalRequest;
import com.crm.domain.FlaggedIdentity;
import com.crm.domain.Role;
import com.crm.domain.AppUser;
import com.crm.repository.CompanyRepository;
import com.crm.repository.ContactEmailRepository;
import com.crm.repository.ContactRepository;
import com.crm.repository.EventLeadRepository;
import com.crm.repository.RemovalRequestRepository;
import com.crm.repository.FlaggedIdentityRepository;
import com.crm.service.SuspiciousIdentityService;
import com.crm.service.SecurityHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/contacts")
public class ContactController {

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private ContactEmailRepository contactEmailRepository;

    @Autowired
    private EventLeadRepository eventLeadRepository;

    @Autowired
    private RemovalRequestRepository removalRequestRepository;

    @Autowired
    private FlaggedIdentityRepository flaggedIdentityRepository;

    @Autowired
    private SuspiciousIdentityService suspiciousIdentityService;

    @Autowired
    private SecurityHelper securityHelper;

    @GetMapping
    public ResponseEntity<?> getAllContacts(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        return ResponseEntity.ok(contactRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<?> createContact(
            @RequestBody Contact contact, 
            @RequestParam(required = false) Long companyId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can create contacts");
        }

        if (companyId != null) {
            Company company = companyRepository.findById(companyId).orElse(null);
            contact.setCompany(company);
        }
        Contact saved = contactRepository.save(contact);
        suspiciousIdentityService.checkAndFlagContact(saved);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getContactById(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        return contactRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateContact(
            @PathVariable Long id, 
            @RequestBody Contact contactDetails, 
            @RequestParam(required = false) Long companyId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can update contacts");
        }

        return contactRepository.findById(id).map(existing -> {
            existing.setSalutation(contactDetails.getSalutation());
            existing.setFirstName(contactDetails.getFirstName());
            existing.setLastName(contactDetails.getLastName());
            existing.setPositionLevel(contactDetails.getPositionLevel());
            existing.setSpecialityDivision(contactDetails.getSpecialityDivision());
            existing.setJobTitle(contactDetails.getJobTitle());
            existing.setMobilePhone(contactDetails.getMobilePhone());
            existing.setNormalizedPhone(contactDetails.getNormalizedPhone());
            existing.setLinkedinUrl(contactDetails.getLinkedinUrl());
            existing.setContactType(contactDetails.getContactType());
            existing.setSource(contactDetails.getSource());
            if (contactDetails.getIsActive() != null) {
                existing.setIsActive(contactDetails.getIsActive());
            }

            if (companyId != null) {
                Company company = companyRepository.findById(companyId).orElse(null);
                existing.setCompany(company);
            } else if (contactDetails.getCompany() != null) {
                existing.setCompany(contactDetails.getCompany());
            } else {
                existing.setCompany(null);
            }

            Contact saved = contactRepository.save(existing);
            suspiciousIdentityService.checkAndFlagContact(saved);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteContact(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN can delete contacts");
        }

        if (contactRepository.existsById(id)) {
            // Delete associated emails
            List<ContactEmail> emails = contactEmailRepository.findAll().stream()
                    .filter(e -> e.getContact() != null && e.getContact().getId().equals(id))
                    .toList();
            contactEmailRepository.deleteAll(emails);

            // Delete associated event leads
            List<EventLead> eventLeads = eventLeadRepository.findByContactId(id);
            eventLeadRepository.deleteAll(eventLeads);

            // Delete associated removal requests
            List<RemovalRequest> removalRequests = removalRequestRepository.findAll().stream()
                    .filter(r -> r.getContact() != null && r.getContact().getId().equals(id))
                    .toList();
            removalRequestRepository.deleteAll(removalRequests);

            // Delete associated flagged identities
            List<FlaggedIdentity> flaggedIdentities = flaggedIdentityRepository.findAll().stream()
                    .filter(f -> f.getContact() != null && f.getContact().getId().equals(id))
                    .toList();
            flaggedIdentityRepository.deleteAll(flaggedIdentities);

            contactRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/{contactId}/emails")
    public ResponseEntity<?> addContactEmail(
            @PathVariable Long contactId, 
            @RequestBody ContactEmail contactEmail,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can add emails");
        }

        if (contactEmail.getEmail() == null || contactEmail.getEmail().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Email address is required");
        }

        String cleanEmail = contactEmail.getEmail().trim().toLowerCase();
        if (contactEmailRepository.findByEmail(cleanEmail).isPresent()) {
            return ResponseEntity.badRequest().body("Email address is already in use");
        }

        return contactRepository.findById(contactId).map(contact -> {
            contactEmail.setContact(contact);
            contactEmail.setEmail(cleanEmail);
            ContactEmail savedEmail = contactEmailRepository.save(contactEmail);
            suspiciousIdentityService.checkAndFlagContact(contact);
            return ResponseEntity.ok(savedEmail);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{contactId}/emails")
    public ResponseEntity<?> getContactEmails(
            @PathVariable Long contactId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        return contactRepository.findById(contactId).map(contact -> {
            List<ContactEmail> emails = contactEmailRepository.findAll().stream()
                    .filter(e -> e.getContact().getId().equals(contactId))
                    .toList();
            return ResponseEntity.ok(emails);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{contactId}/event-leads")
    public ResponseEntity<?> getContactEventLeads(
            @PathVariable("contactId") Long contactId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        if (!contactRepository.existsById(contactId)) {
            return ResponseEntity.notFound().build();
        }
        List<EventLead> leads = eventLeadRepository.findByContactId(contactId);
        return ResponseEntity.ok(leads);
    }

    @PutMapping("/{contactId}/emails/{emailId}")
    public ResponseEntity<?> updateContactEmail(
            @PathVariable Long contactId, 
            @PathVariable Long emailId, 
            @RequestBody ContactEmail updated,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can edit emails");
        }

        return contactEmailRepository.findById(emailId).map(email -> {
            if (updated.getEmail() != null && !updated.getEmail().trim().isEmpty()) {
                String cleanEmail = updated.getEmail().trim().toLowerCase();
                Optional<ContactEmail> existing = contactEmailRepository.findByEmail(cleanEmail);
                if (existing.isPresent() && !existing.get().getId().equals(emailId)) {
                    return ResponseEntity.badRequest().body("Email address is already in use");
                }
                email.setEmail(cleanEmail);
            }
            if (updated.getEmailType() != null) {
                email.setEmailType(updated.getEmailType());
            }
            if (updated.getIsPrimary() != null) {
                email.setIsPrimary(updated.getIsPrimary());
            }
            ContactEmail savedEmail = contactEmailRepository.save(email);
            if (email.getContact() != null) {
                suspiciousIdentityService.checkAndFlagContact(email.getContact());
            }
            return ResponseEntity.ok(savedEmail);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{contactId}/emails/{emailId}")
    public ResponseEntity<?> deleteContactEmail(
            @PathVariable Long contactId, 
            @PathVariable Long emailId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can delete emails");
        }

        if (contactEmailRepository.existsById(emailId)) {
            contactEmailRepository.deleteById(emailId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
