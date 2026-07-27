package com.crm.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "event_participant_activities")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventParticipantActivity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_participant_id", nullable = false)
    private EventParticipant eventParticipant;

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

    @com.fasterxml.jackson.annotation.JsonProperty("participantName")
    public String getParticipantName() {
        if (eventParticipant != null && eventParticipant.getDatabase() != null) {
            String f = eventParticipant.getDatabase().getFirstName();
            String l = eventParticipant.getDatabase().getLastName();
            String name = ((f != null ? f : "") + " " + (l != null ? l : "")).trim();
            return name.isEmpty() ? "Unknown Participant" : name;
        }
        return null;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("companyName")
    public String getCompanyName() {
        if (eventParticipant != null && eventParticipant.getDatabase() != null && eventParticipant.getDatabase().getCompany() != null) {
            return eventParticipant.getDatabase().getCompany().getName();
        }
        return null;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("mobilePhone")
    public String getMobilePhone() {
        if (eventParticipant != null && eventParticipant.getDatabase() != null) {
            return eventParticipant.getDatabase().getMobilePhone();
        }
        return null;
    }
}
