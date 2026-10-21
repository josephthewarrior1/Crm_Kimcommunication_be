package com.crm.tagtog;

import com.fasterxml.jackson.databind.JsonNode;
import com.crm.domain.AppUser;
import com.crm.domain.SessionToken;
import com.crm.repository.SessionTokenRepository;
import com.crm.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class TagtogService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final TagtogProperties config;
    private final TagtogIamClient iam;
    private final TagtogIdentityRepository identities;
    private final SessionTokenRepository sessions;
    private final UserRepository users;

    public TagtogService(TagtogProperties config, TagtogIamClient iam, TagtogIdentityRepository identities,
                         SessionTokenRepository sessions, UserRepository users) {
        this.config = config; this.iam = iam; this.identities = identities;
        this.sessions = sessions; this.users = users;
    }

    @Transactional
    public Map<String, Object> exchange(String code, String verifier, String redirectUri) {
        config.requireEnabled();
        if (!Objects.equals(config.redirectUri, redirectUri) || code == null || !code.matches("[A-Za-z0-9_-]{43}")
                || verifier == null || !verifier.matches("[A-Za-z0-9._~-]{43,128}"))
            throw new TagtogException(400, "SSO_REQUEST_INVALID", "Invalid central login exchange");
        Instant started = Instant.now();
        JsonNode issued = iam.exchange(code, verifier, redirectUri);
        String subject = issued.path("user").path("id").asText();
        requireSubject(subject);
        String centralSession = issued.path("ssoSessionId").asText();
        long ttl = issued.path("expiresIn").asLong(0);
        if (!centralSession.matches("[A-Za-z0-9_-]{43}") || ttl <= 0)
            throw loginRequired();
        requirePermission(iam.check(subject, centralSession), subject, "GET", "/api/auth/me");
        var link = identities.findByIssuerAndSubject(config.issuer, subject).orElseThrow(() ->
                new TagtogException(409, "CRM_IDENTITY_UNMAPPED", "Ask a TAGTOG administrator to link your existing CRM account"));
        AppUser user = users.findById(link.getCrmUserId()).orElseThrow(TagtogService::unavailableUser);
        requireLocalUser(user);
        if (link.getLinkVersion() == null) throw loginRequired();
        Instant expires = started.plusSeconds(Math.min(ttl, 43200));
        if (!expires.isAfter(Instant.now())) throw loginRequired();
        byte[] random = new byte[32]; RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        sessions.save(SessionToken.builder().user(user).tokenHash(digest(token))
                .expiresAt(LocalDateTime.ofInstant(expires, ZoneOffset.UTC)).tagtogIssuer(config.issuer)
                .tagtogSubject(subject).tagtogSessionId(centralSession).tagtogExpiresAt(expires)
                .tagtogMappingVersion(link.getLinkVersion())
                .tagtogRevoked(false).build());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token); result.put("expiresAt", expires);
        result.put("userId", user.getId()); result.put("username", user.getUsername());
        result.put("email", user.getEmail()); result.put("fullName", user.getFullName());
        result.put("roles", user.getRoles()); result.put("allowedEventIds", user.getAllowedEventIds());
        return result;
    }

    /** Recheck IAM and the explicit local link for each protected request; outages deny access. */
    @Transactional(readOnly = true)
    public AppUser authorize(String token, String method, String path) {
        config.requireEnabled();
        if (!opaque(token)) throw loginRequired();
        SessionToken session = sessions.findByTokenHash(digest(token)).orElseThrow(TagtogService::loginRequired);
        Instant now = Instant.now();
        if (session.isTagtogRevoked() || !Objects.equals(config.issuer, session.getTagtogIssuer())
                || session.getTagtogExpiresAt() == null || !session.getTagtogExpiresAt().isAfter(now)
                || session.getExpiresAt() == null || !session.getExpiresAt().isAfter(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                || session.getTagtogSubject() == null || session.getTagtogSessionId() == null || session.getTagtogMappingVersion() == null) throw loginRequired();
        requireLocalUser(session.getUser());
        var link = identities.findByIssuerAndSubject(config.issuer, session.getTagtogSubject()).orElseThrow(TagtogService::loginRequired);
        if (!Objects.equals(link.getCrmUserId(), session.getUser().getId())
                || !Objects.equals(link.getLinkVersion(), session.getTagtogMappingVersion())) throw loginRequired();
        requirePermission(iam.check(session.getTagtogSubject(), session.getTagtogSessionId()), session.getTagtogSubject(), method, path);
        return session.getUser();
    }

    static void requirePermission(JsonNode check, String subject, String method, String path) {
        if (!check.path("active").asBoolean(false) || !subject.equals(check.path("user").path("id").asText())) throw loginRequired();
        if (!check.path("hasSystemAccess").asBoolean(false)) throw denied();
        boolean view = false, create = false, edit = false, delete = false;
        for (JsonNode permission : check.path("permissions")) {
            if (!"CRM".equals(permission.path("systemCode").asText()) || !"workspace".equals(permission.path("featureCode").asText())) continue;
            view |= permission.path("canView").asBoolean(false); create |= permission.path("canCreate").asBoolean(false);
            edit |= permission.path("canEdit").asBoolean(false); delete |= permission.path("canDelete").asBoolean(false);
        }
        boolean allowed = switch (method) {
            case "GET", "HEAD" -> view;
            case "DELETE" -> delete;
            case "PUT", "PATCH" -> {
                // These update bodies/statuses can soft-delete or restore existing contacts.
                if (path.matches("/api/databases/[1-9][0-9]*")
                        || path.matches("/api/flagged-identities/[1-9][0-9]*")
                        || path.matches("/api/removal-requests/[1-9][0-9]*/status")) yield edit && delete;
                yield edit;
            }
            case "POST" -> {
                if (path.equals("/api/databases/import/preview") || path.equals("/api/databases/merge/preview")
                        || path.matches("/api/events/[1-9][0-9]*/participants/import/validate")) yield view;
                // Merge/cleaner operations delete source records; the HTTP verb alone is insufficient.
                if (path.equals("/api/data-cleaner/apply") || path.equals("/api/data-cleaner/merge-groups")
                        || path.equals("/api/databases/merge")) yield edit && delete;
                if (path.equals("/api/removal-requests") || path.equals("/api/flagged-identities")) yield create && edit && delete;
                if (path.equals("/api/databases/import") || path.matches("/api/events/[1-9][0-9]*/participants/import")
                        || path.matches("/api/events/[1-9][0-9]*/sync-ems")) yield create && edit;
                if (path.matches("/api/events/[1-9][0-9]*/assignments/auto-split")) yield edit;
                if (path.equals("/api/companies") || path.matches("/api/companies/[1-9][0-9]*/branches")
                        || path.equals("/api/databases") || path.matches("/api/databases/[1-9][0-9]*/emails")
                        || path.equals("/api/events") || path.equals("/api/event-participants")
                        || path.matches("/api/event-participants/[1-9][0-9]*/activities") || path.equals("/api/groups")
                        || path.equals("/api/audit-logs") || path.equals("/api/personal-email-domains")) yield create;
                // New/unknown POST handlers must be audited before granting a narrower action set.
                yield create && edit && delete;
            }
            default -> false;
        };
        if (!view || !allowed) throw denied();
    }

    /** Local-only sign-out remains available even when IAM is unavailable or revoked. */
    @Transactional
    public void logout(String token) {
        config.requireEnabled();
        if (!opaque(token)) throw loginRequired();
        sessions.findByTokenHash(digest(token)).ifPresent(sessions::delete);
    }

    public void requireAdmin(String auth) { iam.requireSuperAdmin(auth); }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> users() {
        return users.findAll().stream().sorted(Comparator.comparing(AppUser::getId)).map(TagtogService::userView).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> links() {
        return identities.findAll().stream().filter(link -> Objects.equals(config.issuer, link.getIssuer()))
                .sorted(Comparator.comparing(TagtogIdentityLink::getSubject))
                .map(link -> Map.<String, Object>of("issuer", link.getIssuer(), "subject", link.getSubject(),
                        "crmUserId", link.getCrmUserId(), "updatedAt", link.getUpdatedAt())).toList();
    }

    @Transactional
    public void link(String subject, Long userId, String authorization) {
        requireSubject(subject);
        if (userId == null || userId <= 0) throw new TagtogException(400, "CRM_USER_REQUIRED", "Select an existing CRM user");
        iam.requireActiveIdentity(authorization, subject);
        requireLocalUser(users.findById(userId).orElseThrow(() -> new TagtogException(404, "CRM_USER_MISSING", "CRM user not found")));
        identities.findByCrmUserId(userId).ifPresent(existing -> {
            if (!Objects.equals(existing.getIssuer(), config.issuer) || !existing.getSubject().equals(subject))
                throw new TagtogException(409, "CRM_USER_LINKED", "CRM user is already linked to another identity");
        });
        revokeSubject(subject);
        var link = identities.findByIssuerAndSubject(config.issuer, subject).orElseGet(TagtogIdentityLink::new);
        link.setIssuer(config.issuer); link.setSubject(subject); link.setCrmUserId(userId); link.setUpdatedAt(Instant.now());
        link.setLinkVersion(UUID.randomUUID().toString());
        identities.saveAndFlush(link);
    }

    @Transactional
    public void unlink(String subject) {
        requireSubject(subject); revokeSubject(subject);
        identities.findByIssuerAndSubject(config.issuer, subject).ifPresent(identities::delete);
    }

    private void revokeSubject(String subject) {
        var bound = sessions.findByTagtogIssuerAndTagtogSubject(config.issuer, subject);
        bound.forEach(session -> session.setTagtogRevoked(true)); sessions.saveAll(bound);
    }

    public static String bearer(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ") && authorization.length() == 50
                ? authorization.substring(7) : null;
    }
    private static boolean opaque(String token) { return token != null && token.matches("[A-Za-z0-9_-]{43}"); }
    public static String digest(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable"); }
    }
    private static void requireSubject(String subject) {
        if (subject == null || !subject.matches("[1-9][0-9]{0,18}"))
            throw new TagtogException(400, "IAM_SUBJECT_INVALID", "Invalid central user identity");
    }
    private static void requireLocalUser(AppUser user) {
        if (user == null || user.getId() == null || user.getRoles() == null || user.getRoles().isEmpty()) throw unavailableUser();
    }
    public static Map<String, Object> userView(AppUser user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId()); result.put("username", user.getUsername()); result.put("email", user.getEmail());
        result.put("fullName", user.getFullName()); result.put("roles", user.getRoles()); result.put("allowedEventIds", user.getAllowedEventIds());
        return result;
    }
    private static TagtogException unavailableUser() { return new TagtogException(403, "CRM_USER_UNAVAILABLE", "An existing CRM account with assigned roles is required"); }
    private static TagtogException loginRequired() { return new TagtogException(401, "TAGTOG_LOGIN_REQUIRED", "Sign in through TAGTOG to access CRM"); }
    private static TagtogException denied() { return new TagtogException(403, "CRM_ACCESS_DENIED", "Current TAGTOG permissions do not allow this CRM action"); }
}
