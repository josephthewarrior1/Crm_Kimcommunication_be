package com.crm.service;

import com.crm.domain.AppUser;
import com.crm.domain.Role;
import com.crm.domain.SessionToken;
import com.crm.repository.SessionTokenRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class SecurityHelper {

    @Autowired
    private SessionTokenRepository sessionTokenRepository;

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
                if (session.getExpiresAt().isAfter(LocalDateTime.now())) {
                    return session.getUser();
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
