package com.crm.controller;

import com.crm.domain.Event;
import com.crm.domain.Role;
import com.crm.domain.AppUser;
import com.crm.domain.DatabaseSource;
import com.crm.domain.EventParticipant;
import com.crm.domain.ParticipantStatus;
import com.crm.domain.PositionLevel;
import com.crm.repository.EventRepository;
import com.crm.repository.EventParticipantRepository;
import com.crm.repository.UserRepository;
import com.crm.service.SecurityHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/events")
public class EventController {
    private static final Pattern PIC_PATTERN = Pattern.compile("\\[PIC:\\s*([^\\]]+)\\]", Pattern.CASE_INSENSITIVE);


    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventParticipantRepository eventParticipantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired
    private com.crm.service.EmsService emsService;

    @GetMapping("/ems-upcoming")
    public ResponseEntity<?> getEmsUpcomingEvents(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        return ResponseEntity.ok(emsService.getUpcomingEvents());
    }

    @GetMapping("/ems-participants/{emsEventId}")
    public ResponseEntity<?> getEmsEventParticipants(
            @PathVariable Long emsEventId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        return ResponseEntity.ok(emsService.getEventParticipants(emsEventId));
    }

    @GetMapping
    public ResponseEntity<?> getAllEvents(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (isViewer(currentUser)) {
            return ResponseEntity.ok(eventRepository.findAll().stream()
                    .filter(event -> currentUser.getAllowedEventIds().contains(event.getId()))
                    .toList());
        }
        return ResponseEntity.ok(eventRepository.findAll());
    }

    @GetMapping("/{id}/eligible-managers")
    public ResponseEntity<?> getEligibleManagers(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        Event event = eventRepository.findById(id).orElse(null);
        if (event == null) {
            return ResponseEntity.notFound().build();
        }

        boolean isAdmin = securityHelper.hasRole(currentUser, Role.ADMIN);
        boolean hasEventAccess = currentUser.getAllowedEventIds() != null && currentUser.getAllowedEventIds().contains(id);
        if (!isAdmin && !hasEventAccess) {
            return ResponseEntity.status(403).body("Forbidden: You don't have access to this event");
        }

        List<EligibleManagerResponse> managers = userRepository.findAll().stream()
                .filter(user -> securityHelper.hasRole(user, Role.MANAGER))
                .filter(user -> user.getAllowedEventIds() != null && user.getAllowedEventIds().contains(id))
                .map(user -> EligibleManagerResponse.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .fullName(user.getFullName())
                        .email(user.getEmail())
                        .roles(user.getRoles() != null ? user.getRoles() : new HashSet<>())
                        .allowedEventIds(user.getAllowedEventIds() != null ? user.getAllowedEventIds() : new HashSet<>())
                        .build())
                .toList();

        return ResponseEntity.ok(managers);
    }

