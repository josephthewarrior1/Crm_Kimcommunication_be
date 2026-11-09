package com.crm.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "databases")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Database {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToMany(mappedBy = "database", fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    @JsonIgnoreProperties("database")
    private List<DatabaseEmail> emails;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    private CompanyBranch branch;

    public void setCompany(Company company) {
        if (branch != null && !java.util.Objects.equals(branch.getCompanyId(), company != null ? company.getId() : null)) {
            branch = null;
        }
        this.company = company;
    }

    // Destination is an explicit staff choice. Email domains never move a contact.
    @Enumerated(EnumType.STRING)
    @Column(name = "database_destination", nullable = false, length = 16)
    private DatabaseDestination databaseDestination;

    @Column(name = "institution_name", length = 255)
    private String institutionName;

    @Column(name = "participant_category", length = 32)
    private String participantCategory;

    // Participant-owned text; these fields never refer to Company/Branch masters.
    @Column(name = "organization_group_name", length = 255)
    private String organizationGroupName;

    @Column(name = "organization_brand_name", length = 255)
    private String organizationBrandName;

    @Column(name = "organization_address", columnDefinition = "TEXT")
    private String organizationAddress;

    @Column(name = "organization_office_phone", length = 50)
    private String organizationOfficePhone;

    @Column(name = "organization_industry", length = 100)
    private String organizationIndustry;

    @Column(name = "organization_size_revenue", length = 100)
    private String organizationSizeRevenue;

    @Column(name = "organization_size_employee", length = 100)
    private String organizationSizeEmployee;

    @Column(name = "organization_branch_name", length = 255)
    private String organizationBranchName;

    @Column(name = "organization_postal_code", length = 20)
    private String organizationPostalCode;

    @Column(name = "organization_website", columnDefinition = "TEXT")
    private String organizationWebsite;

    private String salutation;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "position_level")
    private PositionLevel positionLevel;

    @Column(name = "speciality_division")
    private String specialityDivision;

    @Column(name = "job_title")
    private String jobTitle;

    @Column(name = "mobile_phone")
    private String mobilePhone;

    @Column(name = "normalized_phone")
    private String normalizedPhone;

    @Column(name = "linkedin_url")
    private String linkedinUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "database_type")
    private DatabaseType databaseType;

    @Enumerated(EnumType.STRING)
    private DatabaseSource source;

    // Server-owned attribution; editing a contact must never transfer upload credit.
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    @Column(name = "created_by_user_id", updatable = false)
    private Long createdByUserId;

    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    @Column(name = "entry_method", updatable = false)
    private String entryMethod;

    @com.fasterxml.jackson.annotation.JsonProperty("isActive")
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        databaseDestination = DatabaseDestination.effective(databaseDestination);
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (isActive == null) {
            isActive = true;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
