package com.pms.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "talents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Talent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "vendor_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Vendor vendor;

    @Column(name = "categories_json", columnDefinition = "TEXT")
    private String categoriesJson;

    @NotBlank(message = "Talent name is required")
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(name = "id_number")
    private String idNumber;

    @Column(name = "phone_code")
    private String phoneCode;

    private String phone;

    private String email;

    @Column(name = "social_media_link", length = 500)
    private String socialMediaLink;

    @ManyToOne
    @JoinColumn(name = "city_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private City city;

    private String sex;

    @Column(name = "height_cm")
    private Integer heightCm;

    @Column(name = "photo_file_name")
    private String photoFileName;

    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    @Column(name = "languages_json", columnDefinition = "TEXT")
    private String languagesJson;

    @Column(precision = 12, scale = 2)
    private BigDecimal rate;

    @Column(name = "halfday_rate", precision = 12, scale = 2)
    private BigDecimal halfdayRate;

    @Column(name = "fullday_rate", precision = 12, scale = 2)
    private BigDecimal fulldayRate;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "account_number")
    private String accountNumber;

    private String pic;

    @Column(name = "info_source", length = 500)
    private String infoSource;

    @Column(name = "gadget_proficient")
    private Boolean gadgetProficient;

    @Column(name = "cv_file_name")
    private String cvFileName;

    @Column(name = "cv_url", length = 500)
    private String cvUrl;

    @Column(length = 2000)
    private String remarks;

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
