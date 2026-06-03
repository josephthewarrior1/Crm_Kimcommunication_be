package com.pms.domain;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "venue_rooms")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VenueRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id", nullable = false)
    @JsonBackReference
    private Venue venue;

    @Column(name = "room_name")
    private String roomName;

    @Column(name = "room_floor")
    private String roomFloor;

    @Column(name = "room_length", precision = 10, scale = 2)
    private BigDecimal roomLength;

    @Column(name = "room_width", precision = 10, scale = 2)
    private BigDecimal roomWidth;

    @Column(name = "room_height", precision = 10, scale = 2)
    private BigDecimal roomHeight;

    @Column(name = "room_unit", length = 5)
    private String roomUnit;

    @Column(name = "foyer_length", precision = 10, scale = 2)
    private BigDecimal foyerLength;

    @Column(name = "foyer_width", precision = 10, scale = 2)
    private BigDecimal foyerWidth;

    @Column(name = "foyer_height", precision = 10, scale = 2)
    private BigDecimal foyerHeight;

    @Column(name = "foyer_unit", length = 5)
    private String foyerUnit;

    @Column(name = "screen_length", precision = 10, scale = 2)
    private BigDecimal screenLength;

    @Column(name = "screen_width", precision = 10, scale = 2)
    private BigDecimal screenWidth;

    @Column(name = "existing_screen")
    private Boolean existingScreen;

    @Column(name = "screen_unit", length = 5)
    private String screenUnit;

    @Column(name = "ibm_table_length", precision = 10, scale = 2)
    private BigDecimal ibmTableLength;

    @Column(name = "ibm_table_width", precision = 10, scale = 2)
    private BigDecimal ibmTableWidth;

    @Column(name = "ibm_table_height", precision = 10, scale = 2)
    private BigDecimal ibmTableHeight;

    @Column(name = "round_table_diameter", precision = 10, scale = 2)
    private BigDecimal roundTableDiameter;

    @Column(name = "round_table_height", precision = 10, scale = 2)
    private BigDecimal roundTableHeight;

    @Column(name = "table_unit", length = 5)
    private String tableUnit;

    @Column(name = "electricity_watt")
    private Integer electricityWatt;

    @Column(name = "sound_system_watt")
    private Integer soundSystemWatt;

    @Column(name = "microphone_provided")
    private Boolean microphoneProvided;

    @Column(name = "microphone_model")
    private String microphoneModel;

    @Column(name = "microphone_quantity")
    private Integer microphoneQuantity;

    @Column(name = "total_stage_modules")
    private Integer totalStageModules;

    @Column(name = "generator_allowed")
    private Boolean generatorAllowed;
}
