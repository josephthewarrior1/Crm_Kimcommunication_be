package com.crm.controller;

import com.crm.domain.Event;
import com.crm.domain.Role;
import com.crm.domain.AppUser;
import com.crm.domain.AttendanceStatus;
import com.crm.domain.Company;
import com.crm.domain.Database;
import com.crm.domain.DatabaseEmail;
import com.crm.domain.DatabaseSource;
import com.crm.domain.DatabaseType;
import com.crm.domain.EventParticipant;
import com.crm.domain.Group;
import com.crm.domain.ParticipantStatus;
import com.crm.domain.PositionLevel;
import com.crm.repository.CompanyRepository;
import com.crm.repository.DatabaseEmailRepository;
import com.crm.repository.DatabaseRepository;
import com.crm.repository.EventRepository;
import com.crm.repository.EventParticipantRepository;
import com.crm.repository.GroupRepository;
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
    private UserRepository userRepository;

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired
    private AuditLogService auditLogService;

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
            row.put("Office Phone", safe(participant.getDatabase() != null && participant.getDatabase().getCompany() != null ? participant.getDatabase().getCompany().getOfficePhone() : null));
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
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String tab,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body(Map.of("message", "Forbidden: Only ADMIN or MANAGER can validate imports"));
        }
        if (!canAccessEvent(currentUser, id)) {
            return ResponseEntity.status(403).body(Map.of("message", "Forbidden: You don't have access to this event"));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Uploaded file is empty"));
        }

        Event event = eventRepository.findById(id).orElse(null);
        if (event == null) {
            return ResponseEntity.notFound().build();
        }

        List<EventParticipant> existingParticipants = eventParticipantRepository.findByEventId(id);
        List<Database> allDatabases = databaseRepository.findAll();

        try (InputStream is = file.getInputStream(); Workbook workbook = new XSSFWorkbook(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "Header row not found"));
            }

            Map<String, Integer> headerMap = buildHeaderMap(headerRow);
            boolean hasImportHeader = hasAnyHeader(headerMap, "first name", "company name", "company", "company email address");
            List<ImportValidationRowDraft> drafts = new ArrayList<>();
            Map<String, List<EventImportEmailOwnerInfo>> emailOwnersInFile = new LinkedHashMap<>();
            int validCount = 0;
            int duplicateCount = 0;
            int errorCount = 0;
            int blankCount = 0;

            for (int rowIndex = hasImportHeader ? 1 : 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }

                String firstName = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 5, "first name", "firstname", "first_name", "nama depan", "name", "nama"));
                String lastName = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 6, "last name", "lastname", "last_name", "nama belakang"));
                String groupName = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 1, "nama group", "group name", "group", "nama group holding", "nama group/holding company", "group holding company", "group/holding company"));
                String brandName = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 2, "nama brand", "brand name", "brand"));
                String companyName = cleanCompanyName(normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 3, "company name", "companyname", "company", "perusahaan")));
                String mobilePhone = cleanImportPhone(getImportCell(row, headerMap, hasImportHeader, 12, "mobile phone", "phone", "no hp", "whatsapp"));
                String companyEmail = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 13, "company email address", "company email", "email"));
                String personalEmail = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 14, "personal email address", "personal email"));
                String salutation = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 4, "salutation"));
                String position = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 7, "position", "postition", "position level"));
                String specialityDivision = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 8, "division", "speciality/division", "speciality"));
                String jobTitle = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 9, "jobtitle", "job title", "jabatan"));
                String address = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 10, "address", "company address", "alamat"));
                String officePhone = cleanImportPhone(getImportCell(row, headerMap, hasImportHeader, 11, "office phone", "company phone", "telepon kantor"));
                String industry = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 15, "industry", "industri"));
                String sizeRevenue = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 16, "company size (revenue)"));
                String sizeEmployee = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 17, "company size (employee)"));
                String hardware = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 18, "company hardware"));
                String linkedinUrl = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 19, "linkedin link", "linkedin"));
                String city = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 20, "city", "kota"));
                String postalCode = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 21, "postal code"));
                String website = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 22, "company website", "website"));

                if (firstName.isBlank() && lastName.isBlank() && companyName.isBlank() && mobilePhone.isBlank() && companyEmail.isBlank() && personalEmail.isBlank()) {
                    blankCount++;
                    continue;
                }

                List<String> issues = getEventImportMissingMandatoryFields(
                        groupName, brandName, companyName, salutation, firstName, lastName,
                        position, jobTitle, address, officePhone, mobilePhone, companyEmail,
                        industry, city, website
                );

                Database personalEmailOwner = findPersonalEmailOwner(allDatabases, personalEmail);
                if (personalEmailOwner != null && !sameName(personalEmailOwner, firstName, lastName)) {
                    issues.add("Personal Email sudah dipakai kontak lain: "
                            + safe(personalEmailOwner.getFirstName()) + " " + safe(personalEmailOwner.getLastName())
                            + " (ID " + personalEmailOwner.getId() + ")");
                }

                EventParticipant existingEventParticipant = findExistingEventParticipant(existingParticipants, firstName, lastName, companyName, mobilePhone, companyEmail, personalEmail);
                Database existingDatabase = findExistingDatabase(allDatabases, firstName, lastName, companyName, mobilePhone, companyEmail, personalEmail);

                registerEventImportEmails(
                        emailOwnersInFile,
                        "Baris " + (rowIndex + 1),
                        buildImportFullName(firstName, lastName),
                        companyEmail,
                        personalEmail,
                        rowIndex + 1
                );

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
                        existingEventParticipant != null
                ));
            }

            Map<Integer, List<String>> intraFileConflictsByRow = buildEventImportConflictMessages(emailOwnersInFile);
            List<Map<String, Object>> rows = new ArrayList<>();

            for (ImportValidationRowDraft draft : drafts) {
                List<String> issues = new ArrayList<>(draft.issues());
                List<String> conflictMessages = intraFileConflictsByRow.getOrDefault(draft.rowNumber(), List.of());
                String status = "valid";

                if (!conflictMessages.isEmpty()) {
                    issues.addAll(conflictMessages);
                }

                if (!draft.issues().isEmpty()) {
                    status = "error";
                } else if (!conflictMessages.isEmpty()) {
                    status = "conflict";
                } else if (draft.existingEventParticipant()) {
                    status = "duplicate";
                    issues.add("Already registered in this event");
                }

                if ("valid".equals(status)) {
                    validCount++;
                } else if ("duplicate".equals(status)) {
                    duplicateCount++;
                } else {
                    errorCount++;
                }

                Map<String, Object> rowResult = new LinkedHashMap<>();
                rowResult.put("rowNumber", draft.rowNumber());
                rowResult.put("status", status);
                rowResult.put("firstName", draft.firstName());
                rowResult.put("lastName", draft.lastName());
                rowResult.put("companyName", draft.companyName());
                rowResult.put("mobilePhone", draft.mobilePhone());
                rowResult.put("companyEmail", draft.companyEmail());
                rowResult.put("personalEmail", draft.personalEmail());
                rowResult.put("salutation", draft.salutation());
                rowResult.put("position", draft.position());
                rowResult.put("jobTitle", draft.jobTitle());
                rowResult.put("existingDatabaseId", draft.existingDatabaseId());
                rowResult.put("existingParticipantId", draft.existingParticipantId());
                rowResult.put("issues", issues);
                rows.add(rowResult);
            }

            return ResponseEntity.ok(Map.of(
                    "eventId", id,
                    "eventName", event.getName(),
                    "tab", safe(tab),
                    "fileName", safe(file.getOriginalFilename()),
                    "summary", Map.of(
                            "validCount", validCount,
                            "duplicateCount", duplicateCount,
                            "errorCount", errorCount,
                            "blankCount", blankCount,
                            "conflictCount", intraFileConflictsByRow.size(),
                            "totalRows", rows.size()
                    ),
                    "rows", rows
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "message", "Failed to validate import file: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/{id}/participants/import")
    public ResponseEntity<?> importEventParticipants(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String tab,
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
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Uploaded file is empty"));
        }

        Event event = eventRepository.findById(id).orElse(null);
        if (event == null) {
            return ResponseEntity.notFound().build();
        }

        List<EventParticipant> existingParticipants = eventParticipantRepository.findByEventId(id);
        List<Database> allDatabases = new ArrayList<>(databaseRepository.findAll());
        List<ImportValidationRowDraft> drafts;

        try {
            drafts = readEventImportDrafts(file, existingParticipants, allDatabases);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "message", "Failed to parse import file: " + e.getMessage()
            ));
        }

        Map<String, List<EventImportEmailOwnerInfo>> emailOwnersInFile = new LinkedHashMap<>();
        for (ImportValidationRowDraft draft : drafts) {
            registerEventImportEmails(
                    emailOwnersInFile,
                    "Baris " + draft.rowNumber(),
                    buildImportFullName(draft.firstName(), draft.lastName()),
                    draft.companyEmail(),
                    draft.personalEmail(),
                    draft.rowNumber()
            );
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
                    ? databaseRepository.findById(draft.existingDatabaseId()).orElse(null)
                    : findExistingDatabase(allDatabases, draft.firstName(), draft.lastName(), draft.companyName(), draft.mobilePhone(), draft.companyEmail(), draft.personalEmail());
            boolean isNewContact = database == null;

            Group group = null;
            if (!draft.groupName().isBlank()) {
                group = groupRepository.findByNameIgnoreCase(draft.groupName())
                        .orElseGet(() -> groupRepository.save(Group.builder().name(draft.groupName()).build()));
            }

            Company company = null;
            if (!draft.companyName().isBlank()) {
                company = companyRepository.findByNameIgnoreCase(draft.companyName()).orElse(null);
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
            if (!draft.mobilePhone().isBlank()) database.setMobilePhone(draft.mobilePhone());
            if (!draft.linkedinUrl().isBlank()) database.setLinkedinUrl(draft.linkedinUrl());
            if (company != null) database.setCompany(company);
            database = databaseRepository.save(database);
            allDatabases.add(database);

            saveImportEmail(database, draft.companyEmail(), "company", true);
            saveImportEmail(database, draft.personalEmail(), "personal", false);

            if (eventParticipantRepository.findByEventIdAndDatabaseId(id, database.getId()).isPresent()) {
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
            eventParticipantRepository.save(participant);
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
                .map(database -> database.getCompany() != null ? safe(database.getCompany().getCity()).trim() : "")
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
        return safe(database.getCompany() != null ? database.getCompany().getCity() : null)
                .equalsIgnoreCase(city.trim());
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

    private List<ImportValidationRowDraft> readEventImportDrafts(
            MultipartFile file,
            List<EventParticipant> existingParticipants,
            List<Database> allDatabases) throws Exception {
        List<ImportValidationRowDraft> drafts = new ArrayList<>();

        try (InputStream is = file.getInputStream(); Workbook workbook = new XSSFWorkbook(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                return drafts;
            }

            Map<String, Integer> headerMap = buildHeaderMap(headerRow);
            boolean hasImportHeader = hasAnyHeader(headerMap, "first name", "company name", "company", "company email address");

            for (int rowIndex = hasImportHeader ? 1 : 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }

                String firstName = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 5, "first name", "firstname", "first_name", "nama depan", "name", "nama"));
                String lastName = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 6, "last name", "lastname", "last_name", "nama belakang"));
                String groupName = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 1, "nama group", "group name", "group", "nama group holding", "nama group/holding company", "group holding company", "group/holding company"));
                String brandName = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 2, "nama brand", "brand name", "brand"));
                String companyName = cleanCompanyName(normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 3, "company name", "companyname", "company", "perusahaan")));
                String mobilePhone = cleanImportPhone(getImportCell(row, headerMap, hasImportHeader, 12, "mobile phone", "phone", "no hp", "whatsapp"));
                String companyEmail = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 13, "company email address", "company email", "email"));
                String personalEmail = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 14, "personal email address", "personal email"));
                String salutation = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 4, "salutation"));
                String position = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 7, "position", "postition", "position level"));
                String specialityDivision = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 8, "division", "speciality/division", "speciality"));
                String jobTitle = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 9, "jobtitle", "job title", "jabatan"));
                String address = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 10, "address", "company address", "alamat"));
                String officePhone = cleanImportPhone(getImportCell(row, headerMap, hasImportHeader, 11, "office phone", "company phone", "telepon kantor"));
                String industry = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 15, "industry", "industri"));
                String sizeRevenue = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 16, "company size (revenue)"));
                String sizeEmployee = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 17, "company size (employee)"));
                String hardware = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 18, "company hardware"));
                String linkedinUrl = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 19, "linkedin link", "linkedin"));
                String city = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 20, "city", "kota"));
                String postalCode = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 21, "postal code"));
                String website = normalizeImportValue(getImportCell(row, headerMap, hasImportHeader, 22, "company website", "website"));

                if (firstName.isBlank() && lastName.isBlank() && companyName.isBlank() && mobilePhone.isBlank() && companyEmail.isBlank() && personalEmail.isBlank()) {
                    continue;
                }

                List<String> issues = getEventImportMissingMandatoryFields(
                        groupName, brandName, companyName, salutation, firstName, lastName,
                        position, jobTitle, address, officePhone, mobilePhone, companyEmail,
                        industry, city, website
                );

                EventParticipant existingEventParticipant = findExistingEventParticipant(existingParticipants, firstName, lastName, companyName, mobilePhone, companyEmail, personalEmail);
                Database existingDatabase = findExistingDatabase(allDatabases, firstName, lastName, companyName, mobilePhone, companyEmail, personalEmail);

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
                        existingEventParticipant != null
                ));
            }
        }

        return drafts;
    }

    private void saveImportEmail(Database database, String email, String emailType, boolean corporate) {
        String normalizedEmail = safe(email).trim().toLowerCase(Locale.ROOT);
        if (normalizedEmail.isBlank()) {
            return;
        }

        List<DatabaseEmail> existingEmails = databaseEmailRepository.findAllByEmailIgnoreCase(normalizedEmail);
        boolean personal = "personal".equalsIgnoreCase(emailType);
        if ((personal && !existingEmails.isEmpty())
                || existingEmails.stream().anyMatch(existing -> existing.getDatabase() != null
                        && existing.getDatabase().getId().equals(database.getId()))) {
            return;
        }

        databaseEmailRepository.save(DatabaseEmail.builder()
                .database(database)
                .email(normalizedEmail)
                .emailType(emailType)
                .isPrimary(corporate)
                .isVerified(true)
                .isCorporate(corporate)
                .build());
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
        return getCellValue(row.getCell(fallbackIndex));
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
        return safe(value).replaceAll("[^0-9+]", "").trim();
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
        if (!safe(personalEmail).isBlank()) {
            emailsInRow.add(personalEmail.trim().toLowerCase(Locale.ROOT));
        }

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

    private EventParticipant findExistingEventParticipant(
            List<EventParticipant> participants,
            String firstName,
            String lastName,
            String companyName,
            String mobilePhone,
            String companyEmail,
            String personalEmail) {
        return participants.stream()
                .filter(participant -> matchesParticipantIdentity(participant, firstName, lastName, companyName, mobilePhone, companyEmail, personalEmail))
                .findFirst()
                .orElse(null);
    }

    private Database findExistingDatabase(
            List<Database> databases,
            String firstName,
            String lastName,
            String companyName,
            String mobilePhone,
            String companyEmail,
            String personalEmail) {
        return databases.stream()
                .filter(database -> matchesDatabaseIdentity(database, firstName, lastName, companyName, mobilePhone, companyEmail, personalEmail))
                .findFirst()
                .orElse(null);
    }

    private boolean matchesParticipantIdentity(
            EventParticipant participant,
            String firstName,
            String lastName,
            String companyName,
            String mobilePhone,
            String companyEmail,
            String personalEmail) {
        return participant.getDatabase() != null
                && matchesDatabaseIdentity(participant.getDatabase(), firstName, lastName, companyName, mobilePhone, companyEmail, personalEmail);
    }

    private boolean matchesDatabaseIdentity(
            Database database,
            String firstName,
            String lastName,
            String companyName,
            String mobilePhone,
            String companyEmail,
            String personalEmail) {
        String dbFirstName = safe(database.getFirstName()).trim();
        String dbLastName = safe(database.getLastName()).trim();
        String dbCompanyName = database.getCompany() != null ? safe(database.getCompany().getName()).trim() : "";
        boolean nameMatch = !firstName.isBlank() && dbFirstName.equalsIgnoreCase(firstName)
                && (lastName.isBlank() || dbLastName.equalsIgnoreCase(lastName));
        boolean companyMatch = companyName.isBlank() || dbCompanyName.equalsIgnoreCase(companyName);
        boolean emailMatch = !personalEmail.isBlank() && database.getEmails() != null
                && database.getEmails().stream().anyMatch(email ->
                        "personal".equalsIgnoreCase(email.getEmailType())
                                && safe(email.getEmail()).equalsIgnoreCase(personalEmail));

        return nameMatch && (companyMatch || emailMatch);
    }

    private Database findPersonalEmailOwner(List<Database> databases, String personalEmail) {
        if (personalEmail.isBlank()) return null;
        return databases.stream()
                .filter(database -> database.getEmails() != null && database.getEmails().stream().anyMatch(email ->
                        safe(email.getEmail()).equalsIgnoreCase(personalEmail)))
                .findFirst()
                .orElse(null);
    }

    private boolean sameName(Database database, String firstName, String lastName) {
        return safe(database.getFirstName()).trim().equalsIgnoreCase(firstName.trim())
                && safe(database.getLastName()).trim().equalsIgnoreCase(lastName.trim());
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
            boolean existingEventParticipant) {
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
