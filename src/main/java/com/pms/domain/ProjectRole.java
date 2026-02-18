package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "project_roles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectRole {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name; // e.g., "ADMIN", "EDITOR", "VIEWER"

    private String description;

    // The CRUD Matrix
    @Column(name = "can_create")
    private boolean canCreate;

    @Column(name = "can_read")
    private boolean canRead;

    @Column(name = "can_update")
    private boolean canUpdate;

    @Column(name = "can_delete")
    private boolean canDelete;
}