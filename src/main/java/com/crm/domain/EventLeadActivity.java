package com.crm.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "event_lead_activities")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventLeadActivity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_lead_id", nullable = false)
    private EventLead eventLead;

    @Column(name = "activity_type", nullable = false)
    private String activityType; // CALL, EMAIL, WHATSAPP, MEETING

    @Column(nullable = false)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
