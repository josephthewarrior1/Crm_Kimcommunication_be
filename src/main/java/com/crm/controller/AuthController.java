package com.crm.controller;

import com.crm.domain.AppUser;
import com.crm.domain.SessionToken;
import com.crm.repository.SessionTokenRepository;
import com.crm.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SessionTokenRepository sessionTokenRepository;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        String identifier = request.getUsernameOrEmail();
        if (identifier == null || identifier.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("usernameOrEmail is required");
        }

        identifier = identifier.trim();
        AppUser user;
        
        if (identifier.contains("@")) {
            // Identifier is email
            Optional<AppUser> existing = userRepository.findByEmail(identifier);
            if (existing.isPresent()) {
                user = existing.get();
            } else {
                // Auto register
                String username = identifier.split("@")[0];
                if (userRepository.findByUsername(username).isPresent()) {
                    username = username + "_" + UUID.randomUUID().toString().substring(0, 4);
                }
                user = AppUser.builder()
                        .username(username)
                        .email(identifier)
                        .fullName(username)
                        .build();
                user = userRepository.save(user);
            }
        } else {
            // Identifier is username
            Optional<AppUser> existing = userRepository.findByUsername(identifier);
            if (existing.isPresent()) {
                user = existing.get();
            } else {
                // Auto register
                user = AppUser.builder()
                        .username(identifier)
                        .email(identifier + "@example.com")
                        .fullName(identifier)
                        .build();
                user = userRepository.save(user);
            }
        }

        // Create Session Token
        SessionToken sessionToken = SessionToken.builder()
                .user(user)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
        SessionToken savedToken = sessionTokenRepository.save(sessionToken);

        return ResponseEntity.ok(LoginResponse.builder()
                .token(savedToken.getId().toString())
                .expiresAt(savedToken.getExpiresAt())
                .username(user.getUsername())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .build());
    }

    @PostMapping("/logout")
    public ResponseEntity<String> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(required = false) String token) {
        
        String tokenStr = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            tokenStr = authHeader.substring(7);
        } else if (token != null) {
            tokenStr = token;
        }

        if (tokenStr == null || tokenStr.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Token is required in Authorization header or query param");
        }

        try {
            UUID tokenUuid = UUID.fromString(tokenStr.trim());
            if (sessionTokenRepository.existsById(tokenUuid)) {
                sessionTokenRepository.deleteById(tokenUuid);
                return ResponseEntity.ok("Logged out successfully");
            } else {
                return ResponseEntity.ok("Session not found or already logged out");
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid token format");
        }
    }

    @lombok.Data
    public static class LoginRequest {
        private String usernameOrEmail;
    }

    @lombok.Data
    @lombok.Builder
    public static class LoginResponse {
        private String token;
        private LocalDateTime expiresAt;
        private String username;
        private String email;
        private String fullName;
    }
}
