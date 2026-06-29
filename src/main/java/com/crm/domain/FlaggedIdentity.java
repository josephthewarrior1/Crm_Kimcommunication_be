package com.crm.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "flagged_identities")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlaggedIdentity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id")
    private Contact contact;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    private Event event;

    @Column(name = "name_used")
    private String nameUsed;

    @Column(name = "email_used")
    private String emailUsed;

    @Column(name = "phone_used")
    private String phoneUsed;

    @Enumerated(EnumType.STRING)
    @Column(name = "flag_reason")
    private FlagReason flagReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_flag_id")
    private FlaggedIdentity linkedFlag;

    @Column(name = "evidence_notes")
    private String evidenceNotes;

    @Enumerated(EnumType.STRING)
    private FlagStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
