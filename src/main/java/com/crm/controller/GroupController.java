package com.crm.controller;

import com.crm.domain.Group;
import com.crm.domain.Role;
import com.crm.domain.AppUser;
import com.crm.repository.GroupRepository;
import com.crm.repository.CompanyRepository;
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
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private SecurityHelper securityHelper;

    @GetMapping
    public ResponseEntity<?> getAllGroups(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        return ResponseEntity.ok(groupRepository.findAll());
    }

    @GetMapping("/list")
    public ResponseEntity<?> getGroupsList(
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "id") String sortBy,
            @RequestParam(required = false, defaultValue = "asc") String sortOrder,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        Map<Long, Long> companyCounts = companyRepository.findAll().stream()
                .filter(company -> company.getGroup() != null && company.getGroup().getId() != null)
                .collect(Collectors.groupingBy(company -> company.getGroup().getId(), Collectors.counting()));

        Comparator<Group> comparator = getGroupComparator(sortBy, companyCounts);
        if ("desc".equalsIgnoreCase(sortOrder)) {
            comparator = comparator.reversed();
        }

        List<Map<String, Object>> filtered = groupRepository.findAll().stream()
                .filter(group -> search == null || search.isBlank() || safe(group.getName()).toLowerCase(Locale.ROOT).contains(search.trim().toLowerCase(Locale.ROOT)))
                .sorted(comparator.thenComparing(Group::getId))
                .map(group -> groupRow(group, companyCounts.getOrDefault(group.getId(), 0L)))
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

    @GetMapping("/summary")
    public ResponseEntity<?> getGroupsSummary(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        long groupedCompanies = companyRepository.findAll().stream().filter(company -> company.getGroup() != null).count();
        return ResponseEntity.ok(Map.of(
                "totalGroups", groupRepository.count(),
                "totalCompanies", companyRepository.count(),
                "groupedCompanies", groupedCompanies,
                "ungroupedCompanies", companyRepository.count() - groupedCompanies
        ));
    }

    @PostMapping
    public ResponseEntity<?> createGroup(
            @RequestBody Group group,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can create groups");
        }

        if (group.getName() == null || group.getName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Group name is required");
        }

        String cleanName = group.getName().trim();
        if (groupRepository.findByNameIgnoreCase(cleanName).isPresent()) {
            return ResponseEntity.badRequest().body("Group name already exists");
        }

        group.setName(cleanName);
        return ResponseEntity.ok(groupRepository.save(group));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getGroupById(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        return groupRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateGroup(
            @PathVariable Long id, 
            @RequestBody Group groupDetails,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can update groups");
        }

        return groupRepository.findById(id).map(existing -> {
            if (groupDetails.getName() == null || groupDetails.getName().trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Group name is required");
            }
            String cleanName = groupDetails.getName().trim();
            Optional<Group> duplicate = groupRepository.findByNameIgnoreCase(cleanName);
            if (duplicate.isPresent() && !duplicate.get().getId().equals(id)) {
                return ResponseEntity.badRequest().body("Group name already exists");
            }
            existing.setName(cleanName);
            existing.setNotes(groupDetails.getNotes());
            return ResponseEntity.ok(groupRepository.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteGroup(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN can delete groups");
        }

        if (groupRepository.existsById(id)) {
            // Nullify group_id references in companies
            companyRepository.findAll().stream()
                .filter(c -> c.getGroup() != null && c.getGroup().getId().equals(id))
                .forEach(c -> {
                    c.setGroup(null);
                    companyRepository.save(c);
                });
            groupRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private Comparator<Group> getGroupComparator(String sortBy, Map<Long, Long> companyCounts) {
        return switch (safe(sortBy).toLowerCase(Locale.ROOT)) {
            case "name" -> Comparator.comparing(group -> safe(group.getName()).toLowerCase(Locale.ROOT));
            case "companies" -> Comparator.comparing(group -> companyCounts.getOrDefault(group.getId(), 0L));
            case "created_at" -> Comparator.comparing(group -> group.getCreatedAt() != null ? group.getCreatedAt() : java.time.LocalDateTime.MIN);
            default -> Comparator.comparing(Group::getId);
        };
    }

    private Map<String, Object> groupRow(Group group, Long companyCount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", group.getId());
        row.put("name", group.getName());
        row.put("notes", group.getNotes());
        row.put("createdAt", group.getCreatedAt());
        row.put("updatedAt", group.getUpdatedAt());
        row.put("companyCount", companyCount);
        return row;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
