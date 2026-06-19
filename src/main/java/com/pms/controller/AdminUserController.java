package com.pms.controller;

import com.pms.domain.AppUser;
import com.pms.domain.EmploymentType;
import com.pms.domain.Role;
import com.pms.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserRepository users;
    private final org.springframework.security.crypto.argon2.Argon2PasswordEncoder encoder = new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(
            16, 32, 1, 1 << 14, 3);

    public AdminUserController(UserRepository users) {
        this.users = users;
    }

    record UserDto(Long id, String name, String username, String email, Set<Role> roles, boolean active,
            boolean approved, String dob, String employmentType, String phone, String location, String avatar) {
    }

    private UserDto convertToDto(AppUser u) {
        return new UserDto(
                u.getId(),
                u.getName(),
                u.getUsername(),
                u.getEmail(),
                u.getRoles(),
                u.isActive(),
                u.isApproved(),
                u.getDob() != null ? u.getDob().toString() : null,
                u.getEmploymentType() != null ? u.getEmploymentType().name() : null,
                u.getPhone(),
                u.getLocation(),
                u.getAvatar());
    }

    @GetMapping
    public List<UserDto> list() {
        return users.findAll().stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @GetMapping("/available")
    public List<UserDto> listAvailable() {
        return users.findUsersNotOnTeam().stream().map(this::convertToDto).collect(Collectors.toList());
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return users.findById(id).map(u -> {
            if (body.containsKey("active"))
                u.setActive(parseBoolean(body.get("active")));
            if (body.containsKey("approved"))
                u.setApproved(parseBoolean(body.get("approved")));
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

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return updateUser(id, body);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> patch(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return updateUser(id, body);
    }

    private ResponseEntity<?> updateUser(Long id, Map<String, Object> body) {
        try {
            AppUser u = users.findById(id).orElse(null);
            if (u == null) {
                return ResponseEntity.notFound().build();
            }

            String name = stringValue(body.get("name"));
            String username = stringValue(body.get("username")).toLowerCase();
            String email = stringValue(body.get("email")).toLowerCase();

            if (name.isBlank() || username.isBlank() || email.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "name, username, email required"));
            }

            if (users.findByEmail(email).filter(existing -> !existing.getId().equals(id)).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Email already in use"));
            }
            if (users.findByUsername(username).filter(existing -> !existing.getId().equals(id)).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Username already in use"));
            }

            u.setName(name);
            u.setUsername(username);
            u.setEmail(email);

            if (body.containsKey("dob")) {
                String dob = stringValue(body.get("dob"));
                try {
                    u.setDob(dob.isBlank() ? null : LocalDate.parse(dob));
                } catch (DateTimeParseException e) {
                    return ResponseEntity.badRequest().body(Map.of("error", "dob must use yyyy-MM-dd format"));
                }
            }

            if (body.containsKey("employmentType")) {
                String empTypeStr = stringValue(body.get("employmentType")).toUpperCase();
                if (empTypeStr.isBlank()) {
                    u.setEmploymentType(null);
                } else {
                    try {
                        u.setEmploymentType(EmploymentType.valueOf(empTypeStr));
                    } catch (IllegalArgumentException e) {
                        return ResponseEntity.badRequest().body(Map.of("error", "Invalid employmentType"));
                    }
                }
            }

            if (body.containsKey("phone")) u.setPhone(blankToNull(body.get("phone")));
            if (body.containsKey("location")) u.setLocation(blankToNull(body.get("location")));
            if (body.containsKey("avatar")) u.setAvatar(blankToNull(body.get("avatar")));
            if (body.containsKey("active")) u.setActive(parseBoolean(body.get("active")));
            if (body.containsKey("approved")) u.setApproved(parseBoolean(body.get("approved")));

            if (body.containsKey("roles")) {
                Set<Role> newRoles = parseRolesFlexible(body.get("roles"));
                if (newRoles == null || newRoles.isEmpty()) {
                    return ResponseEntity.badRequest().body(Map.of("error", "roles must include at least one valid role"));
                }
                u.setRoles(newRoles);
            }

            users.save(u);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            String message = e.getMessage() == null ? "Invalid request" : e.getMessage();
            return ResponseEntity.badRequest().body(Map.of("error", message));
        }
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
                return ResponseEntity.badRequest().body(Map.of("error", "name, username, email, password required"));
            boolean active = parseBoolean(body.get("active"));
            boolean approved = parseBoolean(body.get("approved"));
            Set<Role> roles = parseRolesFlexible(body.get("roles"));
            if (roles == null || roles.isEmpty())
                roles = new HashSet<>(Set.of(Role.USER));
            if (users.existsByEmail(email))
                return ResponseEntity.badRequest().body(Map.of("error", "Email already in use"));
            if (users.existsByUsername(username)) {
                String base = username;
                int i = 1;
                while (users.existsByUsername(base + i))
                    i++;
                username = base + i;
            }
            EmploymentType empType = null;
            String empTypeStr = String.valueOf(body.getOrDefault("employmentType", "")).trim().toUpperCase();
            if (!empTypeStr.isEmpty()) {
                try { empType = EmploymentType.valueOf(empTypeStr); } catch (Exception ignored) {}
            }
            AppUser u = AppUser.builder().name(name).email(email).username(username)
                    .passwordHash(encoder.encode(password))
                    .roles(roles).active(active).approved(approved).employmentType(empType).build();
            users.save(u);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
        }
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<?> resetPassword(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String newPassword = String.valueOf(body.getOrDefault("password", ""));
        // Relaxed policy: minimum 6 characters
        if (newPassword.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password too short"));
        }
        return users.findById(id).map(u -> {
            u.setPasswordHash(encoder.encode(newPassword));
            users.save(u);
            return ResponseEntity.ok().build();
        }).orElse(ResponseEntity.notFound().build());
    }

    private String stringValue(Object value) {
        if (value == null) return "";
        return String.valueOf(value).trim();
    }

    private String blankToNull(Object value) {
        String s = stringValue(value);
        return s.isBlank() ? null : s;
    }
    private boolean parseBoolean(Object value) {
        if (value instanceof Boolean b)
            return b;
        if (value == null)
            return false;
        String s = String.valueOf(value).trim().toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y");
    }

    @SuppressWarnings("unchecked")
    private Set<Role> parseRolesFlexible(Object value) {
        try {
            if (value == null)
                return null;
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
                if (str.isEmpty())
                    return null;
                // support comma-separated
                String[] parts = str.contains(",") ? str.split(",") : new String[] { str };
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

