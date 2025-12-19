package com.pms.controller;

import com.pms.domain.AppUser;
import com.pms.domain.TeamMember;
import com.pms.repository.TeamMemberRepository;
import com.pms.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/team-members")
public class TeamMemberController {

    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository users;
    
    @PersistenceContext
    private EntityManager entityManager;

    public TeamMemberController(TeamMemberRepository teamMemberRepository, UserRepository users) {
        this.teamMemberRepository = teamMemberRepository;
        this.users = users;
    }

    @GetMapping
    public List<TeamMember> list() {
        List<TeamMember> members = teamMemberRepository.findAll();
        System.out.println("GET /api/team-members - returning " + members.size() + " team members");
        return members;
    }

    @GetMapping("/{id}")
    public ResponseEntity<TeamMember> get(@PathVariable Long id) {
        return teamMemberRepository.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<TeamMember> create(@Valid @RequestBody TeamMember member,
                                             @RequestParam(name = "userId", required = false) Long userId) {
        if (userId != null) {
            Optional<AppUser> opt = users.findById(userId);
            opt.ifPresent(member::setUser);
            opt.ifPresent(u -> {
                if (member.getName() == null || member.getName().isBlank()) member.setName(u.getName());
                if (member.getEmail() == null || member.getEmail().isBlank()) member.setEmail(u.getEmail());
            });
        }
        TeamMember saved = teamMemberRepository.save(member);
        return ResponseEntity.created(URI.create("/api/team-members/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TeamMember> update(@PathVariable Long id, @Valid @RequestBody TeamMember member,
                                             @RequestParam(name = "userId", required = false) Long userId) {
        return teamMemberRepository.findById(id).map(existing -> {
            member.setId(existing.getId());
            if (userId != null) {
                users.findById(userId).ifPresent(member::setUser);
            } else if (member.getUser() == null) {
                member.setUser(existing.getUser());
            }
            return ResponseEntity.ok(teamMemberRepository.save(member));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!teamMemberRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        
        try {
            // First, remove all project assignments from the join table
            int deleted = entityManager.createNativeQuery(
                "DELETE FROM project_team_members WHERE member_id = ?"
            )
            .setParameter(1, id)
            .executeUpdate();
            
            System.out.println("Deleted " + deleted + " project assignments for team member " + id);
            
            // Now delete the team member
            teamMemberRepository.deleteById(id);
            entityManager.flush();
            
            System.out.println("Successfully deleted team member " + id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            System.err.println("Error deleting team member " + id + ": " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).build();
        }
    }
}
