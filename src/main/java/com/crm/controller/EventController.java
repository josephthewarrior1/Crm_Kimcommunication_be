package com.crm.controller;

import com.crm.domain.Event;
import com.crm.domain.EmsSource;
import com.crm.domain.Role;
import com.crm.domain.AppUser;
import com.crm.domain.AttendanceStatus;
import com.crm.domain.Company;
import com.crm.domain.Database;
import com.crm.domain.DatabaseEmail;
import com.crm.domain.DatabaseDestination;
import com.crm.util.ContactPhone;
import com.crm.util.ContactEmailCategory;
import com.crm.domain.DatabaseSource;
import com.crm.domain.DatabaseType;
import com.crm.domain.EventParticipant;
import com.crm.domain.FlaggedIdentity;
import com.crm.domain.Group;
import com.crm.domain.RemovalRequest;
import com.crm.domain.ParticipantStatus;
import com.crm.domain.PositionLevel;
import com.crm.repository.CompanyRepository;
import com.crm.repository.DatabaseEmailRepository;
import com.crm.repository.DatabaseRepository;
import com.crm.repository.EventRepository;
import com.crm.repository.EventParticipantRepository;
import com.crm.repository.FlaggedIdentityRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.RemovalRequestRepository;
import com.crm.repository.UserRepository;
import com.crm.service.AuditLogService;
import com.crm.service.SecurityHelper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/events")
public class EventController {
    private static final Pattern PIC_PATTERN = Pattern.compile("\\[PIC:\\s*([^\\]]+)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVENT_IMPORT_EMAIL = Pattern.compile("^[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]+$", Pattern.UNICODE_CHARACTER_CLASS);


    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DatabaseRepository databaseRepository;

    @Autowired
    private DatabaseEmailRepository databaseEmailRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private EventParticipantRepository eventParticipantRepository;

    @Autowired
    private FlaggedIdentityRepository flaggedIdentityRepository;

    @Autowired
    private RemovalRequestRepository removalRequestRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private com.crm.service.EmsService emsService;

    @GetMapping("/ems-upcoming")
    public ResponseEntity<?> getEmsUpcomingEvents(
            @RequestParam(defaultValue = "LEGACY") EmsSource source,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        Object events = emsService.getUpcomingEvents(source);
        return ResponseEntity.ok(source == EmsSource.V2 ? Map.of("source", "V2", "data", events) : events);
    }

    @GetMapping("/ems-participants/{emsEventId}")
    public ResponseEntity<?> getEmsEventParticipants(
            @PathVariable Long emsEventId,
            @RequestParam(defaultValue = "LEGACY") EmsSource source,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        Object participants = emsService.getEventParticipants(emsEventId, source);
        return ResponseEntity.ok(source == EmsSource.V2 ? Map.of("source", "V2", "data", participants) : participants);
    }

    @GetMapping
    public ResponseEntity<?> getAllEvents(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        return ResponseEntity.ok(getVisibleEvents(currentUser));
    }

    @GetMapping("/list")
    public ResponseEntity<?> getEventList(
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "12") Integer size,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        int safePage = page == null || page < 1 ? 1 : page;
        int safeSize = size == null || size < 1 ? 12 : Math.min(size, 100);

        List<Event> visibleEvents = getVisibleEvents(currentUser);
        List<Event> filteredEvents = visibleEvents.stream()
                .filter(event -> matchesEventSearch(event, search))
                .sorted(Comparator
                        .comparing((Event event) -> event.getDateStart() != null ? event.getDateStart() : LocalDate.MIN)
                        .reversed()
                        .thenComparing(Event::getId, Comparator.reverseOrder()))
                .collect(Collectors.toList());

        Map<Long, List<EventParticipant>> participantsByEventId = eventParticipantRepository.findAll().stream()
                .filter(participant -> participant.getEvent() != null && participant.getEvent().getId() != null)
                .collect(Collectors.groupingBy(participant -> participant.getEvent().getId()));

        List<Event> items = filteredEvents.stream()
                .skip((long) (safePage - 1) * safeSize)
                .limit(safeSize)
                .map(event -> {
                    List<EventParticipant> eventParticipants = participantsByEventId.getOrDefault(event.getId(), List.of());
                    long registeredCount = eventParticipants.stream().filter(this::isRegisteredParticipant).count();
                    long onLocationCount = eventParticipants.stream()
                            .filter(participant -> "on_location".equals(getHariHStatus(participant))
                                    || (participant.getAttendanceStatus() != null
                                    && "attended".equalsIgnoreCase(participant.getAttendanceStatus().name())))
                            .count();
                    int targetParticipants = event.getTargetParticipants() != null ? event.getTargetParticipants() : 0;

                    event.setRegisteredCount((int) registeredCount);
                    event.setOnLocationCount((int) onLocationCount);
                    event.setTargetAchieved(targetParticipants > 0 && onLocationCount >= targetParticipants);
                    return event;
                })
                .toList();

        long upcomingCount = visibleEvents.stream().filter(event -> getEventTimingStatus(event) == EventTimingStatus.UPCOMING).count();
        long ongoingCount = visibleEvents.stream().filter(event -> getEventTimingStatus(event) == EventTimingStatus.ONGOING).count();
        long pastCount = visibleEvents.stream().filter(event -> getEventTimingStatus(event) == EventTimingStatus.PAST).count();

        return ResponseEntity.ok(Map.of(
                "page", safePage,
                "size", safeSize,
                "total", filteredEvents.size(),
                "totalPages", filteredEvents.isEmpty() ? 1 : (int) Math.ceil((double) filteredEvents.size() / safeSize),
                "items", items,
                "summary", Map.of(
                        "total", visibleEvents.size(),
                        "upcoming", upcomingCount,
                        "ongoing", ongoingCount,
                        "past", pastCount
                )
        ));
    }

    @GetMapping("/filter-options")
    public ResponseEntity<?> getEventFilterOptions(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        List<Event> visibleEvents = getVisibleEvents(currentUser);

        List<String> clients = visibleEvents.stream()
                .map(event -> safe(event.getClientName()).trim())
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(String::compareToIgnoreCase)
                .toList();

        List<String> eventTypes = visibleEvents.stream()
                .map(event -> event.getEventType() != null ? event.getEventType().name() : "")
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(String::compareToIgnoreCase)
                .toList();

        List<String> cities = List.of();

        List<Integer> years = visibleEvents.stream()
                .map(event -> event.getDateStart() != null ? event.getDateStart().getYear() : null)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();

        return ResponseEntity.ok(Map.of(
                "clients", clients,
                "eventTypes", eventTypes,
                "cities", cities,
                "years", years
        ));
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
                .filter(user -> securityHelper.hasAnyRole(user, Role.ADMIN, Role.MANAGER))
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
                .filter(user -> securityHelper.hasAnyRole(user, Role.ADMIN, Role.MANAGER))
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

    @GetMapping("/{id}/participants/export")
    public ResponseEntity<?> exportEventParticipants(
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

        String normalizedTab = safe(tab).isBlank() ? "request" : tab.trim().toLowerCase(Locale.ROOT);
        List<EventParticipant> participants = filterParticipants(
                eventParticipantRepository.findByEventId(id),
                currentUser,
                normalizedTab,
                pic,
                company,
                position,
                industry,
                confirmationStatus,
                reminderHariH,
                search
        );

        String sheetName = switch (normalizedTab) {
            case "request" -> "Request Handover";
            case "pre_event" -> "Pre Event Approval";
            case "declined" -> "Declined Handover";
            case "reminder" -> "Reminder Status";
            case "reminder_dday" -> "Reminder Dday Status";
            default -> "Participants Export";
        };

        String fileName = sanitizeFileName(safe(event.getName()).isBlank() ? "event_participants" : event.getName())
                + "_" + normalizedTab.replaceAll("[^a-z0-9]+", "_") + ".xlsx";

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < participants.size(); i++) {
            EventParticipant participant = participants.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("No", i + 1);
            row.put("Company Name", safe(participant.getDatabase() != null && participant.getDatabase().getCompany() != null ? participant.getDatabase().getCompany().getName() : null));
            row.put("Salutation", safe(participant.getDatabase() != null ? participant.getDatabase().getSalutation() : null));
            row.put("First Name", safe(participant.getDatabase() != null ? participant.getDatabase().getFirstName() : null));
            row.put("Last Name", safe(participant.getDatabase() != null ? participant.getDatabase().getLastName() : null));
            row.put("Position", safe(participant.getDatabase() != null && participant.getDatabase().getPositionLevel() != null ? participant.getDatabase().getPositionLevel().getValue() : null));
            row.put("Job Title", safe(participant.getDatabase() != null ? participant.getDatabase().getJobTitle() : null));
            row.put("Office Phone", contactOfficePhone(participant.getDatabase()));
            row.put("Mobile Phone", safe(participant.getDatabase() != null ? participant.getDatabase().getMobilePhone() : null));
            row.put("Office Email", getOfficeEmail(participant));
            row.put("Personal Email", getPersonalEmail(participant));
            row.put("Industry", safe(participant.getDatabase() != null && participant.getDatabase().getCompany() != null ? participant.getDatabase().getCompany().getIndustry() : null));
            row.put("PIC", extractPic(participant.getNotes()));
            row.put("Notes", stripMetadataNotes(participant.getNotes()));

            if ("pre_event".equals(normalizedTab)) {
                row.put("Pre Event Approval", getPreEventApprovalStatus(participant));
            } else if ("request".equals(normalizedTab) || "declined".equals(normalizedTab)) {
                row.put("Client Approval", getEffectiveConfirmationStatus(participant));
            } else if ("reminder".equals(normalizedTab)) {
                row.put("H-7", normalizeExportStatus(participant.getReminderH7()));
                row.put("H-3", normalizeExportStatus(participant.getReminderH3()));
                row.put("H-1", normalizeExportStatus(participant.getReminderH1()));
            } else if ("reminder_dday".equals(normalizedTab)) {
                row.put("Hari H", normalizeExportStatus(getHariHStatus(participant)));
            }

            rows.add(row);
        }

        return ResponseEntity.ok(Map.of(
                "eventId", id,
                "eventName", event.getName(),
                "tab", normalizedTab,
                "sheetName", sheetName,
                "fileName", fileName,
                "total", rows.size(),
                "rows", rows
        ));
    }

