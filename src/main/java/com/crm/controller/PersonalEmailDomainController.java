package com.crm.controller;

import com.crm.domain.PersonalEmailDomain;
import com.crm.repository.PersonalEmailDomainRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/personal-email-domains")
public class PersonalEmailDomainController {

    @Autowired
    private PersonalEmailDomainRepository personalEmailDomainRepository;

    @GetMapping
    public List<PersonalEmailDomain> getAllDomains() {
        return personalEmailDomainRepository.findAll();
    }

    @PostMapping
    public ResponseEntity<?> createDomain(@RequestBody PersonalEmailDomain domain) {
        if (domain.getDomain() == null || domain.getDomain().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Domain name is required");
        }

        String cleanDomain = domain.getDomain().trim().toLowerCase();
        if (personalEmailDomainRepository.findByDomain(cleanDomain).isPresent()) {
            return ResponseEntity.badRequest().body("Domain already exists");
        }

        domain.setDomain(cleanDomain);
        return ResponseEntity.ok(personalEmailDomainRepository.save(domain));
    }
}
