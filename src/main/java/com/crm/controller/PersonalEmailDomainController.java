package com.crm.controller;

import com.crm.domain.PersonalEmailDomain;
import com.crm.repository.PersonalEmailDomainRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

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
    public PersonalEmailDomain createDomain(@RequestBody PersonalEmailDomain domain) {
        return personalEmailDomainRepository.save(domain);
    }
}
