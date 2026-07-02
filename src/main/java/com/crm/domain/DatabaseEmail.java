package com.crm.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "database_emails")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DatabaseEmail {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "database_id", nullable = false)
    private Database database;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "email_type")
    private String emailType;

    @Column(name = "is_primary", nullable = false)
    @Builder.Default
    private Boolean isPrimary = false;

    @Column(name = "is_verified", nullable = false)
    @Builder.Default
    private Boolean isVerified = false;

    @Column(name = "is_corporate", nullable = false)
    @Builder.Default
    private Boolean isCorporate = false;

    private String domain;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (isPrimary == null) isPrimary = false;
        if (isVerified == null) isVerified = false;
        if (isCorporate == null) isCorporate = false;

        // Auto-extract domain from email if not set
        if (email != null && domain == null && email.contains("@")) {
            domain = email.substring(email.indexOf("@") + 1);
        }
    }
}
