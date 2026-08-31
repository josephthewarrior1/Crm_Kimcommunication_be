package com.crm.controller;

import com.crm.domain.AppUser;
import com.crm.domain.Role;
import com.crm.repository.UserRepository;
import com.crm.service.SecurityHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SecurityHelper securityHelper;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @GetMapping
    public ResponseEntity<?> getAllUsers(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN users can manage accounts");
        }
        
        List<AppUser> users = userRepository.findAll();
        // Hide password hashes
        users.forEach(u -> u.setPassword(null));
        return ResponseEntity.ok(users);
    }

    @GetMapping("/list")
    public ResponseEntity<?> getUsersList(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String role,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN users can manage accounts");
        }

        int safePage = page == null || page < 1 ? 1 : page;
        int safeSize = size == null || size < 1 ? 10 : Math.min(size, 100);

        List<AppUser> filteredUsers = userRepository.findAll().stream()
                .filter(user -> matchesUserSearch(user, search))
                .filter(user -> matchesUserRole(user, role))
                .sorted(Comparator.comparing(AppUser::getId))
                .toList();

        List<AppUser> items = filteredUsers.stream()
                .skip((long) (safePage - 1) * safeSize)
                .limit(safeSize)
                .map(user -> {
                    user.setPassword(null);
                    return user;
                })
                .toList();

        return ResponseEntity.ok(Map.of(
                "items", items,
                "page", safePage,
                "size", safeSize,
                "total", filteredUsers.size(),
                "totalPages", filteredUsers.isEmpty() ? 1 : (int) Math.ceil((double) filteredUsers.size() / safeSize)
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getUserById(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN) && !currentUser.getId().equals(id)) {
            return ResponseEntity.status(403).body("Forbidden: You can only view your own account");
        }

        return userRepository.findById(id).map(user -> {
            user.setPassword(null);
            return ResponseEntity.ok(user);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/role")
    public ResponseEntity<?> updateUserRole(
            @PathVariable Long id,
            @RequestParam String role,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN users can manage roles");
        }

        // Prevent self-demotion
        if (currentUser.getId().equals(id)) {
            return ResponseEntity.badRequest().body("Admin cannot modify their own roles");
        }

        try {
            Role targetRole = Role.valueOf(role.toUpperCase().trim());
            return userRepository.findById(id).map(user -> {
                Set<Role> roles = new HashSet<>();
                roles.add(targetRole);
                user.setRoles(roles);
                AppUser saved = userRepository.save(user);
                saved.setPassword(null);
                return ResponseEntity.ok(saved);
            }).orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid role. Supported: ADMIN, MANAGER, USER");
        }
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<?> updateUserPassword(
            @PathVariable Long id,
            @RequestBody UpdatePasswordRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN users can reset passwords");
        }

        if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Password cannot be empty");
        }

        return userRepository.findById(id).map(user -> {
            user.setPassword(passwordEncoder.encode(request.getPassword().trim()));
            AppUser saved = userRepository.save(user);
            saved.setPassword(null);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN users can delete accounts");
        }

        // Prevent self-deletion
        if (currentUser.getId().equals(id)) {
            return ResponseEntity.badRequest().body("Admin cannot delete their own account");
        }

        if (userRepository.existsById(id)) {
            userRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    @PutMapping("/{id}/allowed-events")
    public ResponseEntity<?> updateUserAllowedEvents(
            @PathVariable Long id,
            @RequestBody Set<Long> eventIds,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN users can manage event permissions");
        }

        return userRepository.findById(id).map(user -> {
            user.setAllowedEventIds(eventIds != null ? eventIds : new HashSet<>());
            AppUser saved = userRepository.save(user);
            saved.setPassword(null);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/profile")
    public ResponseEntity<?> updateUserProfile(
            @PathVariable Long id,
            @RequestBody UpdateProfileRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN users can update user profiles");
        }

        return userRepository.findById(id).map(user -> {
            if (request.getUsername() != null) {
                String cleanUsername = request.getUsername().trim();
                if (cleanUsername.isEmpty()) {
                    return ResponseEntity.badRequest().body("Username cannot be empty");
                }
                if (userRepository.findByUsernameIgnoreCase(cleanUsername)
                        .filter(existing -> !existing.getId().equals(id))
                        .isPresent()) {
                    return ResponseEntity.badRequest().body("Username already exists");
                }
                user.setUsername(cleanUsername);
            }
            if (request.getFullName() != null) {
                user.setFullName(request.getFullName().trim());
            }
            if (request.getEmail() != null) {
                String cleanEmail = request.getEmail().trim();
                if (cleanEmail.isEmpty()) {
                    return ResponseEntity.badRequest().body("Email cannot be empty");
                }
                if (userRepository.findByEmailIgnoreCase(cleanEmail)
                        .filter(existing -> !existing.getId().equals(id))
                        .isPresent()) {
                    return ResponseEntity.badRequest().body("Email already exists");
                }
                user.setEmail(cleanEmail);
            }
            AppUser saved = userRepository.save(user);
            saved.setPassword(null);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @lombok.Data
    public static class UpdateProfileRequest {
        private String username;
        private String fullName;
        private String email;
    }

    @lombok.Data
    public static class UpdatePasswordRequest {
        private String password;
    }

    private boolean matchesUserSearch(AppUser user, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }
        String query = search.trim().toLowerCase(Locale.ROOT);
        return safe(user.getUsername()).toLowerCase(Locale.ROOT).contains(query)
                || safe(user.getFullName()).toLowerCase(Locale.ROOT).contains(query)
                || safe(user.getEmail()).toLowerCase(Locale.ROOT).contains(query);
    }

    private boolean matchesUserRole(AppUser user, String role) {
        if (role == null || role.isBlank() || "ALL".equalsIgnoreCase(role)) {
            return true;
        }
        return user.getRoles() != null && user.getRoles().stream()
                .anyMatch(existingRole -> existingRole.name().equalsIgnoreCase(role.trim()));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
