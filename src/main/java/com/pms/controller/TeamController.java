package com.pms.controller;

import com.pms.domain.AppUser;
import com.pms.domain.Role;
import com.pms.domain.Team;
import com.pms.domain.TeamMember;
import com.pms.repository.SessionRepository;
import com.pms.repository.TeamMemberRepository;
import com.pms.repository.TeamRepository;
import com.pms.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/teams")
public class TeamController {

    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final SessionRepository sessions;
    private final UserRepository users;

    public TeamController(TeamRepository teams, TeamMemberRepository members, SessionRepository sessions, UserRepository users) {
        this.teams = teams;
        this.members = members;
        this.sessions = sessions;
        this.users = users;
    }

    @GetMapping
    public List<Team> list() {
        return teams.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Team> get(@PathVariable Long id) {
        return teams.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Team team,
                                    @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null || !isAdminOrManager(u)) return ResponseEntity.status(403).build();
        if (team.getId() != null) team.setId(null);
        if (team.getName() == null || team.getName().trim().isEmpty()) return ResponseEntity.badRequest().body(java.util.Map.of("error","name required"));
        if (teams.existsByNameIgnoreCase(team.getName())) return ResponseEntity.badRequest().body(java.util.Map.of("error","team exists"));
        Team saved = teams.save(team);
        return ResponseEntity.created(URI.create("/api/teams/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Team team,
                                    @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null || !isAdminOrManager(u)) return ResponseEntity.status(403).build();
        return teams.findById(id).map(existing -> {
            existing.setName(team.getName() != null ? team.getName() : existing.getName());
            existing.setDescription(team.getDescription());
            return ResponseEntity.ok(teams.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null || !isAdminOrManager(u)) return ResponseEntity.status(403).build();
        if (!teams.existsById(id)) return ResponseEntity.notFound().build();
        teams.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<List<TeamMember>> listMembers(@PathVariable Long id,
                                                        @RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<Team> opt = teams.findById(id);
        if (opt.isEmpty()) return ResponseEntity.status(404).build();
        AppUser u = currentUser(auth);
        // viewing allowed to all authenticated, modifying restricted; if no auth, still allow viewing for now
        return ResponseEntity.ok(opt.get().getMembers().stream().toList());
    }

    @PostMapping("/{id}/members/{memberId}")
    public ResponseEntity<Void> addMember(@PathVariable Long id, @PathVariable Long memberId,
                                          @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null || !isAdminOrManager(u)) return ResponseEntity.status(403).build();
        Optional<Team> optT = teams.findById(id);
        Optional<TeamMember> optM = members.findById(memberId);
        if (optT.isEmpty() || optM.isEmpty()) return ResponseEntity.status(404).build();
        Team t = optT.get();
        t.getMembers().add(optM.get());
        teams.save(t);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/members/{memberId}")
    public ResponseEntity<Void> removeMember(@PathVariable Long id, @PathVariable Long memberId,
                                             @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null || !isAdminOrManager(u)) return ResponseEntity.status(403).build();
        Optional<Team> optT = teams.findById(id);
        Optional<TeamMember> optM = members.findById(memberId);
        if (optT.isEmpty() || optM.isEmpty()) return ResponseEntity.status(404).build();
        Team t = optT.get();
        t.getMembers().remove(optM.get());
        teams.save(t);
        return ResponseEntity.noContent().build();
    }

    private AppUser currentUser(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        String token = auth.substring(7);
        return sessions.findByTokenAndRevokedFalse(token)
                .filter(st -> st.getExpiresAt().isAfter(java.time.Instant.now()))
                .map(st -> st.getUser())
                .orElse(null);
    }

    private boolean isAdminOrManager(AppUser u) {
        if (u == null || u.getRoles() == null) return false;
        return u.getRoles().contains(Role.ADMIN) || u.getRoles().contains(Role.MANAGER);
    }
}
