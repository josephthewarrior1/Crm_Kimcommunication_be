package com.crm.controller;

import com.crm.domain.Database;
import com.crm.domain.DatabaseDestination;
import com.crm.domain.FlagReason;
import com.crm.domain.FlaggedIdentity;
import com.crm.domain.FlagStatus;
import com.crm.domain.Role;
import com.crm.domain.AppUser;
import com.crm.repository.DatabaseRepository;
import com.crm.repository.FlaggedIdentityRepository;
import com.crm.service.SecurityHelper;
import com.crm.service.EmsIdentityReview;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Comparator;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/flagged-identities")
@org.springframework.transaction.annotation.Transactional
public class FlaggedIdentityController {

    @Autowired
    private FlaggedIdentityRepository flaggedIdentityRepository;

    @Autowired
    private DatabaseRepository databaseRepository;

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired
    private com.crm.repository.EventRepository eventRepository;

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
                    if (!DatabaseDestination.isExact(db.getDatabaseDestination(), flagScope(flag))) continue;
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

    @GetMapping("/list")
    public ResponseEntity<?> getFlaggedIdentitiesList(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String flagReason,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        int safePage = page == null || page < 1 ? 1 : page;
        int safeSize = size == null || size < 1 ? 10 : Math.min(size, 100);

        List<FlaggedIdentity> filteredFlags = getNormalizedFlags().stream()
                .filter(flag -> matchesSearch(flag, search))
                .filter(flag -> matchesStatus(flag, status))
                .filter(flag -> matchesFlagReason(flag, flagReason))
                .sorted(Comparator.comparing(FlaggedIdentity::getId, Comparator.reverseOrder()))
                .toList();

        List<FlaggedIdentity> items = filteredFlags.stream()
                .skip((long) (safePage - 1) * safeSize)
                .limit(safeSize)
                .toList();

        return ResponseEntity.ok(Map.of(
                "items", items,
                "page", safePage,
                "size", safeSize,
                "total", filteredFlags.size(),
                "totalPages", filteredFlags.isEmpty() ? 1 : (int) Math.ceil((double) filteredFlags.size() / safeSize)
        ));
    }

    @GetMapping("/filter-options")
    public ResponseEntity<?> getFlaggedIdentityFilterOptions(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        return ResponseEntity.ok(Map.of(
                "statuses", Arrays.stream(FlagStatus.values()).map(Enum::name).toList(),
                "flagReasons", Arrays.stream(FlagReason.values()).map(Enum::name).toList()
        ));
    }

    @PostMapping
    public ResponseEntity<?> createFlaggedIdentity(
            @RequestBody FlaggedIdentity flaggedIdentity,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can manually flag identities");
        }

