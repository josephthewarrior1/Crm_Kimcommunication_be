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

    // 1. GET TEAM: List all members (optionally filtered by team type)
    @GetMapping
    public ResponseEntity<List<ProjectMemberDto>> getTeam(
            @PathVariable Long projectId,
            @RequestParam(value = "teamType", required = false) String teamType) {
        List<ProjectMember> members = (teamType != null)
                ? memberRepository.findByProjectIdAndTeamType(projectId, teamType)
                : memberRepository.findByProjectId(projectId);

        List<ProjectMemberDto> dtos = members.stream()
                .map(this::convertToDto)
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

        // Step 4: Fetch Manager (Optional)
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

        // Step 5: Resolve team type (default to ADMINISTRATION)
        String teamType = (request.teamType != null) ? request.teamType : "ADMINISTRATION";

        // Step 6: Logic Checks (User already in this team type?)
        if (memberRepository.existsByProjectIdAndUserIdAndTeamType(projectId, user.getId(), teamType)) {
            return ResponseEntity.badRequest().body("User is already in this team");
        }

        // Step 7: Save & Return
        ProjectMember member = ProjectMember.builder()
                .project(project)
                .user(user)
                .role(role)
                .jobTitle(request.jobTitle)
                .manager(manager)
                .teamType(teamType)
                .joinedAt(java.time.LocalDateTime.now())
                .build();

        ProjectMember savedMember = memberRepository.save(member);

        return ResponseEntity.ok(convertToDto(savedMember));
    }

    // 3. UPDATE MEMBER
    @PutMapping("/{memberId}")
    public ResponseEntity<?> updateMember(@PathVariable Long projectId, @PathVariable Long memberId,
            @RequestBody UpdateMemberRequest request) {
        var memberOpt = memberRepository.findById(memberId);
        if (memberOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var member = memberOpt.get();

        if (!member.getProject().getId().equals(projectId)) {
            return ResponseEntity.badRequest().body("Member does not belong to this project");
        }

        // Update role
        if (request.roleId != null) {
            var roleOpt = roleRepository.findById(request.roleId);
            roleOpt.ifPresent(member::setRole);
        }

        // Update job title
        if (request.jobTitle != null) {
            member.setJobTitle(request.jobTitle);
        }

        // Update manager
        if (request.managerId != null) {
            if (request.managerId == 0) {
                member.setManager(null); // 0 means "no manager"
            } else {
                memberRepository.findById(request.managerId).ifPresent(member::setManager);
            }
        }

        memberRepository.save(member);
        return ResponseEntity.ok(convertToDto(member));
    }

    // 4. REMOVE MEMBER
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

        // 3. Extract Manager Details
        Long managerId = null;
        String managerName = null;

        if (m.getManager() != null) {
            managerId = m.getManager().getId();
            if (m.getManager().getUser() != null) {
                managerName = m.getManager().getUser().getName();
            }
        }

        // 4. Return DTO with teamType
        return new ProjectMemberDto(
                m.getId(),
                userDto,
                roleDto,
                m.getJobTitle(),
                m.getJoinedAt(),
                managerId,
                managerName,
                m.getTeamType()
        );
    }

    // Request DTOs
    public static class AddMemberRequest {
        public Long userId;
        public Long roleId;
        public String jobTitle;
        public Long managerId;
        public String teamType;
    }

    public static class UpdateMemberRequest {
        public Long roleId;
        public String jobTitle;
        public Long managerId;
    }
}
