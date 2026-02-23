package com.pms.controller;

import com.pms.domain.*;
import com.pms.repository.*;
import com.pms.service.ProjectPermissionService;
import com.pms.service.ProjectService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectRepository projectRepository;
    private final WorkflowStageRepository workflowStageRepository;
    private final ProjectEventRepository projectEventRepository;
    private final ProjectDocumentRepository projectDocumentRepository;
    private final ContactRepository contactRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final SessionRepository sessions;
    private final UserRepository users;
    private final ProjectService projectService;
    private final ProjectPermissionService permissionService;
    private final ClientRepository clientRepository;
    private final ClientContactRepository clientContactRepository;
    private final VenueRepository venueRepository;
    private final CityRepository cityRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public ProjectController(ProjectRepository projectRepository,
            WorkflowStageRepository workflowStageRepository,
            ProjectEventRepository projectEventRepository,
            ProjectDocumentRepository projectDocumentRepository,
            ContactRepository contactRepository,
            TeamMemberRepository teamMemberRepository,
            SessionRepository sessions,
            UserRepository users,
            ProjectService projectService,
            ProjectPermissionService permissionService,
            ClientRepository clientRepository,
            ClientContactRepository clientContactRepository,
            VenueRepository venueRepository,
            CityRepository cityRepository) {
        this.projectRepository = projectRepository;
        this.workflowStageRepository = workflowStageRepository;
        this.projectEventRepository = projectEventRepository;
        this.projectDocumentRepository = projectDocumentRepository;
        this.contactRepository = contactRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.sessions = sessions;
        this.users = users;
        this.projectService = projectService;
        this.permissionService = permissionService;
        this.clientRepository = clientRepository;
        this.clientContactRepository = clientContactRepository;
        this.venueRepository = venueRepository;
        this.cityRepository = cityRepository;
    }

    @GetMapping
    public List<Project> listProjects(@RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null)
            return projectRepository.findAll();
        if (isAdminOrManager(u))
            return projectRepository.findAll();
        return projectRepository.findAll().stream()
                .filter(p -> hasAccess(p, u))
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Project> getProject(@PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        return projectRepository.findById(id)
                .map(p -> {
                    if (u != null && !isAdminOrManager(u) && !hasAccess(p, u)) {
                        return ResponseEntity.status(403).body((Project) null);
                    }
                    return ResponseEntity.ok(p);
                })
                .orElseGet(() -> ResponseEntity.status(404).body((Project) null));
    }

    @PostMapping
    public ResponseEntity<Project> createProject(@RequestBody CreateProjectRequest request,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null || !isAdminOrManager(u))
            return ResponseEntity.status(403).body((Project) null);

        // Build Project entity from request
        Project project = new Project();
        project.setName(request.name);
        project.setDescription(request.description);
        project.setStatus(ProjectStatus.IN_PROGRESS);
        project.setProgress(0);

        if (request.target != null) {
            project.setTarget(request.target);
        }

        if (request.hedging != null) {
            project.setHedging(request.hedging);
        }

        if (request.eventDate != null && !request.eventDate.isBlank()) {
            try {
                String dateStr = request.eventDate.contains("T")
                        ? request.eventDate.split("T")[0]
                        : request.eventDate;
                java.time.LocalDate date = java.time.LocalDate.parse(dateStr);
                project.setStartDate(date);
                project.setEndDate(date);
            } catch (Exception e) {
                // Invalid date format, skip
            }
        }

        // Resolve client (company)
        if (request.clientId != null) {
            clientRepository.findById(request.clientId).ifPresent(client -> {
                project.setClientEntity(client);
                project.setClient(client.getName());
            });
        } else if (request.clientName != null && !request.clientName.isBlank()) {
            Client client = clientRepository.findByName(request.clientName)
                    .orElseGet(() -> {
                        Client newClient = new Client();
                        newClient.setName(request.clientName);
                        return clientRepository.save(newClient);
                    });
            project.setClientEntity(client);
            project.setClient(client.getName());
        }

        // Resolve contact (find or create under client)
        if (request.contactId == null && request.contactName != null
                && !request.contactName.isBlank() && project.getClientEntity() != null) {
            Client client = project.getClientEntity();
            java.util.List<ClientContact> existing = clientContactRepository.findByClientId(client.getId());
            boolean contactExists = existing.stream()
                    .anyMatch(c -> c.getName().equalsIgnoreCase(request.contactName));
            if (!contactExists) {
                ClientContact newContact = ClientContact.builder()
                        .name(request.contactName)
                        .client(client)
                        .build();
                clientContactRepository.save(newContact);
            }
        }

        // Resolve venue (find or create)
        if (request.venueId != null) {
            venueRepository.findById(request.venueId).ifPresent(project::setVenue);
        } else if (request.venueName != null && !request.venueName.isBlank()) {
            // Resolve city entity from venueCity string
            City cityEntity = null;
            if (request.venueCity != null && !request.venueCity.isBlank()) {
                String cityName = request.venueCity.trim();
                cityEntity = cityRepository.findByName(cityName)
                        .orElseGet(() -> {
                            City c = new City();
                            c.setName(cityName);
                            return cityRepository.save(c);
                        });
            }
            City finalCity = cityEntity;
            Venue venue = venueRepository.findByNameAndCity(request.venueName.trim(), cityEntity)
                    .orElseGet(() -> {
                        Venue v = new Venue();
                        v.setName(request.venueName.trim());
                        v.setCity(finalCity);
                        return venueRepository.save(v);
                    });
            project.setVenue(venue);
        }

        // Build team assignments map — Account Manager is the top-level role (PROJECT_ADMIN)
        java.util.Map<String, Long> departmentHeads = new java.util.LinkedHashMap<>();
        if (request.picUserId != null)
            departmentHeads.put("Project Officer", request.picUserId);
        if (request.adminUserId != null)
            departmentHeads.put("Admin", request.adminUserId);
        if (request.productionUserId != null)
            departmentHeads.put("Production", request.productionUserId);
        if (request.designUserId != null)
            departmentHeads.put("Design", request.designUserId);

        Project saved = projectService.createProjectWithDefaults(project, request.accountManagerUserId, departmentHeads);
        return ResponseEntity.created(URI.create("/api/projects/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Project> updateProject(@PathVariable Long id,
            @RequestBody java.util.Map<String, Object> updates,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        System.out.println("PUT /api/projects/" + id + " called with updates: " + updates);
        AppUser u = currentUser(auth);
        if (u == null || !isAdminOrManager(u)) {
            System.err.println("Unauthorized access attempt for project " + id);
            return ResponseEntity.status(403).body((Project) null);
        }
        return projectRepository.findById(id)
                .map(existing -> {
                    System.out.println("Found project: " + existing.getId() + " - " + existing.getName());
                    // Merge fields from incoming updates with existing project (partial update
                    // support)
                    if (updates.containsKey("name")) {
                        Object nameObj = updates.get("name");
                        if (nameObj != null && !nameObj.toString().isBlank()) {
                            existing.setName(nameObj.toString());
                        }
                    }
                    if (updates.containsKey("description")) {
                        Object descObj = updates.get("description");
                        if (descObj != null) {
                            existing.setDescription(descObj.toString());
                        }
                    }
                    if (updates.containsKey("status")) {
                        Object statusObj = updates.get("status");
                        if (statusObj != null) {
                            try {
                                existing.setStatus(ProjectStatus.valueOf(statusObj.toString().toUpperCase()));
                            } catch (IllegalArgumentException e) {
                                // Invalid status, skip
                            }
                        }
                    }
                    if (updates.containsKey("target")) {
                        Object targetObj = updates.get("target");
                        if (targetObj != null) {
                            try {
                                existing.setTarget(Integer.parseInt(targetObj.toString()));
                            } catch (NumberFormatException e) {
                                // Invalid target, skip
                            }
                        }
                    }
                    if (updates.containsKey("progress")) {
                        Object progressObj = updates.get("progress");
                        if (progressObj != null) {
                            try {
                                existing.setProgress(Integer.parseInt(progressObj.toString()));
                            } catch (NumberFormatException e) {
                                // Invalid progress, skip
                            }
                        }
                    } else {
                        // Calculate progress from workflow stages if not explicitly set
                        List<WorkflowStage> stages = workflowStageRepository.findByProjectId(id);
                        if (stages != null && !stages.isEmpty()) {
                            long completed = stages.stream()
                                    .filter(s -> s.getStatus() == com.pms.domain.StageStatus.COMPLETED)
                                    .count();
                            int progress = (int) ((completed * 100) / stages.size());
                            existing.setProgress(progress);
                        }
                    }
                    if (updates.containsKey("startDate")) {
                        Object startDateObj = updates.get("startDate");
                        if (startDateObj != null) {
                            try {
                                existing.setStartDate(java.time.LocalDate.parse(startDateObj.toString()));
                            } catch (Exception e) {
                                // Invalid date format, skip
                            }
                        }
                    }
                    // Handle both endDate and eventDate (eventDate is mapped to endDate)
                    if (updates.containsKey("endDate") || updates.containsKey("eventDate")) {
                        Object dateObj = updates.containsKey("endDate") ? updates.get("endDate")
                                : updates.get("eventDate");
                        if (dateObj != null && !dateObj.toString().trim().isEmpty()) {
                            try {
                                // Handle both LocalDate objects and string dates
                                if (dateObj instanceof java.time.LocalDate) {
                                    existing.setEndDate((java.time.LocalDate) dateObj);
                                    System.out.println("Updated endDate from LocalDate: " + existing.getEndDate());
                                } else {
                                    String dateStr = dateObj.toString().trim();
                                    // Handle date strings that might include time (take only the date part)
                                    if (dateStr.contains("T")) {
                                        dateStr = dateStr.split("T")[0];
                                    }
                                    existing.setEndDate(java.time.LocalDate.parse(dateStr));
                                    System.out.println(
                                            "Updated endDate from string: " + dateStr + " -> " + existing.getEndDate());
                                }
                            } catch (Exception e) {
                                System.err.println("Failed to parse date: " + dateObj + " - " + e.getMessage());
                                e.printStackTrace();
                                // Invalid date format, skip
                            }
                        }
                    }
                    if (updates.containsKey("budget")) {
                        Object budgetObj = updates.get("budget");
                        if (budgetObj != null) {
                            try {
                                existing.setBudget(new java.math.BigDecimal(budgetObj.toString()));
                            } catch (NumberFormatException e) {
                                // Invalid budget, skip
                            }
                        }
                    }
                    if (updates.containsKey("hedging")) {
                        Object hedgingObj = updates.get("hedging");
                        if (hedgingObj != null) {
                            try {
                                existing.setHedging(new java.math.BigDecimal(hedgingObj.toString()));
                            } catch (NumberFormatException e) {
                                // Invalid hedging value, skip
                            }
                        }
                    }
                    if (updates.containsKey("client")) {
                        Object clientObj = updates.get("client");
                        if (clientObj != null) {
                            existing.setClient(clientObj.toString());
                        }
                    }
                    if (updates.containsKey("venueId")) {
                        Object venueIdObj = updates.get("venueId");
                        if (venueIdObj != null) {
                            try {
                                Long vid = Long.parseLong(venueIdObj.toString());
                                venueRepository.findById(vid).ifPresent(existing::setVenue);
                            } catch (NumberFormatException e) {
                                // Invalid venue ID, skip
                            }
                        } else {
                            existing.setVenue(null);
                        }
                    }
                    // Note: We don't update relationships (clientEntity, stages, events, etc.) from
                    // the request
                    // to avoid accidentally clearing them. Use specific endpoints for those.

                    Project saved = projectRepository.save(existing);
                    System.out.println("Saved project " + saved.getId() + " with endDate: " + saved.getEndDate());
                    return ResponseEntity.ok(saved);
                })
                .orElseGet(() -> {
                    System.err.println("Project not found with id: " + id);
                    return ResponseEntity.status(404).body((Project) null);
                });
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProject(@PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null || !isAdminOrManager(u))
            return ResponseEntity.status(403).<Void>build();
        if (!projectRepository.existsById(id))
            return ResponseEntity.status(404).<Void>build();
        projectRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<?> completeProject(@PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null || !isAdminOrManager(u))
            return ResponseEntity.status(403).body("Unauthorized");
        return projectRepository.findById(id)
                .map(project -> {
                    List<WorkflowStage> stages = workflowStageRepository.findByProjectId(id);
                    if (stages != null && !stages.isEmpty()) {
                        // Find the last stage by highest orderSequence
                        WorkflowStage lastStage = stages.stream()
                                .max(java.util.Comparator.comparingInt(
                                        s -> s.getOrderSequence() != null ? s.getOrderSequence() : 0))
                                .orElse(null);
                        if (lastStage != null && lastStage.getStatus() != com.pms.domain.StageStatus.COMPLETED) {
                            return ResponseEntity.badRequest()
                                    .body("Cannot complete project: the last stage is not yet completed");
                        }
                    }
                    project.setStatus(ProjectStatus.COMPLETED);
                    project.setProgress(100);
                    projectRepository.save(project);
                    return ResponseEntity.ok(project);
                })
                .orElseGet(() -> ResponseEntity.status(404).body(null));
    }

    // --- Nested resources ---
    @GetMapping("/{projectId}/stages")
    public ResponseEntity<List<WorkflowStage>> listStages(@PathVariable Long projectId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<Project> opt = projectRepository.findById(projectId);
        if (opt.isEmpty())
            return ResponseEntity.status(404).<List<WorkflowStage>>build();
        AppUser u = currentUser(auth);
        if (u != null && !permissionService.canRead(opt.get(), u))
            return ResponseEntity.status(403).body((java.util.List<com.pms.domain.WorkflowStage>) null);
        return ResponseEntity.ok(workflowStageRepository.findByProjectId(projectId));
    }

    @PostMapping("/{projectId}/stages")
    public ResponseEntity<WorkflowStage> addStage(@PathVariable Long projectId, @Valid @RequestBody WorkflowStage stage,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<Project> opt = projectRepository.findById(projectId);
        if (opt.isEmpty())
            return ResponseEntity.status(404).<WorkflowStage>build();
        AppUser u = currentUser(auth);
        if (u != null && !permissionService.canCreate(opt.get(), u))
            return ResponseEntity.status(403).body((WorkflowStage) null);
        stage.setId(null);
        stage.setProject(opt.get());
        WorkflowStage saved = workflowStageRepository.save(stage);
        return ResponseEntity.created(URI.create("/api/projects/" + projectId + "/stages/" + saved.getId()))
                .body(saved);
    }

    @GetMapping("/{projectId}/events")
    public ResponseEntity<List<ProjectEvent>> listEvents(@PathVariable Long projectId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<Project> opt = projectRepository.findById(projectId);
        if (opt.isEmpty())
            return ResponseEntity.status(404).<List<ProjectEvent>>build();
        AppUser u = currentUser(auth);
        if (u != null && !permissionService.canRead(opt.get(), u))
            return ResponseEntity.status(403).body((java.util.List<com.pms.domain.ProjectEvent>) null);
        return ResponseEntity.ok(projectEventRepository.findByProjectId(projectId));
    }

    @PostMapping("/{projectId}/events")
    public ResponseEntity<ProjectEvent> addEvent(@PathVariable Long projectId, @Valid @RequestBody ProjectEvent event,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<Project> opt = projectRepository.findById(projectId);
        if (opt.isEmpty())
            return ResponseEntity.status(404).<ProjectEvent>build();
        AppUser u = currentUser(auth);
        if (u != null && !permissionService.canCreate(opt.get(), u))
            return ResponseEntity.status(403).body((ProjectEvent) null);
        event.setId(null);
        event.setProject(opt.get());
        ProjectEvent saved = projectEventRepository.save(event);
        return ResponseEntity.created(URI.create("/api/projects/" + projectId + "/events/" + saved.getId()))
                .body(saved);
    }

    @GetMapping("/{projectId}/documents")
    public ResponseEntity<List<ProjectDocument>> listDocuments(@PathVariable Long projectId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<Project> opt = projectRepository.findById(projectId);
        if (opt.isEmpty())
            return ResponseEntity.status(404).<List<ProjectDocument>>build();
        AppUser u = currentUser(auth);
        if (u != null && !permissionService.canRead(opt.get(), u))
            return ResponseEntity.status(403).body((java.util.List<com.pms.domain.ProjectDocument>) null);
        return ResponseEntity.ok(projectDocumentRepository.findByProjectId(projectId));
    }

    @PostMapping("/{projectId}/documents")
    public ResponseEntity<ProjectDocument> addDocument(@PathVariable Long projectId,
            @Valid @RequestBody ProjectDocument doc,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<Project> opt = projectRepository.findById(projectId);
        if (opt.isEmpty())
            return ResponseEntity.status(404).<ProjectDocument>build();
        AppUser u = currentUser(auth);
        if (u != null && !permissionService.canCreate(opt.get(), u))
            return ResponseEntity.status(403).body((ProjectDocument) null);
        doc.setId(null);
        doc.setProject(opt.get());
        ProjectDocument saved = projectDocumentRepository.save(doc);
        return ResponseEntity.created(URI.create("/api/projects/" + projectId + "/documents/" + saved.getId()))
                .body(saved);
    }

    @GetMapping("/{projectId}/contacts")
    public ResponseEntity<List<Contact>> listContacts(@PathVariable Long projectId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<Project> opt = projectRepository.findById(projectId);
        if (opt.isEmpty())
            return ResponseEntity.status(404).<List<Contact>>build();
        AppUser u = currentUser(auth);
        if (u != null && !permissionService.canRead(opt.get(), u))
            return ResponseEntity.status(403).body((java.util.List<com.pms.domain.Contact>) null);
        return ResponseEntity.ok(contactRepository.findByProjectId(projectId));
    }

    @PostMapping("/{projectId}/contacts")
    public ResponseEntity<Contact> addContact(@PathVariable Long projectId, @Valid @RequestBody Contact contact,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<Project> opt = projectRepository.findById(projectId);
        if (opt.isEmpty())
            return ResponseEntity.status(404).<Contact>build();
        AppUser u = currentUser(auth);
        if (u != null && !permissionService.canCreate(opt.get(), u))
            return ResponseEntity.status(403).body((Contact) null);
        contact.setId(null);
        contact.setProject(opt.get());
        Contact saved = contactRepository.save(contact);
        return ResponseEntity.created(URI.create("/api/projects/" + projectId + "/contacts/" + saved.getId()))
                .body(saved);
    }

    @GetMapping("/{projectId}/team-members")
    public ResponseEntity<List<TeamMember>> listTeam(@PathVariable Long projectId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        System.out.println("GET /api/projects/" + projectId + "/team-members called");

        try {
            // First check if project exists and user has access
            Optional<Project> optP = projectRepository.findById(projectId);
            if (optP.isEmpty()) {
                System.err.println("Project not found: " + projectId);
                return ResponseEntity.status(404).<List<TeamMember>>build();
            }

            Project p = optP.get();
            AppUser u = currentUser(auth);
            if (u != null && !isAdminOrManager(u) && !hasAccess(p, u)) {
                System.err.println("Access denied for user to project " + projectId);
                return ResponseEntity.status(403).<List<TeamMember>>build();
            }

            // Query the join table directly to get member IDs, then fetch team members
            // This avoids loading the full Project entity with all its relationships
            @SuppressWarnings("unchecked")
            List<Long> memberIds = entityManager.createNativeQuery(
                    "SELECT member_id FROM project_team_members WHERE project_id = ?")
                    .setParameter(1, projectId)
                    .getResultList();

            System.out.println("Found " + memberIds.size() + " team member IDs for project " + projectId);

            if (memberIds.isEmpty()) {
                return ResponseEntity.ok(new java.util.ArrayList<>());
            }

            // Fetch the team members by their IDs
            List<TeamMember> result = teamMemberRepository.findAllById(memberIds);
            System.out.println("Returning " + result.size() + " team members");

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            System.err.println("Error in listTeam: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).<List<TeamMember>>build();
        }
    }

    @PostMapping("/{projectId}/team-members/{memberId}")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Void> assignTeamMember(@PathVariable Long projectId, @PathVariable Long memberId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null || !isAdminOrManager(u)) {
            System.err.println("Unauthorized access attempt for project " + projectId);
            return ResponseEntity.status(403).<Void>build();
        }

        try {
            Optional<Project> optP = projectRepository.findById(projectId);
            Optional<TeamMember> optM = teamMemberRepository.findById(memberId);

            if (optP.isEmpty()) {
                System.err.println("Project not found: " + projectId);
                return ResponseEntity.status(404).<Void>build();
            }
            if (optM.isEmpty()) {
                System.err.println("TeamMember not found: " + memberId);
                return ResponseEntity.status(404).<Void>build();
            }

            Project p = optP.get();
            TeamMember m = optM.get();

            System.out.println("Assigning team member " + m.getId() + " (" + m.getName() + ") to project " + p.getId()
                    + " (" + p.getName() + ")");

            // Check if already exists using native query
            try {
                Long count = ((Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM project_team_members WHERE project_id = ? AND member_id = ?")
                        .setParameter(1, projectId)
                        .setParameter(2, memberId)
                        .getSingleResult()).longValue();

                if (count > 0) {
                    System.out.println("Team member already assigned to project");
                    return ResponseEntity.noContent().build();
                }
            } catch (Exception checkEx) {
                System.err.println("Error checking existing assignment: " + checkEx.getMessage());
            }

            // Use direct SQL insert - works for both H2 and PostgreSQL
            try {
                int rowsAffected = entityManager.createNativeQuery(
                        "INSERT INTO project_team_members (project_id, member_id) VALUES (?, ?)")
                        .setParameter(1, projectId)
                        .setParameter(2, memberId)
                        .executeUpdate();
                entityManager.flush();

                System.out.println("Direct SQL insert successful, rows affected: " + rowsAffected);
                System.out.println("Successfully assigned team member to project");
                return ResponseEntity.noContent().build();

            } catch (Exception sqlEx) {
                // If it's a constraint violation (duplicate), that's okay
                if (sqlEx.getMessage() != null && sqlEx.getMessage().contains("constraint") ||
                        sqlEx.getMessage() != null && sqlEx.getMessage().contains("duplicate") ||
                        sqlEx.getMessage() != null && sqlEx.getMessage().contains("unique")) {
                    System.out.println("Team member already assigned (constraint violation)");
                    return ResponseEntity.noContent().build();
                }

                System.err.println("Direct SQL insert failed: " + sqlEx.getMessage());
                sqlEx.printStackTrace();

                // Fallback to JPA approach
                System.out.println("Falling back to JPA approach...");
                try {
                    // Load project with team members
                    Project projectWithMembers = projectRepository.findWithTeamMembersById(projectId).orElse(p);
                    java.util.Set<TeamMember> teamMembers = projectWithMembers.getTeamMembers();
                    if (teamMembers == null) {
                        teamMembers = new java.util.HashSet<>();
                        projectWithMembers.setTeamMembers(teamMembers);
                    }

                    // Add the team member
                    teamMembers.add(m);

                    // Save
                    projectRepository.saveAndFlush(projectWithMembers);

                    System.out.println("JPA fallback successful");
                    return ResponseEntity.noContent().build();
                } catch (Exception jpaEx) {
                    System.err.println("JPA fallback also failed: " + jpaEx.getMessage());
                    jpaEx.printStackTrace();
                    return ResponseEntity.status(500).<Void>build();
                }
            }

        } catch (Exception e) {
            System.err.println("Error assigning team member: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).<Void>build();
        }
    }

    @DeleteMapping("/{projectId}/team-members/{memberId}")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Void> unassignTeamMember(@PathVariable Long projectId, @PathVariable Long memberId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null || !isAdminOrManager(u))
            return ResponseEntity.status(403).<Void>build();
        Optional<Project> optP = projectRepository.findById(projectId);
        Optional<TeamMember> optM = teamMemberRepository.findById(memberId);
        if (optP.isEmpty() || optM.isEmpty())
            return ResponseEntity.status(404).<Void>build();
        Project p = optP.get();
        TeamMember m = optM.get();

        // Remove from both sides of the relationship
        if (p.getTeamMembers() != null && p.getTeamMembers().contains(m)) {
            p.getTeamMembers().remove(m);
            if (m.getProjects() != null) {
                m.getProjects().remove(p);
            }
            projectRepository.saveAndFlush(p);
            teamMemberRepository.saveAndFlush(m);
        }
        return ResponseEntity.noContent().build();
    }

    // --- Project users (AppUser) membership ---
    @GetMapping("/{projectId}/users")
    public ResponseEntity<List<java.util.Map<String, Object>>> listProjectUsers(@PathVariable Long projectId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return projectRepository.findById(projectId)
                .map(p -> {
                    AppUser u = currentUser(auth);
                    if (u != null && !isAdminOrManager(u) && !hasAccess(p, u))
                        return ResponseEntity.status(403).body((java.util.List<java.util.Map<String, Object>>) null);
                    var list = p.getUsers().stream().map(x -> java.util.Map.of(
                            "id", x.getId(),
                            "name", x.getName(),
                            "email", x.getEmail(),
                            "roles", x.getRoles())).toList();
                    return ResponseEntity.ok(list);
                })
                .orElse(ResponseEntity.status(404).<List<java.util.Map<String, Object>>>build());
    }

    @PostMapping("/{projectId}/users/{userId}")
    public ResponseEntity<Void> assignProjectUser(@PathVariable Long projectId, @PathVariable Long userId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null || !isAdminOrManager(u))
            return ResponseEntity.status(403).<Void>build();
        Optional<Project> optP = projectRepository.findById(projectId);
        Optional<AppUser> optU = users.findById(userId);
        if (optP.isEmpty() || optU.isEmpty())
            return ResponseEntity.status(404).<Void>build();
        Project p = optP.get();
        p.getUsers().add(optU.get());
        projectRepository.save(p);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{projectId}/users/{userId}")
    public ResponseEntity<Void> unassignProjectUser(@PathVariable Long projectId, @PathVariable Long userId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null || !isAdminOrManager(u))
            return ResponseEntity.status(403).<Void>build();
        Optional<Project> optP = projectRepository.findById(projectId);
        Optional<AppUser> optU = users.findById(userId);
        if (optP.isEmpty() || optU.isEmpty())
            return ResponseEntity.status(404).<Void>build();
        Project p = optP.get();
        p.getUsers().remove(optU.get());
        projectRepository.save(p);
        return ResponseEntity.noContent().build();
    }

    private AppUser currentUser(String auth) {
        if (auth == null || !auth.startsWith("Bearer "))
            return null;
        String token = auth.substring(7);
        return sessions.findByTokenAndRevokedFalse(token)
                .filter(st -> st.getExpiresAt().isAfter(java.time.Instant.now()))
                .map(st -> st.getUser())
                .orElse(null);
    }

    private boolean isAdminOrManager(AppUser u) {
        if (u == null || u.getRoles() == null)
            return false;
        return u.getRoles().contains(Role.ADMIN) || u.getRoles().contains(Role.MANAGER);
    }

    private boolean hasAccess(Project p, AppUser u) {
        return permissionService.hasProjectAccess(p, u);
    }

    /** DTO for project creation requests */
    public static class CreateProjectRequest {
        public String name;
        public String description;
        public Integer target;
        public String eventDate;
        // Client fields
        public Long clientId;
        public String clientName;
        public Long contactId;
        public String contactName;
        // Venue fields
        public Long venueId;
        public String venueName;
        public String venueCity;
        // Hedging
        public java.math.BigDecimal hedging;
        // Team member user IDs
        public Long picUserId;
        public Long accountManagerUserId;
        public Long adminUserId;
        public Long productionUserId;
        public Long designUserId;
        public Long financeUserId;

        // Jackson needs getters/setters for deserialization
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Integer getTarget() { return target; }
        public void setTarget(Integer target) { this.target = target; }
        public String getEventDate() { return eventDate; }
        public void setEventDate(String eventDate) { this.eventDate = eventDate; }
        public Long getClientId() { return clientId; }
        public void setClientId(Long clientId) { this.clientId = clientId; }
        public String getClientName() { return clientName; }
        public void setClientName(String clientName) { this.clientName = clientName; }
        public Long getContactId() { return contactId; }
        public void setContactId(Long contactId) { this.contactId = contactId; }
        public String getContactName() { return contactName; }
        public void setContactName(String contactName) { this.contactName = contactName; }
        public Long getVenueId() { return venueId; }
        public void setVenueId(Long venueId) { this.venueId = venueId; }
        public String getVenueName() { return venueName; }
        public void setVenueName(String venueName) { this.venueName = venueName; }
        public String getVenueCity() { return venueCity; }
        public void setVenueCity(String venueCity) { this.venueCity = venueCity; }
        public java.math.BigDecimal getHedging() { return hedging; }
        public void setHedging(java.math.BigDecimal hedging) { this.hedging = hedging; }
        public Long getPicUserId() { return picUserId; }
        public void setPicUserId(Long picUserId) { this.picUserId = picUserId; }
        public Long getAccountManagerUserId() { return accountManagerUserId; }
        public void setAccountManagerUserId(Long v) { this.accountManagerUserId = v; }
        public Long getAdminUserId() { return adminUserId; }
        public void setAdminUserId(Long v) { this.adminUserId = v; }
        public Long getProductionUserId() { return productionUserId; }
        public void setProductionUserId(Long v) { this.productionUserId = v; }
        public Long getDesignUserId() { return designUserId; }
        public void setDesignUserId(Long v) { this.designUserId = v; }
        public Long getFinanceUserId() { return financeUserId; }
        public void setFinanceUserId(Long v) { this.financeUserId = v; }
    }
}
