package com.crm.controller;

import com.crm.domain.Company;
import com.crm.domain.Database;
import com.crm.domain.DatabaseEmail;
import com.crm.domain.EventParticipant;
import com.crm.domain.RemovalRequest;
import com.crm.domain.FlaggedIdentity;
import com.crm.domain.FlagStatus;
import com.crm.domain.Role;
import com.crm.domain.AppUser;
import com.crm.repository.CompanyRepository;
import com.crm.repository.DatabaseEmailRepository;
import com.crm.repository.DatabaseRepository;
import com.crm.repository.EventParticipantRepository;
import com.crm.repository.RemovalRequestRepository;
import com.crm.repository.FlaggedIdentityRepository;
import com.crm.service.SuspiciousIdentityService;
import com.crm.service.SecurityHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    private EventParticipantRepository eventParticipantRepository;

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

    @GetMapping("/list")
    public ResponseEntity<?> getDatabasesList(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) String positionLevel,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String city,
            @RequestParam(required = false, defaultValue = "all") String tab,
            @RequestParam(required = false, defaultValue = "id") String sortBy,
            @RequestParam(required = false, defaultValue = "asc") String sortOrder,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        List<Database> allDatabases = databaseRepository.findAll();
        Set<Long> confirmedFlaggedDatabaseIds = flaggedIdentityRepository.findAll().stream()
                .filter(flag -> flag.getStatus() == FlagStatus.confirmed)
                .map(flag -> flag.getDatabase() != null ? flag.getDatabase().getId() : null)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        List<Database> visibleDatabases = allDatabases.stream()
                .filter(database -> database.getIsActive() == null || database.getIsActive())
                .filter(database -> !confirmedFlaggedDatabaseIds.contains(database.getId()))
                .collect(Collectors.toList());

        long totalVisible = visibleDatabases.size();
        long cleanCount = visibleDatabases.stream().filter(database -> !isIncomplete(database)).count();
        long dirtyCount = visibleDatabases.stream().filter(this::isIncomplete).count();

        List<Database> filteredDatabases = visibleDatabases.stream()
                .filter(database -> matchesTab(database, tab))
                .filter(database -> matchesSearch(database, search))
                .filter(database -> matchesGroup(database, groupId))
                .filter(database -> matchesCompany(database, companyId))
                .filter(database -> matchesPositionLevel(database, positionLevel))
                .filter(database -> matchesIndustry(database, industry))
                .filter(database -> matchesCity(database, city))
                .sorted(buildComparator(sortBy, sortOrder))
                .collect(Collectors.toList());

        int safeSize = size == null ? 10 : Math.max(1, Math.min(size, 200));
        int safePage = page == null ? 1 : Math.max(1, page);
        int total = filteredDatabases.size();
        int fromIndex = Math.min((safePage - 1) * safeSize, total);
        int toIndex = Math.min(fromIndex + safeSize, total);

        return ResponseEntity.ok(new LinkedHashMap<String, Object>() {{
            put("page", safePage);
            put("size", safeSize);
            put("total", total);
            put("totalPages", (int) Math.ceil(total / (double) safeSize));
            put("items", filteredDatabases.subList(fromIndex, toIndex));
            put("summary", new LinkedHashMap<String, Long>() {{
                put("all", totalVisible);
                put("clean", cleanCount);
                put("dirty", dirtyCount);
            }});
        }});
    }

    @GetMapping("/export")
    public ResponseEntity<?> exportDatabases(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) String positionLevel,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String city,
            @RequestParam(required = false, defaultValue = "all") String tab,
            @RequestParam(required = false, defaultValue = "id") String sortBy,
            @RequestParam(required = false, defaultValue = "asc") String sortOrder,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        List<Database> items = filterVisibleDatabases(search, groupId, companyId, positionLevel, industry, city, tab).stream()
                .sorted(buildComparator(sortBy, sortOrder))
                .toList();

        return ResponseEntity.ok(Map.of(
                "total", items.size(),
                "items", items
        ));
    }

    @GetMapping("/filter-options")
    public ResponseEntity<?> getDatabaseFilterOptions(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) String positionLevel,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String city,
            @RequestParam(required = false, defaultValue = "all") String tab,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        List<Database> scopedDatabases = filterVisibleDatabases(search, groupId, companyId, positionLevel, industry, city, tab);
        List<Database> groupScopedDatabases = filterVisibleDatabases(search, null, companyId, positionLevel, industry, city, tab);
        List<Database> companyScopedDatabases = filterVisibleDatabases(search, groupId, null, positionLevel, industry, city, tab);
        List<Database> cityScopedDatabases = filterVisibleDatabases(search, groupId, companyId, positionLevel, industry, null, tab);
        List<Database> industryScopedDatabases = filterVisibleDatabases(search, groupId, companyId, positionLevel, null, city, tab);
        List<Database> positionScopedDatabases = filterVisibleDatabases(search, groupId, companyId, null, industry, city, tab);

        List<Map<String, Object>> groups = groupScopedDatabases.stream()
                .filter(database -> database.getCompany() != null && database.getCompany().getGroup() != null)
                .collect(Collectors.toMap(
                        database -> database.getCompany().getGroup().getId(),
                        database -> database.getCompany().getGroup(),
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ))
                .values().stream()
                .sorted(Comparator.comparing(group -> safe(group.getName()).toLowerCase(Locale.ROOT)))
                .map(group -> Map.<String, Object>of(
                        "id", group.getId(),
                        "name", safe(group.getName())
                ))
                .toList();

        List<Map<String, Object>> companies = companyScopedDatabases.stream()
                .filter(database -> database.getCompany() != null)
                .collect(Collectors.toMap(
                        database -> database.getCompany().getId(),
                        Database::getCompany,
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ))
                .values().stream()
                .sorted(Comparator.comparing(company -> safe(company.getName()).toLowerCase(Locale.ROOT)))
                .map(company -> Map.<String, Object>of(
                        "id", company.getId(),
                        "name", safe(company.getName())
                ))
                .toList();

        List<Map<String, String>> cities = cityScopedDatabases.stream()
                .map(database -> database.getCompany() != null ? database.getCompany().getCity() : null)
                .filter(value -> !isBlank(value))
                .collect(Collectors.toMap(
                        this::normalizeCity,
                        value -> toTitleCase(value.trim()),
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByValue(String::compareToIgnoreCase))
                .map(entry -> Map.of(
                        "value", entry.getKey(),
                        "label", entry.getValue()
                ))
                .toList();

        List<String> industries = industryScopedDatabases.stream()
                .map(database -> database.getCompany() != null ? safe(database.getCompany().getIndustry()).trim() : "")
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(String::compareToIgnoreCase)
                .toList();

        List<String> positionLevels = positionScopedDatabases.stream()
                .map(database -> database.getPositionLevel() != null ? safe(database.getPositionLevel().getValue()).trim() : "")
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(String::compareToIgnoreCase)
                .toList();

        return ResponseEntity.ok(Map.of(
                "cities", cities,
                "groups", groups,
                "companies", companies,
                "industries", industries,
                "positionLevels", positionLevels
        ));
    }

    private List<Database> filterVisibleDatabases(
            String search,
            Long groupId,
            Long companyId,
            String positionLevel,
            String industry,
            String city,
            String tab) {
        return getVisibleDatabases().stream()
                .filter(database -> matchesTab(database, tab))
                .filter(database -> matchesSearch(database, search))
                .filter(database -> matchesGroup(database, groupId))
                .filter(database -> matchesCompany(database, companyId))
                .filter(database -> matchesPositionLevel(database, positionLevel))
                .filter(database -> matchesIndustry(database, industry))
                .filter(database -> matchesCity(database, city))
                .collect(Collectors.toList());
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
            if (databaseDetails.getSalutation() != null) existing.setSalutation(databaseDetails.getSalutation());
            if (databaseDetails.getFirstName() != null) existing.setFirstName(databaseDetails.getFirstName());
            if (databaseDetails.getLastName() != null) existing.setLastName(databaseDetails.getLastName());
            if (databaseDetails.getPositionLevel() != null) existing.setPositionLevel(databaseDetails.getPositionLevel());
            if (databaseDetails.getSpecialityDivision() != null) existing.setSpecialityDivision(databaseDetails.getSpecialityDivision());
            if (databaseDetails.getJobTitle() != null) existing.setJobTitle(databaseDetails.getJobTitle());
            if (databaseDetails.getMobilePhone() != null) existing.setMobilePhone(databaseDetails.getMobilePhone());
            if (databaseDetails.getNormalizedPhone() != null) existing.setNormalizedPhone(databaseDetails.getNormalizedPhone());
            if (databaseDetails.getLinkedinUrl() != null) existing.setLinkedinUrl(databaseDetails.getLinkedinUrl());
            if (databaseDetails.getDatabaseType() != null) existing.setDatabaseType(databaseDetails.getDatabaseType());
            if (databaseDetails.getSource() != null) existing.setSource(databaseDetails.getSource());
            if (databaseDetails.getIsActive() != null) {
                existing.setIsActive(databaseDetails.getIsActive());
            }

            if (companyId != null) {
                Company company = companyRepository.findById(companyId).orElse(null);
                existing.setCompany(company);
            } else if (databaseDetails.getCompany() != null) {
                existing.setCompany(databaseDetails.getCompany());
            }

            Database saved = databaseRepository.save(existing);
            suspiciousIdentityService.checkAndFlagDatabase(saved);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @org.springframework.transaction.annotation.Transactional
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
            // Delete associated entities using query methods (emails are deleted automatically via CascadeType.ALL)
            eventParticipantRepository.deleteByDatabaseId(id);
            removalRequestRepository.deleteByDatabaseId(id);
            flaggedIdentityRepository.deleteByDatabaseId(id);

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
        boolean personalEmail = "personal".equalsIgnoreCase(databaseEmail.getEmailType());
        List<DatabaseEmail> existingEmails = databaseEmailRepository.findAllByEmailIgnoreCase(cleanEmail);
        boolean duplicate = personalEmail
                ? !existingEmails.isEmpty()
                : existingEmails.stream().anyMatch(email -> email.getDatabase() != null && email.getDatabase().getId().equals(databaseId));
        if (duplicate) {
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
            return ResponseEntity.ok(database.getEmails());
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{databaseId}/event-participants")
    public ResponseEntity<?> getDatabaseEventParticipants(
            @PathVariable("databaseId") Long databaseId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        if (!databaseRepository.existsById(databaseId)) {
            return ResponseEntity.notFound().build();
        }
        List<EventParticipant> participants = eventParticipantRepository.findByDatabaseId(databaseId);
        return ResponseEntity.ok(participants);
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
            String cleanEmail = updated.getEmail() != null && !updated.getEmail().trim().isEmpty()
                    ? updated.getEmail().trim().toLowerCase()
                    : email.getEmail();
            String targetType = updated.getEmailType() != null ? updated.getEmailType() : email.getEmailType();
            boolean personalEmail = "personal".equalsIgnoreCase(targetType);
            boolean duplicate = databaseEmailRepository.findAllByEmailIgnoreCase(cleanEmail).stream()
                    .filter(existing -> !existing.getId().equals(emailId))
                    .anyMatch(existing -> personalEmail || (existing.getDatabase() != null && existing.getDatabase().getId().equals(databaseId)));
            if (duplicate) {
                return ResponseEntity.badRequest().body("Email address is already in use");
            }
            if (updated.getEmail() != null && !updated.getEmail().trim().isEmpty()) {
                email.setEmail(cleanEmail);
            }
            if (updated.getEmailType() != null) {
                email.setEmailType(updated.getEmailType());
                if ("personal".equalsIgnoreCase(updated.getEmailType())) email.setIsCorporate(false);
                if ("company".equalsIgnoreCase(updated.getEmailType())) email.setIsCorporate(true);
            }
            if (updated.getIsPrimary() != null) {
                email.setIsPrimary(updated.getIsPrimary());
            }
            if (updated.getIsCorporate() != null) {
                email.setIsCorporate(updated.getIsCorporate());
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

    private boolean matchesTab(Database database, String tab) {
        String normalizedTab = tab == null ? "all" : tab.trim().toLowerCase(Locale.ROOT);
        boolean incomplete = isIncomplete(database);
        return switch (normalizedTab) {
            case "clean" -> !incomplete;
            case "dirty" -> incomplete;
            default -> true;
        };
    }

    private List<Database> getVisibleDatabases() {
        Set<Long> confirmedFlaggedDatabaseIds = flaggedIdentityRepository.findAll().stream()
                .filter(flag -> flag.getStatus() == FlagStatus.confirmed)
                .map(flag -> flag.getDatabase() != null ? flag.getDatabase().getId() : null)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        return databaseRepository.findAll().stream()
                .filter(database -> database.getIsActive() == null || database.getIsActive())
                .filter(database -> !confirmedFlaggedDatabaseIds.contains(database.getId()))
                .collect(Collectors.toList());
    }

    private boolean matchesSearch(Database database, String search) {
        if (search == null || search.isBlank()) return true;
        String query = search.trim().toLowerCase(Locale.ROOT);
        String fullName = (safe(database.getFirstName()) + " " + safe(database.getLastName())).toLowerCase(Locale.ROOT);
        return fullName.contains(query)
                || safe(database.getJobTitle()).toLowerCase(Locale.ROOT).contains(query)
                || safe(database.getPositionLevel() != null ? database.getPositionLevel().getValue() : null).toLowerCase(Locale.ROOT).contains(query)
                || safe(database.getSpecialityDivision()).toLowerCase(Locale.ROOT).contains(query)
                || safe(database.getMobilePhone()).toLowerCase(Locale.ROOT).contains(query)
                || safe(database.getSource() != null ? database.getSource().name() : null).toLowerCase(Locale.ROOT).contains(query)
                || safe(database.getCompany() != null ? database.getCompany().getName() : null).toLowerCase(Locale.ROOT).contains(query)
                || safe(database.getCompany() != null ? database.getCompany().getBrandName() : null).toLowerCase(Locale.ROOT).contains(query)
                || safe(database.getCompany() != null && database.getCompany().getGroup() != null ? database.getCompany().getGroup().getName() : null).toLowerCase(Locale.ROOT).contains(query)
                || safe(database.getCompany() != null ? database.getCompany().getCity() : null).toLowerCase(Locale.ROOT).contains(query);
    }

    private boolean matchesGroup(Database database, Long groupId) {
        if (groupId == null) return true;
        return database.getCompany() != null
                && database.getCompany().getGroup() != null
                && groupId.equals(database.getCompany().getGroup().getId());
    }

    private boolean matchesCompany(Database database, Long companyId) {
        if (companyId == null) return true;
        return database.getCompany() != null && companyId.equals(database.getCompany().getId());
    }

    private boolean matchesPositionLevel(Database database, String positionLevel) {
        if (positionLevel == null || positionLevel.isBlank()) return true;
        return safe(database.getPositionLevel() != null ? database.getPositionLevel().getValue() : null)
                .equalsIgnoreCase(positionLevel.trim());
    }

    private boolean matchesIndustry(Database database, String industry) {
        if (industry == null || industry.isBlank()) return true;
        String databaseIndustry = normalizeIndustry(database.getCompany() != null ? database.getCompany().getIndustry() : null);
        String targetIndustry = normalizeIndustry(industry);
        return !databaseIndustry.isBlank()
                && (databaseIndustry.equals(targetIndustry)
                || databaseIndustry.contains(targetIndustry)
                || targetIndustry.contains(databaseIndustry));
    }

    private boolean matchesCity(Database database, String city) {
        if (city == null || city.isBlank()) return true;
        return normalizeCity(database.getCompany() != null ? database.getCompany().getCity() : null)
                .equalsIgnoreCase(normalizeCity(city));
    }

    private Comparator<Database> buildComparator(String sortBy, String sortOrder) {
        String field = sortBy == null ? "id" : sortBy.trim();
        Comparator<Database> comparator;
        if ("id".equalsIgnoreCase(field)) {
            comparator = Comparator.comparing(d -> d.getId() == null ? 0L : d.getId());
        } else {
            comparator = Comparator.comparing(d -> getSortString(d, field));
        }

        if ("desc".equalsIgnoreCase(sortOrder)) {
            comparator = comparator.reversed();
        }
        return comparator;
    }

    private String getSortString(Database database, String field) {
        return switch (field) {
            case "firstName" -> safe(database.getFirstName()).toLowerCase(Locale.ROOT);
            case "lastName" -> safe(database.getLastName()).toLowerCase(Locale.ROOT);
            case "companyName" -> safe(database.getCompany() != null ? database.getCompany().getName() : null).toLowerCase(Locale.ROOT);
            case "groupName" -> safe(database.getCompany() != null && database.getCompany().getGroup() != null ? database.getCompany().getGroup().getName() : null).toLowerCase(Locale.ROOT);
            case "brandName" -> safe(database.getCompany() != null ? database.getCompany().getBrandName() : null).toLowerCase(Locale.ROOT);
            case "jobTitle" -> safe(database.getJobTitle()).toLowerCase(Locale.ROOT);
            case "position" -> safe(database.getPositionLevel() != null ? database.getPositionLevel().getValue() : null).toLowerCase(Locale.ROOT);
            case "industry" -> safe(database.getCompany() != null ? database.getCompany().getIndustry() : null).toLowerCase(Locale.ROOT);
            case "city" -> safe(database.getCompany() != null ? database.getCompany().getCity() : null).toLowerCase(Locale.ROOT);
            default -> String.valueOf(database.getId() == null ? 0L : database.getId());
        };
    }

    private boolean isIncomplete(Database database) {
        if (database == null) return true;
        if (isBlank(database.getCompany() != null && database.getCompany().getGroup() != null ? database.getCompany().getGroup().getName() : null)) return true;
        if (isBlank(database.getCompany() != null ? database.getCompany().getBrandName() : null)) return true;
        if (isBlank(database.getCompany() != null ? database.getCompany().getName() : null)) return true;
        if (isBlank(database.getSalutation())) return true;
        if (isBlank(database.getFirstName())) return true;
        if (isBlank(database.getLastName())) return true;
        if (isBlank(database.getPositionLevel() != null ? database.getPositionLevel().getValue() : null)) return true;
        if (isBlank(database.getJobTitle())) return true;
        if (isBlank(database.getCompany() != null ? database.getCompany().getAddress() : null)) return true;
        if (isBlank(database.getCompany() != null ? database.getCompany().getOfficePhone() : null)) return true;
        if (isBlank(database.getMobilePhone())) return true;
        boolean hasCompanyEmail = database.getEmails() != null && database.getEmails().stream()
                .anyMatch(email -> Boolean.TRUE.equals(email.getIsCorporate()) || "company".equalsIgnoreCase(safe(email.getEmailType())));
        if (!hasCompanyEmail) return true;
        if (isBlank(database.getCompany() != null ? database.getCompany().getIndustry() : null)) return true;
        if (isBlank(database.getCompany() != null ? database.getCompany().getCity() : null)) return true;
        return isBlank(database.getCompany() != null ? database.getCompany().getWebsite() : null);
    }

    private String normalizeIndustry(String value) {
        return safe(value)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace("mm", "m")
                .replaceAll("s$", "");
    }

    private String normalizeCity(String value) {
        return safe(value)
                .trim()
                .toUpperCase(Locale.ROOT)
                .replaceFirst("^(KABUPATEN|KOTA ADMINISTRASI|KOTA)\\s+", "")
                .replaceAll("[^A-Z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String toTitleCase(String value) {
        String normalized = safe(value).trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return "";
        }

        String[] parts = normalized.toLowerCase(Locale.ROOT).split(" ");
        StringBuilder builder = new StringBuilder();

        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }

            if (builder.length() > 0) {
                builder.append(' ');
            }

            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }

        return builder.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
