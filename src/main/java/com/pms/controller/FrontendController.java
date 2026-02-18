package com.pms.controller;

import com.pms.domain.AppUser;
import com.pms.domain.Project;
import com.pms.domain.StageStatus;
import com.pms.domain.WorkflowStage;
import com.pms.dto.FrontendProjectDto;
import com.pms.repository.ProjectRepository;
import com.pms.service.ProjectPermissionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/frontend")
public class FrontendController {

    private final ProjectRepository projectRepository;
    private final ProjectPermissionService permissionService;

    public FrontendController(ProjectRepository projectRepository, ProjectPermissionService permissionService) {
        this.projectRepository = projectRepository;
        this.permissionService = permissionService;
    }

    @GetMapping("/projects")
    public List<FrontendProjectDto> listFrontendProjects(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        var list = projectRepository.findAll();
        AppUser user = permissionService.resolveUser(auth);
        if (user != null && !permissionService.isAdminOrManager(user)) {
            list = list.stream()
                    .filter(p -> permissionService.hasProjectAccess(p, user))
                    .toList();
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

        // SIZE
        String size = p.getSize() != null ? p.getSize().name() : null;
        String sizeLabel = size != null
                ? size.charAt(0) + size.substring(1).toLowerCase()
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
                size,
                sizeLabel);
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
