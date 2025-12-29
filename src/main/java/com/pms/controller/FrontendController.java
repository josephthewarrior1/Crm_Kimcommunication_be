package com.pms.controller;

import com.pms.domain.Project;
// import com.pms.domain.ProjectStatus;
import com.pms.domain.StageStatus;
import com.pms.domain.WorkflowStage;
import com.pms.dto.FrontendProjectDto;
import com.pms.repository.ProjectRepository;
import com.pms.repository.SessionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
// import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/frontend")
public class FrontendController {

    private final ProjectRepository projectRepository;
    private final SessionRepository sessions;

    public FrontendController(ProjectRepository projectRepository, SessionRepository sessions) {
        this.projectRepository = projectRepository;
        this.sessions = sessions;
    }

    @GetMapping("/projects")
    public List<FrontendProjectDto> listFrontendProjects(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        var list = projectRepository.findAll();
        // If Authorization present and valid, restrict non-admin users to projects
        // where their email appears in team members
        if (auth != null && auth.startsWith("Bearer ")) {
            var token = auth.substring(7);
            var opt = sessions.findByTokenAndRevokedFalse(token)
                    .filter(st -> st.getExpiresAt().isAfter(Instant.now()));
            if (opt.isPresent()) {
                var user = opt.get().getUser();
                Set<String> roles = user.getRoles().stream().map(Enum::name).collect(Collectors.toSet());
                if (!(roles.contains("ADMIN") || roles.contains("MANAGER"))) {
                    String email = user.getEmail();
                    Long uid = user.getId();
                    list = list.stream().filter(p -> {
                        boolean inUsers = p.getUsers() != null && uid != null
                                && p.getUsers().stream().anyMatch(x -> uid.equals(x.getId()));
                        boolean inMembers = email != null && p.getTeamMembers() != null
                                && p.getTeamMembers().stream().anyMatch(m -> email.equalsIgnoreCase(m.getEmail()));
                        return inUsers || inMembers;
                    }).toList();
                }
            }
        }
        return list.stream()
                .map(this::toFrontendDto)
                .toList();
    }

    private FrontendProjectDto toFrontendDto(Project p) {

        // STATUS
        String status = p.getStatus() != null ? p.getStatus().name() : "IN_PROGRESS";
        String statusLabel = switch (p.getStatus()) {
            case IN_PROGRESS -> "In Progress";
            case APPROVAL_PENDING -> "Approval Pending";
            case COMPLETED -> "Completed";
            case DELIVERED -> "Delivered";
            case CANCELLED -> "Cancelled";
            case DELAYED -> "Delayed";
            default -> "In Progress";
        };

        // PRIORITY
        String priority = p.getPriority() != null ? p.getPriority().name() : null;
        String priorityLabel = priority != null
                ? priority.charAt(0) + priority.substring(1).toLowerCase()
                : null;

        // CURRENT STAGE (DERIVED)
        String currentStage = deriveCurrentStageEnum(p);
        String currentStageLabel = deriveCurrentStageLabel(p);

        // EVENT DATE (derived from endDate)
        String eventDate = (p.getEndDate() != null) ? p.getEndDate().toString() : null;

        // DAYS UNTIL EVENT
        Integer daysUntilEvent = (p.getEndDate() != null)
                ? (int) ChronoUnit.DAYS.between(LocalDate.now(), p.getEndDate())
                : null;

        // CLIENT NAME (prefer relation)
        String client = p.getClientEntity() != null ? p.getClientEntity().getName() : p.getClient();

        return new FrontendProjectDto(
                p.getId().toString(),
                p.getName(),
                client,
                eventDate,
                status,
                statusLabel,
                p.getProgress(),
                daysUntilEvent,
                currentStage,
                currentStageLabel,
                priority,
                priorityLabel);
    }

    private String deriveCurrentStageEnum(Project p) {
        if (p.getStages() == null || p.getStages().isEmpty())
            return "NOT_STARTED";

        // First check if any stage is in progress
        return p.getStages().stream()
                .filter(s -> s.getStatus() == StageStatus.IN_PROGRESS)
                .findFirst()
                .map(s -> s.getStatus().name())
                .orElseGet(() -> {
                    // If no stage is in progress, find the first pending stage after completed
                    // stages
                    List<WorkflowStage> sortedStages = p.getStages().stream()
                            .sorted((s1, s2) -> {
                                Integer seq1 = s1.getOrderSequence() != null ? s1.getOrderSequence() : 0;
                                Integer seq2 = s2.getOrderSequence() != null ? s2.getOrderSequence() : 0;
                                return seq1.compareTo(seq2);
                            })
                            .toList();

                    // Find the first pending stage after all completed stages
                    for (WorkflowStage stage : sortedStages) {
                        if (stage.getStatus() == StageStatus.PENDING) {
                            return stage.getStatus().name();
                        }
                    }
                    return "PENDING";
                });
    }

    private String deriveCurrentStageLabel(Project p) {
        if (p.getStages() == null || p.getStages().isEmpty())
            return "Not Started";

        // First check if any stage is in progress
        return p.getStages().stream()
                .filter(s -> s.getStatus() == StageStatus.IN_PROGRESS)
                .findFirst()
                .map(s -> s.getName())
                .orElseGet(() -> {
                    // If no stage is in progress, find the first pending stage after completed
                    // stages
                    List<WorkflowStage> sortedStages = p.getStages().stream()
                            .sorted((s1, s2) -> {
                                Integer seq1 = s1.getOrderSequence() != null ? s1.getOrderSequence() : 0;
                                Integer seq2 = s2.getOrderSequence() != null ? s2.getOrderSequence() : 0;
                                return seq1.compareTo(seq2);
                            })
                            .toList();

                    // Find the first pending stage after all completed stages
                    for (WorkflowStage stage : sortedStages) {
                        if (stage.getStatus() == StageStatus.PENDING) {
                            return stage.getName();
                        }
                    }
                    return "Pending";
                });
    }
}
