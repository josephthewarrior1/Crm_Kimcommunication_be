package com.crm.controller;

import com.crm.domain.Company;
import com.crm.domain.Database;
import com.crm.domain.DatabaseEmail;
import com.crm.domain.EventLead;
import com.crm.domain.RemovalRequest;
import com.crm.domain.FlaggedIdentity;
import com.crm.domain.Role;
import com.crm.domain.AppUser;
import com.crm.repository.CompanyRepository;
import com.crm.repository.DatabaseEmailRepository;
import com.crm.repository.DatabaseRepository;
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
@RequestMapping("/api/databases")
public class DatabaseController {

    @Autowired
    private DatabaseRepository databaseRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private DatabaseEmailRepository databaseEmailRepository;

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
    public ResponseEntity<?> getAllDatabases(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        return ResponseEntity.ok(databaseRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<?> createDatabase(
            @RequestBody Database database, 
            @RequestParam(required = false) Long companyId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can create database records");
        }

        if (companyId != null) {
            Company company = companyRepository.findById(companyId).orElse(null);
            database.setCompany(company);
        }
        Database saved = databaseRepository.save(database);
        suspiciousIdentityService.checkAndFlagDatabase(saved);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getDatabaseById(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        return databaseRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateDatabase(
            @PathVariable Long id, 
            @RequestBody Database databaseDetails, 
            @RequestParam(required = false) Long companyId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can update database records");
        }

        return databaseRepository.findById(id).map(existing -> {
            existing.setSalutation(databaseDetails.getSalutation());
            existing.setFirstName(databaseDetails.getFirstName());
            existing.setLastName(databaseDetails.getLastName());
            existing.setPositionLevel(databaseDetails.getPositionLevel());
            existing.setSpecialityDivision(databaseDetails.getSpecialityDivision());
            existing.setJobTitle(databaseDetails.getJobTitle());
            existing.setMobilePhone(databaseDetails.getMobilePhone());
            existing.setNormalizedPhone(databaseDetails.getNormalizedPhone());
            existing.setLinkedinUrl(databaseDetails.getLinkedinUrl());
            existing.setDatabaseType(databaseDetails.getDatabaseType());
            existing.setSource(databaseDetails.getSource());
            if (databaseDetails.getIsActive() != null) {
                existing.setIsActive(databaseDetails.getIsActive());
            }

            if (companyId != null) {
                Company company = companyRepository.findById(companyId).orElse(null);
                existing.setCompany(company);
            } else if (databaseDetails.getCompany() != null) {
                existing.setCompany(databaseDetails.getCompany());
            } else {
                existing.setCompany(null);
            }

            Database saved = databaseRepository.save(existing);
            suspiciousIdentityService.checkAndFlagDatabase(saved);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteDatabase(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN can delete database records");
        }

        if (databaseRepository.existsById(id)) {
            // Delete associated emails
            List<DatabaseEmail> emails = databaseEmailRepository.findAll().stream()
                    .filter(e -> e.getDatabase() != null && e.getDatabase().getId().equals(id))
                    .toList();
            databaseEmailRepository.deleteAll(emails);

            // Delete associated event leads
            List<EventLead> eventLeads = eventLeadRepository.findByDatabaseId(id);
            eventLeadRepository.deleteAll(eventLeads);

            // Delete associated removal requests
            List<RemovalRequest> removalRequests = removalRequestRepository.findAll().stream()
                    .filter(r -> r.getDatabase() != null && r.getDatabase().getId().equals(id))
                    .toList();
            removalRequestRepository.deleteAll(removalRequests);

            // Delete associated flagged identities
            List<FlaggedIdentity> flaggedIdentities = flaggedIdentityRepository.findAll().stream()
                    .filter(f -> f.getDatabase() != null && f.getDatabase().getId().equals(id))
                    .toList();
            flaggedIdentityRepository.deleteAll(flaggedIdentities);

            databaseRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/{databaseId}/emails")
    public ResponseEntity<?> addDatabaseEmail(
            @PathVariable Long databaseId, 
            @RequestBody DatabaseEmail databaseEmail,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can add emails");
        }

        if (databaseEmail.getEmail() == null || databaseEmail.getEmail().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Email address is required");
        }

        String cleanEmail = databaseEmail.getEmail().trim().toLowerCase();
        if (databaseEmailRepository.findByEmail(cleanEmail).isPresent()) {
            return ResponseEntity.badRequest().body("Email address is already in use");
        }

        return databaseRepository.findById(databaseId).map(database -> {
            databaseEmail.setDatabase(database);
            databaseEmail.setEmail(cleanEmail);
            DatabaseEmail savedEmail = databaseEmailRepository.save(databaseEmail);
            suspiciousIdentityService.checkAndFlagDatabase(database);
            return ResponseEntity.ok(savedEmail);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{databaseId}/emails")
    public ResponseEntity<?> getDatabaseEmails(
            @PathVariable Long databaseId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        return databaseRepository.findById(databaseId).map(database -> {
            List<DatabaseEmail> emails = databaseEmailRepository.findAll().stream()
                    .filter(e -> e.getDatabase().getId().equals(databaseId))
                    .toList();
            return ResponseEntity.ok(emails);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{databaseId}/event-leads")
    public ResponseEntity<?> getDatabaseEventLeads(
            @PathVariable("databaseId") Long databaseId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        if (!databaseRepository.existsById(databaseId)) {
            return ResponseEntity.notFound().build();
        }
        List<EventLead> leads = eventLeadRepository.findByDatabaseId(databaseId);
        return ResponseEntity.ok(leads);
    }

    @PutMapping("/{databaseId}/emails/{emailId}")
    public ResponseEntity<?> updateDatabaseEmail(
            @PathVariable Long databaseId, 
            @PathVariable Long emailId, 
            @RequestBody DatabaseEmail updated,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can edit emails");
        }

        return databaseEmailRepository.findById(emailId).map(email -> {
            if (updated.getEmail() != null && !updated.getEmail().trim().isEmpty()) {
                String cleanEmail = updated.getEmail().trim().toLowerCase();
                Optional<DatabaseEmail> existing = databaseEmailRepository.findByEmail(cleanEmail);
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
            DatabaseEmail savedEmail = databaseEmailRepository.save(email);
            if (email.getDatabase() != null) {
                suspiciousIdentityService.checkAndFlagDatabase(email.getDatabase());
            }
            return ResponseEntity.ok(savedEmail);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{databaseId}/emails/{emailId}")
    public ResponseEntity<?> deleteDatabaseEmail(
            @PathVariable Long databaseId, 
            @PathVariable Long emailId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can delete emails");
        }

        if (databaseEmailRepository.existsById(emailId)) {
            databaseEmailRepository.deleteById(emailId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
