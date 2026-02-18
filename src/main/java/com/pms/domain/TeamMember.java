package com.pms.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "team_members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeamMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String role;
    private String email;
    private String phone;
    private String location;
    private String avatar;

    @Enumerated(EnumType.STRING)
    private MemberStatus status;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private AppUser user;

    @ManyToMany(mappedBy = "teamMembers")
    @JsonIgnore
    // @ManyToOne(fetch = FetchType.LAZY)
    // @JoinColumn(name = "project_id")
    // @JsonIgnore // Prevent infinite JSON recursion
    @Builder.Default
    private Set<Project> projects = new HashSet<>();
}
