package com.pms.service;

import com.pms.domain.*;
import com.pms.repository.ClientFolderShareRepository;
import com.pms.repository.ProjectMemberRepository;
import com.pms.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Centralized service for resolving the current user from a session token
 * and checking project-level CRUD permissions via the project_members table.
 *
 * Admin/Manager system-level roles always get full access.
 * Regular users get permissions based on their ProjectRole in the project_members table.
 */
@Service
public class ProjectPermissionService {

    private static final Logger log = LoggerFactory.getLogger(ProjectPermissionService.class);

    private final SessionRepository sessions;
    private final ProjectMemberRepository projectMembers;
    private final ClientFolderShareRepository clientFolderShares;

    public ProjectPermissionService(SessionRepository sessions,
                                     ProjectMemberRepository projectMembers,
                                     ClientFolderShareRepository clientFolderShares) {
        this.sessions = sessions;
        this.projectMembers = projectMembers;
        this.clientFolderShares = clientFolderShares;
    }

    /**
     * Resolve the currently authenticated user from the Authorization header.
     */
    public AppUser resolveUser(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        String token = auth.substring(7);
        return sessions.findByTokenAndRevokedFalse(token)
                .filter(st -> st.getExpiresAt().isAfter(Instant.now()))
                .map(st -> st.getUser())
                .orElse(null);
    }

    /**
     * Check if the user has system-level ADMIN or MANAGER role.
     */
    public boolean isAdminOrManager(AppUser u) {
        if (u == null || u.getRoles() == null) return false;
        return u.getRoles().contains(Role.ADMIN) || u.getRoles().contains(Role.MANAGER);
    }

    /**
     * Check if the user is a CLIENT employment type.
     */
    public boolean isClientUser(AppUser u) {
        return u != null && u.getEmploymentType() == EmploymentType.CLIENT;
    }

    /**
     * Check if a user has basic access to a project (can view it).
     * Admin/Manager always has access.
     * Regular users need to be in project_users, project_members, or matched by email in team_members.
     * Client users with folder shares in the project also get access.
     */
    public boolean hasProjectAccess(Project p, AppUser u) {
        if (u == null || p == null) return false;
        if (isAdminOrManager(u)) return true;
        // Check project_users table
        if (p.getUsers() != null && u.getId() != null) {
            Long uid = u.getId();
            if (p.getUsers().stream().anyMatch(x -> uid.equals(x.getId()))) return true;
        }
        // Check project_members table (users assigned via Team Management)
        if (u.getId() != null && p.getId() != null) {
            if (projectMembers.existsByProjectIdAndUserId(p.getId(), u.getId())) return true;
        }
        // Fallback: email match in team_members
        String email = u.getEmail();
        if (email != null && p.getTeamMembers() != null) {
            if (p.getTeamMembers().stream().anyMatch(m -> email.equalsIgnoreCase(m.getEmail()))) return true;
        }
        // Client users with folder shares in this project
        if (isClientUser(u) && u.getId() != null && p.getId() != null) {
            if (!clientFolderShares.findByProjectIdAndUserId(p.getId(), u.getId()).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Check if the user can CREATE resources within the given project.
     */
    public boolean canCreate(Project p, AppUser u) {
        if (isAdminOrManager(u)) return true;
        return checkMemberPermission(p, u, ProjectRole::isCanCreate);
    }

    /**
     * Check if the user can READ resources within the given project.
     */
    public boolean canRead(Project p, AppUser u) {
        if (isAdminOrManager(u)) return true;
        // Any user with project access can read
        if (hasProjectAccess(p, u)) return true;
        return checkMemberPermission(p, u, ProjectRole::isCanRead);
    }

    /**
     * Check if the user can UPDATE resources within the given project.
     */
    public boolean canUpdate(Project p, AppUser u) {
        if (isAdminOrManager(u)) return true;
        return checkMemberPermission(p, u, ProjectRole::isCanUpdate);
    }

    /**
     * Check if the user can DELETE resources within the given project.
     */
    public boolean canDelete(Project p, AppUser u) {
        if (isAdminOrManager(u)) return true;
        return checkMemberPermission(p, u, ProjectRole::isCanDelete);
    }

    /**
     * Get the user's project role name (e.g. "PROJECT_ADMIN", "FINANCE", "EDITOR").
     * Returns null if the user is not a project member.
     */
    public String getProjectRoleName(Project p, AppUser u) {
        if (u == null || p == null || u.getId() == null || p.getId() == null) return null;
        return projectMembers.findByProjectIdAndUserId(p.getId(), u.getId())
                .map(m -> m.getRole() != null ? m.getRole().getName() : null)
                .orElse(null);
    }

    /**
     * Check a specific CRUD permission for a user in a project
     * by looking up their ProjectMember record and its associated ProjectRole.
     */
    private boolean checkMemberPermission(Project p, AppUser u, Predicate<ProjectRole> permCheck) {
        if (u == null || p == null || u.getId() == null || p.getId() == null) {
            log.warn("[checkMemberPermission] Null check failed: u={}, p={}", u, p);
            return false;
        }

        log.info("[checkMemberPermission] Looking up ProjectMember for projectId={}, userId={}", p.getId(), u.getId());

        Optional<ProjectMember> memberOpt = projectMembers.findByProjectIdAndUserId(p.getId(), u.getId());

        if (memberOpt.isEmpty()) {
            log.warn("[checkMemberPermission] NO ProjectMember found for projectId={}, userId={}", p.getId(), u.getId());
            return false;
        }

        ProjectMember member = memberOpt.get();
        ProjectRole role = member.getRole();

        if (role == null) {
            log.warn("[checkMemberPermission] ProjectMember found (id={}) but role is NULL", member.getId());
            return false;
        }

        log.info("[checkMemberPermission] Found role: id={}, name='{}', canCreate={}, canRead={}, canUpdate={}, canDelete={}",
                role.getId(), role.getName(), role.isCanCreate(), role.isCanRead(), role.isCanUpdate(), role.isCanDelete());

        boolean result = permCheck.test(role);
        log.info("[checkMemberPermission] Permission check result: {}", result);
        return result;
    }
}
