package com.crm.controller;

import com.crm.domain.Company;
import com.crm.domain.Contact;
import com.crm.domain.ContactEmail;
import com.crm.domain.EventLead;
import com.crm.domain.RemovalRequest;
import com.crm.repository.CompanyRepository;
import com.crm.repository.ContactEmailRepository;
import com.crm.repository.ContactRepository;
import com.crm.repository.EventLeadRepository;
import com.crm.repository.RemovalRequestRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;
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

    @GetMapping
    public List<Contact> getAllContacts() {
        return contactRepository.findAll();
    }

    @PostMapping
    public ResponseEntity<Contact> createContact(@RequestBody Contact contact, @RequestParam(required = false) UUID companyId) {
        if (companyId != null) {
            Company company = companyRepository.findById(companyId).orElse(null);
            contact.setCompany(company);
        }
        return ResponseEntity.ok(contactRepository.save(contact));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Contact> getContactById(@PathVariable UUID id) {
        return contactRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateContact(@PathVariable UUID id, @RequestBody Contact contactDetails, @RequestParam(required = false) UUID companyId) {
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

            return ResponseEntity.ok(contactRepository.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteContact(@PathVariable UUID id) {
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

            contactRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/{contactId}/emails")
    public ResponseEntity<?> addContactEmail(@PathVariable UUID contactId, @RequestBody ContactEmail contactEmail) {
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
            return ResponseEntity.ok(contactEmailRepository.save(contactEmail));
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{contactId}/emails")
    public ResponseEntity<List<ContactEmail>> getContactEmails(@PathVariable UUID contactId) {
        return contactRepository.findById(contactId).map(contact -> {
            List<ContactEmail> emails = contactEmailRepository.findAll().stream()
                    .filter(e -> e.getContact().getId().equals(contactId))
                    .toList();
            return ResponseEntity.ok(emails);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{contactId}/emails/{emailId}")
    public ResponseEntity<?> updateContactEmail(@PathVariable UUID contactId, @PathVariable UUID emailId, @RequestBody ContactEmail updated) {
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
            return ResponseEntity.ok(contactEmailRepository.save(email));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{contactId}/emails/{emailId}")
    public ResponseEntity<?> deleteContactEmail(@PathVariable UUID contactId, @PathVariable UUID emailId) {
        if (contactEmailRepository.existsById(emailId)) {
            contactEmailRepository.deleteById(emailId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}