    @GetMapping("/{id}/participants")
    public ResponseEntity<?> getEventParticipantsByEvent(
            @PathVariable Long id,
            @RequestParam(required = false) String tab,
            @RequestParam(required = false) String pic,
            @RequestParam(required = false) String company,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String confirmationStatus,
            @RequestParam(required = false) String reminderHariH,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "25") Integer size,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!canAccessEvent(currentUser, id)) {
            return ResponseEntity.status(403).body("Forbidden: You don't have access to this event");
        }

        Event event = eventRepository.findById(id).orElse(null);
        if (event == null) {
            return ResponseEntity.notFound().build();
        }

        List<EventParticipant> filtered = filterParticipants(
                eventParticipantRepository.findByEventId(id),
                currentUser,
                tab,
                pic,
                company,
                position,
                industry,
                confirmationStatus,
                reminderHariH,
                search
        );

        int safeSize = size == null ? 25 : Math.max(1, Math.min(size, 500));
        int safePage = page == null ? 1 : Math.max(1, page);
        int total = filtered.size();
        int fromIndex = Math.min((safePage - 1) * safeSize, total);
        int toIndex = Math.min(fromIndex + safeSize, total);
        List<EventParticipant> items = filtered.subList(fromIndex, toIndex);

        return ResponseEntity.ok(Map.of(
                "eventId", id,
                "page", safePage,
                "size", safeSize,
                "total", total,
                "totalPages", (int) Math.ceil(total / (double) safeSize),
                "items", items
        ));
    }

    @GetMapping("/{id}/participants/summary")
    public ResponseEntity<?> getEventParticipantsSummary(
            @PathVariable Long id,
            @RequestParam(required = false) String tab,
            @RequestParam(required = false) String pic,
            @RequestParam(required = false) String company,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String confirmationStatus,
            @RequestParam(required = false) String reminderHariH,
            @RequestParam(required = false) String search,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!canAccessEvent(currentUser, id)) {
            return ResponseEntity.status(403).body("Forbidden: You don't have access to this event");
        }

        Event event = eventRepository.findById(id).orElse(null);
        if (event == null) {
            return ResponseEntity.notFound().build();
        }

        List<EventParticipant> participants = filterParticipants(
                eventParticipantRepository.findByEventId(id),
                currentUser,
                tab,
                pic,
                company,
                position,
                industry,
                confirmationStatus,
                reminderHariH,
                search
        );

        List<EligibleManagerResponse> eligibleManagers = userRepository.findAll().stream()
                .filter(user -> securityHelper.hasRole(user, Role.MANAGER))
                .filter(user -> user.getAllowedEventIds() != null && user.getAllowedEventIds().contains(id))
                .map(user -> EligibleManagerResponse.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .fullName(user.getFullName())
                        .email(user.getEmail())
                        .roles(user.getRoles() != null ? user.getRoles() : new HashSet<>())
                        .allowedEventIds(user.getAllowedEventIds() != null ? user.getAllowedEventIds() : new HashSet<>())
                        .build())
                .toList();

        Map<String, Long> picAssignments = new LinkedHashMap<>();
        for (EligibleManagerResponse manager : eligibleManagers) {
            String name = normalizePicName(manager.getFullName(), manager.getUsername());
            long count = participants.stream()
                    .filter(participant -> extractPic(participant.getNotes()).equalsIgnoreCase(name))
                    .count();
            picAssignments.put(name, count);
        }

        long unassigned = participants.stream()
                .filter(participant -> {
                    String picName = extractPic(participant.getNotes());
                    return picName.isBlank() || picAssignments.keySet().stream().noneMatch(name -> name.equalsIgnoreCase(picName));
                })
                .count();

        long approved = participants.stream().filter(participant -> "approve".equalsIgnoreCase(participant.getConfirmationStatus())).count();
        long pending = participants.stream().filter(participant -> "pending".equalsIgnoreCase(participant.getConfirmationStatus())).count();
        long declined = participants.stream().filter(participant -> "decline".equalsIgnoreCase(participant.getConfirmationStatus())).count();
        long registered = participants.stream().filter(this::isRegisteredParticipant).count();

        return ResponseEntity.ok(Map.of(
                "eventId", id,
                "totalParticipants", participants.size(),
                "approvedCount", approved,
                "pendingCount", pending,
                "declinedCount", declined,
                "registeredCount", registered,
                "unassignedCount", unassigned,
                "eligibleManagers", eligibleManagers,
                "picAssignments", picAssignments
        ));
    }

    @PostMapping("/{id}/assignments/auto-split")
    public ResponseEntity<?> autoSplitAssignments(
            @PathVariable Long id,
            @RequestBody AutoSplitRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN can auto-split assignments");
        }

        Event event = eventRepository.findById(id).orElse(null);
        if (event == null) {
            return ResponseEntity.notFound().build();
        }

        List<AppUser> eligibleManagers = userRepository.findAll().stream()
                .filter(user -> securityHelper.hasRole(user, Role.MANAGER))
                .filter(user -> user.getAllowedEventIds() != null && user.getAllowedEventIds().contains(id))
                .filter(user -> request.getManagerIds() == null || request.getManagerIds().isEmpty() || request.getManagerIds().contains(user.getId()))
                .sorted(Comparator.comparing(user -> normalizePicName(user.getFullName(), user.getUsername())))
                .toList();

        if (eligibleManagers.isEmpty()) {
            return ResponseEntity.badRequest().body("No eligible managers available for this event");
        }

        String mode = request.getMode() == null ? "all" : request.getMode().trim().toLowerCase(Locale.ROOT);
        List<EventParticipant> targetParticipants = new ArrayList<>(eventParticipantRepository.findByEventId(id));
        if ("unassigned".equals(mode)) {
            targetParticipants = targetParticipants.stream()
                    .filter(participant -> extractPic(participant.getNotes()).isBlank())
                    .toList();
        }

        if (targetParticipants.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "eventId", id,
                    "updatedCount", 0,
                    "managerCount", eligibleManagers.size(),
                    "message", "No participants matched the auto-split criteria"
            ));
        }

        int updatedCount = 0;
        for (int i = 0; i < targetParticipants.size(); i++) {
            EventParticipant participant = targetParticipants.get(i);
            AppUser manager = eligibleManagers.get(i % eligibleManagers.size());
            participant.setNotes(setPic(participant.getNotes(), normalizePicName(manager.getFullName(), manager.getUsername())));
            eventParticipantRepository.save(participant);
            updatedCount++;
        }

        return ResponseEntity.ok(Map.of(
                "eventId", id,
                "updatedCount", updatedCount,
                "managerCount", eligibleManagers.size(),
                "mode", mode
        ));
    }

    @PostMapping
    public ResponseEntity<?> createEvent(
            @RequestBody Event event,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can create events");
        }

        if (event.getName() == null || event.getName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Event name is required");
        }

        String cleanName = event.getName().trim();
        if (eventRepository.findByNameIgnoreCase(cleanName).isPresent()) {
            return ResponseEntity.badRequest().body("Event name already exists");
        }

        event.setName(cleanName);
        return ResponseEntity.ok(eventRepository.save(event));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getEventById(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        return eventRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateEvent(
            @PathVariable Long id, 
            @RequestBody Event eventDetails,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can update events");
        }

        if (eventDetails.getName() == null || eventDetails.getName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Event name is required");
        }
        String cleanName = eventDetails.getName().trim();

        Event existing = eventRepository.findById(id).orElseGet(() -> {
            java.util.Optional<Event> duplicate = eventRepository.findByNameIgnoreCase(cleanName);
            if (duplicate.isPresent()) {
                return duplicate.get();
            }
            return new Event();
        });

        existing.setName(cleanName);
        existing.setEventType(eventDetails.getEventType());
        existing.setClientName(eventDetails.getClientName());
        existing.setDateStart(eventDetails.getDateStart());
        existing.setDateEnd(eventDetails.getDateEnd());
        existing.setNotes(eventDetails.getNotes());
        existing.setTargetParticipants(eventDetails.getTargetParticipants());
        existing.setEmsEventId(eventDetails.getEmsEventId());

        Event saved = eventRepository.save(existing);
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/{id}/sync-ems")
    public ResponseEntity<?> syncEmsParticipants(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can sync EMS participants");
        }
        Event event = eventRepository.findById(id).orElse(null);
        if (event == null) {
            event = eventRepository.findByEmsEventId(id).orElse(null);
        }
        if (event == null) {
            event = eventRepository.findAll().stream()
                    .filter(e -> e.getEmsEventId() != null && e.getEmsEventId() > 0)
                    .findFirst().orElse(null);
        }
        if (event == null) {
            return ResponseEntity.notFound().build();
        }
        int count = emsService.syncParticipantsForEvent(event);
        return ResponseEntity.ok(java.util.Map.of("message", "Synced EMS participants", "count", count));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteEvent(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN can delete events");
        }

        if (eventRepository.existsById(id)) {
            // Delete associated event participants
            List<com.crm.domain.EventParticipant> participants = eventParticipantRepository.findByEventId(id);
            eventParticipantRepository.deleteAll(participants);

            eventRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private boolean isViewer(AppUser user) {
        return securityHelper.hasRole(user, Role.USER) && !securityHelper.hasAnyRole(user, Role.ADMIN, Role.MANAGER);
    }

    private boolean canAccessEvent(AppUser user, Long eventId) {
        return securityHelper.hasRole(user, Role.ADMIN)
                || (user.getAllowedEventIds() != null && user.getAllowedEventIds().contains(eventId));
    }

    private List<EventParticipant> filterParticipants(
            List<EventParticipant> participants,
            AppUser currentUser,
            String tab,
            String pic,
            String company,
            String position,
            String industry,
            String confirmationStatus,
            String reminderHariH,
            String search) {
        return participants.stream()
                .filter(participant -> !isViewer(currentUser) || canAccessEvent(currentUser, participant.getEvent().getId()))
                .filter(participant -> matchesTab(participant, tab))
                .filter(participant -> matchesCompany(participant, company))
                .filter(participant -> matchesPosition(participant, position))
                .filter(participant -> matchesIndustry(participant, industry))
                .filter(participant -> matchesConfirmationStatus(participant, tab, confirmationStatus))
                .filter(participant -> matchesReminderHariH(participant, tab, reminderHariH))
                .filter(participant -> matchesPic(participant, pic))
                .filter(participant -> matchesSearch(participant, search))
                .sorted(Comparator.comparing(EventParticipant::getId))
                .collect(Collectors.toList());
    }

    private boolean matchesTab(EventParticipant participant, String tab) {
        if (tab == null || tab.isBlank()) return true;
        String normalizedTab = tab.trim().toLowerCase(Locale.ROOT);
        String confirmation = getEffectiveConfirmationStatus(participant);
        String preEvent = safe(participant.getPreEventApprovalStatus()).toLowerCase(Locale.ROOT);
        boolean isEms = isEmsParticipant(participant);

        return switch (normalizedTab) {
            case "request" -> !isPublicEmsOnlyParticipant(participant);
            case "pre_event" -> {
                if ("decline".equals(confirmation) || "declined".equals(confirmation) || "decline".equals(preEvent)) {
                    yield false;
                }
                if (!isEms && !"approve".equals(confirmation) && !"confirmed".equals(confirmation)) {
                    yield false;
                }
                yield true;
            }
            case "declined" -> "decline".equals(confirmation)
                    || "declined".equals(confirmation)
                    || "decline".equals(preEvent)
                    || isDeclinedParticipant(participant);
            case "reminder" -> ("approve".equals(confirmation) || "confirmed".equals(confirmation))
                    && isRegisteredParticipant(participant);
            case "reminder_dday" -> ("approve".equals(confirmation) || "confirmed".equals(confirmation))
                    && isRegisteredParticipant(participant);
            default -> true;
        };
    }

    private boolean matchesCompany(EventParticipant participant, String company) {
        if (company == null || company.isBlank()) return true;
        String participantCompany = participant.getDatabase() != null && participant.getDatabase().getCompany() != null
                ? safe(participant.getDatabase().getCompany().getName())
                : "";
        return participantCompany.equalsIgnoreCase(company.trim());
    }

    private boolean matchesPosition(EventParticipant participant, String position) {
        if (position == null || position.isBlank()) return true;
        String requestedPosition = position.trim();
        PositionLevel participantPosition = participant.getDatabase() != null
                ? participant.getDatabase().getPositionLevel()
                : null;
        PositionLevel normalizedRequestedPosition = PositionLevel.fromValue(requestedPosition);

        if (participantPosition != null && normalizedRequestedPosition != PositionLevel.UNKNOWN) {
            return participantPosition == normalizedRequestedPosition;
        }

        String participantPositionValue = participantPosition != null ? participantPosition.getValue() : "";
        return participantPositionValue.equalsIgnoreCase(requestedPosition);
    }

    private boolean matchesIndustry(EventParticipant participant, String industry) {
        if (industry == null || industry.isBlank()) return true;
        String participantIndustry = participant.getDatabase() != null && participant.getDatabase().getCompany() != null
                ? safe(participant.getDatabase().getCompany().getIndustry())
                : "";
        return participantIndustry.equalsIgnoreCase(industry.trim());
    }

    private boolean matchesConfirmationStatus(EventParticipant participant, String tab, String confirmationStatus) {
        if (confirmationStatus == null || confirmationStatus.isBlank()) return true;
        String target = confirmationStatus.trim().toLowerCase(Locale.ROOT);
        if ("pre_event".equalsIgnoreCase(safe(tab))) {
            return safe(participant.getPreEventApprovalStatus()).equalsIgnoreCase(target);
        }
        String current = safe(participant.getConfirmationStatus()).toLowerCase(Locale.ROOT);
        if ("approve".equals(target)) {
            return "approve".equals(current) || "confirmed".equals(current);
        }
        if ("decline".equals(target)) {
            return "decline".equals(current) || "declined".equals(current);
        }
        return current.equals(target);
    }

    private boolean matchesReminderHariH(EventParticipant participant, String tab, String reminderHariH) {
        if (!"reminder_dday".equalsIgnoreCase(safe(tab))) return true;
        if (reminderHariH == null || reminderHariH.isBlank()) return true;

        String effectiveHariH = getHariHStatus(participant);
        String filterVal = reminderHariH.trim().toLowerCase(Locale.ROOT);
        if ("not_respond_yet".equals(filterVal)) {
            return effectiveHariH.isBlank() || "not_respon_yet".equals(effectiveHariH) || effectiveHariH.startsWith("not_respond_");
        }
        return effectiveHariH.equals(filterVal);
    }

    private boolean matchesPic(EventParticipant participant, String pic) {
        if (pic == null || pic.isBlank()) return true;
        return extractPic(participant.getNotes()).equalsIgnoreCase(pic.trim());
    }

    private boolean matchesSearch(EventParticipant participant, String search) {
        if (search == null || search.isBlank()) return true;
        String[] terms = search.trim().toLowerCase(Locale.ROOT).split("\\s+");
        String haystack = String.join(" ",
                safe(participant.getDatabase() != null ? participant.getDatabase().getFirstName() : null),
                safe(participant.getDatabase() != null ? participant.getDatabase().getLastName() : null),
                safe(participant.getDatabase() != null ? participant.getDatabase().getJobTitle() : null),
                safe(participant.getDatabase() != null ? participant.getDatabase().getMobilePhone() : null),
                safe(participant.getDatabase() != null && participant.getDatabase().getCompany() != null ? participant.getDatabase().getCompany().getName() : null),
                safe(participant.getNotes())
        ).toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (!term.isBlank() && !haystack.contains(term)) {
                return false;
            }
        }
        return true;
    }

    private boolean isRegisteredParticipant(EventParticipant participant) {
        String ps = participant.getParticipantStatus() != null ? participant.getParticipantStatus().name().toLowerCase(Locale.ROOT) : "";
        String att = participant.getAttendanceStatus() != null ? participant.getAttendanceStatus().name().toLowerCase(Locale.ROOT) : "";
        return Objects.equals(ps, "registered")
                || Objects.equals(ps, "green")
                || Objects.equals(ps, "confirm")
                || Objects.equals(ps, "confirmed")
                || Objects.equals(att, "registered")
                || Objects.equals(att, "attended");
    }

    private boolean isEmsParticipant(EventParticipant participant) {
        String attendanceStatus = participant.getAttendanceStatus() != null
                ? participant.getAttendanceStatus().name().toLowerCase(Locale.ROOT)
                : "";
        String notes = safe(participant.getNotes());
        return notes.contains("[Origin: EMS Sync]")
                || notes.contains("[EMS]")
                || (participant.getDatabase() != null
                && participant.getDatabase().getSource() == DatabaseSource.event_registration)
                || "registered".equals(attendanceStatus)
                || "attended".equals(attendanceStatus);
    }

    private boolean isPublicEmsOnlyParticipant(EventParticipant participant) {
        String notes = safe(participant.getNotes());
        boolean hasRequestOrigin = notes.contains("[Origin: Request]");
        boolean hasEmsOrigin = notes.contains("[Origin: EMS Sync]") || notes.contains("[EMS]");
        boolean isEventRegistration = participant.getDatabase() != null
                && participant.getDatabase().getSource() == DatabaseSource.event_registration;
        return !hasRequestOrigin && (hasEmsOrigin || isEventRegistration);
    }

    private boolean isDeclinedParticipant(EventParticipant participant) {
        String att = participant.getAttendanceStatus() != null ? participant.getAttendanceStatus().name().toLowerCase(Locale.ROOT) : "";
        String notes = safe(participant.getNotes()).toLowerCase(Locale.ROOT);
        return Objects.equals(att, "cancelled")
                || Objects.equals(att, "canceled")
                || Objects.equals(att, "no_show")
                || notes.contains("[ems declined]")
                || notes.contains("declined_at");
    }

    private String getEffectiveConfirmationStatus(EventParticipant participant) {
        String confirmationStatus = safe(participant.getConfirmationStatus()).toLowerCase(Locale.ROOT);
        if (confirmationStatus.isBlank()) {
            confirmationStatus = "pending";
        }
        String attendanceStatus = participant.getAttendanceStatus() != null ? participant.getAttendanceStatus().name().toLowerCase(Locale.ROOT) : "";
        String participantStatus = participant.getParticipantStatus() != null ? participant.getParticipantStatus().name().toLowerCase(Locale.ROOT) : "";
        boolean isActiveEms = isEmsParticipant(participant)
                && !isDeclinedParticipant(participant)
                && ("registered".equals(attendanceStatus)
                || "attended".equals(attendanceStatus)
                || "registered".equals(participantStatus)
                || "green".equals(participantStatus)
                || "confirm".equals(participantStatus)
                || "confirmed".equals(participantStatus)
                || "on_location".equals(safe(participant.getReminderHariH()).toLowerCase(Locale.ROOT)));

        if (isActiveEms && ("decline".equals(confirmationStatus) || "declined".equals(confirmationStatus))) {
            return "approve";
        }
        return confirmationStatus;
    }

    private String extractPic(String notes) {
        if (notes == null || notes.isBlank()) return "";
        Matcher matcher = PIC_PATTERN.matcher(notes);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private String getHariHStatus(EventParticipant participant) {
        String reminder = safe(participant.getReminderHariH()).toLowerCase(Locale.ROOT);
        if (!reminder.isBlank() && !"null".equals(reminder) && !"undefined".equals(reminder)) {
            return reminder;
        }
        String attendance = participant.getAttendanceStatus() != null ? participant.getAttendanceStatus().name().toLowerCase(Locale.ROOT) : "";
        return "attended".equals(attendance) ? "on_location" : "";
    }

    private String setPic(String notes, String picName) {
        String cleanNotes = notes == null ? "" : notes.trim();
        if (cleanNotes.isBlank()) {
            return "[PIC: " + picName + "]";
        }
        if (PIC_PATTERN.matcher(cleanNotes).find()) {
            return cleanNotes.replaceAll("(?i)\\[PIC:\\s*[^\\]]+\\]", "[PIC: " + picName + "]");
        }
        return "[PIC: " + picName + "] " + cleanNotes;
    }

    private String normalizePicName(String fullName, String username) {
        String value = safe(fullName).isBlank() ? safe(username) : safe(fullName);
        return value.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    @lombok.Data
    @lombok.Builder
    private static class EligibleManagerResponse {
        private Long id;
        private String username;
        private String fullName;
        private String email;
        private Set<Role> roles;
        private Set<Long> allowedEventIds;
    }

    @lombok.Data
    public static class AutoSplitRequest {
        private String mode;
        private Set<Long> managerIds;
    }
}
