package com.pms.domain;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Client entity representing a client company or organization.
 * This entity is used to normalize client data and establish proper relationships with projects.
 * 
 * @author Juan
 * @version 1.0
 */
@Entity
@Table(name = "clients")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Client {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Client name - must be unique across all clients
     */
    @NotBlank(message = "Client name is required")
    @Column(unique = true)
    private String name;

    /**
     * Industry sector the client operates in (e.g., "Technology", "Healthcare", "Finance")
     */
    private String industry;

    /**
     * Country where the client is based
     */
    private String country;

    /**
     * Timestamp when the client record was created
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * Timestamp when the client record was last updated
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * List of projects associated with this client.
     * This is the inverse side of the many-to-one relationship.
     */
    @OneToMany(mappedBy = "clientEntity", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference
    @Builder.Default
    private List<Project> projects = new ArrayList<>();

    /**
     * Pre-persist hook to set creation timestamp
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /**
     * Pre-update hook to set update timestamp
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * String representation of the client
     */
    @Override
    public String toString() {
        return "Client{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", industry='" + industry + '\'' +
                ", country='" + country + '\'' +
                '}';
    }
}
