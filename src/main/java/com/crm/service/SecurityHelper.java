package com.crm.service;

import com.crm.domain.AppUser;
import com.crm.domain.Role;
import com.crm.domain.SessionToken;
import com.crm.repository.SessionTokenRepository;
import com.crm.repository.UserRepository;
import com.crm.tagtog.TagtogProperties;
import com.crm.tagtog.TagtogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class SecurityHelper {
    @Autowired private SessionTokenRepository sessionTokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TagtogProperties tagtogConfig;
    @Autowired private TagtogService tagtogService;

    public AppUser getAuthenticatedUser(String authHeader) {
        if (tagtogConfig != null && tagtogConfig.enabled)
            return tagtogService.authorize(TagtogService.bearer(authHeader), "GET", "/api/auth/me");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String tokenStr = authHeader.substring(7).trim();
        try {
            Long tokenId = Long.parseLong(tokenStr);
            Optional<SessionToken> sessionOpt = sessionTokenRepository.findById(tokenId);
            if (sessionOpt.isPresent()) {
                SessionToken session = sessionOpt.get();
                if (session.getExpiresAt().isAfter(LocalDateTime.now()) && session.getUser() != null) {
                    Long userId = session.getUser().getId();
                    if (userId != null) return userRepository.findById(userId).orElse(null);
                }
            }
        } catch (NumberFormatException ignored) { }
        return null;
    }
    public boolean hasRole(AppUser user, Role role) {
        return user != null && user.getRoles() != null && user.getRoles().contains(role);
    }
    public boolean hasAnyRole(AppUser user, Role... roles) {
        if (user == null || user.getRoles() == null) return false;
        for (Role role : roles) if (user.getRoles().contains(role)) return true;
        return false;
    }
}
