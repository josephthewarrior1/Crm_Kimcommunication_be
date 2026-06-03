package com.pms.controller;

import com.pms.domain.ProjectTeam;
import com.pms.repository.ProjectRepository;
import com.pms.repository.ProjectTeamRepository;
import com.pms.repository.ProjectMemberRepository;
import com.pms.repository.TaskRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/teams")
public class ProjectTeamGroupController {

    private final ProjectRepository projectRepository;
    private final ProjectTeamRepository teamRepository;
    private final ProjectMemberRepository memberRepository;
    private final TaskRepository taskRepository;

    public ProjectTeamGroupController(ProjectRepository projectRepository,
            ProjectTeamRepository teamRepository,
            ProjectMemberRepository memberRepository,
            TaskRepository taskRepository) {
        this.projectRepository = projectRepository;
        this.teamRepository = teamRepository;
        this.memberRepository = memberRepository;
        this.taskRepository = taskRepository;
    }

    @GetMapping
    public ResponseEntity<?> listTeams(@PathVariable Long projectId) {
        if (!projectRepository.existsById(projectId)) {
            return ResponseEntity.notFound().build();
        }

        List<ProjectTeam> teams = teamRepository.findByProjectIdOrderBySortOrder(projectId);
        List<Map<String, Object>> result = teams.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("sortOrder", t.getSortOrder());
            m.put("memberCount", t.getMembers() != null ? t.getMembers().size() : 0);
            return m;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<?> createTeam(@PathVariable Long projectId,
            @RequestBody TeamRequest request) {
        var projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        if (request.name == null || request.name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Team name is required"));
        }

        // Check for duplicate name
        var existing = teamRepository.findByProjectIdAndName(projectId, request.name.trim());
        if (existing.isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "A team with this name already exists"));
        }

        ProjectTeam team = ProjectTeam.builder()
                .project(projectOpt.get())
                .name(request.name.trim())
                .sortOrder(request.sortOrder != null ? request.sortOrder : 0)
                .build();

        team = teamRepository.save(team);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", team.getId());
        result.put("name", team.getName());
        result.put("sortOrder", team.getSortOrder());
        result.put("memberCount", 0);

        return ResponseEntity.ok(result);
    }

    @PutMapping("/{teamId}")
    public ResponseEntity<?> updateTeam(@PathVariable Long projectId,
            @PathVariable Long teamId,
            @RequestBody TeamRequest request) {
        var teamOpt = teamRepository.findById(teamId);
        if (teamOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ProjectTeam team = teamOpt.get();
        if (!team.getProject().getId().equals(projectId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Team does not belong to this project"));
        }

        if (request.name != null && !request.name.isBlank()) {
            // Check for duplicate name (excluding this team)
            var existing = teamRepository.findByProjectIdAndName(projectId, request.name.trim());
            if (existing.isPresent() && !existing.get().getId().equals(teamId)) {
                return ResponseEntity.badRequest().body(Map.of("error", "A team with this name already exists"));
            }
            team.setName(request.name.trim());
        }
        if (request.sortOrder != null) {
            team.setSortOrder(request.sortOrder);
        }

        teamRepository.save(team);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", team.getId());
        result.put("name", team.getName());
        result.put("sortOrder", team.getSortOrder());
        result.put("memberCount", team.getMembers() != null ? team.getMembers().size() : 0);

        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{teamId}")
    public ResponseEntity<?> deleteTeam(@PathVariable Long projectId,
            @PathVariable Long teamId) {
        var teamOpt = teamRepository.findById(teamId);
        if (teamOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ProjectTeam team = teamOpt.get();
        if (!team.getProject().getId().equals(projectId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Team does not belong to this project"));
        }

        // Unassign all members from this team (set team_id to null)
        if (team.getMembers() != null) {
            for (var member : team.getMembers()) {
                member.setTeam(null);
                memberRepository.save(member);
            }
        }
        taskRepository.findByTeamId(teamId).forEach(task -> {
            task.setTeam(null);
            taskRepository.save(task);
        });

        teamRepository.delete(team);
        return ResponseEntity.ok().build();
    }

    public static class TeamRequest {
        public String name;
        public Integer sortOrder;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getSortOrder() { return sortOrder; }
        public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    }
}
