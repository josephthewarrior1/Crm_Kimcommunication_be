package com.pms.domain;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "workflow_stages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowStage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Stage name is required")
    private String name;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @NotNull(message = "Status is required")
    private StageStatus status;

    private LocalDate dueDate;

    private String pic; // Person In Charge

    @Column(name = "order_sequence")
    private Integer orderSequence; // Order/position of this worklog in the workflow
    
    @Column(name = "previous_stage_name")
    private String previousStageName; // Name of the worklog that comes before this
    
    @Column(name = "next_stage_name")
    private String nextStageName; // Name of the worklog that comes after this

    @ElementCollection
    @CollectionTable(name = "workflow_stage_deliverables", joinColumns = @JoinColumn(name = "stage_id"))
    @Column(name = "deliverable")
    @Builder.Default
    private List<String> deliverables = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "workflow_stage_dependencies", joinColumns = @JoinColumn(name = "stage_id"))
    @Column(name = "dependency")
    @Builder.Default
    private List<String> dependencies = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_id")
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "passwordHash", "roles"})
    private AppUser approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    @JsonBackReference
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "members", "project", "createdAt", "updatedAt"})
    private ProjectTeam team;

    @ManyToMany
    @JoinTable(
        name = "workflow_stage_documents",
        joinColumns = @JoinColumn(name = "stage_id"),
        inverseJoinColumns = @JoinColumn(name = "document_id")
    )
    @Builder.Default
    private List<ProjectDocument> relatedDocuments = new ArrayList<>();

    @OneToMany(mappedBy = "stage", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderPosition ASC")
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"stage"})
    @Builder.Default
    private List<ChecklistItem> checklistItems = new ArrayList<>();
}
