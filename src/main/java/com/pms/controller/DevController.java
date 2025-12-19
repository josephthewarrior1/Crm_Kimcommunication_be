package com.pms.controller;

import com.pms.domain.*;
import com.pms.repository.*;
import com.pms.service.TeamManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Development Controller
 * 
 * This controller provides development and testing endpoints for:
 * - API health checks
 * - Data seeding status
 * - Team management testing
 * - Client management testing
 * 
 * @author Juan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/dev")
@RequiredArgsConstructor
@Slf4j
public class DevController {

    private final UserRepository userRepository;
    private final ClientRepository clientRepository;
    private final ProjectRepository projectRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamManagementService teamManagementService;

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", System.currentTimeMillis());
        health.put("database", "Connected");
        
        // Check entity counts
        health.put("users", userRepository.count());
        health.put("clients", clientRepository.count());
        health.put("projects", projectRepository.count());
        health.put("teamMembers", teamMemberRepository.count());
        
        return ResponseEntity.ok(health);
    }

    /**
     * Get all clients (for testing)
     */
    @GetMapping("/clients")
    public ResponseEntity<List<Map<String, Object>>> getAllClients() {
        try {
            List<Client> clients = clientRepository.findAllOrderByName();
            List<Map<String, Object>> clientData = clients.stream()
                    .map(client -> {
                        Map<String, Object> data = new HashMap<>();
                        data.put("id", client.getId());
                        data.put("name", client.getName());
                        data.put("industry", client.getIndustry());
                        data.put("country", client.getCountry());
                        data.put("createdAt", client.getCreatedAt());
                        data.put("projectCount", client.getProjects().size());
                        return data;
                    })
                    .collect(Collectors.toList());
            
            return ResponseEntity.ok(clientData);
        } catch (Exception e) {
            log.error("Error fetching clients", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Get all users (for testing)
     */
    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> getAllUsers() {
        try {
            List<AppUser> users = userRepository.findAll();
            List<Map<String, Object>> userData = users.stream()
                    .map(user -> {
                        Map<String, Object> data = new HashMap<>();
                        data.put("id", user.getId());
                        data.put("name", user.getName());
                        data.put("email", user.getEmail());
                        data.put("username", user.getUsername());
                        data.put("roles", user.getRoles());
                        data.put("active", user.isActive());
                        data.put("approved", user.isApproved());
                        return data;
                    })
                    .collect(Collectors.toList());
            
            return ResponseEntity.ok(userData);
        } catch (Exception e) {
            log.error("Error fetching users", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Get all team members (for testing)
     */
    @GetMapping("/team-members")
    public ResponseEntity<List<Map<String, Object>>> getAllTeamMembers() {
        try {
            List<TeamMember> members = teamMemberRepository.findAll();
            List<Map<String, Object>> memberData = members.stream()
                    .map(member -> {
                        Map<String, Object> data = new HashMap<>();
                        data.put("id", member.getId());
                        data.put("name", member.getName());
                        data.put("role", member.getRole());
                        data.put("email", member.getEmail());
                        data.put("phone", member.getPhone());
                        data.put("location", member.getLocation());
                        data.put("status", member.getStatus());
                        data.put("userId", member.getUser() != null ? member.getUser().getId() : null);
                        data.put("projectCount", member.getProjects().size());
                        return data;
                    })
                    .collect(Collectors.toList());
            
            return ResponseEntity.ok(memberData);
        } catch (Exception e) {
            log.error("Error fetching team members", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Test team member assignment
     */
    @PostMapping("/test/assign-team-member")
    public ResponseEntity<Map<String, Object>> testAssignTeamMember(
            @RequestParam Long projectId,
            @RequestParam Long memberId) {
        try {
            boolean success = teamManagementService.assignTeamMemberToProject(projectId, memberId);
            Map<String, Object> result = new HashMap<>();
            result.put("success", success);
            result.put("message", success ? "Team member assigned successfully" : "Failed to assign team member");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error testing team member assignment", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "Error: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Test user assignment
     */
    @PostMapping("/test/assign-user")
    public ResponseEntity<Map<String, Object>> testAssignUser(
            @RequestParam Long projectId,
            @RequestParam Long userId) {
        try {
            boolean success = teamManagementService.assignUserToProject(projectId, userId);
            Map<String, Object> result = new HashMap<>();
            result.put("success", success);
            result.put("message", success ? "User assigned successfully" : "Failed to assign user");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error testing user assignment", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "Error: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Get project team information
     */
    @GetMapping("/projects/{projectId}/team")
    public ResponseEntity<Map<String, Object>> getProjectTeam(@PathVariable Long projectId) {
        try {
            List<TeamMember> teamMembers = teamManagementService.getProjectTeamMembers(projectId);
            List<AppUser> projectUsers = teamManagementService.getProjectUsers(projectId);
            
            Map<String, Object> result = new HashMap<>();
            result.put("projectId", projectId);
            result.put("teamMembers", teamMembers.stream()
                    .map(member -> {
                        Map<String, Object> data = new HashMap<>();
                        data.put("id", member.getId());
                        data.put("name", member.getName());
                        data.put("role", member.getRole());
                        data.put("email", member.getEmail());
                        return data;
                    })
                    .collect(Collectors.toList()));
            result.put("users", projectUsers.stream()
                    .map(user -> {
                        Map<String, Object> data = new HashMap<>();
                        data.put("id", user.getId());
                        data.put("name", user.getName());
                        data.put("email", user.getEmail());
                        data.put("roles", user.getRoles());
                        return data;
                    })
                    .collect(Collectors.toList()));
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error fetching project team", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Reset database (development only)
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetDatabase() {
        try {
            // This is a development-only endpoint
            // In production, this should be disabled or heavily secured
            log.warn("Database reset requested - this should only be used in development!");
            
            Map<String, Object> result = new HashMap<>();
            result.put("message", "Database reset functionality not implemented for safety");
            result.put("warning", "This endpoint should be disabled in production");
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error in database reset", e);
            return ResponseEntity.status(500).build();
        }
    }
}
