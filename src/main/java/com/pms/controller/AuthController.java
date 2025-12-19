package com.pms.controller;

import com.pms.domain.AppUser;
import com.pms.domain.Role;
import com.pms.domain.SessionToken;
import com.pms.repository.SessionRepository;
import com.pms.repository.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserRepository users;
    private final SessionRepository sessions;
    private final org.springframework.security.crypto.argon2.Argon2PasswordEncoder encoder;

    public AuthController(UserRepository users, SessionRepository sessions) {
        this.users = users;
        this.sessions = sessions;
        this.encoder = new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16, 32, 1, 1 << 14, 3);
    }

    record RegisterRequest(@NotBlank String name, @NotBlank String username, @Email @NotBlank String email, @NotBlank String password, @NotBlank String confirmPassword, @NotBlank String dob) {}
    record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {
        if (users.existsByEmail(req.email())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email already in use"));
        }
        if (users.existsByUsername(req.username())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username already in use"));
        }
        if (!req.password().equals(req.confirmPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Passwords do not match"));
        }
        if (!isStrongPassword(req.password())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Password must be 8+ chars with uppercase, number and symbol"
            ));
        }
        LocalDate parsedDob;
        try {
            parsedDob = LocalDate.parse(req.dob());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid date of birth format (expected YYYY-MM-DD)"));
        }
        AppUser u = AppUser.builder()
                .name(req.name())
                .email(req.email().toLowerCase())
                .username(req.username().toLowerCase())
                .passwordHash(encoder.encode(req.password()))
                .roles(Set.of(Role.USER))
                .dob(parsedDob)
                .active(true)
                .approved(false)
                .build();
        users.save(u);
        return ResponseEntity.created(URI.create("/api/users/" + u.getId())).build();
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        String ident = req.email().toLowerCase();
        return users.findByEmailOrUsername(ident, ident)
                .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
                .map(u -> {
                    if (!u.isActive() || !u.isApproved()) {
                        return ResponseEntity.status(403).body(Map.of("error", "Account not approved/active"));
                    }
                    String token = UUID.randomUUID().toString().replace("-", "");
                    SessionToken st = SessionToken.builder()
                            .token(token)
                            .user(u)
                            .createdAt(Instant.now())
                            .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                            .revoked(false)
                            .build();
                    sessions.save(st);
                    return ResponseEntity.ok(Map.of(
                            "token", token,
                            "user", Map.of(
                                    "id", u.getId(),
                                    "name", u.getName(),
                                    "email", u.getEmail(),
                                    "roles", u.getRoles(),
                                    "active", u.isActive(),
                                    "approved", u.isApproved()
                            )
                    ));
                })
                .orElse(ResponseEntity.status(401).body(Map.of("error", "Invalid credentials")));
    }

    private boolean isStrongPassword(String p) {
        if (p == null || p.length() < 8) return false;
        boolean hasUpper = p.chars().anyMatch(Character::isUpperCase);
        boolean hasDigit = p.chars().anyMatch(Character::isDigit);
        boolean hasSymbol = p.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch));
        return hasUpper && hasDigit && hasSymbol;
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        String token = extractToken(auth);
        if (token != null) {
            sessions.findByTokenAndRevokedFalse(token).ifPresent(st -> { st.setRevoked(true); sessions.save(st); });
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        String token = extractToken(auth);
        if (token == null) return ResponseEntity.status(401).build();
        return sessions.findByTokenAndRevokedFalse(token)
                .filter(st -> st.getExpiresAt().isAfter(Instant.now()))
                .map(st -> st.getUser())
                .map(u -> ResponseEntity.ok(Map.of(
                        "id", u.getId(),
                        "name", u.getName(),
                        "email", u.getEmail(),
                        "roles", u.getRoles(),
                        "active", u.isActive(),
                        "approved", u.isApproved()
                )))
                .orElse(ResponseEntity.status(401).build());
    }

    private String extractToken(String auth) {
        if (auth == null) return null;
        if (auth.startsWith("Bearer ")) return auth.substring(7);
        return null;
    }
}
