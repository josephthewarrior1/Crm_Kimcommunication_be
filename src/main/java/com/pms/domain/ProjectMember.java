package com.pms.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "project_members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 1. Link to the User (Who)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    // 2. Link to the Project (Where)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    @JsonIgnore // Prevent infinite recursion in JSON
    private Project project;

    // 3. Link to the Role (What permissions)
    @ManyToOne(fetch = FetchType.EAGER) // Eager because we usually need permissions immediately
    @JoinColumn(name = "project_role_id", nullable = false)
    private ProjectRole role;

    // 4. Specific Job Title for this Project (e.g. "Videographer")
    @Column(name = "project_job_title")
    private String jobTitle;

    @Column(name = "joined_at")
    private LocalDateTime joinedAt;

    // New: Who does this person report to?
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    @JsonIgnoreProperties({ "subordinates", "manager", "project", "user" }) // Prevent recursion
    private ProjectMember manager;

    // New: Who reports to this person? (Optional, but useful)
    @OneToMany(mappedBy = "manager", fetch = FetchType.LAZY)
    private List<ProjectMember> subordinates;
}