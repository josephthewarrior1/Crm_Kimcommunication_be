package com.pms.controller;

import com.pms.domain.*;
import com.pms.dto.ProjectMemberDto;
import com.pms.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/team")
public class ProjectTeamController {

    private final ProjectMemberRepository memberRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ProjectRoleRepository roleRepository;

    public ProjectTeamController(ProjectMemberRepository memberRepository, ProjectRepository projectRepository,
            UserRepository userRepository, ProjectRoleRepository roleRepository) {
        this.memberRepository = memberRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
    }

    // 1. GET TEAM: List all members
    @GetMapping
    public ResponseEntity<List<ProjectMemberDto>> getTeam(@PathVariable Long projectId) {
        List<ProjectMember> members = memberRepository.findByProjectId(projectId);

        List<ProjectMemberDto> dtos = members.stream()
                .map(this::convertToDto) // Use a helper method for safety
                .toList();

        return ResponseEntity.ok(dtos);
    }

    // 2. ADD MEMBER: Add a user to the project
    @PostMapping
    public ResponseEntity<?> addMember(@PathVariable Long projectId, @RequestBody AddMemberRequest request) {

        // Step 1: Fetch Project
        var projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var project = projectOpt.get();

        // Step 2: Fetch User
        var userOpt = userRepository.findById(request.userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body("User not found");
        }
        var user = userOpt.get();

        // Step 3: Fetch Role
        var roleOpt = roleRepository.findById(request.roleId);
        if (roleOpt.isEmpty()) {
            return ResponseEntity.badRequest().body("Role not found");
        }
        var role = roleOpt.get();

        // --- NEW: Step 4: Fetch Manager (Optional) ---
        ProjectMember manager = null;
        if (request.managerId != null) {
            var managerOpt = memberRepository.findById(request.managerId);
            if (managerOpt.isEmpty()) {
                return ResponseEntity.badRequest().body("Selected manager not found");
            }
            manager = managerOpt.get();

            // Safety Check: Ensure the manager actually belongs to THIS project
            if (!manager.getProject().getId().equals(projectId)) {
                return ResponseEntity.badRequest().body("Manager must belong to the same project");
            }
        }

        // Step 5: Logic Checks (User already in team?)
        if (memberRepository.existsByProjectIdAndUserId(projectId, user.getId())) {
            return ResponseEntity.badRequest().body("User is already in the team");
        }

        // Step 6: Save & Return
        ProjectMember member = ProjectMember.builder()
                .project(project)
                .user(user)
                .role(role)
                .jobTitle(request.jobTitle)
                .manager(manager) // <--- Set the manager here
                .joinedAt(java.time.LocalDateTime.now()) // Good practice to set this
                .build();

        ProjectMember savedMember = memberRepository.save(member);

        return ResponseEntity.ok(convertToDto(savedMember));
    }

    // 3. REMOVE MEMBER
    @DeleteMapping("/{memberId}")
    public ResponseEntity<Void> removeMember(@PathVariable Long projectId, @PathVariable Long memberId) {
        if (memberRepository.existsById(memberId)) {
            memberRepository.deleteById(memberId);
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    // --- HELPER: Safe DTO Conversion ---
    private ProjectMemberDto convertToDto(ProjectMember m) {
        // 1. Convert User
        var userDto = (m.getUser() != null)
                ? new ProjectMemberDto.UserSummaryDto(
                        m.getUser().getId(),
                        m.getUser().getName(),
                        m.getUser().getEmail(),
                        m.getUser().getPhone(),
                        m.getUser().getLocation(),
                        m.getUser().getEmploymentType())
                : null;

        // 2. Convert Role (including permissions)
        var roleDto = (m.getRole() != null)
                ? new ProjectMemberDto.RoleSummaryDto(
                        m.getRole().getId(),
                        m.getRole().getName(),
                        m.getRole().getDescription(),
                        m.getRole().isCanCreate(),
                        m.getRole().isCanRead(),
                        m.getRole().isCanUpdate(),
                        m.getRole().isCanDelete())
                : null;

        // 3. Extract Manager Details (New Logic)
        Long managerId = null;
        String managerName = null;

        if (m.getManager() != null) {
            managerId = m.getManager().getId();
            // Ensure the manager has a User attached before getting the name
            if (m.getManager().getUser() != null) {
                managerName = m.getManager().getUser().getName();
            }
        }

        // 4. Return new DTO with ALL 7 arguments
        return new ProjectMemberDto(
                m.getId(),
                userDto,
                roleDto,
                m.getJobTitle(), // Note: Make sure your getter matches the field name (getJobTitle)
                m.getJoinedAt(),
                managerId, // <--- Added
                managerName // <--- Added
        );
    }

    // Helper DTO class
    public static class AddMemberRequest {
        public Long userId;
        public Long roleId;
        public String jobTitle;
        public Long managerId; // <--- NEW FIELD
    }
}