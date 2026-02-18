package com.pms.controller;

import com.pms.domain.ProjectRole;
import com.pms.repository.ProjectRoleRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/roles")
public class ProjectRoleController {

    private final ProjectRoleRepository roleRepository;

    public ProjectRoleController(ProjectRoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    @GetMapping
    public List<ProjectRole> list() {
        return roleRepository.findAll();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Name is required"));
        }
        if (roleRepository.findByName(name).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Role name already exists"));
        }
        ProjectRole role = ProjectRole.builder()
                .name(name)
                .description(String.valueOf(body.getOrDefault("description", "")).trim())
                .canCreate(parseBoolean(body.get("canCreate")))
                .canRead(parseBoolean(body.get("canRead")))
                .canUpdate(parseBoolean(body.get("canUpdate")))
                .canDelete(parseBoolean(body.get("canDelete")))
                .build();
        roleRepository.save(role);
        return ResponseEntity.ok(role);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return roleRepository.findById(id).map(role -> {
            if (body.containsKey("name")) {
                String newName = String.valueOf(body.get("name")).trim();
                if (!newName.isEmpty() && !newName.equals(role.getName())) {
                    if (roleRepository.findByName(newName).isPresent()) {
                        return ResponseEntity.badRequest().body((Object) Map.of("error", "Role name already exists"));
                    }
                    role.setName(newName);
                }
            }
            if (body.containsKey("description"))
                role.setDescription(String.valueOf(body.getOrDefault("description", "")).trim());
            if (body.containsKey("canCreate"))
                role.setCanCreate(parseBoolean(body.get("canCreate")));
            if (body.containsKey("canRead"))
                role.setCanRead(parseBoolean(body.get("canRead")));
            if (body.containsKey("canUpdate"))
                role.setCanUpdate(parseBoolean(body.get("canUpdate")));
            if (body.containsKey("canDelete"))
                role.setCanDelete(parseBoolean(body.get("canDelete")));
            roleRepository.save(role);
            return ResponseEntity.ok((Object) role);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!roleRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        roleRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private boolean parseBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value == null) return false;
        String s = String.valueOf(value).trim().toLowerCase();
        return s.equals("true") || s.equals("1");
    }
}
