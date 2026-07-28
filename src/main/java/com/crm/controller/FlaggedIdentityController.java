package com.crm.controller;

import com.crm.domain.Database;
import com.crm.domain.FlaggedIdentity;
import com.crm.domain.FlagStatus;
import com.crm.domain.Role;
import com.crm.domain.AppUser;
import com.crm.repository.DatabaseRepository;
import com.crm.repository.FlaggedIdentityRepository;
import com.crm.service.SecurityHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/flagged-identities")
public class FlaggedIdentityController {

    @Autowired
    private FlaggedIdentityRepository flaggedIdentityRepository;

    @Autowired
    private DatabaseRepository databaseRepository;

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired
    private com.crm.service.SuspiciousIdentityService suspiciousIdentityService;

    @GetMapping
    public ResponseEntity<?> getAllFlaggedIdentities(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        // Auto-link any unlinked FlaggedIdentity rows with existing Database contacts by subscriber phone digits or email
        List<FlaggedIdentity> allFlags = flaggedIdentityRepository.findAll();
        List<Database> allDbs = databaseRepository.findAll();
        boolean updatedAny = false;

        for (FlaggedIdentity flag : allFlags) {
            if (flag.getDatabase() == null && flag.getStatus() != FlagStatus.cleared) {
                String flagPhoneDigits = suspiciousIdentityService.extractSubscriberDigits(flag.getPhoneUsed());
                String flagEmail = flag.getEmailUsed() != null ? flag.getEmailUsed().trim().toLowerCase() : "";

                for (Database db : allDbs) {
                    boolean phoneMatch = false;
                    if (!flagPhoneDigits.isEmpty() && db.getMobilePhone() != null) {
                        String dbDigits = suspiciousIdentityService.extractSubscriberDigits(db.getMobilePhone());
                        if (!dbDigits.isEmpty() && dbDigits.equals(flagPhoneDigits)) {
                            phoneMatch = true;
                        }
                    }

                    boolean emailMatch = false;
                    if (!flagEmail.isEmpty() && db.getEmails() != null) {
                        emailMatch = db.getEmails().stream().anyMatch(e -> e.getEmail() != null && e.getEmail().trim().equalsIgnoreCase(flagEmail));
                    }

                    if (phoneMatch || emailMatch) {
                        flag.setDatabase(db);
                        flaggedIdentityRepository.save(flag);
                        updateDatabaseActiveStatus(db, flag.getStatus());
                        updatedAny = true;
                        break;
                    }
                }
            }
        }

        if (updatedAny) {
            allFlags = flaggedIdentityRepository.findAll();
        }

        return ResponseEntity.ok(allFlags);
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

        // Auto-match unlinked database record if database is not set
        if (flaggedIdentity.getDatabase() == null) {
            String flagPhoneDigits = suspiciousIdentityService.extractSubscriberDigits(flaggedIdentity.getPhoneUsed());
            String flagEmail = flaggedIdentity.getEmailUsed() != null ? flaggedIdentity.getEmailUsed().trim().toLowerCase() : "";

            for (Database db : databaseRepository.findAll()) {
                boolean phoneMatch = false;
                if (!flagPhoneDigits.isEmpty() && db.getMobilePhone() != null) {
                    String dbDigits = suspiciousIdentityService.extractSubscriberDigits(db.getMobilePhone());
                    if (!dbDigits.isEmpty() && dbDigits.equals(flagPhoneDigits)) {
                        phoneMatch = true;
                    }
                }

                boolean emailMatch = false;
                if (!flagEmail.isEmpty() && db.getEmails() != null) {
                    emailMatch = db.getEmails().stream().anyMatch(e -> e.getEmail() != null && e.getEmail().trim().equalsIgnoreCase(flagEmail));
                }

                if (phoneMatch || emailMatch) {
                    flaggedIdentity.setDatabase(db);
                    break;
                }
            }
        }

        // Save FlaggedIdentity
        FlaggedIdentity saved = flaggedIdentityRepository.save(flaggedIdentity);

        // Auto update database record isActive status
        if (saved.getDatabase() != null) {
            updateDatabaseActiveStatus(saved.getDatabase(), saved.getStatus());
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
            Database oldDatabase = existing.getDatabase();

            existing.setNameUsed(details.getNameUsed());
            existing.setEmailUsed(details.getEmailUsed());
            existing.setPhoneUsed(details.getPhoneUsed());
            existing.setFlagReason(details.getFlagReason());
            existing.setEvidenceNotes(details.getEvidenceNotes());
            existing.setStatus(details.getStatus());

            Database newDatabase = null;
            if (details.getDatabase() != null && details.getDatabase().getId() != null) {
                newDatabase = databaseRepository.findById(details.getDatabase().getId()).orElse(null);
                existing.setDatabase(newDatabase);
            } else {
                existing.setDatabase(null);
            }

            if (details.getEvent() != null) {
                existing.setEvent(details.getEvent());
            } else {
                existing.setEvent(null);
            }

            FlaggedIdentity saved = flaggedIdentityRepository.save(existing);

            // Update active status for both old and new database record
            if (newDatabase != null) {
                updateDatabaseActiveStatus(newDatabase, existing.getStatus());
            }
            if (oldDatabase != null && (newDatabase == null || !oldDatabase.getId().equals(newDatabase.getId()))) {
                updateDatabaseActiveStatus(oldDatabase, FlagStatus.cleared); // Reset old record's state
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
            Database database = flag.getDatabase();
            flaggedIdentityRepository.delete(flag);

            // After deleting the flag, evaluate if the database record has other confirmed flags
            if (database != null) {
                updateDatabaseActiveStatus(database, FlagStatus.cleared);
            }

            return ResponseEntity.noContent().build();
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Updates the active status (isActive) of a database record based on flag status.
     * If the flag is confirmed, record is soft-deleted (isActive = false).
     * If suspected/cleared, it is restored, provided there are no other confirmed flags.
     */
    private void updateDatabaseActiveStatus(Database database, FlagStatus status) {
        if (database == null || database.getId() == null) {
            return;
        }
        databaseRepository.findById(database.getId()).ifPresent(d -> {
            if (status == FlagStatus.confirmed) {
                d.setIsActive(false);
            } else {
                // Check if there are any other CONFIRMED flags for this database record
                boolean hasOtherConfirmed = flaggedIdentityRepository.findAll().stream()
                        .anyMatch(f -> f.getDatabase() != null 
                                       && f.getDatabase().getId().equals(d.getId()) 
                                       && f.getStatus() == FlagStatus.confirmed);
                if (!hasOtherConfirmed) {
                    d.setIsActive(true);
                }
            }
            databaseRepository.save(d);
        });
    }
}
