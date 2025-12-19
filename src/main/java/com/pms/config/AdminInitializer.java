package com.pms.config;

import com.pms.domain.AppUser;
import com.pms.domain.Role;
import com.pms.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashSet;
import java.util.Set;

@Configuration
public class AdminInitializer {

    @Value("${app.admin.username:admin}")
    private String adminUsername;
    @Value("${app.admin.email:admin@example.com}")
    private String adminEmail;
    @Value("${app.admin.password:admin}")
    private String adminPassword;

    @Bean
    CommandLineRunner ensureAdmin(UserRepository users) {
        return args -> {
            var encoder = new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16, 32, 1, 1 << 14, 3);
            var existingOpt = users.findByUsername(adminUsername);
            if (existingOpt.isEmpty()) {
                Set<Role> roles = new HashSet<>();
                roles.add(Role.ADMIN);
                AppUser admin = AppUser.builder()
                        .name("Admin User")
                        .username(adminUsername)
                        .email(adminEmail)
                        .passwordHash(encoder.encode(adminPassword))
                        .roles(roles)
                        .active(true)
                        .approved(true)
                        .build();
                users.save(admin);
            } else {
                AppUser admin = existingOpt.get();
                // ensure admin has ADMIN role, active, approved
                Set<Role> roles = admin.getRoles() != null ? new HashSet<>(admin.getRoles()) : new HashSet<>();
                roles.add(Role.ADMIN);
                admin.setRoles(roles);
                if (!admin.isActive()) admin.setActive(true);
                if (!admin.isApproved()) admin.setApproved(true);
                // Do NOT overwrite password if exists to avoid surprises
                users.save(admin);
            }
        };
    }
}

