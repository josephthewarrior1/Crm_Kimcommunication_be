package com.pms.controller;

import com.pms.domain.*;
import com.pms.dto.FrontendProjectDto;
import com.pms.repository.ProjectMemberRepository;
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
    private final ProjectMemberRepository projectMemberRepository;

    public FrontendController(ProjectRepository projectRepository,
                              ProjectPermissionService permissionService,
                              ProjectMemberRepository projectMemberRepository) {
        this.projectRepository = projectRepository;
        this.permissionService = permissionService;
        this.projectMemberRepository = projectMemberRepository;
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
        String status = p.getStatus() != null ? p.getStatus().name() : "PENDING";
        String statusLabel = switch (p.getStatus()) {
            case PENDING -> "Pending";
            case PITCHING -> "Pitching";
            case APPROVED -> "Approved";
            case COMPLETED -> "Completed";
            case CANCELLED -> "Cancelled";
            default -> "Pending";
        };

        // CURRENT STAGE (DERIVED)
        String currentStage = deriveCurrentStageEnum(p);
        String currentStageLabel = deriveCurrentStageLabel(p);

        String startDate = (p.getStartDate() != null) ? p.getStartDate().toString() : null;
        String endDate = (p.getEndDate() != null) ? p.getEndDate().toString() : null;
        // EVENT DATE (kept for backwards compatibility, derived from endDate)
        String eventDate = endDate;

        // DAYS UNTIL EVENT
        Integer daysUntilEvent = (p.getEndDate() != null)
                ? (int) ChronoUnit.DAYS.between(LocalDate.now(), p.getEndDate())
                : null;

        // CLIENT NAME (prefer relation)
        String client = p.getClientEntity() != null ? p.getClientEntity().getName() : p.getClient();
        Long clientId = p.getClientEntity() != null ? p.getClientEntity().getId() : null;

        // ACCOUNT MANAGER — find project member with PROJECT_ADMIN role
        String accountManager = null;
        List<ProjectMember> members = projectMemberRepository.findByProjectId(p.getId());
        for (ProjectMember m : members) {
            if (m.getRole() != null && "PROJECT_ADMIN".equals(m.getRole().getName())) {
                accountManager = m.getUser() != null ? m.getUser().getName() : null;
                break;
            }
        }

        // VENUE
        String venueName = p.getVenue() != null ? p.getVenue().getName() : null;
        String venueCity = (p.getVenue() != null && p.getVenue().getCity() != null)
                ? p.getVenue().getCity().getName() : null;
        Long venueId = p.getVenue() != null ? p.getVenue().getId() : null;
        Long venueRoomId = p.getVenueRoom() != null ? p.getVenueRoom().getId() : null;
        String venueRoomName = p.getVenueRoom() != null ? p.getVenueRoom().getRoomName() : null;
        String venueAddress = p.getVenue() != null ? p.getVenue().getAddress() : null;
        String venueProvince = p.getVenue() != null ? p.getVenue().getProvince() : null;
        String venueGoogleMapsLink = p.getVenue() != null ? p.getVenue().getGoogleMapsLink() : null;

        // REMARKS (from description)
        String remarks = p.getDescription();

        return new FrontendProjectDto(
                p.getId().toString(),
                p.getName(),
                client,
                clientId,
                startDate,
                endDate,
                eventDate,
                status,
                statusLabel,
                p.getProgress(),
                daysUntilEvent,
                currentStage,
                currentStageLabel,
                p.getTarget(),
                accountManager,
                venueName,
                venueCity,
                venueId,
                venueRoomId,
                venueRoomName,
                venueAddress,
                venueProvince,
                venueGoogleMapsLink,
                remarks,
                p.getHedging(),
                p.getQtnNo(),
                p.getPoNo(),
                p.getInvoiceNo());
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
