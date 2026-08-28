package com.crm.controller;

import com.crm.domain.AppUser;
import com.crm.domain.AttendanceStatus;
import com.crm.domain.Company;
import com.crm.domain.Event;
import com.crm.domain.EventParticipant;
import com.crm.domain.FlagStatus;
import com.crm.domain.Role;
import com.crm.repository.CompanyRepository;
import com.crm.repository.DatabaseRepository;
import com.crm.repository.EventParticipantRepository;
import com.crm.repository.EventRepository;
import com.crm.repository.FlaggedIdentityRepository;
import com.crm.repository.GroupRepository;
import com.crm.service.SecurityHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private DatabaseRepository databaseRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventParticipantRepository eventParticipantRepository;

    @Autowired
    private FlaggedIdentityRepository flaggedIdentityRepository;

    @Autowired
    private SecurityHelper securityHelper;

    @GetMapping("/summary")
    public ResponseEntity<?> getDashboardSummary(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        List<Event> visibleEvents = getVisibleEvents(currentUser);
        Set<Long> visibleEventIds = visibleEvents.stream().map(Event::getId).collect(Collectors.toSet());
        List<EventParticipant> visibleParticipants = eventParticipantRepository.findAll().stream()
                .filter(participant -> participant.getEvent() != null && visibleEventIds.contains(participant.getEvent().getId()))
                .toList();
        Map<Long, List<EventParticipant>> participantsByEventId = visibleParticipants.stream()
                .collect(Collectors.groupingBy(participant -> participant.getEvent().getId()));

        List<Map<String, Object>> industryDistribution = companyRepository.findAll().stream()
                .collect(Collectors.groupingBy(company -> cleanIndustry(company), Collectors.counting()))
                .entrySet()
                .stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(6)
                .map(entry -> Map.<String, Object>of("name", entry.getKey(), "value", entry.getValue()))
                .toList();

        List<Map<String, Object>> eventAttendancePerformance = visibleEvents.stream()
                .sorted(Comparator
                        .comparing((Event event) -> event.getDateStart() != null ? event.getDateStart() : LocalDate.MIN)
                        .reversed()
                        .thenComparing(Event::getId, Comparator.reverseOrder()))
                .limit(5)
                .map(event -> {
                    List<EventParticipant> participants = participantsByEventId.getOrDefault(event.getId(), List.of());
                    long attended = participants.stream()
                            .filter(participant -> participant.getAttendanceStatus() == AttendanceStatus.attended)
                            .count();
                    return Map.<String, Object>of(
                            "name", event.getName() != null && event.getName().length() > 20 ? event.getName().substring(0, 20) + "..." : safe(event.getName()),
                            "Invited", participants.size(),
                            "Attended", attended
                    );
                })
                .toList();

        List<Map<String, Object>> flaggedAlerts = flaggedIdentityRepository.findAll().stream()
                .filter(flag -> flag.getStatus() == FlagStatus.suspected || flag.getStatus() == FlagStatus.confirmed)
                .sorted(Comparator.comparing(flag -> flag.getCreatedAt() != null ? flag.getCreatedAt() : java.time.LocalDateTime.MIN, Comparator.reverseOrder()))
                .limit(20)
                .map(flag -> Map.<String, Object>of(
                        "id", flag.getId(),
                        "nameUsed", safe(flag.getNameUsed()),
                        "emailUsed", safe(flag.getEmailUsed()),
                        "phoneUsed", safe(flag.getPhoneUsed()),
                        "flagReason", flag.getFlagReason() != null ? flag.getFlagReason().name() : "",
                        "evidenceNotes", safe(flag.getEvidenceNotes()),
                        "status", flag.getStatus() != null ? flag.getStatus().name() : ""
                ))
                .toList();

        long activeDatabases = databaseRepository.findAll().stream()
                .filter(database -> !Boolean.FALSE.equals(database.getIsActive()))
                .count();

        return ResponseEntity.ok(Map.of(
                "metrics", Map.of(
                        "totalGroups", groupRepository.count(),
                        "totalCompanies", companyRepository.count(),
                        "totalDatabase", activeDatabases,
                        "totalEvents", visibleEvents.size(),
                        "suspectedTikus", flaggedAlerts.size()
                ),
                "industryDistribution", industryDistribution,
                "eventAttendancePerformance", eventAttendancePerformance,
                "flaggedAlerts", flaggedAlerts
        ));
    }

    private List<Event> getVisibleEvents(AppUser currentUser) {
        List<Event> events = eventRepository.findAll();
        if (securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return events;
        }
        Set<Long> allowedEventIds = currentUser.getAllowedEventIds() != null ? currentUser.getAllowedEventIds() : Set.of();
        return events.stream()
                .filter(event -> allowedEventIds.contains(event.getId()))
                .toList();
    }

    private String cleanIndustry(Company company) {
        String industry = company.getIndustry();
        return industry == null || industry.isBlank() ? "Unspecified" : industry.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
