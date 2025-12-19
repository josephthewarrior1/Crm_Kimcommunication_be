package com.pms.controller;

import com.pms.domain.AppUser;
import com.pms.domain.Role;
import com.pms.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserRepository users;
    private final org.springframework.security.crypto.argon2.Argon2PasswordEncoder encoder = new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16,32,1,1<<14,3);

    public AdminUserController(UserRepository users) {
        this.users = users;
    }

    record UserDto(Long id, String name, String username, String email, Set<Role> roles, boolean active, boolean approved, String dob) {}

    @GetMapping
    public List<UserDto> list() {
        return users.findAll().stream()
                .map(u -> new UserDto(u.getId(), u.getName(), u.getUsername(), u.getEmail(), u.getRoles(), u.isActive(), u.isApproved(), u.getDob() != null ? u.getDob().toString() : null))
                .collect(Collectors.toList());
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return users.findById(id).map(u -> {
            if (body.containsKey("active")) u.setActive(parseBoolean(body.get("active")));
            if (body.containsKey("approved")) u.setApproved(parseBoolean(body.get("approved")));
            users.save(u);
            return ResponseEntity.ok().build();
        }).orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/roles")
    public ResponseEntity<?> updateRoles(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object rolesObj = body.containsKey("roles") ? body.get("roles") : body.get("role");
        Set<Role> newRoles = parseRolesFlexible(rolesObj);
        if (newRoles == null || newRoles.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "roles must include at least one valid role"));
        }
        return users.findById(id).map(u -> {
            u.setRoles(newRoles);
            users.save(u);
            return ResponseEntity.ok().build();
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        try {
            String name = String.valueOf(body.getOrDefault("name", "")).trim();
            String username = String.valueOf(body.getOrDefault("username", "")).trim().toLowerCase();
            String email = String.valueOf(body.getOrDefault("email", "")).trim().toLowerCase();
            String password = String.valueOf(body.getOrDefault("password", ""));
            if (username.isEmpty() && !email.isEmpty() && email.contains("@")) {
                username = email.substring(0, email.indexOf('@')).replaceAll("[^a-z0-9]", "");
            }
            if (username.isEmpty()) {
                username = "user";
            }
            if (name.isEmpty() || username.isEmpty() || email.isEmpty() || password.isEmpty())
                return ResponseEntity.badRequest().body(Map.of("error","name, username, email, password required"));
            boolean active = parseBoolean(body.get("active"));
            boolean approved = parseBoolean(body.get("approved"));
            Set<Role> roles = parseRolesFlexible(body.get("roles"));
            if (roles == null || roles.isEmpty()) roles = java.util.Set.of(Role.USER);
            if (users.existsByEmail(email)) return ResponseEntity.badRequest().body(Map.of("error","Email already in use"));
            if (users.existsByUsername(username)) {
                String base = username;
                int i = 1;
                while (users.existsByUsername(base + i)) i++;
                username = base + i;
            }
            AppUser u = AppUser.builder().name(name).email(email).username(username)
                    .passwordHash(encoder.encode(password))
                    .roles(roles).active(active).approved(approved).build();
            users.save(u);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error","Invalid request"));
        }
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<?> resetPassword(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String newPassword = String.valueOf(body.getOrDefault("password", ""));
        // Relaxed policy: minimum 6 characters
        if (newPassword.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error","Password too short"));
        }
        return users.findById(id).map(u -> {
            u.setPasswordHash(encoder.encode(newPassword));
            users.save(u);
            return ResponseEntity.ok().build();
        }).orElse(ResponseEntity.notFound().build());
    }

    private boolean parseBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value == null) return false;
        String s = String.valueOf(value).trim().toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y");
    }

    @SuppressWarnings("unchecked")
    private Set<Role> parseRolesFlexible(Object value) {
        try {
            if (value == null) return null;
            if (value instanceof List<?> list) {
                return list.stream()
                        .map(String::valueOf)
                        .map(String::trim)
                        .filter(v -> !v.isEmpty())
                        .map(String::toUpperCase)
                        .map(Role::valueOf)
                        .collect(Collectors.toSet());
            }
            if (value instanceof String s) {
                String str = s.trim();
                if (str.isEmpty()) return null;
                // support comma-separated
                String[] parts = str.contains(",") ? str.split(",") : new String[]{str};
                return java.util.Arrays.stream(parts)
                        .map(String::trim)
                        .filter(v -> !v.isEmpty())
                        .map(String::toUpperCase)
                        .map(Role::valueOf)
                        .collect(Collectors.toSet());
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
