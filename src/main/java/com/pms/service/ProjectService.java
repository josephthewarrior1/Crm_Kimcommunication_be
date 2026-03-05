package com.pms.service;

import com.pms.domain.DocumentTemplate;
import com.pms.domain.Project;
import com.pms.domain.ProjectDocument;
import com.pms.domain.ProjectMember;
import com.pms.domain.ProjectRole;
import com.pms.domain.ProjectTeam;
import com.pms.domain.StageStatus;
import com.pms.domain.WorkflowStage;
import com.pms.repository.DocumentTemplateRepository;
import com.pms.repository.ProjectDocumentRepository;
import com.pms.repository.ProjectMemberRepository;
import com.pms.repository.ProjectRepository;
import com.pms.repository.ProjectRoleRepository;
import com.pms.repository.ProjectTeamRepository;
import com.pms.repository.TeamMemberRepository;
import com.pms.repository.UserRepository;
import com.pms.repository.WorkflowStageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class ProjectService {
        private final ProjectRepository projectRepository;
        private final WorkflowStageRepository stageRepository;
        private final ProjectDocumentRepository documentRepository;
        private final DocumentTemplateRepository templateRepository;
        private final UserRepository userRepository;
        private final TeamMemberRepository teamMemberRepository;
        private final ProjectRoleRepository roleRepository;
        private final ProjectMemberRepository memberRepository;
        private final ProjectTeamRepository teamRepository;

        public ProjectService(ProjectRepository projectRepository, WorkflowStageRepository stageRepository,
                        ProjectDocumentRepository documentRepository, DocumentTemplateRepository templateRepository,
                        UserRepository userRepository, TeamMemberRepository teamMemberRepository,
                        ProjectRoleRepository roleRepository, ProjectMemberRepository memberRepository,
                        ProjectTeamRepository teamRepository) {
                this.projectRepository = projectRepository;
                this.stageRepository = stageRepository;
                this.documentRepository = documentRepository;
                this.templateRepository = templateRepository;
                this.userRepository = userRepository;
                this.teamMemberRepository = teamMemberRepository;
                this.roleRepository = roleRepository;
                this.memberRepository = memberRepository;
                this.teamRepository = teamRepository;
        }

        // @Transactional ensures that if saving stages fails, the Project is also
        // undone (rolled back)
        @Transactional
        public Project createProjectWithDefaults(Project project) {
                // 1. Save Project
                Project savedProject = projectRepository.save(project);

                // 2. Create Docs from Templates AND capture the list
                List<ProjectDocument> createdDocs = cloneFromTemplates(savedProject);

                // 3. Generate Stages (Passing docs so we can link them)
                List<WorkflowStage> createdStages = generateDefaultStages(savedProject, createdDocs);

                // 4. Save Stages
                stageRepository.saveAll(createdStages);

                // 5. Update Documents (To save the Stage FK relationship)
                // Since we modified the docs inside generateDefaultStages by setting their
                // stage,
                // we must save them again to persist the relationship.
                documentRepository.saveAll(createdDocs);

                return savedProject;
        }

        /**
         * Create project with defaults AND team members.
         */
        @Transactional
        public Project createProjectWithDefaults(Project project, Long accountManagerUserId,
                        java.util.Map<String, Long> departmentHeads) {
                // Delegate to existing method for project + docs + stages
                Project saved = createProjectWithDefaults(project);

                // Resolve roles
                ProjectRole adminRole = roleRepository.findByName("PROJECT_ADMIN").orElse(null);
                ProjectRole editorRole = roleRepository.findByName("EDITOR").orElse(null);

                // Create Account Manager first (top-level role, needed as manager reference for department heads)
                ProjectMember amMember = addMember(saved, accountManagerUserId, "Account Manager", adminRole, null);

                // Create department heads with Account Manager as manager
                for (java.util.Map.Entry<String, Long> entry : departmentHeads.entrySet()) {
                        addMember(saved, entry.getValue(), entry.getKey(), editorRole, amMember);
                }

                // Create default Finance team
                ProjectTeam financeTeam = ProjectTeam.builder()
                                .project(saved)
                                .name("Finance")
                                .sortOrder(0)
                                .build();
                financeTeam = teamRepository.save(financeTeam);

                // Auto-assign Finance members to the Finance team
                ProjectMember financeHead = addMemberByName(saved, "Sabrina", "Finance", editorRole, amMember,
                                financeTeam);
                addMemberByName(saved, "Renny", "Finance Assistant", editorRole,
                                financeHead != null ? financeHead : amMember, financeTeam);

                return saved;
        }

        private ProjectMember addMemberByName(Project project, String userName, String jobTitle,
                        ProjectRole role, ProjectMember manager) {
                return addMemberByName(project, userName, jobTitle, role, manager, null);
        }

        private ProjectMember addMemberByName(Project project, String userName, String jobTitle,
                        ProjectRole role, ProjectMember manager, ProjectTeam team) {
                return userRepository.findByName(userName)
                                .map(user -> addMember(project, user.getId(), jobTitle, role, manager, team))
                                .orElse(null);
        }

        private ProjectMember addMember(Project project, Long userId, String jobTitle,
                        ProjectRole role, ProjectMember manager) {
                return addMember(project, userId, jobTitle, role, manager, null);
        }

        private ProjectMember addMember(Project project, Long userId, String jobTitle,
                        ProjectRole role, ProjectMember manager, ProjectTeam team) {
                if (userId == null)
                        return null;
                return userRepository.findById(userId).map(user -> {
                        if (!memberRepository.existsByProjectIdAndUserId(project.getId(), user.getId())) {
                                ProjectMember member = ProjectMember.builder()
                                                .project(project)
                                                .user(user)
                                                .jobTitle(jobTitle)
                                                .role(role)
                                                .manager(manager)
                                                .team(team)
                                                .teamType("ADMINISTRATION")
                                                .joinedAt(LocalDateTime.now())
                                                .build();
                                return memberRepository.save(member);
                        }
                        return memberRepository.findByProjectIdAndUserId(project.getId(), user.getId())
                                        .orElse(null);
                }).orElse(null);
        }

        private List<ProjectDocument> cloneFromTemplates(Project newProject) {
                List<DocumentTemplate> templates = templateRepository.findByIsActiveTrue();
                List<ProjectDocument> newDocs = new ArrayList<>();

                for (DocumentTemplate tmpl : templates) {
                        newDocs.add(ProjectDocument.builder()
                                        .project(newProject)
                                        .name(tmpl.getName())
                                        .description(tmpl.getDescription())
                                        .type(tmpl.getType())
                                        .status("pending")
                                        .uploadedAt(LocalDateTime.now())
                                        .url(tmpl.getUrl())
                                        .build());
                }

                // Save first to generate IDs (if needed) and return the managed entities
                return documentRepository.saveAll(newDocs);
        }

        private List<WorkflowStage> generateDefaultStages(Project project, List<ProjectDocument> documents) {
                List<WorkflowStage> stages = new ArrayList<>();

                // 1. Client Brief -> Link "Template MOM" & "Template Brief"
                WorkflowStage stage1 = createStage(project, 1, "Client Brief",
                                "Initial client request...");
                linkDoc(stage1, documents, "Template MOM");
                stages.add(stage1);

                // 2. Initial Proposal
                WorkflowStage stage2 = createStage(project, 2, "Initial Proposal", "Pre-event budgeting...");
                linkDoc(stage2, documents, "Template MOM");
                stages.add(stage2);

                // 3. Proposal Revision
                WorkflowStage stage3 = createStage(project, 3, "Proposal Revision", "Solve feedback...");
                linkDoc(stage3, documents, "Template MOM");
                stages.add(stage3);

                // 4. Approval Client
                WorkflowStage stage4 = createStage(project, 4, "Approval Client", "Confirm PO...");
                // linkDoc(stage4, documents, "Template MOM"); // Checklist file?
                stages.add(stage4);

                // 5. Checklist -> Link "Template Checklist"
                WorkflowStage stage5 = createStage(project, 5, "Checklist", "3rd party approvals...");
                linkDoc(stage5, documents, "Template Checklist");
                stages.add(stage5);

                // 6. Final Proposal -> Link "Template Manual Book" (PDF + PPTX)
                WorkflowStage stage6 = createStage(project, 6, "Final Proposal: Manual Book", "Final Proposal...");
                linkAllDocs(stage6, documents, "Template Manual Book Event");
                stages.add(stage6);

                // 7. Contact List -> Link "Template Contact List"
                WorkflowStage stage7 = createStage(project, 7, "Contact List", "");
                linkDoc(stage7, documents, "Template Contact List Client, Vendor & Team");
                stages.add(stage7);

                // 8. Storyboard -> Link "Template Storyboard" (PDF + PPTX)
                WorkflowStage stage8 = createStage(project, 8, "Storyboard", "");
                linkAllDocs(stage8, documents, "Template Storyboard Event");
                stages.add(stage8);

                // 9. MC QUE Card -> Link "Template Cue Card"
                WorkflowStage stage9 = createStage(project, 9, "MC QUE Card", "");
                linkDoc(stage9, documents, "Template Cue Card MC");
                stages.add(stage9);

                // 10. PPT Final Client
                stages.add(createStage(project, 10, "PPT Final Client", ""));

                // ... Add remaining stages ...

                // 13. Briefing Show Management -> Link "Template Team Structure" + Brief Registration (PDF + PPTX)
                WorkflowStage stage13 = createStage(project, 13, "Briefing Show Management",
                                "Briefing to all employees...");
                linkDoc(stage13, documents, "Template Team Structure");
                linkDoc(stage13, documents, "Template Brief & Registration");
                linkDoc(stage13, documents, "Brief Registration Manpower Jobdesk");
                stages.add(stage13);

                // 14. Post Event
                stages.add(createStage(project, 14, "Post-event Report (Invoice)", "The report include photo..."));

                return stages;
        }

        // --- Helper to create stage object ---
        private WorkflowStage createStage(Project p, int order, String name, String desc) {
                return WorkflowStage.builder()
                                .project(p)
                                .name(name)
                                .description(desc)
                                .orderSequence(order)
                                .status(StageStatus.PENDING)
                                .relatedDocuments(new ArrayList<>()) // Initialize list to avoid NullPointer
                                .build();
        }

        // --- Helper: Find first document by partial name and link it to the stage ---
        private void linkDoc(WorkflowStage stage, List<ProjectDocument> docs, String partialName) {
                Optional<ProjectDocument> match = docs.stream()
                                .filter(d -> d.getName().contains(partialName))
                                .findFirst();

                match.ifPresent(doc -> {
                        stage.getRelatedDocuments().add(doc);
                });
        }

        // --- Helper: Find ALL documents matching partial name and link them to the stage ---
        private void linkAllDocs(WorkflowStage stage, List<ProjectDocument> docs, String partialName) {
                docs.stream()
                                .filter(d -> d.getName().contains(partialName))
                                .forEach(doc -> stage.getRelatedDocuments().add(doc));
        }
}
