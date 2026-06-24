package com.crm.controller;

import com.crm.domain.Company;
import com.crm.domain.Contact;
import com.crm.domain.ContactEmail;
import com.crm.repository.CompanyRepository;
import com.crm.repository.ContactEmailRepository;
import com.crm.repository.ContactRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/contacts")
public class ContactController {

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private ContactEmailRepository contactEmailRepository;

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

    @PostMapping("/{contactId}/emails")
    public ResponseEntity<ContactEmail> addContactEmail(@PathVariable UUID contactId, @RequestBody ContactEmail contactEmail) {
        return contactRepository.findById(contactId).map(contact -> {
            contactEmail.setContact(contact);
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
}
