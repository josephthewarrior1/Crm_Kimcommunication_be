package com.crm.controller;

import com.crm.domain.Company;
import com.crm.domain.Group;
import com.crm.repository.CompanyRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.ContactRepository;
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

    @Autowired
    private ContactRepository contactRepository;

    @GetMapping
    public List<Company> getAllCompanies() {
        return companyRepository.findAll();
    }

    @PostMapping
    public ResponseEntity<?> createCompany(@RequestBody Company company, @RequestParam(required = false) UUID groupId) {
        if (company.getName() == null || company.getName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Company name is required");
        }

        String cleanName = company.getName().trim();
        if (companyRepository.findByNameIgnoreCase(cleanName).isPresent()) {
            return ResponseEntity.badRequest().body("Company name already exists");
        }

        if (groupId != null) {
            Group group = groupRepository.findById(groupId).orElse(null);
            company.setGroup(group);
        }
        company.setName(cleanName);
        return ResponseEntity.ok(companyRepository.save(company));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Company> getCompanyById(@PathVariable UUID id) {
        return companyRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateCompany(@PathVariable UUID id, @RequestBody Company companyDetails, @RequestParam(required = false) UUID groupId) {
        return companyRepository.findById(id).map(existing -> {
            if (companyDetails.getName() == null || companyDetails.getName().trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Company name is required");
            }
            String cleanName = companyDetails.getName().trim();
            java.util.Optional<Company> duplicate = companyRepository.findByNameIgnoreCase(cleanName);
            if (duplicate.isPresent() && !duplicate.get().getId().equals(id)) {
                return ResponseEntity.badRequest().body("Company name already exists");
            }

            existing.setName(cleanName);
            existing.setBrandName(companyDetails.getBrandName());
            existing.setAddress(companyDetails.getAddress());
            existing.setOfficePhone(companyDetails.getOfficePhone());
            existing.setWebsite(companyDetails.getWebsite());
            existing.setIndustry(companyDetails.getIndustry());
            existing.setCompanySizeRevenue(companyDetails.getCompanySizeRevenue());
            existing.setCompanySizeEmployee(companyDetails.getCompanySizeEmployee());
            existing.setCompanyHardware(companyDetails.getCompanyHardware());
            existing.setCity(companyDetails.getCity());

            if (groupId != null) {
                Group group = groupRepository.findById(groupId).orElse(null);
                existing.setGroup(group);
            } else if (companyDetails.getGroup() != null) {
                existing.setGroup(companyDetails.getGroup());
            } else {
                existing.setGroup(null);
            }

            return ResponseEntity.ok(companyRepository.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteCompany(@PathVariable UUID id) {
        if (companyRepository.existsById(id)) {
            // Nullify company references in contacts
            contactRepository.findAll().stream()
                    .filter(c -> c.getCompany() != null && c.getCompany().getId().equals(id))
                    .forEach(c -> {
                        c.setCompany(null);
                        contactRepository.save(c);
                    });
            companyRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
