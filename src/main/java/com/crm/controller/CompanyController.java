package com.crm.controller;

import com.crm.domain.Company;
import com.crm.domain.Group;
import com.crm.repository.CompanyRepository;
import com.crm.repository.GroupRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/companies")
public class CompanyController {

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private GroupRepository groupRepository;

    @GetMapping
    public List<Company> getAllCompanies() {
        return companyRepository.findAll();
    }

    @PostMapping
    public ResponseEntity<Company> createCompany(@RequestBody Company company, @RequestParam(required = false) UUID groupId) {
        if (groupId != null) {
            Group group = groupRepository.findById(groupId).orElse(null);
            company.setGroup(group);
        }
        return ResponseEntity.ok(companyRepository.save(company));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Company> getCompanyById(@PathVariable UUID id) {
        return companyRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