    @PostMapping("/{id}/participants/import/validate")
    public ResponseEntity<?> validateEventParticipantsImport(
            @PathVariable Long id, @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String tab,
            @RequestParam(value = "destination", required = false) String destination,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER) || !canAccessEvent(currentUser, id)) {
            return ResponseEntity.status(403).body(Map.of("message", "Forbidden"));
        }
        Event event = eventRepository.findById(id).orElse(null);
        if (event == null) return ResponseEntity.notFound().build();
        ResponseEntity<?> destinationError = eventImportDestinationError(event, destination);
        if (destinationError != null) return destinationError;
        destination = event.getSyncDestination().value();
        if (file == null || file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("message", "Uploaded file is empty"));
        try {
            EventImportBatch batch = readEventImportDrafts(file, eventParticipantRepository.findByEventId(id), databaseRepository.findAll(), destination);
            List<ImportValidationRowDraft> drafts = batch.rows();
            Map<String, List<EventImportEmailOwnerInfo>> owners = new LinkedHashMap<>();
            for (ImportValidationRowDraft draft : drafts) registerImportDraftEmails(owners, draft, destination);
            Map<Integer, List<String>> conflicts = buildEventImportConflictMessages(owners);
            List<Map<String, Object>> rows = new ArrayList<>();
            int valid = 0, duplicate = 0, errors = 0;
            for (ImportValidationRowDraft draft : drafts) {
                List<String> issues = new ArrayList<>(draft.issues());
                List<String> fileConflicts = conflicts.getOrDefault(draft.rowNumber(), List.of());
                issues.addAll(fileConflicts);
                String status = !draft.issues().isEmpty() ? "error" : !fileConflicts.isEmpty() ? "conflict"
                        : draft.existingEventParticipant() ? "duplicate" : "valid";
                if (status.equals("duplicate")) { duplicate++; issues.add("Already registered in this event"); }
                else if (status.equals("valid")) valid++;
                else errors++;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rowNumber", draft.rowNumber()); row.put("status", status);
                row.put("firstName", draft.firstName()); row.put("lastName", draft.lastName());
                row.put("companyName", draft.companyName()); row.put("mobilePhone", draft.mobilePhone());
                row.put("companyEmail", draft.companyEmail()); row.put("personalEmail", draft.personalEmail());
                row.put("salutation", draft.salutation()); row.put("position", draft.position()); row.put("jobTitle", draft.jobTitle());
                row.put("existingDatabaseId", draft.existingDatabaseId()); row.put("existingParticipantId", draft.existingParticipantId());
                row.put("issues", issues); row.put("missingFields", draft.missingFields());
                row.put("warnings", draft.warnings()); rows.add(row);
            }
            return ResponseEntity.ok(Map.of("eventId", id, "eventName", safe(event.getName()), "destination", destination,
                    "tab", safe(tab), "fileName", safe(file.getOriginalFilename()), "rows", rows,
                    "summary", Map.of("validCount", valid, "duplicateCount", duplicate, "errorCount", errors,
                            "blankCount", batch.blankCount(), "conflictCount", conflicts.size(), "totalRows", rows.size())));
        } catch (Exception failure) {
            return ResponseEntity.badRequest().body(Map.of("message", "Failed to validate import file: " + failure.getMessage()));
        }
    }

    @PostMapping("/{id}/participants/import")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> importEventParticipants(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String tab,
            @RequestParam(value = "destination", required = false) String destination,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body(Map.of("message", "Forbidden: Only ADMIN or MANAGER can import event participants"));
        }
        if (!canAccessEvent(currentUser, id)) {
            return ResponseEntity.status(403).body(Map.of("message", "Forbidden: You don't have access to this event"));
        }
        Event event = eventRepository.findForSyncById(id).orElse(null);
        if (event == null) return ResponseEntity.notFound().build();
        ResponseEntity<?> destinationError = eventImportDestinationError(event, destination);
        if (destinationError != null) return destinationError;
        destination = event.getSyncDestination().value();
        boolean participantImport = "participant".equals(destination);
        if (file == null || file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("message", "Uploaded file is empty"));

        List<EventParticipant> existingParticipants = new ArrayList<>(eventParticipantRepository.findByEventId(id));
        List<Database> allDatabases = new ArrayList<>(databaseRepository.findAll());
        List<ImportValidationRowDraft> drafts;

        try {
            drafts = readEventImportDrafts(file, existingParticipants, allDatabases, destination).rows();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "message", "Failed to parse import file: " + e.getMessage()
            ));
        }

        Map<String, List<EventImportEmailOwnerInfo>> emailOwnersInFile = new LinkedHashMap<>();
        for (ImportValidationRowDraft draft : drafts) {
            registerImportDraftEmails(emailOwnersInFile, draft, destination);
        }
        Map<Integer, List<String>> conflictsByRow = buildEventImportConflictMessages(emailOwnersInFile);
        long invalidCount = drafts.stream()
                .filter(draft -> !draft.issues().isEmpty() || !conflictsByRow.getOrDefault(draft.rowNumber(), List.of()).isEmpty())
                .count();
        if (invalidCount > 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Import ditolak: " + invalidCount + " baris data masih bermasalah. Silakan preview dan perbaiki file Excel."
            ));
        }

        int createdContacts = 0;
        int updatedContacts = 0;
        int addedParticipants = 0;
        int skippedParticipants = 0;

        for (ImportValidationRowDraft draft : drafts) {
            Database database = draft.existingDatabaseId() != null
                    ? databaseRepository.findById(draft.existingDatabaseId()).orElseThrow(() -> new IllegalStateException("Kontak berubah; preview ulang sebelum import"))
                    : findEventImportDatabase(allDatabases, draft.firstName(), draft.lastName(), draft.mobilePhone(), draft.companyEmail(), draft.personalEmail(), participantImport);
            if (database != null && !DatabaseDestination.isExact(database.getDatabaseDestination(), event.getSyncDestination())) {
                throw new IllegalStateException("Jenis data kontak berubah; preview ulang sebelum import.");
            }
            boolean isNewContact = database == null;
            String mobilePhone = draft.mobilePhone().isBlank() ? ""
                    : mergeEventImportPhones(database != null ? database.getMobilePhone() : "", draft.mobilePhone());

            if (participantImport) {
                if (database == null) {
                    database = Database.builder().firstName(draft.firstName()).lastName(draft.lastName())
                            .databaseDestination(DatabaseDestination.PARTICIPANT).source(DatabaseSource.excel_import)
                            .createdByUserId(currentUser.getId()).entryMethod("excel_import").isActive(true).build();
                }
                database.setCompany(null); database.setBranch(null);
                if (!draft.firstName().isBlank()) database.setFirstName(draft.firstName());
                if (!draft.lastName().isBlank()) database.setLastName(draft.lastName());
                if (!draft.companyName().isBlank()) database.setInstitutionName(draft.companyName());
                if (!draft.participantCategory().isBlank()) database.setParticipantCategory(draft.participantCategory());
                if (!mobilePhone.isBlank()) {
                    database.setMobilePhone(mobilePhone); database.setNormalizedPhone(normalizeEventImportPhone(mobilePhone));
                }
                database = databaseRepository.save(database);
                for (String email : eventImportEmails(draft.companyEmail(), draft.personalEmail())) saveParticipantImportEmail(database, email);
            } else {
            Group group = null;
            if (!draft.groupName().isBlank()) {
                group = groupRepository.findByNameIgnoreCase(draft.groupName())
                        .orElseGet(() -> groupRepository.save(Group.builder().name(draft.groupName()).build()));
            }

            Company company = database != null ? database.getCompany() : null;
            if (!draft.companyName().isBlank()) {
                if (company == null) company = companyRepository.findByNameIgnoreCase(draft.companyName()).orElse(null);
                if (company == null) {
                    company = Company.builder().name(draft.companyName()).build();
                }
                if (!draft.brandName().isBlank()) company.setBrandName(draft.brandName());
                if (!draft.address().isBlank()) company.setAddress(draft.address());
                if (!draft.officePhone().isBlank()) company.setOfficePhone(draft.officePhone());
                if (!draft.website().isBlank()) company.setWebsite(draft.website());
                if (!draft.industry().isBlank()) company.setIndustry(draft.industry());
                if (!draft.sizeRevenue().isBlank()) company.setCompanySizeRevenue(draft.sizeRevenue());
                if (!draft.sizeEmployee().isBlank()) company.setCompanySizeEmployee(draft.sizeEmployee());
                if (!draft.hardware().isBlank()) company.setCompanyHardware(draft.hardware());
                if (!draft.city().isBlank()) company.setCity(draft.city());
                if (!draft.postalCode().isBlank()) company.setPostalCode(draft.postalCode());
                if (group != null) company.setGroup(group);
                company = companyRepository.save(company);
            }

            PositionLevel positionLevel = PositionLevel.fromValue(draft.position());
            if (database == null) {
                database = Database.builder()
                        .databaseDestination(DatabaseDestination.COMPANY)
                        .createdByUserId(currentUser.getId()).entryMethod("excel_import")
                        .firstName(draft.firstName())
                        .lastName(draft.lastName().isBlank() ? null : draft.lastName())
                        .databaseType(DatabaseType.unknown)
                        .source(DatabaseSource.excel_import)
                        .isActive(true)
                        .build();
            }
            if (!draft.salutation().isBlank()) database.setSalutation(draft.salutation());
            if (!draft.firstName().isBlank()) database.setFirstName(draft.firstName());
            if (!draft.lastName().isBlank()) database.setLastName(draft.lastName());
            if (!draft.position().isBlank()) database.setPositionLevel(positionLevel);
            if (!draft.specialityDivision().isBlank()) database.setSpecialityDivision(draft.specialityDivision());
            if (!draft.jobTitle().isBlank()) database.setJobTitle(draft.jobTitle());
            if (!mobilePhone.isBlank()) {
                database.setMobilePhone(mobilePhone); database.setNormalizedPhone(normalizeEventImportPhone(mobilePhone));
            }
            if (!draft.linkedinUrl().isBlank()) database.setLinkedinUrl(draft.linkedinUrl());
            if (company != null) database.setCompany(company);
            database = databaseRepository.save(database);
            for (String email : eventImportEmails(draft.companyEmail())) saveImportEmail(database, email, "company", true);
            for (String email : eventImportEmails(draft.personalEmail())) saveImportEmail(database, email, "personal", false);
            }
            Database savedContact = database;
            if (allDatabases.stream().noneMatch(existing -> Objects.equals(existing.getId(), savedContact.getId()))) allDatabases.add(database);
            if (existingParticipants.stream().anyMatch(existing -> existing.getDatabase() != null && Objects.equals(existing.getDatabase().getId(), savedContact.getId()))
                    || eventParticipantRepository.findByEventIdAndDatabaseId(id, database.getId()).isPresent()) {
                skippedParticipants++;
                continue;
            }

            EventParticipant participant = EventParticipant.builder()
                    .event(event)
                    .database(database)
                    .participantStatus(ParticipantStatus.white)
                    .attendanceStatus(AttendanceStatus.invited)
                    .confirmationStatus("pending")
                    .preEventApprovalStatus("pending")
                    .notes("request".equalsIgnoreCase(safe(tab)) ? "[Origin: Request]" : null)
                    .build();
            existingParticipants.add(eventParticipantRepository.save(participant));
            addedParticipants++;

            if (isNewContact) {
                createdContacts++;
            } else {
                updatedContacts++;
            }
        }

        String eventName = event.getName() == null ? "Tanpa Nama Event" : event.getName();
        auditLogService.recordUserAction(
                currentUser,
                "EVENT_PARTICIPANT",
                "IMPORT_EXCEL",
                event.getId(),
                eventName,
                "Import Excel menambahkan " + addedParticipants + " peserta ke event '" + eventName + "'"
        );

        return ResponseEntity.ok(Map.of(
                "destination", destination,
                "message", "Excel participants imported successfully",
                "eventId", id,
                "eventName", eventName,
                "addedParticipants", addedParticipants,
                "skippedParticipants", skippedParticipants,
                "createdContacts", createdContacts,
                "updatedContacts", updatedContacts,
                "totalRows", drafts.size()
        ));
    }

    @GetMapping("/{id}/participants/summary-by-pic")
    public ResponseEntity<?> getEventParticipantsSummaryByPic(
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
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN can view PIC summaries");
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
                .filter(user -> securityHelper.hasAnyRole(user, Role.ADMIN, Role.MANAGER))
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

        List<Map<String, Object>> items = eligibleManagers.stream()
                .map(manager -> {
                    String name = normalizePicName(manager.getFullName(), manager.getUsername());
                    List<EventParticipant> picParticipants = participants.stream()
                            .filter(participant -> extractPic(participant.getNotes()).equalsIgnoreCase(name))
                            .toList();

                    return Map.<String, Object>of(
                            "userId", manager.getId(),
                            "name", name,
                            "roleLabel", manager.getRoles() != null && manager.getRoles().contains(Role.ADMIN) ? "ADMIN" : "MANAGER",
                            "totalAssigned", picParticipants.size(),
                            "approveCount", picParticipants.stream().filter(participant -> "approve".equals(getPreEventApprovalStatus(participant))).count(),
                            "pendingCount", picParticipants.stream().filter(participant -> "pending".equals(getPreEventApprovalStatus(participant))).count(),
                            "registeredCount", countRegistered(picParticipants),
                            "tentativeCount", countTentative(picParticipants),
                            "notRespondCount", countParticipantStatus(picParticipants, "not_respond_yet"),
                            "notInterestCount", countParticipantStatus(picParticipants, "not_interest")
                    );
                })
                .filter(item -> {
                    Object totalAssigned = item.get("totalAssigned");
                    return totalAssigned instanceof Number && ((Number) totalAssigned).longValue() > 0;
                })
                .sorted(Comparator
                        .comparing((Map<String, Object> item) -> ((Number) item.get("totalAssigned")).longValue())
                        .reversed()
                        .thenComparing(item -> String.valueOf(item.get("name")), String.CASE_INSENSITIVE_ORDER))
                .toList();

        long totalAssigned = items.stream()
                .mapToLong(item -> ((Number) item.get("totalAssigned")).longValue())
                .sum();
        long unassignedCount = participants.stream()
                .filter(participant -> {
                    String picName = extractPic(participant.getNotes());
                    return picName.isBlank() || items.stream().noneMatch(item -> String.valueOf(item.get("name")).equalsIgnoreCase(picName));
                })
                .count();

        return ResponseEntity.ok(Map.of(
                "eventId", id,
                "tab", safe(tab).isBlank() ? "request" : tab.trim().toLowerCase(Locale.ROOT),
                "totalParticipants", participants.size(),
                "totalAssigned", totalAssigned,
                "unassignedCount", unassignedCount,
                "activePicsCount", items.size(),
                "items", items
        ));
    }

    @GetMapping("/{id}/statistics")
    public ResponseEntity<?> getEventStatistics(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "request") String tab,
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

        List<EventParticipant> eventParticipants = eventParticipantRepository.findByEventId(id);
        String normalizedTab = safe(tab).isBlank() ? "request" : tab.trim().toLowerCase(Locale.ROOT);

        List<EventParticipant> allScopeParticipants = filterParticipants(
                eventParticipants,
                currentUser,
                normalizedTab,
                pic,
                company,
                position,
                industry,
                confirmationStatus,
                reminderHariH,
                search
        );

        String minePic = resolveMinePic(currentUser, normalizedTab);
        List<EventParticipant> myScopeParticipants = filterParticipants(
                eventParticipants,
                currentUser,
                normalizedTab,
                minePic,
                company,
                position,
                industry,
                confirmationStatus,
                reminderHariH,
                search
        );

        return ResponseEntity.ok(Map.of(
                "eventId", id,
                "tab", normalizedTab,
                "scopes", Map.of(
                        "all", buildStatisticsForTab(allScopeParticipants, normalizedTab),
                        "mine", buildStatisticsForTab(myScopeParticipants, normalizedTab)
                )
        ));
    }

    @GetMapping("/{id}/participants/filter-options")
    public ResponseEntity<?> getEventParticipantFilterOptions(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "request") String tab,
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

        String normalizedTab = safe(tab).isBlank() ? "request" : tab.trim().toLowerCase(Locale.ROOT);
        String scopedPic = resolveMinePic(currentUser, normalizedTab);
        String effectivePicFilter = scopedPic != null ? scopedPic : pic;
        List<EventParticipant> participants = filterParticipants(
                eventParticipantRepository.findByEventId(id),
                currentUser,
                normalizedTab,
                effectivePicFilter,
                company,
                position,
                industry,
                confirmationStatus,
                reminderHariH,
                search
        );

        List<String> companies = participants.stream()
                .map(participant -> participant.getDatabase() != null && participant.getDatabase().getCompany() != null
                        ? safe(participant.getDatabase().getCompany().getName()).trim()
                        : "")
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(String::compareToIgnoreCase)
                .toList();

        List<String> positions = participants.stream()
                .map(participant -> participant.getDatabase() != null && participant.getDatabase().getPositionLevel() != null
                        ? safe(participant.getDatabase().getPositionLevel().getValue()).trim()
                        : "")
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(String::compareToIgnoreCase)
                .toList();

        List<String> industries = participants.stream()
                .map(participant -> participant.getDatabase() != null && participant.getDatabase().getCompany() != null
                        ? safe(participant.getDatabase().getCompany().getIndustry()).trim()
                        : "")
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(String::compareToIgnoreCase)
                .toList();

        List<String> pics = userRepository.findAll().stream()
                .filter(user -> securityHelper.hasAnyRole(user, Role.ADMIN, Role.MANAGER))
                .filter(user -> user.getAllowedEventIds() != null && user.getAllowedEventIds().contains(id))
                .map(user -> normalizePicName(user.getFullName(), user.getUsername()))
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(String::compareToIgnoreCase)
                .toList();

        return ResponseEntity.ok(Map.of(
                "eventId", id,
                "tab", normalizedTab,
                "companies", companies,
                "positions", positions,
                "industries", industries,
                "pics", pics
        ));
    }

    @GetMapping("/{id}/available-databases")
    public ResponseEntity<?> getAvailableDatabasesForEvent(
            @PathVariable Long id,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String company,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Long invitedEventId,
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

        if (!isEventDestination(event.getSyncDestination())) return unclassifiedEvent();
        List<EventParticipant> allParticipants = eventParticipantRepository.findAll();
        Set<Long> currentEventDatabaseIds = allParticipants.stream()
                .filter(participant -> participant.getEvent() != null && Objects.equals(participant.getEvent().getId(), id))
                .map(participant -> participant.getDatabase() != null ? participant.getDatabase().getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, List<EventParticipant>> participantsByDatabaseId = allParticipants.stream()
                .filter(participant -> participant.getDatabase() != null && participant.getDatabase().getId() != null)
                .collect(Collectors.groupingBy(participant -> participant.getDatabase().getId()));

        List<Event> visibleEvents = getVisibleEvents(currentUser).stream()
                .filter(visibleEvent -> !Objects.equals(visibleEvent.getId(), id))
                .sorted(Comparator.comparing(Event::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        Set<Long> visibleEventIds = visibleEvents.stream().map(Event::getId).collect(Collectors.toSet());

        List<Database> availableDatabases = databaseRepository.findAll().stream()
                .filter(database -> DatabaseDestination.isExact(database.getDatabaseDestination(), event.getSyncDestination()))
                .filter(database -> Boolean.TRUE.equals(database.getIsActive()))
                .filter(database -> !currentEventDatabaseIds.contains(database.getId()))
                .filter(database -> matchesAvailableDatabaseSearch(database, search))
                .filter(database -> matchesAvailableDatabaseCompany(database, company))
                .filter(database -> matchesAvailableDatabasePosition(database, position))
                .filter(database -> matchesAvailableDatabaseIndustry(database, industry))
                .filter(database -> matchesAvailableDatabaseCity(database, city))
                .filter(database -> matchesAvailableDatabaseInvitedEvent(database, invitedEventId, participantsByDatabaseId, visibleEventIds))
                .sorted(Comparator
                        .comparing((Database database) -> safe(database.getFirstName()).toLowerCase(Locale.ROOT))
                        .thenComparing(database -> safe(database.getLastName()).toLowerCase(Locale.ROOT))
                        .thenComparing(Database::getId))
                .toList();

        List<Map<String, Object>> items = availableDatabases.stream()
                .map(database -> {
                    List<Map<String, Object>> invitedEvents = participantsByDatabaseId
                            .getOrDefault(database.getId(), List.of())
                            .stream()
                            .map(EventParticipant::getEvent)
                            .filter(Objects::nonNull)
                            .filter(participantEvent -> !Objects.equals(participantEvent.getId(), id))
                            .filter(participantEvent -> visibleEventIds.contains(participantEvent.getId()))
                            .collect(Collectors.toMap(
                                    Event::getId,
                                    participantEvent -> participantEvent,
                                    (left, right) -> left,
                                    LinkedHashMap::new
                            ))
                            .values()
                            .stream()
                            .sorted(Comparator.comparing(Event::getName, String.CASE_INSENSITIVE_ORDER))
                            .map(participantEvent -> Map.<String, Object>of(
                                    "id", participantEvent.getId(),
                                    "name", participantEvent.getName()
                            ))
                            .toList();

                    return Map.<String, Object>of(
                            "database", database,
                            "invitedEvents", invitedEvents
                    );
                })
                .toList();

        List<String> companyOptions = availableDatabases.stream()
                .map(database -> database.getCompany() != null ? safe(database.getCompany().getName()).trim() : "")
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(String::compareToIgnoreCase)
                .toList();

        List<String> positionOptions = availableDatabases.stream()
                .map(database -> database.getPositionLevel() != null ? safe(database.getPositionLevel().getValue()).trim() : "")
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(String::compareToIgnoreCase)
                .toList();

        List<String> industryOptions = availableDatabases.stream()
                .map(database -> database.getCompany() != null ? safe(database.getCompany().getIndustry()).trim() : "")
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(String::compareToIgnoreCase)
                .toList();

        List<String> cityOptions = availableDatabases.stream()
                .map(database -> contactCity(database).trim())
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(String::compareToIgnoreCase)
                .toList();

        List<Map<String, Object>> invitedEventOptions = visibleEvents.stream()
                .map(visibleEvent -> Map.<String, Object>of(
                        "id", visibleEvent.getId(),
                        "name", visibleEvent.getName()
                ))
                .toList();

        return ResponseEntity.ok(Map.of(
                "eventId", id,
                "total", items.size(),
                "items", items,
                "filterOptions", Map.of(
                        "companies", companyOptions,
                        "positions", positionOptions,
                        "industries", industryOptions,
                        "cities", cityOptions,
                        "invitedEvents", invitedEventOptions
                )
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
                .filter(user -> securityHelper.hasAnyRole(user, Role.ADMIN, Role.MANAGER))
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

        if (!isEventDestination(event.getSyncDestination())) {
            return ResponseEntity.badRequest().body("Pilih tipe event Korporat atau Peserta saat membuat event.");
        }
        ResponseEntity<?> pmsError = pmsEventIdError(null, event.getPmsEventId());
        if (pmsError != null) return pmsError;
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
        if (!canAccessEvent(currentUser, id)) {
            return ResponseEntity.status(403).body("Forbidden: You don't have access to this event");
        }

        return eventRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    @org.springframework.transaction.annotation.Transactional
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

        Event existing = eventRepository.findForSyncById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }

        if (!canAccessEvent(currentUser, id)) return ResponseEntity.status(403).body("Forbidden: You don't have access to this event");
        ResponseEntity<?> destinationError = eventDestinationChangeError(existing, eventDetails.getSyncDestination());
        if (destinationError != null) return destinationError;
        ResponseEntity<?> pmsError = pmsEventIdError(existing, eventDetails.getPmsEventId());
        if (pmsError != null) return pmsError;
        if (eventDetails.getPmsEventId() != null) existing.setPmsEventId(eventDetails.getPmsEventId());
        if (eventDetails.getSyncDestination() != null) {
            classifyLegacyEventProfiles(existing, eventDetails.getSyncDestination());
            existing.setSyncDestination(eventDetails.getSyncDestination());
        }
        existing.setName(cleanName);
        existing.setEventType(eventDetails.getEventType());
        existing.setClientName(eventDetails.getClientName());
        existing.setDateStart(eventDetails.getDateStart());
        existing.setDateEnd(eventDetails.getDateEnd());
        existing.setNotes(eventDetails.getNotes());
        existing.setTargetParticipants(eventDetails.getTargetParticipants());
        existing.setEmsEventId(eventDetails.getEmsEventId());
        if (eventDetails.getEmsSource() != null) {
            existing.setEmsSource(eventDetails.getEmsSource());
        }

        Event saved = eventRepository.save(existing);
        return ResponseEntity.ok(saved);
    }

    @PatchMapping("/{id}/sync-destination")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> setSyncDestination(@PathVariable Long id, @RequestBody Map<String, String> request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) return ResponseEntity.status(401).body("Unauthorized");
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) return ResponseEntity.status(403).body("Forbidden");
        Event event = eventRepository.findForSyncById(id).orElse(null);
        if (event == null) return ResponseEntity.notFound().build();
        if (!canAccessEvent(currentUser, id)) return ResponseEntity.status(403).body("Forbidden: You don't have access to this event");
        String destination = request.get("destination");
        if (!"company".equals(destination) && !"participant".equals(destination)) {
            return ResponseEntity.badRequest().body("Pilih tujuan sync Database Perusahaan atau Database Peserta.");
        }
        DatabaseDestination selected = DatabaseDestination.fromValue(destination);
        ResponseEntity<?> destinationError = eventDestinationChangeError(event, selected);
        if (destinationError != null) return destinationError;
        classifyLegacyEventProfiles(event, selected);
        event.setSyncDestination(selected);
        return ResponseEntity.ok(eventRepository.save(event));
    }

    @PostMapping("/{id}/sync-ems/preview")
    public ResponseEntity<?> previewEmsParticipants(@PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) return ResponseEntity.status(401).body("Unauthorized");
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) return ResponseEntity.status(403).body("Forbidden");
        Event event = eventRepository.findById(id).orElse(null);
        if (event == null) return ResponseEntity.notFound().build();
        if (!canAccessEvent(currentUser, id)) return ResponseEntity.status(403).body("Forbidden: You don't have access to this event");
        return ResponseEntity.ok(emsService.previewParticipantsForEvent(event));
    }

    @PostMapping("/{id}/sync-ems")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> syncEmsParticipants(@PathVariable Long id,
            @RequestBody(required = false) Map<String, String> request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) return ResponseEntity.status(401).body("Unauthorized");
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) return ResponseEntity.status(403).body("Forbidden");
        Event event = eventRepository.findForSyncById(id).orElse(null);
        if (event == null) return ResponseEntity.notFound().build();
        if (!canAccessEvent(currentUser, id)) return ResponseEntity.status(403).body("Forbidden: You don't have access to this event");
        if (request == null || request.get("previewToken") == null || request.get("previewToken").isBlank()) {
            return ResponseEntity.badRequest().body("Preview ulang dan konfirmasi tujuan sebelum sync.");
        }
        return ResponseEntity.ok(emsService.syncParticipantsForEvent(event, request.get("previewToken")));
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
        String scopedPic = resolveMinePic(currentUser, tab);
        String effectivePic = scopedPic != null ? scopedPic : pic;
        return participants.stream()
                .filter(participant -> !isViewer(currentUser) || canAccessEvent(currentUser, participant.getEvent().getId()))
                .filter(participant -> matchesTab(participant, tab))
                .filter(participant -> matchesCompany(participant, company))
                .filter(participant -> matchesPosition(participant, position))
                .filter(participant -> matchesIndustry(participant, industry))
                .filter(participant -> matchesConfirmationStatus(participant, tab, confirmationStatus))
                .filter(participant -> matchesReminderHariH(participant, tab, reminderHariH))
                .filter(participant -> matchesPic(participant, effectivePic))
                .filter(participant -> matchesSearch(participant, search))
                .sorted(Comparator.comparing(EventParticipant::getId))
                .collect(Collectors.toList());
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

    private boolean matchesEventSearch(Event event, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }

        String query = search.trim().toLowerCase(Locale.ROOT);
        return safe(event.getName()).toLowerCase(Locale.ROOT).contains(query)
                || safe(event.getClientName()).toLowerCase(Locale.ROOT).contains(query)
                || String.valueOf(event.getId()).contains(query)
                || (event.getEmsEventId() != null && String.valueOf(event.getEmsEventId()).contains(query));
    }

    private boolean matchesAvailableDatabaseSearch(Database database, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }

        String query = search.trim().toLowerCase(Locale.ROOT);
        String emailText = database.getEmails() != null
                ? database.getEmails().stream()
                .map(email -> safe(email.getEmail()).toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" "))
                : "";
        String combinedText = String.join(" ",
                safe(database.getFirstName()),
                safe(database.getLastName()),
                safe(database.getCompany() != null ? database.getCompany().getName() : null),
                safe(database.getJobTitle()),
                emailText,
                safe(database.getMobilePhone())
        ).toLowerCase(Locale.ROOT);

        return List.of(query.split("\\s+")).stream()
                .filter(word -> !word.isBlank())
                .allMatch(combinedText::contains);
    }

    private boolean matchesAvailableDatabaseCompany(Database database, String company) {
        if (company == null || company.isBlank()) {
            return true;
        }
        return safe(database.getCompany() != null ? database.getCompany().getName() : null)
                .equalsIgnoreCase(company.trim());
    }

    private boolean matchesAvailableDatabasePosition(Database database, String position) {
        if (position == null || position.isBlank()) {
            return true;
        }
        return safe(database.getPositionLevel() != null ? database.getPositionLevel().getValue() : null)
                .equalsIgnoreCase(position.trim());
    }

    private boolean matchesAvailableDatabaseIndustry(Database database, String industry) {
        if (industry == null || industry.isBlank()) {
            return true;
        }
        return safe(database.getCompany() != null ? database.getCompany().getIndustry() : null)
                .equalsIgnoreCase(industry.trim());
    }

    private boolean matchesAvailableDatabaseCity(Database database, String city) {
        if (city == null || city.isBlank()) {
            return true;
        }
        return contactCity(database)
                .equalsIgnoreCase(city.trim());
    }

    private String contactCity(Database database) {
        if (database == null) return "";
        return safe(database.getBranch() != null ? database.getBranch().getCity()
                : database.getCompany() != null ? database.getCompany().getCity() : null);
    }

    private String contactOfficePhone(Database database) {
        if (database == null) return "";
        return safe(database.getBranch() != null ? database.getBranch().getOfficePhone()
                : database.getCompany() != null ? database.getCompany().getOfficePhone() : null);
    }

    private boolean matchesAvailableDatabaseInvitedEvent(
            Database database,
            Long invitedEventId,
            Map<Long, List<EventParticipant>> participantsByDatabaseId,
            Set<Long> visibleEventIds) {
        if (invitedEventId == null) {
            return true;
        }
        if (!visibleEventIds.contains(invitedEventId)) {
            return false;
        }

        return participantsByDatabaseId.getOrDefault(database.getId(), List.of()).stream()
                .map(EventParticipant::getEvent)
                .filter(Objects::nonNull)
                .anyMatch(event -> Objects.equals(event.getId(), invitedEventId));
    }

    private EventTimingStatus getEventTimingStatus(Event event) {
        if (event == null || event.getDateStart() == null) {
            return EventTimingStatus.PAST;
        }

        LocalDate today = LocalDate.now();
        LocalDate startDate = event.getDateStart();
        LocalDate endDate = event.getDateEnd() != null ? event.getDateEnd() : startDate;

        if (today.isBefore(startDate)) {
            return EventTimingStatus.UPCOMING;
        }
        if (!today.isAfter(endDate)) {
            return EventTimingStatus.ONGOING;
        }
        return EventTimingStatus.PAST;
    }

    private String resolveMinePic(AppUser currentUser, String tab) {
        if (currentUser == null || securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return null;
        }
        if ("request".equalsIgnoreCase(safe(tab))) {
            return null;
        }
        return normalizePicName(currentUser.getFullName(), currentUser.getUsername());
    }

    private Map<String, Long> buildStatisticsForTab(List<EventParticipant> participants, String tab) {
        String normalizedTab = safe(tab).isBlank() ? "request" : tab.trim().toLowerCase(Locale.ROOT);
        LinkedHashMap<String, Long> stats = new LinkedHashMap<>();

        switch (normalizedTab) {
            case "pre_event" -> {
                stats.put("totalRegister", countRegistered(participants));
                stats.put("tentative", countTentative(participants));
                stats.put("notRespondYet", countParticipantStatus(participants, "not_respond_yet"));
                stats.put("notInterest", countParticipantStatus(participants, "not_interest"));
                stats.put("approve", participants.stream().filter(participant -> "approve".equals(getPreEventApprovalStatus(participant))).count());
                stats.put("pending", participants.stream().filter(participant -> "pending".equals(getPreEventApprovalStatus(participant))).count());
            }
            case "declined" -> {
                stats.put("totalDeclined", participants.stream()
                        .filter(participant -> "decline".equals(getEffectiveConfirmationStatus(participant))
                                || "declined".equals(getEffectiveConfirmationStatus(participant))
                                || "decline".equals(getPreEventApprovalStatus(participant)))
                        .count());
                stats.put("declinedFromDbVetting", participants.stream()
                        .filter(participant -> "decline".equals(getEffectiveConfirmationStatus(participant))
                                || "declined".equals(getEffectiveConfirmationStatus(participant)))
                        .count());
                stats.put("declinedFromPreEvent", participants.stream()
                        .filter(participant -> "decline".equals(getPreEventApprovalStatus(participant)))
                        .count());
            }
            case "reminder" -> {
                stats.put("approvedRegisterTotal", participants.stream()
                        .filter(participant -> {
                            String conf = getEffectiveConfirmationStatus(participant);
                            return ("approve".equals(conf) || "confirmed".equals(conf)) && isRegisteredParticipant(participant);
                        })
                        .count());
                stats.put("confirmToAttend", participants.stream().filter(participant -> "confirm".equals(getLatestReminderStatus(participant))).count());
                stats.put("tentative", participants.stream().filter(participant -> "tentative".equals(getLatestReminderStatus(participant))).count());
                stats.put("notRespondYet", participants.stream().filter(participant -> "not_respond_yet".equals(getLatestReminderStatus(participant))).count());
                stats.put("unableToAttend", participants.stream().filter(participant -> "unable_to_attend".equals(getLatestReminderStatus(participant))).count());
            }
            case "reminder_dday" -> {
                stats.put("onLocation", participants.stream().filter(participant -> "on_location".equals(getHariHStatus(participant))).count());
                stats.put("onTheWay", participants.stream().filter(participant -> "on_the_way".equals(getHariHStatus(participant))).count());
                stats.put("notRespondYet", participants.stream().filter(participant -> {
                    String status = getHariHStatus(participant);
                    return status.isBlank() || "not_respon_yet".equals(status) || status.startsWith("not_respond_");
                }).count());
                stats.put("unableToAttend", participants.stream().filter(participant -> "unable_to_attend".equals(getHariHStatus(participant))).count());
            }
            case "request" -> {
                stats.put("totalRequest", participants.stream()
                        .filter(participant -> {
                            String conf = safe(participant.getConfirmationStatus()).toLowerCase(Locale.ROOT);
                            return conf.isBlank() || "pending".equals(conf) || "decline".equals(conf) || "declined".equals(conf);
                        })
                        .count());
                stats.put("pendingApproval", participants.stream()
                        .filter(participant -> {
                            String conf = safe(participant.getConfirmationStatus()).toLowerCase(Locale.ROOT);
                            return conf.isBlank() || "pending".equals(conf);
                        })
                        .count());
                stats.put("takenOut", participants.stream()
                        .filter(participant -> {
                            String conf = safe(participant.getConfirmationStatus()).toLowerCase(Locale.ROOT);
                            return "decline".equals(conf) || "declined".equals(conf);
                        })
                        .count());
            }
            default -> {
                stats.put("totalParticipants", (long) participants.size());
            }
        }

        return stats;
    }

    private long countRegistered(List<EventParticipant> participants) {
        return participants.stream().filter(this::isRegisteredParticipant).count();
    }

    private long countTentative(List<EventParticipant> participants) {
        return participants.stream()
                .filter(participant -> {
                    String status = participant.getParticipantStatus() != null
                            ? participant.getParticipantStatus().name().toLowerCase(Locale.ROOT)
                            : "";
                    return "tentative".equals(status) || "yellow".equals(status);
                })
                .count();
    }

    private long countParticipantStatus(List<EventParticipant> participants, String targetStatus) {
        return participants.stream()
                .filter(participant -> {
                    String status = participant.getParticipantStatus() != null
                            ? participant.getParticipantStatus().name().toLowerCase(Locale.ROOT)
                            : "";
                    if ("not_respond_yet".equals(targetStatus)) {
                        return status.isBlank() || "not_respond_yet".equals(status) || "not_respon_yet".equals(status) || status.startsWith("not_respond_");
                    }
                    return "not_interest".equals(targetStatus) && ("not_interest".equals(status) || "red".equals(status));
                })
                .count();
    }

    private String getPreEventApprovalStatus(EventParticipant participant) {
        String status = safe(participant.getPreEventApprovalStatus()).toLowerCase(Locale.ROOT);
        return status.isBlank() ? "pending" : status;
    }

    private String getLatestReminderStatus(EventParticipant participant) {
        List<String> reminderStatuses = List.of(
                normalizeStatus(participant.getReminderH1()),
                normalizeStatus(participant.getReminderH3()),
                normalizeStatus(participant.getReminderH7())
        );

        String latest = reminderStatuses.stream().filter(value -> !value.isBlank()).findFirst().orElse("");
        if ("confirm".equals(latest) || "confirmed".equals(latest)) {
            return "confirm";
        }
        if ("tentative".equals(latest)) {
            return "tentative";
        }
        if ("unabletoattend".equals(latest)
                || "notinterest".equals(latest)
                || "unableattend".equals(latest)
                || "decline".equals(latest)
                || "declined".equals(latest)) {
            return "unable_to_attend";
        }
        return "not_respond_yet";
    }

    private String normalizeStatus(String value) {
        String normalized = safe(value).toLowerCase(Locale.ROOT).trim().replaceAll("[\\s_-]+", "");
        if ("null".equals(normalized) || "undefined".equals(normalized)) {
            return "";
        }
        return normalized;
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

    private String getOfficeEmail(EventParticipant participant) {
        if (participant.getDatabase() == null || participant.getDatabase().getEmails() == null) {
            return "";
        }
        return participant.getDatabase().getEmails().stream()
                .filter(email -> "company".equalsIgnoreCase(safe(email.getEmailType())) || Boolean.TRUE.equals(email.getIsCorporate()))
                .map(email -> safe(email.getEmail()).trim())
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private String getPersonalEmail(EventParticipant participant) {
        if (participant.getDatabase() == null || participant.getDatabase().getEmails() == null) {
            return "";
        }
        return participant.getDatabase().getEmails().stream()
                .filter(email -> "personal".equalsIgnoreCase(safe(email.getEmailType())) || Boolean.FALSE.equals(email.getIsCorporate()))
                .map(email -> safe(email.getEmail()).trim())
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private String stripMetadataNotes(String notes) {
        return safe(notes)
                .replaceAll("(?i)\\[PIC:\\s*[^\\]]+\\]", "")
                .replaceAll("(?i)\\[PreEventApproval:\\s*[^\\]]+\\]", "")
                .replaceAll("(?i)\\[Origin:\\s*[^\\]]+\\]", "")
                .replaceAll("(?i)\\[(EMS|TAKEOUT|Opt-Out|TIKUS)[^\\]]*\\]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeExportStatus(String value) {
        String normalized = safe(value).trim();
        if (normalized.isBlank()) {
            return "";
        }
        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "not_respon_yet", "not_respond_yet" -> "Not respond yet";
            case "not_respond_2x" -> "Not respond 2x";
            case "not_respond_3x" -> "Not respond 3x";
            case "not_respond_4x" -> "Not respond 4x";
            case "not_respond_5x" -> "Not respond 5x";
            case "not_respond_6x" -> "Not respond 6x";
            case "not_respond_7x" -> "Not respond 7x";
            case "not_respond_8x" -> "Not respond 8x";
            case "not_respond_9x" -> "Not respond 9x";
            case "confirm", "confirmed" -> "Confirm";
            case "on_location" -> "On Location";
            case "on_the_way" -> "On The Way";
            case "unable_to_attend" -> "Unable to attend";
            default -> normalized;
        };
    }

    private String sanitizeFileName(String value) {
        String sanitized = safe(value).trim().replaceAll("[^a-zA-Z0-9._-]+", "_");
        return sanitized.isBlank() ? "export" : sanitized;
    }

    private EventImportBatch readEventImportDrafts(
            MultipartFile file,
            List<EventParticipant> existingParticipants,
            List<Database> allDatabases, String destination) throws Exception {
        boolean participantImport = "participant".equals(destination);
        List<ImportValidationRowDraft> drafts = new ArrayList<>();
        int blankCount = 0;

        try (InputStream is = file.getInputStream(); Workbook workbook = new XSSFWorkbook(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new IllegalArgumentException("Header row not found");
            }

            Map<String, Integer> headerMap = buildHeaderMap(headerRow);
            boolean hasImportHeader = hasAnyHeader(headerMap, "first name", "firts name", "nama", "company name", "company", "company email address", "email peserta", "email", "mobile phone");

            for (int rowIndex = hasImportHeader ? 1 : 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }

                String firstName = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 5, "first name", "firts name", "firstname", "first_name", "nama depan", "name", "nama"));
                String lastName = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 6, "last name", "lastname", "last_name", "nama belakang"));
                String groupName = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 1, "nama group", "group name", "group", "nama group holding", "nama group/holding company", "group holding company", "group/holding company"));
                String brandName = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 2, "nama brand", "brand name", "brand"));
                String companyName = cleanCompanyName(normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 3, "company name", "companyname", "company", "perusahaan")));
                String rawMobilePhone = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 12, "mobile phone", "phone", "no hp", "whatsapp"));
                String mobilePhone = cleanImportPhone(rawMobilePhone);
                String companyEmail = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 13, "company email address", "company email", "email", "email peserta"));
                String personalEmail = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 14, "personal email address", "personal email"));
                String salutation = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 4, "salutation"));
                String position = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 7, "position", "postition", "position level"));
                String specialityDivision = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 8, "division", "speciality/division", "speciality"));
                String jobTitle = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 9, "jobtitle", "job title", "jabatan"));
                String address = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 10, "address", "company address", "alamat"));
                String rawOfficePhone = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 11, "office phone", "company phone", "telepon kantor"));
                String officePhone = cleanImportPhone(rawOfficePhone);
                String industry = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 15, "industry", "industri"));
                String sizeRevenue = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 16, "company size (revenue)"));
                String sizeEmployee = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 17, "company size (employee)"));
                String hardware = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 18, "company hardware"));
                String linkedinUrl = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 19, "linkedin link", "linkedin"));
                String city = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 20, "city", "kota"));
                String postalCode = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 21, "postal code"));
                String website = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 22, "company website", "website"));
                String category = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, -1, "kategori peserta")).toLowerCase(Locale.ROOT);
                if (participantImport) {
                    String institution = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, -1, "sekolah/kampus/instansi", "institution name", "instansi"));
                    if (!institution.isBlank()) companyName = institution;
                }

                if (firstName.isBlank() && lastName.isBlank() && companyName.isBlank() && rawMobilePhone.isBlank() && companyEmail.isBlank() && personalEmail.isBlank()) {
                    blankCount++;
                    continue;
                }

                List<String> missingFields = participantImport ? new ArrayList<>() : getEventImportMissingMandatoryFields(
                        groupName, brandName, companyName, salutation, firstName, lastName,
                        position, jobTitle, address, officePhone, mobilePhone, companyEmail, industry, city, website);
                if (participantImport && firstName.isBlank() && lastName.isBlank()) missingFields.add("Nama Peserta");

                List<String> issues = new ArrayList<>();
                List<String> emails = eventImportEmails(companyEmail, personalEmail);
                if (emails.isEmpty() && rawMobilePhone.isBlank()) issues.add("Isi minimal satu kontak: email atau nomor HP/WhatsApp.");
                if (emails.stream().anyMatch(email -> !EVENT_IMPORT_EMAIL.matcher(email).matches())) issues.add("Format email tidak valid");
                if (!rawMobilePhone.isBlank() && !isUsableEventImportPhone(rawMobilePhone)) issues.add("Mobile Phone tidak valid; isi nomor yang benar atau kosongkan jika ada email.");
                if (!rawOfficePhone.isBlank() && !isUsableEventImportPhone(rawOfficePhone)) issues.add("Office Phone tidak valid.");
                if (officePhone.length() > 50) issues.add("Office Phone maksimal 50 karakter setelah format nomor dirapikan.");
                if (participantImport && !Set.of("", "pelajar", "mahasiswa", "umum").contains(category)) issues.add("Kategori Peserta tidak valid");
                if (firstName.length() > 255 || lastName.length() > 255 || companyName.length() > 255) issues.add("Nama dan perusahaan maksimal 255 karakter");
                List<String> warnings = mobilePhone.isBlank() ? List.of("Nomor HP/WhatsApp belum diisi; peserta belum bisa ditelepon.") : List.of();
                Database existingDatabase = null;
                try {
                    existingDatabase = findEventImportDatabase(allDatabases, firstName, lastName, mobilePhone, companyEmail, personalEmail, participantImport);
                    if (!rawMobilePhone.isBlank() && isUsableEventImportPhone(rawMobilePhone)) {
                        mobilePhone = mergeEventImportPhones(existingDatabase != null ? existingDatabase.getMobilePhone() : "", rawMobilePhone);
                    }
                    if (existingDatabase != null && Boolean.FALSE.equals(existingDatabase.getIsActive())) issues.add("Kontak nonaktif; tinjau profil sebelum import");
                    if (!participantImport && existingDatabase != null && existingDatabase.getCompany() != null && !companyName.isBlank()
                            && !normalizedEventImportName(cleanCompanyName(existingDatabase.getCompany().getName()), "")
                                    .equals(normalizedEventImportName(companyName, ""))) {
                        issues.add("Kontak sudah terdaftar di perusahaan lain; pindahkan perusahaan/cabang melalui Edit kontak sebelum import.");
                    }
                } catch (IllegalArgumentException conflict) { issues.add(conflict.getMessage()); }
                Database matched = existingDatabase;
                EventParticipant existingEventParticipant = existingParticipants.stream().filter(participant -> matched != null
                        && participant.getDatabase() != null && Objects.equals(participant.getDatabase().getId(), matched.getId())).findFirst().orElse(null);

                drafts.add(new ImportValidationRowDraft(
                        rowIndex + 1,
                        firstName,
                        lastName,
                        companyName,
                        mobilePhone,
                        companyEmail,
                        personalEmail,
                        groupName,
                        brandName,
                        salutation,
                        position,
                        specialityDivision,
                        jobTitle,
                        address,
                        officePhone,
                        industry,
                        sizeRevenue,
                        sizeEmployee,
                        hardware,
                        linkedinUrl,
                        city,
                        postalCode,
                        website,
                        existingDatabase != null ? existingDatabase.getId() : null,
                        existingEventParticipant != null ? existingEventParticipant.getId() : null,
                        issues,
                        existingEventParticipant != null,
                        category,
                        missingFields,
                        warnings
                ));
            }
        }

        // ponytail: compare unnamed rows within this file directly; index contact keys if imports become large.
        for (ImportValidationRowDraft draft : drafts) {
            if (!normalizedEventImportName(draft.firstName(), draft.lastName()).isBlank()) continue;
            Set<String> candidateNames = new HashSet<>();
            allDatabases.stream().filter(database -> Objects.equals(database.getId(), draft.existingDatabaseId()))
                    .map(database -> normalizedEventImportName(database.getFirstName(), database.getLastName()))
                    .filter(name -> !name.isBlank()).forEach(candidateNames::add);
            Set<String> addresses = new HashSet<>(eventImportEmails(draft.companyEmail(), draft.personalEmail()));
            Set<String> phones = normalizedEventImportPhones(draft.mobilePhone());
            for (ImportValidationRowDraft candidate : drafts) {
                String name = normalizedEventImportName(candidate.firstName(), candidate.lastName());
                if (name.isBlank()) continue;
                boolean emailMatch = eventImportEmails(candidate.companyEmail(), candidate.personalEmail()).stream().anyMatch(addresses::contains);
                boolean phoneMatch = normalizedEventImportPhones(candidate.mobilePhone()).stream().anyMatch(phones::contains);
                if (emailMatch || phoneMatch) candidateNames.add(name);
            }
            if (candidateNames.size() > 1) draft.issues().add("Identitas peserta cocok dengan beberapa profil di file ini; rapikan duplikat sebelum import.");
        }
        return new EventImportBatch(drafts, blankCount);
    }

    private record EventImportBatch(List<ImportValidationRowDraft> rows, int blankCount) {}

    private List<String> eventImportEmails(String... values) {
        return ExcelImportController.splitEmailTokens(String.join(";", values));
    }

    private Database findEventImportDatabase(List<Database> databases, String firstName, String lastName,
            String phone, String companyEmail, String personalEmail, boolean participantImport) {
        Set<String> addresses = new LinkedHashSet<>(eventImportEmails(companyEmail, personalEmail));
        Set<String> individualAddresses = participantImport ? addresses : new LinkedHashSet<>(eventImportEmails(personalEmail));
        String name = normalizedEventImportName(firstName, lastName);
        Set<String> normalizedPhones = normalizedEventImportPhones(phone);
        List<Database> owners = new ArrayList<>();
        List<Database> contactOwners = new ArrayList<>();
        for (Database database : databases) {
            if (!DatabaseDestination.isExact(database.getDatabaseDestination(), participantImport ? DatabaseDestination.PARTICIPANT : DatabaseDestination.COMPANY)) continue;
            String existingName = normalizedEventImportName(database.getFirstName(), database.getLastName());
            boolean samePersonName = existingName.equals(name);
            boolean emailMatch = false;
            if (database.getEmails() != null) for (DatabaseEmail email : database.getEmails()) {
                String address = safe(email.getEmail()).trim().toLowerCase(Locale.ROOT);
                if (!name.isBlank() && !existingName.isBlank() && !samePersonName && individualAddresses.contains(address)) {
                    throw new IllegalArgumentException("Email sudah dipakai nama lain; tinjau profil lama sebelum import");
                }
                if (addresses.contains(address)) emailMatch = true;
            }
            Set<String> existingPhones = normalizedEventImportPhones(database.getMobilePhone());
            existingPhones.addAll(normalizedEventImportPhones(database.getNormalizedPhone()));
            boolean phoneMatch = normalizedPhones.stream().anyMatch(existingPhones::contains);
            if (emailMatch || phoneMatch) {
                contactOwners.add(database);
                if (name.isBlank() || existingName.isBlank() || samePersonName) owners.add(database);
            }
        }
        if (owners.size() > 1 || owners.size() == 1 && contactOwners.size() > 1
                && normalizedEventImportName(owners.get(0).getFirstName(), owners.get(0).getLastName()).isBlank()) {
            throw new IllegalArgumentException("Identitas peserta cocok dengan beberapa profil; rapikan duplikat sebelum import");
        }
        return owners.isEmpty() ? null : owners.get(0);
    }

    private String normalizedEventImportName(String firstName, String lastName) {
        return buildImportFullName(firstName, lastName).replaceAll("(?U)\\s+", " ").trim();
    }

    private List<String> eventImportPhoneTokens(String value) {
        return ContactPhone.tokens(value);
    }

    private boolean isUsableEventImportPhone(String value) {
        return ContactPhone.isUsable(value);
    }

    private Set<String> normalizedEventImportPhones(String value) {
        return ContactPhone.normalizedNumbers(value);
    }

    private String mergeEventImportPhones(String existing, String incoming) {
        return ContactPhone.merge(existing, incoming);
    }

    private String normalizeEventImportPhone(String value) {
        return ContactPhone.primary(value);
    }

    private void classifyLegacyEventProfiles(Event event, DatabaseDestination selected) {
        if (event.getSyncDestination() != null) return;
        List<EventParticipant> links = eventParticipantRepository.findByEventId(event.getId());
        if (links.isEmpty()) return;
        List<Database> profiles = databaseRepository.findAll();
        Map<Long, Database> targets = new LinkedHashMap<>();
        Set<Long> targetIds = new HashSet<>();
        List<Database> pendingCopies = new ArrayList<>();
        for (EventParticipant link : links) {
            Database contact = link.getDatabase();
            Database target = DatabaseDestination.isExact(contact.getDatabaseDestination(), selected)
                    ? contact : findLegacyEventProfile(profiles, contact, selected);
            if (target != null && !targetIds.add(target.getId())
                    || target == null && pendingCopies.stream().anyMatch(pending -> legacyContactKeysOverlap(pending, contact))) {
                throw legacyClassificationConflict("Beberapa peserta lama cocok dengan profil yang sama. Rapikan duplikat event sebelum menentukan jenis data.");
            }
            if (target == null) pendingCopies.add(contact);
            targets.put(contact.getId(), target);
        }
        for (EventParticipant link : links) {
            Database contact = link.getDatabase();
            if (DatabaseDestination.isExact(contact.getDatabaseDestination(), selected)) continue;
            Database copy = targets.get(contact.getId());
            if (copy == null) copy = copyLegacyEventProfile(contact, selected);
            copyLegacyRestrictions(contact, copy);
            // Keep the event-participant ID, notes, status and activity references unchanged.
            link.setDatabase(copy);
            eventParticipantRepository.save(link);
        }
    }

    private Database findLegacyEventProfile(List<Database> profiles, Database original, DatabaseDestination selected) {
        List<Database> matches = profiles.stream()
                .filter(profile -> DatabaseDestination.isExact(profile.getDatabaseDestination(), selected))
                .filter(profile -> legacyContactKeysOverlap(original, profile)).toList();
        String originalName = normalizedEventImportName(original.getFirstName(), original.getLastName());
        if (matches.size() > 1 || matches.stream().anyMatch(profile -> {
            String name = normalizedEventImportName(profile.getFirstName(), profile.getLastName());
            return !originalName.isBlank() && !name.isBlank() && !originalName.equals(name);
        })) {
            throw legacyClassificationConflict("Identitas peserta lama cocok dengan profil tujuan yang ambigu atau berbeda nama. Tinjau profil sebelum menentukan jenis data event.");
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private boolean legacyContactKeysOverlap(Database first, Database second) {
        Set<String> addresses = (first.getEmails() == null ? List.<DatabaseEmail>of() : first.getEmails()).stream()
                .map(email -> safe(email.getEmail()).trim().toLowerCase(Locale.ROOT)).filter(email -> !email.isBlank()).collect(Collectors.toSet());
        if ((second.getEmails() == null ? List.<DatabaseEmail>of() : second.getEmails()).stream()
                .map(email -> safe(email.getEmail()).trim().toLowerCase(Locale.ROOT)).anyMatch(addresses::contains)) return true;
        Set<String> numbers = normalizedEventImportPhones(first.getMobilePhone());
        numbers.addAll(normalizedEventImportPhones(first.getNormalizedPhone()));
        Set<String> secondNumbers = normalizedEventImportPhones(second.getMobilePhone());
        secondNumbers.addAll(normalizedEventImportPhones(second.getNormalizedPhone()));
        return secondNumbers.stream().anyMatch(numbers::contains);
    }

    private org.springframework.web.server.ResponseStatusException legacyClassificationConflict(String message) {
        return new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private Database copyLegacyEventProfile(Database original, DatabaseDestination selected) {
        Database copy = new Database();
        org.springframework.beans.BeanUtils.copyProperties(original, copy,
                "id", "emails", "company", "branch", "databaseDestination", "createdAt", "updatedAt");
        copy.setDatabaseDestination(selected);
        if (selected == DatabaseDestination.PARTICIPANT) {
            if (safe(copy.getInstitutionName()).isBlank() && original.getCompany() != null) {
                copy.setInstitutionName(original.getCompany().getName());
            }
        } else {
            copy.setCompany(original.getCompany());
            copy.setBranch(original.getBranch());
        }
        copy = databaseRepository.save(copy);
        List<DatabaseEmail> copiedEmails = new ArrayList<>();
        for (DatabaseEmail email : original.getEmails() == null ? List.<DatabaseEmail>of() : original.getEmails()) {
            DatabaseEmail copiedEmail = new DatabaseEmail();
            org.springframework.beans.BeanUtils.copyProperties(email, copiedEmail, "id", "database", "createdAt");
            copiedEmail.setDatabase(copy);
            if (selected == DatabaseDestination.PARTICIPANT) {
                copiedEmail.setEmailType("personal"); copiedEmail.setIsCorporate(false);
                copiedEmail.setIsPrimary(copiedEmails.isEmpty());
            }
            copiedEmails.add(databaseEmailRepository.save(copiedEmail));
        }
        copy.setEmails(copiedEmails);
        return copy;
    }

    private void copyLegacyRestrictions(Database original, Database copy) {
        if (Boolean.FALSE.equals(original.getIsActive()) && !Boolean.FALSE.equals(copy.getIsActive())) {
            copy.setIsActive(false);
            databaseRepository.save(copy);
        }
        for (FlaggedIdentity flag : flaggedIdentityRepository.findAll()) {
            if (flag.getDatabase() == null || !Objects.equals(flag.getDatabase().getId(), original.getId())) continue;
            FlaggedIdentity copiedFlag = new FlaggedIdentity();
            org.springframework.beans.BeanUtils.copyProperties(flag, copiedFlag, "id", "database", "createdAt", "updatedAt");
            copiedFlag.setDatabase(copy);
            copiedFlag.setLinkedFlag(null);
            flaggedIdentityRepository.save(copiedFlag);
        }
        for (RemovalRequest removal : removalRequestRepository.findAll()) {
            if (removal.getDatabase() == null || !Objects.equals(removal.getDatabase().getId(), original.getId())) continue;
            RemovalRequest copiedRemoval = new RemovalRequest();
            org.springframework.beans.BeanUtils.copyProperties(removal, copiedRemoval, "id", "database", "createdAt");
            copiedRemoval.setDatabase(copy);
            removalRequestRepository.save(copiedRemoval);
        }
    }

    private ResponseEntity<?> pmsEventIdError(Event existing, Long pmsEventId) {
        if (pmsEventId == null) return null;
        if (pmsEventId <= 0) return ResponseEntity.badRequest().body("pmsEventId harus berupa ID positif.");
        if (existing != null && existing.getPmsEventId() != null && !existing.getPmsEventId().equals(pmsEventId)) {
            return ResponseEntity.status(409).body("Event CRM sudah terhubung ke event PMS lain; tautan tidak dapat diganti.");
        }
        Event owner = eventRepository.findByPmsEventId(pmsEventId).orElse(null);
        if (owner != null && (existing == null || !Objects.equals(owner.getId(), existing.getId()))) {
            return ResponseEntity.status(409).body("Event PMS sudah terhubung ke event CRM lain.");
        }
        return null;
    }

    private boolean isEventDestination(DatabaseDestination destination) {
        return destination == DatabaseDestination.COMPANY || destination == DatabaseDestination.PARTICIPANT;
    }

    private ResponseEntity<?> unclassifiedEvent() {
        return ResponseEntity.status(409).body(Map.of("message", "Tipe event belum dipilih. Pilih Korporat atau Peserta melalui Edit Event terlebih dahulu."));
    }

    private ResponseEntity<?> eventImportDestinationError(Event event, String requested) {
        if (!isEventDestination(event.getSyncDestination())) return unclassifiedEvent();
        if (requested != null && !requested.equals(event.getSyncDestination().value())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Tujuan import harus mengikuti tipe event; tujuan tidak dapat diganti saat import."));
        }
        return null;
    }

    private ResponseEntity<?> eventDestinationChangeError(Event event, DatabaseDestination selected) {
        if (selected == null) return null; // An omitted edit field preserves an existing or legacy type.
        if (!isEventDestination(selected)) return ResponseEntity.badRequest().body("Pilih tipe event Korporat atau Peserta.");
        if (selected == event.getSyncDestination()) return null;
        List<EventParticipant> participants = eventParticipantRepository.findByEventId(event.getId());
        if (participants.isEmpty()) return null;
        if (event.getSyncDestination() != null) {
            return ResponseEntity.status(409).body("Tipe event sudah terkunci karena event memiliki peserta; buat event lain untuk tipe berbeda.");
        }
        if (participants.stream().anyMatch(row -> row.getDatabase() == null)) {
            return ResponseEntity.status(409).body("Profil peserta lama belum tersedia. Tinjau data sebelum menentukan tipe event.");
        }
        return null;
    }

    private void registerImportDraftEmails(Map<String, List<EventImportEmailOwnerInfo>> owners,
            ImportValidationRowDraft draft, String destination) {
        registerEventImportEmails(owners, "Baris " + draft.rowNumber(), buildImportFullName(draft.firstName(), draft.lastName()),
                "participant".equals(destination) ? "" : draft.companyEmail(),
                "participant".equals(destination) ? String.join(";", draft.companyEmail(), draft.personalEmail()) : draft.personalEmail(), draft.rowNumber());
    }

    private void saveParticipantImportEmail(Database database, String address) {
        List<DatabaseEmail> owners = databaseEmailRepository.findAllByEmailIgnoreCase(address).stream()
                .filter(email -> email.getDatabase() == null
                        || DatabaseDestination.isExact(email.getDatabase().getDatabaseDestination(), DatabaseDestination.PARTICIPANT))
                .toList();
        if (owners.stream().anyMatch(email -> email.getDatabase() == null || !Objects.equals(email.getDatabase().getId(), database.getId()))) {
            throw new IllegalStateException("Pemilik email berubah; preview ulang sebelum import peserta");
        }
        if (owners.stream().anyMatch(email -> Objects.equals(email.getDatabase().getId(), database.getId()))) return;
        DatabaseEmail saved = databaseEmailRepository.save(DatabaseEmail.builder().database(database).email(address)
                .emailType("personal").isCorporate(false).isVerified(true)
                .isPrimary(database.getEmails() == null || database.getEmails().isEmpty()).build());
        List<DatabaseEmail> addresses = database.getEmails() == null ? new ArrayList<>() : new ArrayList<>(database.getEmails());
        addresses.add(saved); database.setEmails(addresses);
    }


    private void saveImportEmail(Database database, String email, String emailType, boolean corporate) {
        String normalizedEmail = safe(email).trim().toLowerCase(Locale.ROOT);
        if (normalizedEmail.isBlank()) {
            return;
        }

        List<DatabaseEmail> existingEmails = databaseEmailRepository.findAllByEmailIgnoreCase(normalizedEmail).stream()
                .filter(existing -> existing.getDatabase() == null
                        || DatabaseDestination.isExact(existing.getDatabase().getDatabaseDestination(), DatabaseDestination.COMPANY))
                .toList();
        if (existingEmails.stream().anyMatch(existing -> existing.getDatabase() == null)) {
            throw new IllegalStateException("Pemilik email tidak tersedia; tinjau data sebelum import.");
        }
        boolean personal = "personal".equalsIgnoreCase(emailType);
        if ((personal && !existingEmails.isEmpty())
                || existingEmails.stream().anyMatch(existing -> existing.getDatabase() != null
                        && existing.getDatabase().getId().equals(database.getId()))) {
            return;
        }

        DatabaseEmail saved = databaseEmailRepository.save(DatabaseEmail.builder()
                .database(database)
                .email(normalizedEmail)
                .emailType(emailType)
                .isPrimary(corporate)
                .isVerified(true)
                .isCorporate(corporate)
                .build());
        List<DatabaseEmail> addresses = database.getEmails() == null ? new ArrayList<>() : new ArrayList<>(database.getEmails());
        addresses.add(saved); database.setEmails(addresses);
    }

    private Map<String, Integer> buildHeaderMap(Row headerRow) {
        Map<String, Integer> headerMap = new LinkedHashMap<>();
        for (Cell cell : headerRow) {
            headerMap.put(normalizeHeader(getCellValue(cell)), cell.getColumnIndex());
        }
        return headerMap;
    }

    private String getCellByAliases(Row row, Map<String, Integer> headerMap, String... aliases) {
        for (String alias : aliases) {
            Integer index = headerMap.get(normalizeHeader(alias));
            if (index != null) {
                return getCellValue(row.getCell(index));
            }
        }
        return "";
    }

    private boolean hasAnyHeader(Map<String, Integer> headerMap, String... aliases) {
        for (String alias : aliases) {
            if (headerMap.containsKey(normalizeHeader(alias))) {
                return true;
            }
        }
        return false;
    }

    private String getImportCell(Row row, Map<String, Integer> headerMap, boolean hasImportHeader, int fallbackIndex, String... aliases) {
        if (hasImportHeader) {
            return getCellByAliases(row, headerMap, aliases);
        }
        return fallbackIndex < 0 ? "" : getCellValue(row.getCell(fallbackIndex));
    }

    private String getCellValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> safe(cell.getStringCellValue());
            case NUMERIC -> {
                double value = cell.getNumericCellValue();
                if (value == Math.rint(value)) {
                    yield String.valueOf((long) value);
                }
                yield String.valueOf(value);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> safe(cell.getCellFormula());
            default -> "";
        };
    }

    private String normalizeHeader(String value) {
        return safe(value).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ");
    }

    private String normalizeImportValue(String value) {
        String normalized = safe(value).trim();
        if (normalized.isBlank() || normalized.matches("^-+$")) {
            return "";
        }
        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "n/a", "na", "none", "null", "tidak ada", "kosong" -> "";
            default -> normalized;
        };
    }

    private String cleanImportPhone(String value) {
        return ContactPhone.clean(value);
    }

    private String buildImportFullName(String firstName, String lastName) {
        return (safe(firstName) + " " + safe(lastName)).trim().toLowerCase(Locale.ROOT);
    }

    private List<String> getEventImportMissingMandatoryFields(
            String groupName, String brandName, String companyName,
            String salutation, String firstName, String lastName,
            String position, String jobTitle, String address,
            String officePhone, String mobilePhone, String companyEmail,
            String industry, String city, String website) {
        List<String> missing = new ArrayList<>();
        if (groupName.isBlank()) missing.add("Nama Group Holding");
        if (brandName.isBlank()) missing.add("Nama Brand");
        if (companyName.isBlank()) missing.add("Company Name");
        if (salutation.isBlank()) missing.add("Salutation");
        if (firstName.isBlank()) missing.add("First Name");
        if (lastName.isBlank()) missing.add("Last Name");
        if (position.isBlank()) missing.add("Position");
        if (jobTitle.isBlank()) missing.add("Job Title");
        if (address.isBlank()) missing.add("Address");
        if (officePhone.isBlank()) missing.add("Office Phone");
        if (mobilePhone.isBlank()) missing.add("Mobile Phone");
        if (companyEmail.isBlank()) missing.add("Company Email");
        if (industry.isBlank()) missing.add("Industry");
        if (city.isBlank()) missing.add("City");
        if (website.isBlank()) missing.add("Company Website");
        return missing;
    }

    private void registerEventImportEmails(
            Map<String, List<EventImportEmailOwnerInfo>> emailOwnersInFile,
            String rowLabel,
            String fullName,
            String companyEmail,
            String personalEmail,
            int excelRowNumber) {
        Set<String> emailsInRow = new LinkedHashSet<>();
        emailsInRow.addAll(eventImportEmails(personalEmail));

        for (String email : emailsInRow) {
            emailOwnersInFile
                    .computeIfAbsent(email, key -> new ArrayList<>())
                    .add(new EventImportEmailOwnerInfo(rowLabel, fullName, excelRowNumber, email));
        }
    }

    private Map<Integer, List<String>> buildEventImportConflictMessages(
            Map<String, List<EventImportEmailOwnerInfo>> emailOwnersInFile) {
        Map<Integer, List<String>> conflictsByRow = new LinkedHashMap<>();

        for (List<EventImportEmailOwnerInfo> owners : emailOwnersInFile.values()) {
            if (owners.size() < 2) {
                continue;
            }

            for (EventImportEmailOwnerInfo owner : owners) {
                List<String> others = owners.stream()
                        .filter(other -> other.excelRowNumber() != owner.excelRowNumber())
                        .map(other -> other.rowLabel() + " (" + other.fullName() + ")")
                        .distinct()
                        .toList();

                if (others.isEmpty()) {
                    continue;
                }

                String message = "Konflik Personal Email: Email '" + owner.email() + "' sama dengan " + String.join(", ", others) + ". Personal email tidak boleh dipakai 2 nama berbeda di Excel.";
                conflictsByRow
                        .computeIfAbsent(owner.excelRowNumber(), key -> new ArrayList<>())
                        .add(message);
            }
        }

        return conflictsByRow;
    }

    private String cleanCompanyName(String name) {
        if (name == null || name.isEmpty()) return "";
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.startsWith("PT ") || upper.startsWith("PT. ")) {
            String base = name.substring(upper.startsWith("PT. ") ? 4 : 3).trim();
            if (base.endsWith(",")) {
                base = base.substring(0, base.length() - 1).trim();
            }
            return base + " PT";
        }
        if (upper.endsWith(" PT.")) {
            return name.substring(0, name.length() - 4).trim() + " PT";
        }
        return name;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record ImportValidationRowDraft(
            int rowNumber,
            String firstName,
            String lastName,
            String companyName,
            String mobilePhone,
            String companyEmail,
            String personalEmail,
            String groupName,
            String brandName,
            String salutation,
            String position,
            String specialityDivision,
            String jobTitle,
            String address,
            String officePhone,
            String industry,
            String sizeRevenue,
            String sizeEmployee,
            String hardware,
            String linkedinUrl,
            String city,
            String postalCode,
            String website,
            Long existingDatabaseId,
            Long existingParticipantId,
            List<String> issues,
            boolean existingEventParticipant,
            String participantCategory,
            List<String> missingFields,
            List<String> warnings) {
    }

    private record EventImportEmailOwnerInfo(
            String rowLabel,
            String fullName,
            int excelRowNumber,
            String email) {
    }

    private enum EventTimingStatus {
        UPCOMING,
        ONGOING,
        PAST
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
