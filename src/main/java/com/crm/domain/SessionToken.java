package com.crm.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "session_tokens")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SessionToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false) private AppUser user;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "expires_at", nullable = false) private LocalDateTime expiresAt;
    @JsonIgnore @Column(name = "token_hash", length = 64) private String tokenHash;
    @JsonIgnore @Column(name = "tagtog_issuer", length = 255) private String tagtogIssuer;
    @JsonIgnore @Column(name = "tagtog_subject", length = 64) private String tagtogSubject;
    @JsonIgnore @Column(name = "tagtog_session_id", length = 128) private String tagtogSessionId;
    @JsonIgnore @Column(name = "tagtog_mapping_version", length = 36) private String tagtogMappingVersion;
    @JsonIgnore @Column(name = "tagtog_expires_at") private Instant tagtogExpiresAt;
    @JsonIgnore @Column(name = "tagtog_revoked", nullable = false) @Builder.Default private boolean tagtogRevoked = false;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (expiresAt == null) expiresAt = LocalDateTime.now().plusDays(7);
    }
}