        if (flaggedIdentity.getId() != null) return ResponseEntity.badRequest().body("Create flag must not include an existing flag ID.");
        if (flaggedIdentity.getDatabase() != null) {
            Database supplied = databaseRepository.findById(flaggedIdentity.getDatabase().getId()).orElse(null);
            if (supplied == null) return ResponseEntity.badRequest().body("Database not found");
            flaggedIdentity.setDatabase(supplied);
        }
        if (flaggedIdentity.getEvent() != null) {
            com.crm.domain.Event event = eventRepository.findById(flaggedIdentity.getEvent().getId()).orElse(null);
            if (event == null) return ResponseEntity.badRequest().body("Event not found");
            flaggedIdentity.setEvent(event);
        }
        DatabaseDestination selected = flagScope(flaggedIdentity);
        if (selected == DatabaseDestination.BOTH || flaggedIdentity.getEvent() != null
                && !DatabaseDestination.isExact(flaggedIdentity.getEvent().getSyncDestination(), selected)) {
            return ResponseEntity.badRequest().body("Flag event and profile must belong to the same database.");
        }
        if (flaggedIdentity.getLinkedFlag() != null) {
            FlaggedIdentity linked = flaggedIdentityRepository.findById(flaggedIdentity.getLinkedFlag().getId()).orElse(null);
            if (linked == null || !DatabaseDestination.isExact(flagScope(linked), selected)) return ResponseEntity.badRequest().body("Linked flag belongs to another database.");
            flaggedIdentity.setLinkedFlag(linked);
        }
        // Auto-match unlinked database record if database is not set
        if (flaggedIdentity.getDatabase() == null) {
            String flagPhoneDigits = suspiciousIdentityService.extractSubscriberDigits(flaggedIdentity.getPhoneUsed());
            String flagEmail = flaggedIdentity.getEmailUsed() != null ? flaggedIdentity.getEmailUsed().trim().toLowerCase() : "";

            for (Database db : databaseRepository.findAll()) {
                if (!DatabaseDestination.isExact(db.getDatabaseDestination(), selected)) continue;
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
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can edit flagged identities status");
        }

        return flaggedIdentityRepository.findById(id).map(existing -> {
            Database oldDatabase = existing.getDatabase();
            DatabaseDestination originalKind = flagScope(existing);
            Database requestedDatabase = details.getDatabase() == null ? null : databaseRepository.findById(details.getDatabase().getId()).orElse(null);
            if (details.getDatabase() != null && requestedDatabase == null) return ResponseEntity.badRequest().body("Database not found");
            com.crm.domain.Event requestedEvent = details.getEvent() == null ? null : eventRepository.findById(details.getEvent().getId()).orElse(null);
            if (details.getEvent() != null && requestedEvent == null) return ResponseEntity.badRequest().body("Event not found");
            DatabaseDestination requestedKind = requestedDatabase != null ? DatabaseDestination.effective(requestedDatabase.getDatabaseDestination())
                    : requestedEvent != null ? DatabaseDestination.effective(requestedEvent.getSyncDestination()) : DatabaseDestination.COMPANY;
            if (!DatabaseDestination.isExact(requestedKind, originalKind) || requestedEvent != null
                    && !DatabaseDestination.isExact(requestedEvent.getSyncDestination(), requestedKind)) {
                return ResponseEntity.status(409).body("Flags cannot move between Company and Participant databases.");
            }
            if (details.getLinkedFlag() != null) {
                FlaggedIdentity linked = flaggedIdentityRepository.findById(details.getLinkedFlag().getId()).orElse(null);
                if (linked == null || !DatabaseDestination.isExact(flagScope(linked), originalKind)) return ResponseEntity.badRequest().body("Linked flag belongs to another database.");
            }
            existing.setNameUsed(details.getNameUsed());
            existing.setEmailUsed(details.getEmailUsed());
            existing.setPhoneUsed(details.getPhoneUsed());
            existing.setFlagReason(details.getFlagReason());
            existing.setEvidenceNotes(details.getEvidenceNotes());
            existing.setStatus(details.getStatus());

            Database newDatabase = null;
            if (details.getDatabase() != null && details.getDatabase().getId() != null) {
                newDatabase = requestedDatabase;
                existing.setDatabase(newDatabase);
            } else {
                existing.setDatabase(null);
            }

            if (details.getEvent() != null) {
                existing.setEvent(requestedEvent);
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
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can delete flagged records");
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

    @Autowired
    private com.crm.repository.EventParticipantRepository eventParticipantRepository;

    /**
     * Updates the active status (isActive) of a database record based on flag status.
     * If the flag is confirmed, record is soft-deleted (isActive = false).
     * If suspected/cleared, it is restored, provided there are no other confirmed flags.
     */
    private void updateDatabaseActiveStatus(Database database, FlagStatus status) {
        if (database == null || database.getId() == null || database.getDatabaseDestination() == DatabaseDestination.BOTH) {
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

                    // Restore event participant confirmation status to approve if previously declined due to flag
                    List<com.crm.domain.EventParticipant> participants = eventParticipantRepository.findByDatabaseId(d.getId());
                    java.util.Set<Long> affectedEvents = new java.util.HashSet<>();
                    for (com.crm.domain.EventParticipant ep : participants) {
                        if (ep.getEvent() != null && !DatabaseDestination.isExact(ep.getEvent().getSyncDestination(), DatabaseDestination.effective(d.getDatabaseDestination()))) continue;
                        if ("decline".equalsIgnoreCase(ep.getConfirmationStatus()) || "declined".equalsIgnoreCase(ep.getConfirmationStatus())) {
                            ep.setConfirmationStatus("approve");
                            eventParticipantRepository.save(ep);
                            if (ep.getEvent() != null) affectedEvents.add(ep.getEvent().getId());
                        }
                    }
                    affectedEvents.forEach(eventId -> EmsIdentityReview.refresh(eventParticipantRepository, eventId));
                }
            }
            databaseRepository.save(d);
        });
    }

    private List<FlaggedIdentity> getNormalizedFlags() {
        List<FlaggedIdentity> allFlags = flaggedIdentityRepository.findAll();
        List<Database> allDbs = databaseRepository.findAll();
        boolean updatedAny = false;

        for (FlaggedIdentity flag : allFlags) {
            if (flag.getDatabase() == null && flag.getStatus() != FlagStatus.cleared) {
                String flagPhoneDigits = suspiciousIdentityService.extractSubscriberDigits(flag.getPhoneUsed());
                String flagEmail = safe(flag.getEmailUsed()).trim().toLowerCase(Locale.ROOT);

                for (Database db : allDbs) {
                    if (!DatabaseDestination.isExact(db.getDatabaseDestination(), flagScope(flag))) continue;
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

        return updatedAny ? flaggedIdentityRepository.findAll() : allFlags;
    }

    private boolean matchesSearch(FlaggedIdentity flag, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }
        String query = search.trim().toLowerCase(Locale.ROOT);
        return safe(flag.getNameUsed()).toLowerCase(Locale.ROOT).contains(query)
                || safe(flag.getEmailUsed()).toLowerCase(Locale.ROOT).contains(query)
                || safe(flag.getPhoneUsed()).contains(query)
                || (flag.getFlagReason() != null && flag.getFlagReason().name().toLowerCase(Locale.ROOT).contains(query))
                || safe(flag.getEvidenceNotes()).toLowerCase(Locale.ROOT).contains(query);
    }

    private boolean matchesStatus(FlaggedIdentity flag, String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return true;
        }
        return flag.getStatus() != null && flag.getStatus().name().equalsIgnoreCase(status.trim());
    }

    private boolean matchesFlagReason(FlaggedIdentity flag, String flagReason) {
        if (flagReason == null || flagReason.isBlank() || "ALL".equalsIgnoreCase(flagReason)) {
            return true;
        }
        return flag.getFlagReason() != null && flag.getFlagReason().name().equalsIgnoreCase(flagReason.trim());
    }

    private DatabaseDestination flagScope(FlaggedIdentity flag) {
        return DatabaseDestination.effective(flag.getDatabase() != null ? flag.getDatabase().getDatabaseDestination()
                : flag.getEvent() != null ? flag.getEvent().getSyncDestination() : DatabaseDestination.COMPANY);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
