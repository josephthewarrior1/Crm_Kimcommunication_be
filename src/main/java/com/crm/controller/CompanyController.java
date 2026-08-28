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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

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

    @GetMapping("/list")
    public ResponseEntity<?> getCompaniesList(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false, defaultValue = "id") String sortBy,
            @RequestParam(required = false, defaultValue = "asc") String sortOrder,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        Map<Long, Long> contactCounts = databaseRepository.findAll().stream()
                .filter(database -> Boolean.TRUE.equals(database.getIsActive()))
                .filter(database -> database.getCompany() != null && database.getCompany().getId() != null)
                .collect(Collectors.groupingBy(database -> database.getCompany().getId(), Collectors.counting()));

        Comparator<Company> comparator = getCompanyComparator(sortBy, contactCounts);
        if ("desc".equalsIgnoreCase(sortOrder)) {
            comparator = comparator.reversed();
        }

        List<Map<String, Object>> filtered = companyRepository.findAll().stream()
                .filter(company -> matchesCompanySearch(company, search))
                .filter(company -> groupId == null || (company.getGroup() != null && groupId.equals(company.getGroup().getId())))
                .filter(company -> matchesText(company.getIndustry(), industry))
                .sorted(comparator.thenComparing(Company::getId))
                .map(company -> companyRow(company, contactCounts.getOrDefault(company.getId(), 0L)))
                .toList();

        int safePage = page == null || page < 1 ? 1 : page;
        int safeSize = size == null || size < 1 ? 10 : Math.min(size, 100);
        int from = Math.min((safePage - 1) * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());

        return ResponseEntity.ok(Map.of(
                "items", filtered.subList(from, to),
                "page", safePage,
                "size", safeSize,
                "total", filtered.size(),
                "totalPages", Math.max(1, (int) Math.ceil(filtered.size() / (double) safeSize))
        ));
    }

    @GetMapping("/filter-options")
    public ResponseEntity<?> getCompanyFilterOptions(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        List<Company> companies = companyRepository.findAll();
        return ResponseEntity.ok(Map.of(
                "groups", groupRepository.findAll().stream()
                        .map(group -> Map.of("id", group.getId(), "name", group.getName()))
                        .toList(),
                "industries", companies.stream()
                        .map(Company::getIndustry)
                        .filter(value -> value != null && !value.isBlank())
                        .distinct()
                        .sorted(String::compareToIgnoreCase)
                        .toList()
        ));
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
            existing.setPostalCode(companyDetails.getPostalCode());

            if (groupId != null) {
                Group group = groupRepository.findById(groupId).orElse(null);
                existing.setGroup(group);
            } else if (companyDetails.getGroup() != null && companyDetails.getGroup().getId() != null) {
                Group group = groupRepository.findById(companyDetails.getGroup().getId()).orElse(null);
                existing.setGroup(group);
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

    private boolean matchesCompanySearch(Company company, String search) {
        if (search == null || search.isBlank()) return true;
        String query = search.trim().toLowerCase(Locale.ROOT);
        return safe(company.getName()).toLowerCase(Locale.ROOT).contains(query)
                || safe(company.getBrandName()).toLowerCase(Locale.ROOT).contains(query)
                || safe(company.getCity()).toLowerCase(Locale.ROOT).contains(query)
                || (company.getGroup() != null && safe(company.getGroup().getName()).toLowerCase(Locale.ROOT).contains(query));
    }

    private boolean matchesText(String value, String expected) {
        return expected == null || expected.isBlank() || safe(value).equalsIgnoreCase(expected.trim());
    }

    private Comparator<Company> getCompanyComparator(String sortBy, Map<Long, Long> contactCounts) {
        return switch (safe(sortBy).toLowerCase(Locale.ROOT)) {
            case "name" -> Comparator.comparing(company -> safe(company.getName()).toLowerCase(Locale.ROOT));
            case "brand" -> Comparator.comparing(company -> safe(company.getBrandName()).toLowerCase(Locale.ROOT));
            case "group" -> Comparator.comparing(company -> company.getGroup() != null ? safe(company.getGroup().getName()).toLowerCase(Locale.ROOT) : "");
            case "industry" -> Comparator.comparing(company -> safe(company.getIndustry()).toLowerCase(Locale.ROOT));
            case "city" -> Comparator.comparing(company -> safe(company.getCity()).toLowerCase(Locale.ROOT));
            case "contacts" -> Comparator.comparing(company -> contactCounts.getOrDefault(company.getId(), 0L));
            default -> Comparator.comparing(Company::getId);
        };
    }

    private Map<String, Object> companyRow(Company company, Long contactCount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", company.getId());
        row.put("group", company.getGroup());
        row.put("brandName", company.getBrandName());
        row.put("name", company.getName());
        row.put("address", company.getAddress());
        row.put("officePhone", company.getOfficePhone());
        row.put("website", company.getWebsite());
        row.put("industry", company.getIndustry());
        row.put("companySizeRevenue", company.getCompanySizeRevenue());
        row.put("companySizeEmployee", company.getCompanySizeEmployee());
        row.put("companyHardware", company.getCompanyHardware());
        row.put("city", company.getCity());
        row.put("postalCode", company.getPostalCode());
        row.put("createdAt", company.getCreatedAt());
        row.put("updatedAt", company.getUpdatedAt());
        row.put("contactCount", contactCount);
        return row;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
