package com.crm.controller;

import com.crm.domain.AppUser;
import com.crm.domain.SessionToken;
import com.crm.repository.SessionTokenRepository;
import com.crm.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        if (request.getUsername() == null || request.getUsername().trim().isEmpty() ||
            request.getEmail() == null || request.getEmail().trim().isEmpty() ||
            request.getPassword() == null || request.getPassword().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Username, email, and password are required");
        }

        String username = request.getUsername().trim();
        String email = request.getEmail().trim();

        if (userRepository.findByUsernameIgnoreCase(username).isPresent()) {
            return ResponseEntity.badRequest().body("Username is already taken");
        }

        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            return ResponseEntity.badRequest().body("Email is already registered");
        }

        AppUser user = AppUser.builder()
                .username(username)
                .email(email)
                .fullName(request.getFullName() != null ? request.getFullName().trim() : username)
                .password(passwordEncoder.encode(request.getPassword()))
                .build();

        AppUser savedUser = userRepository.save(user);
        
        // Hide password in response
        savedUser.setPassword(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedUser);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        String identifier = request.getUsernameOrEmail();
        String rawPassword = request.getPassword();

        if (identifier == null || identifier.trim().isEmpty() ||
            rawPassword == null || rawPassword.isEmpty()) {
            return ResponseEntity.badRequest().body("Username/Email and password are required");
        }

        identifier = identifier.trim();
        Optional<AppUser> userOpt;

        if (identifier.contains("@")) {
            userOpt = userRepository.findByEmail(identifier);
        } else {
            userOpt = userRepository.findByUsername(identifier);
        }

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid username/email or password");
        }

        AppUser user = userOpt.get();

        // Check password matching
        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid username/email or password");
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
                .userId(user.getId())
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
            Long tokenLong = Long.parseLong(tokenStr.trim());
            if (sessionTokenRepository.existsById(tokenLong)) {
                sessionTokenRepository.deleteById(tokenLong);
                return ResponseEntity.ok("Logged out successfully");
            } else {
                return ResponseEntity.ok("Session not found or already logged out");
            }
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body("Invalid token format");
        }
    }

    @lombok.Data
    public static class RegisterRequest {
        private String username;
        private String email;
        private String fullName;
        private String password;
    }

    @lombok.Data
    public static class LoginRequest {
        private String usernameOrEmail;
        private String password;
    }

    @lombok.Data
    @lombok.Builder
    public static class LoginResponse {
        private String token;
        private LocalDateTime expiresAt;
        private Long userId;
        private String username;
        private String email;
        private String fullName;
    }
}
