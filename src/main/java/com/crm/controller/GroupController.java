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
import java.util.Optional;

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
}
