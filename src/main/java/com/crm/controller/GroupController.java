package com.crm.controller;

import com.crm.domain.Group;
import com.crm.repository.GroupRepository;
import com.crm.repository.CompanyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @GetMapping
    public List<Group> getAllGroups() {
        return groupRepository.findAll();
    }

    @PostMapping
    public ResponseEntity<?> createGroup(@RequestBody Group group) {
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
    public ResponseEntity<Group> getGroupById(@PathVariable UUID id) {
        return groupRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateGroup(@PathVariable UUID id, @RequestBody Group groupDetails) {
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
    public ResponseEntity<?> deleteGroup(@PathVariable UUID id) {
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
