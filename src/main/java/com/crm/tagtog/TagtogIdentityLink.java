package com.crm.tagtog;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "tagtog_identity_links", uniqueConstraints = {
        @UniqueConstraint(name = "uq_crm_tagtog_identity", columnNames = {"issuer", "subject"}),
        @UniqueConstraint(name = "uq_crm_tagtog_user", columnNames = {"crm_user_id"})})
@Getter @Setter @NoArgsConstructor
public class TagtogIdentityLink {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 255) private String issuer;
    @Column(nullable = false, length = 64) private String subject;
    @Column(name = "crm_user_id", nullable = false) private Long crmUserId;
    @Column(name = "link_version", nullable = false, length = 36) private String linkVersion;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
