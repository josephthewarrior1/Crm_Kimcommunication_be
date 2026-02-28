package com.pms.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "venues")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Venue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Venue name is required")
    private String name;

    @ManyToOne
    @JoinColumn(name = "city_id")
    private City city;

    @Column(name = "document_data", length = 2000)
    private String documentData;

    // ── Address & Location ─────────────────────────────────────────
    @Column(name = "google_maps_link", length = 500)
    private String googleMapsLink;

    @Column(name = "province", length = 255)
    private String province;

    @Column(name = "address", length = 500)
    private String address;

    // ── Ballroom specifications ──────────────────────────────────────
    @Column(name = "ballroom_name")
    private String ballroomName;

    @Column(name = "ballroom_floor")
    private String ballroomFloor;

    @Column(name = "ballroom_length", precision = 10, scale = 2)
    private BigDecimal ballroomLength;

    @Column(name = "ballroom_width", precision = 10, scale = 2)
    private BigDecimal ballroomWidth;

    @Column(name = "ballroom_height", precision = 10, scale = 2)
    private BigDecimal ballroomHeight;

    // ── Foyer specifications ─────────────────────────────────────────
    @Column(name = "foyer_length", precision = 10, scale = 2)
    private BigDecimal foyerLength;

    @Column(name = "foyer_width", precision = 10, scale = 2)
    private BigDecimal foyerWidth;

    @Column(name = "foyer_height", precision = 10, scale = 2)
    private BigDecimal foyerHeight;

    // ── LCD/LED Screen specifications ────────────────────────────────
    @Column(name = "screen_length", precision = 10, scale = 2)
    private BigDecimal screenLength;

    @Column(name = "screen_width", precision = 10, scale = 2)
    private BigDecimal screenWidth;

    @Column(name = "existing_screen")
    private Boolean existingScreen;

    // ── IBM Table specifications ─────────────────────────────────────
    @Column(name = "ibm_table_length", precision = 10, scale = 2)
    private BigDecimal ibmTableLength;

    @Column(name = "ibm_table_width", precision = 10, scale = 2)
    private BigDecimal ibmTableWidth;

    @Column(name = "ibm_table_height", precision = 10, scale = 2)
    private BigDecimal ibmTableHeight;

    // ── Round Table specifications ───────────────────────────────────
    @Column(name = "round_table_diameter", precision = 10, scale = 2)
    private BigDecimal roundTableDiameter;

    @Column(name = "round_table_height", precision = 10, scale = 2)
    private BigDecimal roundTableHeight;

    // ── Electrical specifications ────────────────────────────────────
    @Column(name = "electricity_watt")
    private Integer electricityWatt;

    @Column(name = "sound_system_watt")
    private Integer soundSystemWatt;

    // ── Microphone specifications ────────────────────────────────────
    @Column(name = "microphone_provided")
    private Boolean microphoneProvided;

    @Column(name = "microphone_model")
    private String microphoneModel;

    @Column(name = "microphone_quantity")
    private Integer microphoneQuantity;

    // ── Stage specifications ─────────────────────────────────────────
    @Column(name = "total_stage_modules")
    private Integer totalStageModules;

    // ── Generator ────────────────────────────────────────────────────
    @Column(name = "generator_allowed")
    private Boolean generatorAllowed;

    // ── Attachments ──────────────────────────────────────────────────
    @OneToMany(mappedBy = "venue", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<VenueAttachment> attachments = new ArrayList<>();

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
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
