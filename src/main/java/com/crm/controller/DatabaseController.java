package com.crm.controller;

import com.crm.domain.Company;
import com.crm.domain.CompanyBranch;
import com.crm.domain.Database;
import com.crm.domain.DatabaseEmail;
import com.crm.domain.DatabaseDestination;
import com.crm.domain.EventParticipant;
import com.crm.domain.RemovalRequest;
import com.crm.domain.FlaggedIdentity;
import com.crm.domain.FlagStatus;
import com.crm.domain.Role;
import com.crm.domain.AppUser;
import com.crm.repository.CompanyRepository;
import com.crm.repository.CompanyBranchRepository;
import com.crm.repository.DatabaseEmailRepository;
import com.crm.repository.DatabaseRepository;
import com.crm.repository.EventParticipantRepository;
import com.crm.repository.RemovalRequestRepository;
import com.crm.repository.FlaggedIdentityRepository;
import com.crm.service.SuspiciousIdentityService;
import com.crm.service.SecurityHelper;
import com.crm.service.EmsIdentityReview;
import com.crm.util.ContactEmailCategory;
import com.crm.util.ContactPhone;
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
    private CompanyBranchRepository companyBranchRepository;

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
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String positionLevel,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String city,
            @RequestParam(required = false, defaultValue = "all") String tab,
            @RequestParam(required = false, defaultValue = "id") String sortBy,
            @RequestParam(required = false, defaultValue = "asc") String sortOrder,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size,
            @RequestParam(required = false, defaultValue = "all") String destination,
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

        long participantCount = visibleDatabases.stream().filter(database ->
                DatabaseDestination.effective(database.getDatabaseDestination()).includesParticipant()).count();
        long companyCount = visibleDatabases.stream().filter(database ->
                DatabaseDestination.effective(database.getDatabaseDestination()).includesCompany()).count();
        List<Database> categoryDatabases = visibleDatabases.stream()
                .filter(database -> matchesDestination(database, destination)).toList();
        long totalVisible = categoryDatabases.size();
        long cleanCount = categoryDatabases.stream().filter(database -> !isIncompleteForDestination(database, destination)).count();
        long dirtyCount = categoryDatabases.stream().filter(database -> isIncompleteForDestination(database, destination)).count();

        List<Database> filteredDatabases = categoryDatabases.stream()
                .filter(database -> matchesTab(database, tab, destination))
                .filter(database -> matchesSearch(database, search))
                .filter(database -> matchesGroup(database, groupId))
                .filter(database -> matchesCompany(database, companyId))
                .filter(database -> matchesBranch(database, branchId))
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
            put("analytics", databaseAnalytics(filteredDatabases, destination));
            put("destinations", Map.of("company", companyCount, "participant", participantCount));
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
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String positionLevel,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String city,
            @RequestParam(required = false, defaultValue = "all") String tab,
            @RequestParam(required = false, defaultValue = "id") String sortBy,
            @RequestParam(required = false, defaultValue = "asc") String sortOrder,
            @RequestParam(required = false, defaultValue = "all") String destination,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        List<Database> items = filterVisibleDatabases(search, groupId, companyId, branchId, positionLevel, industry, city, tab, destination).stream()
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
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String positionLevel,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String city,
            @RequestParam(required = false, defaultValue = "all") String tab,
            @RequestParam(required = false, defaultValue = "all") String destination,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        List<Database> groupScopedDatabases = filterVisibleDatabases(search, null, companyId, branchId, positionLevel, industry, city, tab, destination);
        List<Database> companyScopedDatabases = filterVisibleDatabases(search, groupId, null, branchId, positionLevel, industry, city, tab, destination);
        List<Database> branchScopedDatabases = filterVisibleDatabases(search, groupId, companyId, null, positionLevel, industry, city, tab, destination);
        List<Database> cityScopedDatabases = filterVisibleDatabases(search, groupId, companyId, branchId, positionLevel, industry, null, tab, destination);
        List<Database> industryScopedDatabases = filterVisibleDatabases(search, groupId, companyId, branchId, positionLevel, null, city, tab, destination);
        List<Database> positionScopedDatabases = filterVisibleDatabases(search, groupId, companyId, branchId, null, industry, city, tab, destination);

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

        List<Map<String, Object>> branches = branchScopedDatabases.stream()
                .map(Database::getBranch)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toMap(CompanyBranch::getId, branch -> branch,
                        (existing, replacement) -> existing, LinkedHashMap::new))
                .values().stream()
                .sorted(Comparator.comparing(branch -> safe(branch.getName()).toLowerCase(Locale.ROOT)))
                .map(branch -> Map.<String, Object>of("id", branch.getId(), "companyId", branch.getCompanyId(), "name", safe(branch.getName())))
                .toList();

        List<Map<String, String>> cities = cityScopedDatabases.stream()
                .map(this::contactCity)
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
                .map(database -> industryFilterLabel(database.getCompany() != null ? database.getCompany().getIndustry() : null))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toMap(this::normalizeIndustry, value -> value,
                        (existing, replacement) -> existing, LinkedHashMap::new))
                .values().stream()
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
                "branches", branches,
                "industries", industries,
                "positionLevels", positionLevels
        ));
    }

    private List<Database> filterVisibleDatabases(
            String search,
            Long groupId,
            Long companyId,
            Long branchId,
            String positionLevel,
            String industry,
            String city,
            String tab,
            String destination) {
        return getVisibleDatabases().stream()
                .filter(database -> matchesDestination(database, destination))
                .filter(database -> matchesTab(database, tab, destination))
                .filter(database -> matchesSearch(database, search))
                .filter(database -> matchesGroup(database, groupId))
                .filter(database -> matchesCompany(database, companyId))
                .filter(database -> matchesBranch(database, branchId))
                .filter(database -> matchesPositionLevel(database, positionLevel))
                .filter(database -> matchesIndustry(database, industry))
                .filter(database -> matchesCity(database, city))
                .collect(Collectors.toList());
    }

    @PostMapping
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> createDatabase(
            @RequestBody Database database, 
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false, defaultValue = "false") boolean clearBranch,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can create database records");
        }

        if (database.getId() != null) {
            return ResponseEntity.badRequest().body("Use the update endpoint for an existing database record");
        }
        if (branchId != null && clearBranch) return ResponseEntity.badRequest().body("Choose branchId or clearBranch, not both");
        DatabaseDestination selected = DatabaseDestination.effective(database.getDatabaseDestination());
        if (selected == DatabaseDestination.BOTH) return ResponseEntity.badRequest().body("Pilih satu database: Perusahaan atau Peserta.");
        if (selected == DatabaseDestination.PARTICIPANT && (companyId != null || branchId != null || database.getCompany() != null || database.getBranch() != null)) {
            return ResponseEntity.badRequest().body("Profil Peserta memakai instansi sendiri; perusahaan/cabang master tidak boleh ditautkan.");
        }
        Long resolvedCompanyId = companyId != null ? companyId : database.getCompany() != null ? database.getCompany().getId() : null;
        Company company = resolvedCompanyId == null ? null : companyRepository.findById(resolvedCompanyId).orElse(null);
        if (resolvedCompanyId != null && company == null) return ResponseEntity.badRequest().body("Company not found");
        CompanyBranch branch = branchId == null ? null : companyBranchRepository.findById(branchId).orElse(null);
        if (branchId != null && (branch == null || company == null || !branch.getCompanyId().equals(company.getId()))) {
            return ResponseEntity.badRequest().body("Cabang harus berasal dari perusahaan yang dipilih");
        }
        database.setDatabaseDestination(DatabaseDestination.effective(database.getDatabaseDestination()));
        String metadataError = participantMetadataError(database);
        if (metadataError != null) return ResponseEntity.badRequest().body(metadataError);
        if (selected == DatabaseDestination.PARTICIPANT) {
            String organizationError = participantOrganizationError(database);
            if (organizationError != null) return ResponseEntity.badRequest().body(organizationError);
        } else {
            database.setOrganizationGroupName(null);
            database.setOrganizationBrandName(null);
            database.setOrganizationAddress(null);
            database.setOrganizationOfficePhone(null);
            database.setOrganizationIndustry(null);
            database.setOrganizationSizeRevenue(null);
            database.setOrganizationSizeEmployee(null);
            database.setOrganizationBranchName(null);
            database.setOrganizationPostalCode(null);
            database.setOrganizationWebsite(null);
        }
        database.setCompany(company);
        database.setBranch(branch);
        if (database.getDatabaseDestination() == DatabaseDestination.PARTICIPANT) {
            return createParticipantProfile(database, currentUser);
        }
        if (database.getEmails() != null && !database.getEmails().isEmpty()) {
            try {
                List<EmailBatchEntry> supplied = new java.util.ArrayList<>();
                for (DatabaseEmail email : database.getEmails()) {
                    if (email == null || email.getId() != null || email.getDatabase() != null) {
                        return ResponseEntity.badRequest().body("Email baru tidak boleh memuat ID atau pemilik email tersimpan.");
                    }
                    supplied.add(new EmailBatchEntry(null, email.getEmail(), email.getEmailType(),
                            email.getIsCorporate(), email.getIsPrimary(), email.getIsVerified(), null));
                }
                database.setEmails(new java.util.ArrayList<>(planEmailBatch(database, supplied).stream()
                        .map(write -> applyEmailWrite(database, write)).toList()));
            } catch (IllegalArgumentException invalid) {
                return ResponseEntity.badRequest().body(invalid.getMessage());
            }
        }
        database.setCreatedByUserId(currentUser.getId());
        database.setEntryMethod("manual");
        Database saved = databaseRepository.save(database);
        suspiciousIdentityService.checkAndFlagDatabase(saved);
        return ResponseEntity.ok(saved);
    }

    // The surrounding create transaction owns both the profile and its email rows.
    private ResponseEntity<?> createParticipantProfile(Database incoming, AppUser currentUser) {
        if ((incoming.getEmails() == null || incoming.getEmails().isEmpty()) && isBlank(incoming.getMobilePhone())) {
            return ResponseEntity.badRequest().body("Isi minimal email valid atau nomor HP/WhatsApp peserta.");
        }
        if (!isBlank(incoming.getMobilePhone()) && !ContactPhone.isUsable(incoming.getMobilePhone())) {
            return ResponseEntity.badRequest().body("Mobile Phone peserta tidak valid.");
        }
        if (safe(incoming.getFirstName()).length() > 255 || safe(incoming.getLastName()).length() > 255) {
            return ResponseEntity.badRequest().body("Nama peserta maksimal 255 karakter.");
        }
        Map<String, DatabaseEmail> requested = new LinkedHashMap<>();
        Map<Long, Database> owners = new LinkedHashMap<>();
        for (DatabaseEmail supplied : incoming.getEmails() == null ? List.<DatabaseEmail>of() : incoming.getEmails()) {
            if (supplied == null || supplied.getId() != null
                    || ContactEmailCategory.splitEmails(supplied.getEmail()).isEmpty()) {
                return ResponseEntity.badRequest().body("Email peserta tidak valid atau memuat ID email tersimpan");
            }
            for (String address : ContactEmailCategory.splitEmails(supplied.getEmail())) {
                if (!ContactEmailCategory.isValidEmail(address) || address.length() > 255) {
                    return ResponseEntity.badRequest().body("Format email peserta tidak valid: " + address);
                }
                if (requested.putIfAbsent(address, supplied) != null) {
                    return ResponseEntity.badRequest().body("Email peserta yang sama hanya boleh diisi sekali");
                }
                for (DatabaseEmail existing : databaseEmailRepository.findAllByEmailIgnoreCase(address)) {
                    Database owner = existing.getDatabase();
                    if (owner != null && !DatabaseDestination.isExact(owner.getDatabaseDestination(), DatabaseDestination.PARTICIPANT)) continue;
                    if (owner == null || owner.getId() == null) {
                        return ResponseEntity.badRequest().body("Pemilik email belum dapat dipastikan; periksa kontak lama");
                    }
                    owners.putIfAbsent(owner.getId(), owner);
                }
            }
        }
        if (!isBlank(incoming.getMobilePhone())) {
            Set<String> numbers = ContactPhone.normalizedNumbers(incoming.getMobilePhone());
            for (Database candidate : databaseRepository.findAll()) {
                if (!DatabaseDestination.isExact(candidate.getDatabaseDestination(), DatabaseDestination.PARTICIPANT)) continue;
                Set<String> retainedNumbers = ContactPhone.normalizedNumbers(candidate.getMobilePhone());
                retainedNumbers.addAll(ContactPhone.normalizedNumbers(candidate.getNormalizedPhone()));
                if (numbers.stream().anyMatch(retainedNumbers::contains)) owners.putIfAbsent(candidate.getId(), candidate);
            }
        }
        if (owners.size() > 1) {
            return ResponseEntity.badRequest().body("Email cocok dengan beberapa profil; tinjau kontak lama sebelum menambahkan peserta");
        }
        Database target = owners.isEmpty() ? incoming : owners.values().iterator().next();
        boolean reuse = target != incoming;
        if (reuse) {
            if (!normalizedContactName(incoming).isBlank() && !normalizedContactName(target).isBlank()
                    && !normalizedContactName(incoming).equals(normalizedContactName(target))) {
                return ResponseEntity.badRequest().body("Email sudah dipakai nama lain; periksa profil yang sudah ada");
            }
            if (Boolean.FALSE.equals(target.getIsActive())) {
                return ResponseEntity.badRequest().body("Kontak lama nonaktif; tinjau profil sebelum menambahkan peserta");
            }
            boolean confirmed = flaggedIdentityRepository.findAll().stream().anyMatch(flag ->
                    flag.getStatus() == FlagStatus.confirmed && flag.getDatabase() != null
                            && target.getId().equals(flag.getDatabase().getId()));
            if (confirmed) return ResponseEntity.badRequest().body("Kontak lama ditandai bermasalah; tinjau profil terlebih dahulu");
        }

        String mergedPhone = "";
        if (!isBlank(incoming.getMobilePhone())) {
            try { mergedPhone = ContactPhone.merge(reuse ? target.getMobilePhone() : "", incoming.getMobilePhone()); }
            catch (IllegalArgumentException invalid) { return ResponseEntity.badRequest().body(invalid.getMessage()); }
        }
        // Complete every conflict check before mutating a managed existing profile.
        List<DatabaseEmail> retained = reuse && target.getEmails() != null
                ? new java.util.ArrayList<>(target.getEmails()) : new java.util.ArrayList<>();
        boolean primaryPersonal = retained.stream().anyMatch(email -> email != null
                && "personal".equalsIgnoreCase(email.getEmailType()) && Boolean.TRUE.equals(email.getIsPrimary()));
        for (Map.Entry<String, DatabaseEmail> requestedEmail : requested.entrySet()) {
            boolean present = retained.stream().anyMatch(email -> email != null
                    && requestedEmail.getKey().equalsIgnoreCase(safe(email.getEmail()).trim()));
            if (present) continue;
            retained.add(DatabaseEmail.builder().database(target).email(requestedEmail.getKey())
                    .emailType("personal").isCorporate(false).isPrimary(!primaryPersonal)
                    .isVerified(Boolean.TRUE.equals(requestedEmail.getValue().getIsVerified()))
                    .domain(requestedEmail.getKey().substring(requestedEmail.getKey().indexOf('@') + 1)).build());
            primaryPersonal = true;
        }
        copyParticipantOrganization(incoming, target, reuse);
        if (!isBlank(incoming.getFirstName())) target.setFirstName(incoming.getFirstName());
        if (!isBlank(incoming.getLastName())) target.setLastName(incoming.getLastName());
        if (!mergedPhone.isBlank()) { target.setMobilePhone(mergedPhone); target.setNormalizedPhone(ContactPhone.primary(mergedPhone)); }
        target.setEmails(retained);
        target.setDatabaseDestination(DatabaseDestination.PARTICIPANT);
        if (!reuse) {
            target.setCreatedByUserId(currentUser.getId());
            target.setEntryMethod("manual");
        } else {
            // Existing company, attribution, contact values, and email ownership stay authoritative.
            if (isBlank(target.getInstitutionName()) && !isBlank(incoming.getInstitutionName())) target.setInstitutionName(incoming.getInstitutionName().trim());
            if (isBlank(target.getParticipantCategory()) && !isBlank(incoming.getParticipantCategory())) target.setParticipantCategory(incoming.getParticipantCategory().trim());
        }
        Database saved = databaseRepository.save(target);
        suspiciousIdentityService.checkAndFlagDatabase(saved);
        return ResponseEntity.ok(saved);
    }

    private String normalizedContactName(Database database) {
        return (safe(database.getFirstName()) + " " + safe(database.getLastName()))
                .trim().replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT);
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
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false, defaultValue = "false") boolean clearBranch,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can update database records");
        }

        return databaseRepository.findById(id).map(existing -> {
            DatabaseDestination kind = DatabaseDestination.effective(existing.getDatabaseDestination());
            if (kind == DatabaseDestination.BOTH) return ResponseEntity.status(409).body("Profil legacy harus dipisahkan sebelum diedit.");
            if (databaseDetails.getDatabaseDestination() != null && !DatabaseDestination.isExact(databaseDetails.getDatabaseDestination(), kind)) {
                return ResponseEntity.status(409).body("Jenis database tidak dapat diganti; buat profil terpisah di database tujuan.");
            }
            if (kind == DatabaseDestination.PARTICIPANT && (companyId != null || branchId != null || databaseDetails.getCompany() != null || databaseDetails.getBranch() != null)) {
                return ResponseEntity.badRequest().body("Profil Peserta tidak boleh ditautkan ke perusahaan/cabang master.");
            }
            if (branchId != null && clearBranch) return ResponseEntity.badRequest().body("Choose branchId or clearBranch, not both");
            Long requestedCompanyId = companyId != null ? companyId : databaseDetails.getCompany() != null ? databaseDetails.getCompany().getId() : null;
            Company company = requestedCompanyId == null ? existing.getCompany() : companyRepository.findById(requestedCompanyId).orElse(null);
            if (requestedCompanyId != null && company == null) return ResponseEntity.badRequest().body("Company not found");
            CompanyBranch branch = branchId == null ? null : companyBranchRepository.findById(branchId).orElse(null);
            if (branchId != null && (branch == null || company == null || !branch.getCompanyId().equals(company.getId()))) {
                return ResponseEntity.badRequest().body("Cabang harus berasal dari perusahaan yang dipilih");
            }
            String metadataError = participantMetadataError(databaseDetails);
            if (metadataError != null) return ResponseEntity.badRequest().body(metadataError);
            if (kind == DatabaseDestination.PARTICIPANT) {
                String organizationError = participantOrganizationError(databaseDetails);
                if (organizationError != null) return ResponseEntity.badRequest().body(organizationError);
            }
            // Validate the complete assignment before touching this managed contact.
            existing.setCompany(company);
            if (kind == DatabaseDestination.PARTICIPANT) copyParticipantOrganization(databaseDetails, existing, false);
            if (databaseDetails.getInstitutionName() != null) existing.setInstitutionName(databaseDetails.getInstitutionName().trim());
            if (databaseDetails.getParticipantCategory() != null) existing.setParticipantCategory(databaseDetails.getParticipantCategory().trim());
            if (clearBranch || branchId != null) existing.setBranch(branch);
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

            Database saved = databaseRepository.save(existing);
            suspiciousIdentityService.checkAndFlagDatabase(saved);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    public record ContactUpdateRequest(String salutation, String firstName, String lastName,
            String positionLevel, String specialityDivision, String jobTitle, String mobilePhone,
            List<EmailBatchEntry> emails) {}

    @PutMapping("/{id}/contact")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> updateDatabaseContact(@PathVariable Long id,
            @RequestBody ContactUpdateRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) return ResponseEntity.status(401).body("Unauthorized");
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can update contact data");
        }
        Database existing = databaseRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        if (DatabaseDestination.effective(existing.getDatabaseDestination()) == DatabaseDestination.BOTH) {
            return ResponseEntity.status(409).body("Profil legacy harus dipisahkan sebelum diedit.");
        }
        if (request == null) return ResponseEntity.badRequest().body("Data kontak wajib diisi.");
        String[] texts = {request.salutation(), request.firstName(), request.lastName(),
                request.positionLevel(), request.specialityDivision(), request.jobTitle()};
        int[] limits = {50, 255, 255, 100, 100, 255};
        String[] labels = {"Salutation", "First Name", "Last Name", "Position Level", "Division", "Job Title"};
        for (int index = 0; index < texts.length; index++) {
            if (texts[index] != null && texts[index].trim().codePointCount(0, texts[index].trim().length()) > limits[index]) {
                return ResponseEntity.badRequest().body(labels[index] + " maksimal " + limits[index] + " karakter.");
            }
        }
        String phone = request.mobilePhone() == null ? null : ContactPhone.clean(request.mobilePhone().trim());
        if (request.mobilePhone() != null && !request.mobilePhone().isBlank()
                && (!ContactPhone.isUsable(request.mobilePhone()) || phone.length() > 50)) {
            return ResponseEntity.badRequest().body("Mobile Phone tidak valid; pisahkan setiap nomor dengan koma. Maksimal 50 karakter.");
        }
        List<EmailWrite> writes;
        try {
            writes = request.emails() == null || request.emails().isEmpty()
                    ? List.of() : planEmailBatch(existing, request.emails());
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(invalid.getMessage());
        }

        // Validate every contact/email change before touching the managed profile.
        try {
            if (request.salutation() != null) existing.setSalutation(request.salutation().trim());
            if (request.firstName() != null) existing.setFirstName(request.firstName().trim());
            if (request.lastName() != null) existing.setLastName(request.lastName().trim());
            if (request.positionLevel() != null) existing.setPositionLevel(request.positionLevel().isBlank()
                    ? null : com.crm.domain.PositionLevel.fromValue(request.positionLevel()));
            if (request.specialityDivision() != null) existing.setSpecialityDivision(request.specialityDivision().trim());
            if (request.jobTitle() != null) existing.setJobTitle(request.jobTitle().trim());
            boolean phoneChanged = phone != null && (!java.util.Objects.equals(phone, existing.getMobilePhone())
                    || !java.util.Objects.equals(ContactPhone.primary(phone), existing.getNormalizedPhone()));
            if (phone != null) {
                existing.setMobilePhone(phone);
                existing.setNormalizedPhone(ContactPhone.primary(phone));
            }
            List<DatabaseEmail> allEmails = new java.util.ArrayList<>(existing.getEmails() == null ? List.of() : existing.getEmails());
            for (EmailWrite write : writes) {
                if (write.skip()) continue;
                DatabaseEmail savedEmail = databaseEmailRepository.save(applyEmailWrite(existing, write));
                if (write.existing() == null) allEmails.add(savedEmail);
            }
            existing.setEmails(allEmails);
            Database saved = databaseRepository.saveAndFlush(existing);
            if (phoneChanged) {
                eventParticipantRepository.findByDatabaseId(id).stream().map(EventParticipant::getEvent)
                        .filter(java.util.Objects::nonNull).map(com.crm.domain.Event::getId).distinct()
                        .forEach(eventId -> EmsIdentityReview.refresh(eventParticipantRepository, eventId));
            }
            suspiciousIdentityService.checkAndFlagDatabase(saved);
            databaseRepository.flush();
            return ResponseEntity.ok(saved);
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            // Runtime exception rolls the complete contact edit back, including any email writes.
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Data kontak berubah atau email sudah digunakan. Perubahan dibatalkan; muat ulang lalu periksa kembali.");
        }
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
        if (databaseEmail.getId() != null) return ResponseEntity.badRequest().body("Email baru tidak boleh memuat ID email tersimpan.");
        if (!ContactEmailCategory.isValidEmail(cleanEmail)) return ResponseEntity.badRequest().body("Format email tidak valid.");
        return databaseRepository.findById(databaseId).map(database -> {
            DatabaseDestination kind = DatabaseDestination.effective(database.getDatabaseDestination());
            if (kind == DatabaseDestination.BOTH) return ResponseEntity.status(409).body("Profil legacy harus dipisahkan sebelum diedit.");
            List<DatabaseEmail> owners = databaseEmailRepository.findAllByEmailIgnoreCase(cleanEmail);
            if (owners.stream().anyMatch(email -> email.getDatabase() == null)) return ResponseEntity.badRequest().body("Pemilik email belum dapat dipastikan; tinjau data lama.");
            boolean duplicate = owners.stream()
                    .filter(email -> DatabaseDestination.isExact(email.getDatabase().getDatabaseDestination(), kind))
                    .anyMatch(email -> personalEmail || kind == DatabaseDestination.PARTICIPANT || email.getDatabase().getId().equals(databaseId));
            if (duplicate) return ResponseEntity.badRequest().body("Email address is already in use in this database.");
            if (!ContactEmailCategory.isValidEmail(cleanEmail)) {
                return ResponseEntity.badRequest().body("Format email tidak valid");
            }
            boolean companyAddress = Boolean.TRUE.equals(databaseEmail.getIsCorporate())
                    || "company".equalsIgnoreCase(databaseEmail.getEmailType());
            if (DatabaseDestination.effective(database.getDatabaseDestination()).includesCompany()
                    && companyAddress && ContactEmailCategory.isPublicPersonalEmail(cleanEmail)) {
                return ResponseEntity.badRequest().body("Email pribadi masuk melalui Database Peserta; email perusahaan wajib memakai domain institusi/perusahaan.");
            }
            databaseEmail.setDatabase(database);
            databaseEmail.setEmail(cleanEmail);
            DatabaseEmail savedEmail = databaseEmailRepository.save(databaseEmail);
            suspiciousIdentityService.checkAndFlagDatabase(database);
            return ResponseEntity.ok(savedEmail);
        }).orElse(ResponseEntity.notFound().build());
    }

    public record EmailBatchRequest(List<EmailBatchEntry> emails) {}
    public record EmailBatchEntry(Long id, String email, String emailType, Boolean isCorporate,
                                  Boolean isPrimary, Boolean isVerified, Object database) {}
    private record EmailWrite(DatabaseEmail existing, String address, String type, boolean corporate,
                              boolean primary, boolean verified, boolean skip) {}

    @PostMapping("/{databaseId}/emails/batch")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> saveDatabaseEmails(@PathVariable Long databaseId,
            @RequestBody EmailBatchRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) return ResponseEntity.status(401).body("Unauthorized");
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can edit emails");
        }
        Database database = databaseRepository.findById(databaseId).orElse(null);
        if (database == null) return ResponseEntity.notFound().build();
        if (DatabaseDestination.effective(database.getDatabaseDestination()) == DatabaseDestination.BOTH) {
            return ResponseEntity.status(409).body("Profil legacy harus dipisahkan sebelum diedit.");
        }
        if (request == null || request.emails() == null || request.emails().isEmpty()) {
            return ResponseEntity.badRequest().body("Isi minimal satu alamat email.");
        }
        List<EmailWrite> writes;
        try { writes = planEmailBatch(database, request.emails()); }
        catch (IllegalArgumentException invalid) { return ResponseEntity.badRequest().body(invalid.getMessage()); }

        int created = 0, updated = 0, retained = 0;
        List<DatabaseEmail> result = new java.util.ArrayList<>();
        List<DatabaseEmail> allEmails = new java.util.ArrayList<>(database.getEmails() == null ? List.of() : database.getEmails());
        for (EmailWrite write : writes) {
            if (write.skip()) {
                retained++;
                result.add(write.existing());
                continue;
            }
            DatabaseEmail email = databaseEmailRepository.save(applyEmailWrite(database, write));
            if (write.existing() == null) { created++; allEmails.add(email); }
            else updated++;
            result.add(email);
        }
        if (created > 0 || updated > 0) {
            database.setEmails(allEmails);
            suspiciousIdentityService.checkAndFlagDatabase(database);
        }
        return ResponseEntity.ok(Map.of("createdCount", created, "updatedCount", updated,
                "existingCount", retained, "emails", result));
    }

    // Plan every address and ownership check before mutating any managed email/profile.
    private List<EmailWrite> planEmailBatch(Database database, List<EmailBatchEntry> entries) {
        DatabaseDestination kind = DatabaseDestination.effective(database.getDatabaseDestination());
        Map<String, EmailWrite> writes = new LinkedHashMap<>();
        Set<Long> ids = new java.util.HashSet<>();
        for (EmailBatchEntry entry : entries) {
            if (entry == null || entry.database() != null) throw new IllegalArgumentException("Pemilik email ditentukan oleh profil yang dipilih.");
            DatabaseEmail existing = null;
            if (entry.id() != null) {
                if (database.getId() == null || !ids.add(entry.id())) throw new IllegalArgumentException("ID email baru/berulang tidak valid.");
                existing = databaseEmailRepository.findById(entry.id()).orElse(null);
                if (existing == null || existing.getDatabase() == null
                        || !database.getId().equals(existing.getDatabase().getId())) {
                    throw new IllegalArgumentException("Email bukan milik profil yang dipilih.");
                }
            }
            List<String> addresses = ContactEmailCategory.splitEmails(entry.email());
            if (addresses.isEmpty()) throw new IllegalArgumentException("Alamat email wajib diisi.");
            String type = entry.emailType() != null ? entry.emailType().trim().toLowerCase(Locale.ROOT)
                    : existing == null ? "other" : existing.getEmailType();
            if (entry.emailType() != null && !Set.of("company", "personal", "other").contains(type)) {
                throw new IllegalArgumentException("Tipe email tidak valid.");
            }
            boolean corporate = entry.isCorporate() != null ? entry.isCorporate()
                    : entry.emailType() != null ? "company".equals(type)
                    : existing != null && Boolean.TRUE.equals(existing.getIsCorporate());
            boolean primary = entry.isPrimary() != null ? entry.isPrimary()
                    : existing != null && Boolean.TRUE.equals(existing.getIsPrimary());
            boolean verified = entry.isVerified() != null ? entry.isVerified()
                    : existing != null && Boolean.TRUE.equals(existing.getIsVerified());
            boolean canShare = kind == DatabaseDestination.COMPANY
                    && ("company".equals(type) || !"personal".equals(type) && corporate);
            for (int index = 0; index < addresses.size(); index++) {
                String address = addresses.get(index);
                DatabaseEmail target = index == 0 ? existing : null;
                if (!ContactEmailCategory.isValidEmail(address) || address.length() > 255) {
                    throw new IllegalArgumentException("Format email tidak valid: " + address);
                }
                if (kind == DatabaseDestination.COMPANY && (corporate || "company".equals(type))
                        && ContactEmailCategory.isPublicPersonalEmail(address)) {
                    throw new IllegalArgumentException("Email perusahaan wajib memakai domain institusi/perusahaan; simpan email pribadi sebagai Personal.");
                }
                EmailWrite previous = writes.get(address);
                if (previous != null) {
                    if (target != null || !java.util.Objects.equals(previous.type(), type) || previous.corporate() != corporate) {
                        throw new IllegalArgumentException("Email yang sama memuat ID atau tipe berbeda: " + address);
                    }
                    continue;
                }
                DatabaseEmail retained = null;
                for (DatabaseEmail owner : databaseEmailRepository.findAllByEmailIgnoreCase(address)) {
                    if (owner.getDatabase() == null) throw new IllegalArgumentException("Pemilik email belum dapat dipastikan; tinjau data lama.");
                    if (!DatabaseDestination.isExact(owner.getDatabase().getDatabaseDestination(), kind)) continue;
                    if (target != null && java.util.Objects.equals(owner.getId(), target.getId())) continue;
                    if (java.util.Objects.equals(owner.getDatabase().getId(), database.getId()) && database.getId() != null) {
                        if (target != null) throw new IllegalArgumentException("Email sudah tersimpan pada entri lain di profil ini: " + address);
                        retained = owner;
                    } else {
                        boolean ownerCompany = "company".equalsIgnoreCase(owner.getEmailType())
                                || !"personal".equalsIgnoreCase(owner.getEmailType()) && Boolean.TRUE.equals(owner.getIsCorporate());
                        if (!canShare || !ownerCompany) {
                            throw new IllegalArgumentException("Email sudah digunakan profil lain pada database tujuan ini: " + address);
                        }
                    }
                }
                writes.put(address, new EmailWrite(retained != null ? retained : target, address, type,
                        corporate, index == 0 && primary, verified, retained != null));
            }
        }
        return new java.util.ArrayList<>(writes.values());
    }

    private DatabaseEmail applyEmailWrite(Database database, EmailWrite write) {
        DatabaseEmail email = write.existing() == null ? new DatabaseEmail() : write.existing();
        email.setDatabase(database);
        email.setEmail(write.address());
        email.setDomain(write.address().substring(write.address().indexOf('@') + 1));
        email.setEmailType(write.type());
        email.setIsCorporate(write.corporate());
        email.setIsPrimary(write.primary());
        email.setIsVerified(write.verified());
        return email;
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
            if (email.getDatabase() == null || !databaseId.equals(email.getDatabase().getId())) {
                return ResponseEntity.notFound().build();
            }
            if (!ContactEmailCategory.isValidEmail(cleanEmail)) {
                return ResponseEntity.badRequest().body("Format email tidak valid");
            }
            String targetType = updated.getEmailType() != null ? updated.getEmailType() : email.getEmailType();
            boolean targetCorporate = updated.getIsCorporate() != null ? updated.getIsCorporate()
                    : updated.getEmailType() != null ? "company".equalsIgnoreCase(updated.getEmailType())
                    : Boolean.TRUE.equals(email.getIsCorporate());
            if (DatabaseDestination.effective(email.getDatabase().getDatabaseDestination()).includesCompany()
                    && (targetCorporate || "company".equalsIgnoreCase(targetType))
                    && ContactEmailCategory.isPublicPersonalEmail(cleanEmail)) {
                return ResponseEntity.badRequest().body("Email pribadi masuk melalui Database Peserta; email perusahaan wajib memakai domain institusi/perusahaan.");
            }
            boolean personalEmail = "personal".equalsIgnoreCase(targetType);
            DatabaseDestination kind = DatabaseDestination.effective(email.getDatabase().getDatabaseDestination());
            if (kind == DatabaseDestination.BOTH) return ResponseEntity.status(409).body("Profil legacy harus dipisahkan sebelum diedit.");
            if (!ContactEmailCategory.isValidEmail(cleanEmail)) return ResponseEntity.badRequest().body("Format email tidak valid.");
            List<DatabaseEmail> owners = databaseEmailRepository.findAllByEmailIgnoreCase(cleanEmail);
            if (owners.stream().anyMatch(existing -> existing.getDatabase() == null)) return ResponseEntity.badRequest().body("Pemilik email belum dapat dipastikan; tinjau data lama.");
            boolean duplicate = owners.stream()
                    .filter(existing -> DatabaseDestination.isExact(existing.getDatabase().getDatabaseDestination(), kind))
                    .filter(existing -> !existing.getId().equals(emailId))
                    .anyMatch(existing -> personalEmail || kind == DatabaseDestination.PARTICIPANT || existing.getDatabase().getId().equals(databaseId));
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

        DatabaseEmail email = databaseEmailRepository.findById(emailId).orElse(null);
        if (email == null || email.getDatabase() == null || !databaseId.equals(email.getDatabase().getId())) return ResponseEntity.notFound().build();
        if (email.getDatabase().getDatabaseDestination() == DatabaseDestination.BOTH) return ResponseEntity.status(409).body("Profil legacy harus dipisahkan sebelum diedit.");
        databaseEmailRepository.deleteById(emailId);
        return ResponseEntity.noContent().build();
    }

    private String participantOrganizationError(Database database) {
        if (database.getOrganizationGroupName() != null && database.getOrganizationGroupName().codePointCount(0, database.getOrganizationGroupName().length()) > 255) return "Nama Group/Holding Company maksimal 255 karakter.";
        if (database.getOrganizationBrandName() != null && database.getOrganizationBrandName().codePointCount(0, database.getOrganizationBrandName().length()) > 255) return "Nama Brand maksimal 255 karakter.";
        if (database.getOrganizationOfficePhone() != null && database.getOrganizationOfficePhone().codePointCount(0, database.getOrganizationOfficePhone().length()) > 50) return "Office Phone maksimal 50 karakter.";
        if (database.getOrganizationIndustry() != null && database.getOrganizationIndustry().codePointCount(0, database.getOrganizationIndustry().length()) > 100) return "Industry maksimal 100 karakter.";
        if (database.getOrganizationSizeRevenue() != null && database.getOrganizationSizeRevenue().codePointCount(0, database.getOrganizationSizeRevenue().length()) > 100) return "Company Size (Revenue) maksimal 100 karakter.";
        if (database.getOrganizationSizeEmployee() != null && database.getOrganizationSizeEmployee().codePointCount(0, database.getOrganizationSizeEmployee().length()) > 100) return "Company Size (Employee) maksimal 100 karakter.";
        if (database.getOrganizationBranchName() != null && database.getOrganizationBranchName().codePointCount(0, database.getOrganizationBranchName().length()) > 255) return "Cabang/Kantor maksimal 255 karakter.";
        if (database.getOrganizationPostalCode() != null && database.getOrganizationPostalCode().codePointCount(0, database.getOrganizationPostalCode().length()) > 20) return "Postal Code maksimal 20 karakter.";
        return null;
    }

    private void copyParticipantOrganization(Database incoming, Database target, boolean fillBlanksOnly) {
        if (incoming.getOrganizationGroupName() != null && (!fillBlanksOnly || isBlank(target.getOrganizationGroupName()))) target.setOrganizationGroupName(incoming.getOrganizationGroupName().trim());
        if (incoming.getOrganizationBrandName() != null && (!fillBlanksOnly || isBlank(target.getOrganizationBrandName()))) target.setOrganizationBrandName(incoming.getOrganizationBrandName().trim());
        if (incoming.getOrganizationAddress() != null && (!fillBlanksOnly || isBlank(target.getOrganizationAddress()))) target.setOrganizationAddress(incoming.getOrganizationAddress().trim());
        if (incoming.getOrganizationOfficePhone() != null && (!fillBlanksOnly || isBlank(target.getOrganizationOfficePhone()))) target.setOrganizationOfficePhone(incoming.getOrganizationOfficePhone().trim());
        if (incoming.getOrganizationIndustry() != null && (!fillBlanksOnly || isBlank(target.getOrganizationIndustry()))) target.setOrganizationIndustry(incoming.getOrganizationIndustry().trim());
        if (incoming.getOrganizationSizeRevenue() != null && (!fillBlanksOnly || isBlank(target.getOrganizationSizeRevenue()))) target.setOrganizationSizeRevenue(incoming.getOrganizationSizeRevenue().trim());
        if (incoming.getOrganizationSizeEmployee() != null && (!fillBlanksOnly || isBlank(target.getOrganizationSizeEmployee()))) target.setOrganizationSizeEmployee(incoming.getOrganizationSizeEmployee().trim());
        if (incoming.getOrganizationBranchName() != null && (!fillBlanksOnly || isBlank(target.getOrganizationBranchName()))) target.setOrganizationBranchName(incoming.getOrganizationBranchName().trim());
        if (incoming.getOrganizationPostalCode() != null && (!fillBlanksOnly || isBlank(target.getOrganizationPostalCode()))) target.setOrganizationPostalCode(incoming.getOrganizationPostalCode().trim());
        if (incoming.getOrganizationWebsite() != null && (!fillBlanksOnly || isBlank(target.getOrganizationWebsite()))) target.setOrganizationWebsite(incoming.getOrganizationWebsite().trim());
    }

    private String participantMetadataError(Database database) {
        if (database.getInstitutionName() != null && database.getInstitutionName().codePointCount(0, database.getInstitutionName().length()) > 255) {
            return "Nama sekolah/kampus/instansi maksimal 255 karakter";
        }
        String category = database.getParticipantCategory();
        if (category != null && !category.isBlank() && !Set.of("pelajar", "mahasiswa", "umum").contains(category)) {
            return "Kategori peserta harus pelajar, mahasiswa, atau umum";
        }
        return null;
    }

    private boolean matchesDestination(Database database, String destination) {
        DatabaseDestination assigned = DatabaseDestination.effective(database.getDatabaseDestination());
        String selected = destination == null ? "all" : destination.trim().toLowerCase(Locale.ROOT);
        return switch (selected) {
            case "participant" -> assigned.includesParticipant();
            case "company" -> assigned.includesCompany();
            default -> assigned != DatabaseDestination.BOTH;
        };
    }

    private boolean matchesTab(Database database, String tab, String destination) {
        String normalizedTab = tab == null ? "all" : tab.trim().toLowerCase(Locale.ROOT);
        boolean incomplete = isIncompleteForDestination(database, destination);
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
        Company company = database.getCompany();
        String searchable = String.join(" ", safe(database.getFirstName()), safe(database.getLastName()),
                safe(database.getJobTitle()), safe(database.getPositionLevel() != null ? database.getPositionLevel().getValue() : null),
                safe(database.getSpecialityDivision()), safe(database.getMobilePhone()), safe(database.getNormalizedPhone()),
                database.getEmails() == null ? "" : database.getEmails().stream().filter(java.util.Objects::nonNull)
                        .map(address -> safe(address.getEmail())).collect(Collectors.joining(" ")),
                safe(database.getInstitutionName()), safe(database.getParticipantCategory()),
                safe(database.getOrganizationGroupName()),
                safe(database.getOrganizationBrandName()),
                safe(database.getOrganizationAddress()),
                safe(database.getOrganizationOfficePhone()),
                safe(database.getOrganizationIndustry()),
                safe(database.getOrganizationSizeRevenue()),
                safe(database.getOrganizationSizeEmployee()),
                safe(database.getOrganizationBranchName()),
                safe(database.getOrganizationPostalCode()),
                safe(database.getOrganizationWebsite()),
                safe(database.getSource() != null ? database.getSource().name() : null),
                safe(company != null ? company.getName() : null), safe(company != null ? company.getBrandName() : null),
                safe(company != null && company.getGroup() != null ? company.getGroup().getName() : null),
                safe(database.getBranch() != null ? database.getBranch().getName() : null),
                safe(contactCity(database)), safe(contactAddress(database)), safe(contactOfficePhone(database)))
                .toLowerCase(Locale.ROOT);
        return java.util.Arrays.stream(search.trim().toLowerCase(Locale.ROOT).split("\\s+"))
                .allMatch(searchable::contains);
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

    private boolean matchesBranch(Database database, Long branchId) {
        return branchId == null || (database.getBranch() != null && branchId.equals(database.getBranch().getId()));
    }

    private String contactCity(Database database) {
        return database.getBranch() != null ? database.getBranch().getCity() : database.getCompany() != null ? database.getCompany().getCity() : null;
    }

    private String contactAddress(Database database) {
        return database.getBranch() != null ? database.getBranch().getAddress() : database.getCompany() != null ? database.getCompany().getAddress() : null;
    }

    private String contactOfficePhone(Database database) {
        return database.getBranch() != null ? database.getBranch().getOfficePhone() : database.getCompany() != null ? database.getCompany().getOfficePhone() : null;
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
        return !databaseIndustry.isBlank() && databaseIndustry.equals(targetIndustry);
    }

    private boolean matchesCity(Database database, String city) {
        if (city == null || city.isBlank()) return true;
        return normalizeCity(contactCity(database))
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
        boolean participant = DatabaseDestination.isExact(database.getDatabaseDestination(), DatabaseDestination.PARTICIPANT);
        return switch (field) {
            case "firstName" -> safe(database.getFirstName()).toLowerCase(Locale.ROOT);
            case "lastName" -> safe(database.getLastName()).toLowerCase(Locale.ROOT);
            case "companyName", "institutionName" -> safe(participant ? database.getInstitutionName() : database.getCompany() != null ? database.getCompany().getName() : null).toLowerCase(Locale.ROOT);
            case "groupName", "organizationGroupName" -> safe(participant ? database.getOrganizationGroupName() : database.getCompany() != null && database.getCompany().getGroup() != null ? database.getCompany().getGroup().getName() : null).toLowerCase(Locale.ROOT);
            case "brandName", "organizationBrandName" -> safe(participant ? database.getOrganizationBrandName() : database.getCompany() != null ? database.getCompany().getBrandName() : null).toLowerCase(Locale.ROOT);
            case "jobTitle" -> safe(database.getJobTitle()).toLowerCase(Locale.ROOT);
            case "position" -> safe(database.getPositionLevel() != null ? database.getPositionLevel().getValue() : null).toLowerCase(Locale.ROOT);
            case "industry", "organizationIndustry" -> safe(participant ? database.getOrganizationIndustry() : database.getCompany() != null ? database.getCompany().getIndustry() : null).toLowerCase(Locale.ROOT);
            case "city" -> safe(contactCity(database)).toLowerCase(Locale.ROOT);
            default -> String.valueOf(database.getId() == null ? 0L : database.getId());
        };
    }

    private Map<String, Object> databaseAnalytics(List<Database> databases, String destination) {
        long dirty = 0;
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (Database database : databases) {
            if (isIncompleteForDestination(database, destination)) dirty++;
            String label = industryFilterLabel(DatabaseDestination.isExact(database.getDatabaseDestination(), DatabaseDestination.PARTICIPANT)
                    ? database.getOrganizationIndustry() : database.getCompany() != null ? database.getCompany().getIndustry() : null);
            if (label.isBlank()) label = "Belum diisi";
            String key = label.toLowerCase(Locale.ROOT);
            if (key.equals("lainnya")) label = "Lainnya";
            counts.merge(key, 1L, Long::sum);
            labels.merge(key, label, (first, second) -> first.compareTo(second) <= 0 ? first : second);
        }

        List<Map.Entry<String, Long>> ranked = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .toList();
        Map<String, Long> displayed = new LinkedHashMap<>();
        ranked.stream().limit(6).forEach(entry -> displayed.put(labels.get(entry.getKey()), entry.getValue()));
        long remaining = ranked.stream().skip(6).mapToLong(Map.Entry::getValue).sum();
        if (remaining > 0) displayed.merge("Lainnya", remaining, Long::sum);

        List<Map<String, Object>> otherIndustries = ranked.stream().skip(6)
                .map(entry -> Map.<String, Object>of("name", labels.get(entry.getKey()), "count", entry.getValue()))
                .collect(Collectors.toList());
        if (remaining > 0 && ranked.stream().limit(6).anyMatch(entry -> entry.getKey().equals("lainnya"))) {
            otherIndustries.add(0, Map.of("name", "Lainnya", "count", counts.get("lainnya")));
        }

        List<Map<String, Object>> industries = displayed.entrySet().stream()
                .map(entry -> Map.<String, Object>of("name", entry.getKey(), "count", entry.getValue()))
                .toList();
        return Map.of("total", databases.size(), "clean", databases.size() - dirty, "dirty", dirty,
                "industries", industries, "otherIndustries", otherIndustries);
    }

    private boolean participantIncomplete(Database database) {
        return database == null || (isBlank(database.getFirstName()) && isBlank(database.getLastName()))
                || (!ContactEmailCategory.hasValidEmail(database) && !ContactPhone.isUsable(database.getMobilePhone()));
    }

    private boolean isIncompleteForDestination(Database database, String destination) {
        return "participant".equalsIgnoreCase(safe(destination).trim())
                ? participantIncomplete(database) : isIncomplete(database);
    }

    private boolean isIncomplete(Database database) {
        if (database == null) return true;
        if (ContactEmailCategory.isParticipantOnly(database)) return participantIncomplete(database);
        if (isBlank(database.getCompany() != null && database.getCompany().getGroup() != null ? database.getCompany().getGroup().getName() : null)) return true;
        if (isBlank(database.getCompany() != null ? database.getCompany().getBrandName() : null)) return true;
        if (isBlank(database.getCompany() != null ? database.getCompany().getName() : null)) return true;
        if (isBlank(database.getSalutation())) return true;
        if (isBlank(database.getFirstName())) return true;
        if (isBlank(database.getLastName())) return true;
        if (isBlank(database.getPositionLevel() != null ? database.getPositionLevel().getValue() : null)) return true;
        if (isBlank(database.getJobTitle())) return true;
        if (isBlank(contactAddress(database))) return true;
        if (isBlank(contactOfficePhone(database))) return true;
        if (isBlank(database.getMobilePhone())) return true;
        boolean hasCompanyEmail = database.getEmails() != null && database.getEmails().stream()
                .anyMatch(ContactEmailCategory::isCompanyEmail);
        if (!hasCompanyEmail) return true;
        if (isBlank(database.getCompany() != null ? database.getCompany().getIndustry() : null)) return true;
        if (isBlank(contactCity(database))) return true;
        return isBlank(database.getCompany() != null ? database.getCompany().getWebsite() : null);
    }

    private String normalizeIndustry(String value) {
        return industryFilterLabel(value).toLowerCase(Locale.ROOT);
    }

    private String industryFilterLabel(String value) {
        String label = safe(value).replaceAll("(?U)\\s+", " ").trim()
                .replaceAll("\\s*/\\s*", "/");
        // Read-time aliases only: never reclassify ambiguous industries or update stored data.
        return switch (label.toLowerCase(Locale.ROOT)) {
            case "financial service", "financial services",
                    "financial services (banking/insurance/multifinance/fintech)" -> "Financial Services (Banking / Insurance / Multifinance / Fintech)";
            case "telecommunication", "telecommunications" -> "Telecommunication";
            case "mining/oil/gas" -> "Mining/Oil/Gas";
            case "manufacturing", "manufaktur" -> "Manufacturing";
            default -> label;
        };
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
