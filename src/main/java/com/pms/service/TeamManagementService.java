package com.pms.service;

import com.pms.domain.*;
import com.pms.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Team Management Service
 * 
 * This service handles all team-related operations including:
 * - Assigning team members to projects
 * - Managing project users
 * - Team member lifecycle management
 * 
 * @author Juan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TeamManagementService {

    private final ProjectRepository projectRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;

    /**
     * Assign a team member to a project
     * 
     * @param projectId Project ID
     * @param memberId Team member ID
     * @return true if successful, false otherwise
     */
    @Transactional
    public boolean assignTeamMemberToProject(Long projectId, Long memberId) {
        try {
            Optional<Project> projectOpt = projectRepository.findById(projectId);
            Optional<TeamMember> memberOpt = teamMemberRepository.findById(memberId);
            
            if (projectOpt.isEmpty() || memberOpt.isEmpty()) {
                log.warn("Project {} or TeamMember {} not found", projectId, memberId);
                return false;
            }
            
            Project project = projectOpt.get();
            TeamMember member = memberOpt.get();
            
            // Check if already assigned
            if (project.getTeamMembers().contains(member)) {
                log.info("TeamMember {} already assigned to project {}", memberId, projectId);
                return true;
            }
            
            // Assign the member
            project.getTeamMembers().add(member);
            projectRepository.save(project);
            
            log.info("Successfully assigned team member {} to project {}", memberId, projectId);
            return true;
            
        } catch (Exception e) {
            log.error("Error assigning team member {} to project {}", memberId, projectId, e);
            return false;
        }
    }

    /**
     * Remove a team member from a project
     * 
     * @param projectId Project ID
     * @param memberId Team member ID
     * @return true if successful, false otherwise
     */
    @Transactional
    public boolean removeTeamMemberFromProject(Long projectId, Long memberId) {
        try {
            Optional<Project> projectOpt = projectRepository.findById(projectId);
            Optional<TeamMember> memberOpt = teamMemberRepository.findById(memberId);
            
            if (projectOpt.isEmpty() || memberOpt.isEmpty()) {
                log.warn("Project {} or TeamMember {} not found", projectId, memberId);
                return false;
            }
            
            Project project = projectOpt.get();
            TeamMember member = memberOpt.get();
            
            // Remove the member
            boolean removed = project.getTeamMembers().remove(member);
            if (removed) {
                projectRepository.save(project);
                log.info("Successfully removed team member {} from project {}", memberId, projectId);
                return true;
            } else {
                log.warn("Team member {} was not assigned to project {}", memberId, projectId);
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error removing team member {} from project {}", memberId, projectId, e);
            return false;
        }
    }

    /**
     * Assign a user to a project
     * 
     * @param projectId Project ID
     * @param userId User ID
     * @return true if successful, false otherwise
     */
    @Transactional
    public boolean assignUserToProject(Long projectId, Long userId) {
        try {
            Optional<Project> projectOpt = projectRepository.findById(projectId);
            Optional<AppUser> userOpt = userRepository.findById(userId);
            
            if (projectOpt.isEmpty() || userOpt.isEmpty()) {
                log.warn("Project {} or User {} not found", projectId, userId);
                return false;
            }
            
            Project project = projectOpt.get();
            AppUser user = userOpt.get();
            
            // Check if already assigned
            if (project.getUsers().contains(user)) {
                log.info("User {} already assigned to project {}", userId, projectId);
                return true;
            }
            
            // Assign the user
            project.getUsers().add(user);
            projectRepository.save(project);
            
            log.info("Successfully assigned user {} to project {}", userId, projectId);
            return true;
            
        } catch (Exception e) {
            log.error("Error assigning user {} to project {}", userId, projectId, e);
            return false;
        }
    }

    /**
     * Remove a user from a project
     * 
     * @param projectId Project ID
     * @param userId User ID
     * @return true if successful, false otherwise
     */
    @Transactional
    public boolean removeUserFromProject(Long projectId, Long userId) {
        try {
            Optional<Project> projectOpt = projectRepository.findById(projectId);
            Optional<AppUser> userOpt = userRepository.findById(userId);
            
            if (projectOpt.isEmpty() || userOpt.isEmpty()) {
                log.warn("Project {} or User {} not found", projectId, userId);
                return false;
            }
            
            Project project = projectOpt.get();
            AppUser user = userOpt.get();
            
            // Remove the user
            boolean removed = project.getUsers().remove(user);
            if (removed) {
                projectRepository.save(project);
                log.info("Successfully removed user {} from project {}", userId, projectId);
                return true;
            } else {
                log.warn("User {} was not assigned to project {}", userId, projectId);
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error removing user {} from project {}", userId, projectId, e);
            return false;
        }
    }

    /**
     * Get all team members for a project
     * 
     * @param projectId Project ID
     * @return List of team members
     */
    public List<TeamMember> getProjectTeamMembers(Long projectId) {
        return projectRepository.findById(projectId)
                .map(Project::getTeamMembers)
                .map(List::copyOf)
                .orElse(List.of());
    }

    /**
     * Get all users for a project
     * 
     * @param projectId Project ID
     * @return List of users
     */
    public List<AppUser> getProjectUsers(Long projectId) {
        return projectRepository.findById(projectId)
                .map(Project::getUsers)
                .map(List::copyOf)
                .orElse(List.of());
    }

    /**
     * Get all available team members (not assigned to a specific project)
     * 
     * @param projectId Project ID (optional, for filtering)
     * @return List of available team members
     */
    public List<TeamMember> getAvailableTeamMembers(Long projectId) {
        List<TeamMember> allMembers = teamMemberRepository.findAll();
        
        if (projectId != null) {
            Set<TeamMember> assignedMembers = projectRepository.findById(projectId)
                    .map(Project::getTeamMembers)
                    .orElse(Set.of());
            
            return allMembers.stream()
                    .filter(member -> !assignedMembers.contains(member))
                    .toList();
        }
        
        return allMembers;
    }

    /**
     * Get all available users (not assigned to a specific project)
     * 
     * @param projectId Project ID (optional, for filtering)
     * @return List of available users
     */
    public List<AppUser> getAvailableUsers(Long projectId) {
        List<AppUser> allUsers = userRepository.findAll();
        
        if (projectId != null) {
            Set<AppUser> assignedUsers = projectRepository.findById(projectId)
                    .map(Project::getUsers)
                    .orElse(Set.of());
            
            return allUsers.stream()
                    .filter(user -> !assignedUsers.contains(user))
                    .toList();
        }
        
        return allUsers;
    }

    /**
     * Create a new team member and optionally assign to a project
     * 
     * @param teamMember Team member data
     * @param projectId Optional project ID to assign to
     * @return Created team member
     */
    @Transactional
    public TeamMember createTeamMember(TeamMember teamMember, Long projectId) {
        try {
            TeamMember saved = teamMemberRepository.save(teamMember);
            
            if (projectId != null) {
                assignTeamMemberToProject(projectId, saved.getId());
            }
            
            log.info("Created team member: {}", saved.getName());
            return saved;
            
        } catch (Exception e) {
            log.error("Error creating team member", e);
            throw e;
        }
    }

    /**
     * Update team member status
     * 
     * @param memberId Team member ID
     * @param status New status
     * @return true if successful
     */
    @Transactional
    public boolean updateTeamMemberStatus(Long memberId, MemberStatus status) {
        try {
            Optional<TeamMember> memberOpt = teamMemberRepository.findById(memberId);
            if (memberOpt.isEmpty()) {
                return false;
            }
            
            TeamMember member = memberOpt.get();
            member.setStatus(status);
            teamMemberRepository.save(member);
            
            log.info("Updated team member {} status to {}", memberId, status);
            return true;
            
        } catch (Exception e) {
            log.error("Error updating team member {} status", memberId, e);
            return false;
        }
    }
}
