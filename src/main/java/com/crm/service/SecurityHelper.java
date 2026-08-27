package com.crm.service;

import com.crm.domain.AppUser;
import com.crm.domain.Role;
import com.crm.domain.SessionToken;
import com.crm.repository.SessionTokenRepository;
import com.crm.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class SecurityHelper {

    @Autowired
    private SessionTokenRepository sessionTokenRepository;

    @Autowired
    private UserRepository userRepository;

    public AppUser getAuthenticatedUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String tokenStr = authHeader.substring(7).trim();
        try {
            Long tokenId = Long.parseLong(tokenStr);
            Optional<SessionToken> sessionOpt = sessionTokenRepository.findById(tokenId);
            if (sessionOpt.isPresent()) {
                SessionToken session = sessionOpt.get();
                if (session.getExpiresAt().isAfter(LocalDateTime.now()) && session.getUser() != null) {
                    Long userId = session.getUser().getId();
                    if (userId != null) {
                        return userRepository.findById(userId).orElse(null);
                    }
                }
            }
        } catch (NumberFormatException e) {
            // Ignore invalid token formats
        }
        return null;
    }

    public boolean hasRole(AppUser user, Role role) {
        return user != null && user.getRoles() != null && user.getRoles().contains(role);
    }

    public boolean hasAnyRole(AppUser user, Role... roles) {
        if (user == null || user.getRoles() == null) {
            return false;
        }
        for (Role r : roles) {
            if (user.getRoles().contains(r)) {
                return true;
            }
        }
        return false;
    }
}
