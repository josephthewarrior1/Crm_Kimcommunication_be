package com.crm.controller;

import com.crm.domain.Company;
import com.crm.domain.Group;
import com.crm.domain.Role;
import com.crm.domain.AppUser;
import com.crm.repository.CompanyRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.DatabaseRepository;
import com.crm.service.SecurityHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies")
public class CompanyController {

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private DatabaseRepository databaseRepository;

    @Autowired
    private SecurityHelper securityHelper;

    @GetMapping
    public ResponseEntity<?> getAllCompanies(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        return ResponseEntity.ok(companyRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<?> createCompany(
            @RequestBody Company company, 
            @RequestParam(required = false) Long groupId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can create companies");
        }

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
    public ResponseEntity<?> getCompanyById(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        return companyRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateCompany(
            @PathVariable Long id, 
            @RequestBody Company companyDetails, 
            @RequestParam(required = false) Long groupId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can update companies");
        }

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
    public ResponseEntity<?> deleteCompany(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN can delete companies");
        }

        if (companyRepository.existsById(id)) {
            // Nullify company references in database records
            databaseRepository.findAll().stream()
                    .filter(c -> c.getCompany() != null && c.getCompany().getId().equals(id))
                    .forEach(c -> {
                        c.setCompany(null);
                        databaseRepository.save(c);
                    });
            companyRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
