package com.crm.domain;

import com.crm.util.EventApprovalPolicy;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "event_participants", 
       uniqueConstraints = @UniqueConstraint(name = "unique_event_participant_database", columnNames = {"event_id", "database_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventParticipant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "database_id", nullable = false)
    private Database database;

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_status")
    private ParticipantStatus participantStatus;

    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "attendance_status")
    private AttendanceStatus attendanceStatus;

    @Column(name = "participant_category")
    private String participantCategory;

    @Column(name = "reminder_h7")
    private String reminderH7;

    @Column(name = "reminder_h3")
    private String reminderH3;

    @Column(name = "reminder_hari_h")
    private String reminderHariH;

    @Column(name = "reminder_h1")
    private String reminderH1;

    @Column(name = "confirmation_status")
    @Builder.Default
    private String confirmationStatus = "pending";

    @Column(name = "pre_event_approval_status")
    @Builder.Default
    private String preEventApprovalStatus = "pending";

    // Last synced approval; the editable Pre-Event decision can differ until the next sync.
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Column(name = "ems_approval_status", length = 20)
    private String emsApprovalStatus;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Column(name = "ems_company_name", columnDefinition = "TEXT")
    private String emsCompanyName;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Column(name = "ems_company_review_required")
    private Boolean emsCompanyReviewRequired;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Column(name = "ems_identity_review_message", columnDefinition = "TEXT")
    private String emsIdentityReviewMessage;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Column(name = "ems_client_decline_review_required")
    private Boolean emsClientDeclineReviewRequired;

    public Boolean getEmsClientDeclineReviewRequired() {
        return Boolean.TRUE.equals(emsClientDeclineReviewRequired) && EventApprovalPolicy.isClientApproved(confirmationStatus)
                ? Boolean.FALSE : emsClientDeclineReviewRequired;
    }

    public String getEmsIdentityReviewMessage() {
        return EventApprovalPolicy.identityReviewMessage(emsIdentityReviewMessage, emsClientDeclineReviewRequired, confirmationStatus);
    }

    @JsonIgnore
    public Boolean getRawEmsClientDeclineReviewRequired() { return emsClientDeclineReviewRequired; }

    @JsonIgnore
    public String getRawEmsIdentityReviewMessage() { return emsIdentityReviewMessage; }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getApprovalSource() {
        return EventApprovalPolicy.isEmsManaged(event) ? "ems" : "crm";
    }

    @Column(name = "call_status")
    private String callStatus;

    @Column(name = "email_status")
    private String emailStatus;

    @Column(name = "whatsapp_status")
    private String whatsappStatus;

    @Column(name = "meeting_status")
    private String meetingStatus;

    @Column(name = "business_challenges", columnDefinition = "TEXT")
    private String businessChallenges;

    @Column(name = "project_info", columnDefinition = "TEXT")
    private String projectInfo;

    private String timeline;

    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
