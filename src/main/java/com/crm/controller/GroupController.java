package com.crm.controller;

import com.crm.domain.Group;
import com.crm.repository.GroupRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    @Autowired
    private GroupRepository groupRepository;

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
        if (groupRepository.findByName(cleanName).isPresent()) {
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
}
