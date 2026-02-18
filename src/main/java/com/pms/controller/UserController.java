// package com.pms.controller;

// import com.pms.domain.AppUser;
// import com.pms.domain.Role;
// import com.pms.repository.UserRepository;
// import org.springframework.http.HttpStatus;
// import org.springframework.http.ResponseEntity;
// import org.springframework.security.core.Authentication; // Needed for
// security checks
// import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
// import org.springframework.web.bind.annotation.*;
// import org.springframework.web.server.ResponseStatusException;

// import java.util.*;
// import java.util.stream.Collectors;

// @RestController
// @RequestMapping("/api/users")
// public class UserController {

// private final UserRepository users;
// // Keep the encoder as you had it
// private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16,
// 32, 1, 1 << 14, 3);

// public UserController(UserRepository users) {
// this.users = users;
// }

// // --- DTO Definition (Moved here) ---
// record UserDto(Long id, String name, String username, String email, Set<Role>
// roles, boolean active, boolean approved, String dob) {}

// private UserDto convertToDto(AppUser u) {
// return new UserDto(
// u.getId(),
// u.getName(),
// u.getUsername(),
// u.getEmail(),
// u.getRoles(),
// u.isActive(),
// u.isApproved(),
// u.getDob() != null ? u.getDob().toString() : null
// );
// }

// // --- Endpoints ---

// @GetMapping
// public List<UserDto> list() {
// // You might want to filter this? Usually only Admins should see the WHOLE
// list.
// return
// users.findAll().stream().map(this::convertToDto).collect(Collectors.toList());
// }

// // Kept this useful endpoint
// @GetMapping("/available")
// public List<UserDto> listAvailable() {
// return
// users.findUsersNotOnTeam().stream().map(this::convertToDto).collect(Collectors.toList());
// }

// // Combined Create/Register Endpoint
// @PostMapping
// public ResponseEntity<?> create(@RequestBody Map<String, Object> body,
// Authentication auth) {
// try {
// // 1. Basic Extraction
// String name = String.valueOf(body.getOrDefault("name", "")).trim();
// String email = String.valueOf(body.getOrDefault("email",
// "")).trim().toLowerCase();
// String password = String.valueOf(body.getOrDefault("password", ""));

// // Auto-generate username from email if missing
// String username = String.valueOf(body.getOrDefault("username",
// "")).trim().toLowerCase();
// if (username.isEmpty() && !email.isEmpty() && email.contains("@")) {
// username = email.substring(0, email.indexOf('@')).replaceAll("[^a-z0-9]",
// "");
// }
// if (username.isEmpty()) username = "user";

// // 2. Validation
// if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
// return ResponseEntity.badRequest().body(Map.of("error", "Name, email, and
// password are required"));
// }
// if (users.existsByEmail(email)) return
// ResponseEntity.badRequest().body(Map.of("error", "Email already in use"));

// // Handle unique username collision
// if (users.existsByUsername(username)) {
// String base = username;
// int i = 1;
// while (users.existsByUsername(base + i)) i++;
// username = base + i;
// }

// // 3. Security: Role & Status Handling
// // If the requester is NOT an admin (or is anonymous/registering), force
// defaults.
// boolean isAdmin = isAdmin(auth);

// boolean active = isAdmin ? parseBoolean(body.get("active")) : true; //
// Default to active? Or require approval?
// boolean approved = isAdmin ? parseBoolean(body.get("approved")) : false; //
// Self-registered usually need approval
// Set<Role> roles;

// if (isAdmin) {
// roles = parseRolesFlexible(body.get("roles")); // Admin can assign roles
// } else {
// roles = Set.of(Role.USER); // Regular users are ALWAYS just USER
// }
// if (roles == null || roles.isEmpty()) roles = Set.of(Role.USER);

// // 4. Save
// AppUser u = AppUser.builder()
// .name(name)
// .email(email)
// .username(username)
// .passwordHash(encoder.encode(password))
// .roles(roles)
// .active(active)
// .approved(approved)
// .build();

// users.save(u);
// return ResponseEntity.ok().build();

// } catch (Exception e) {
// return ResponseEntity.badRequest().body(Map.of("error", "Invalid request: " +
// e.getMessage()));
// }
// }

// @PatchMapping("/{id}/roles")
// public ResponseEntity<?> updateRoles(@PathVariable Long id, @RequestBody
// Map<String, Object> body, Authentication auth) {
// // STRICT SECURITY CHECK: Only Admins can change roles
// if (!isAdmin(auth)) return ResponseEntity.status(403).body("Only Admins can
// change roles");

// Object rolesObj = body.containsKey("roles") ? body.get("roles") :
// body.get("role");
// Set<Role> newRoles = parseRolesFlexible(rolesObj);

// return users.findById(id).map(u -> {
// if (newRoles != null && !newRoles.isEmpty()) {
// u.setRoles(newRoles);
// users.save(u);
// }
// return ResponseEntity.ok().build();
// }).orElse(ResponseEntity.notFound().build());
// }

// @PostMapping("/{id}/reset-password")
// public ResponseEntity<?> resetPassword(@PathVariable Long id, @RequestBody
// Map<String, Object> body, Authentication auth) {
// // SECURITY: Allow if Admin OR if the user is resetting their OWN password
// if (!hasAccessToUser(auth, id)) return
// ResponseEntity.status(403).body("Access Denied");

// String newPassword = String.valueOf(body.getOrDefault("password", ""));
// if (newPassword.length() < 6) return
// ResponseEntity.badRequest().body(Map.of("error", "Password too short"));

// return users.findById(id).map(u -> {
// u.setPasswordHash(encoder.encode(newPassword));
// users.save(u);
// return ResponseEntity.ok().build();
// }).orElse(ResponseEntity.notFound().build());
// }

// // --- Helpers ---

// private boolean isAdmin(Authentication auth) {
// if (auth == null) return false;
// return auth.getAuthorities().stream()
// .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") ||
// a.getAuthority().equals("ADMIN"));
// }

// // Checks if the requester is an Admin OR the user themselves
// private boolean hasAccessToUser(Authentication auth, Long targetUserId) {
// if (auth == null) return false;
// if (isAdmin(auth)) return true;

// // Find the current logged in user's ID (Assuming Principal is AppUser or
// UserDetails)
// // You might need to adjust this based on how your UserDetails service is set
// up
// // Example: return ((AppUser)
// auth.getPrincipal()).getId().equals(targetUserId);

// // For now, fail-safe to Admin only if you aren't sure about principal
// mapping
// return false;
// }

// private boolean parseBoolean(Object value) {
// if (value instanceof Boolean b) return b;
// if (value == null) return false;
// String s = String.valueOf(value).trim().toLowerCase();
// return Set.of("true", "1", "yes", "y").contains(s);
// }

// @SuppressWarnings("unchecked")
// private Set<Role> parseRolesFlexible(Object value) {
// try {
// if (value == null) return null;
// if (value instanceof List<?> list) {
// return
// list.stream().map(String::valueOf).map(this::parseRole).filter(Objects::nonNull).collect(Collectors.toSet());
// }
// if (value instanceof String s) {
// return
// Arrays.stream(s.split(",")).map(this::parseRole).filter(Objects::nonNull).collect(Collectors.toSet());
// }
// return null;
// } catch (Exception e) { return null; }
// }

// private Role parseRole(String s) {
// try {
// String clean = s.trim().toUpperCase();
// if (clean.isEmpty()) return null;
// return Role.valueOf(clean);
// } catch (IllegalArgumentException e) { return null; }
// }
// }