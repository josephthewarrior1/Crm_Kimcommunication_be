package com.crm.controller;

import com.crm.domain.*;
import com.crm.repository.*;
import com.crm.service.AuditLogService;
import com.crm.service.SecurityHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/event-participants")
public class EventParticipantController {
    private static final Pattern PIC_PATTERN = Pattern.compile("\\[PIC:\\s*[^\\]]+\\]", Pattern.CASE_INSENSITIVE);

    @Autowired
    private EventParticipantRepository eventParticipantRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DatabaseRepository databaseRepository;

    @Autowired
    private EventParticipantActivityRepository eventParticipantActivityRepository;

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private UserRepository userRepository;

    @GetMapping
    public ResponseEntity<?> getAllEventParticipants(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (isViewer(currentUser)) {
            return ResponseEntity.ok(eventParticipantRepository.findAll().stream()
                    .filter(participant -> participant.getEvent() != null
                            && currentUser.getAllowedEventIds().contains(participant.getEvent().getId()))
                    .toList());
        }
        return ResponseEntity.ok(eventParticipantRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<?> createEventParticipant(
            @RequestBody EventParticipantRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can add event participants");
        }

        Event event = eventRepository.findById(request.getEventId()).orElse(null);
        if (event == null) {
            return ResponseEntity.badRequest().body("Event not found");
        }

        java.util.List<Long> databasesToProcess = new java.util.ArrayList<>();
        if (request.getDatabaseIds() != null && !request.getDatabaseIds().isEmpty()) {
            databasesToProcess.addAll(request.getDatabaseIds());
        } else if (request.getDatabaseId() != null) {
            databasesToProcess.add(request.getDatabaseId());
        }

        if (databasesToProcess.isEmpty()) {
            return ResponseEntity.badRequest().body("No Database IDs provided");
        }

        java.util.List<EventParticipant> savedParticipants = new java.util.ArrayList<>();
        for (Long databaseId : databasesToProcess) {
            Database database = databaseRepository.findById(databaseId).orElse(null);
            if (database == null) {
                continue;
            }

            if (eventParticipantRepository.findByEventIdAndDatabaseId(request.getEventId(), databaseId).isPresent()) {
                continue;
            }

            EventParticipant eventParticipant = EventParticipant.builder()
                    .event(event)
                    .database(database)
                    .participantStatus(request.getParticipantStatus() != null ? ParticipantStatus.valueOf(request.getParticipantStatus()) : ParticipantStatus.white)
                    .attendanceStatus(request.getAttendanceStatus() != null ? AttendanceStatus.valueOf(request.getAttendanceStatus()) : AttendanceStatus.registered)
                    .confirmationStatus(request.getConfirmationStatus() != null ? request.getConfirmationStatus() : "pending")
                    .preEventApprovalStatus(request.getPreEventApprovalStatus() != null ? request.getPreEventApprovalStatus() : "pending")
                    .notes(request.getNotes())
                    .build();
            savedParticipants.add(eventParticipantRepository.save(eventParticipant));
        }

        if (!savedParticipants.isEmpty()) {
            String eventName = event.getName() == null ? "Tanpa Nama Event" : event.getName();
            String description = savedParticipants.size() == 1
                    ? "Menambahkan 1 peserta ke event '" + eventName + "': " + participantDisplayName(savedParticipants.get(0))
                    : "Menambahkan " + savedParticipants.size() + " peserta ke event '" + eventName + "'";
            auditLogService.recordUserAction(
                    currentUser,
                    "EVENT_PARTICIPANT",
                    "CREATE",
                    event.getId(),
                    eventName,
                    description
            );
        }

        return ResponseEntity.ok(savedParticipants);
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable Long id,
            @RequestParam(required = false) String participantStatus,
            @RequestParam(required = false) String attendanceStatus,
            @RequestParam(required = false) String notes,
            @RequestParam(required = false) String participantCategory,
            @RequestParam(required = false) String callStatus,
            @RequestParam(required = false) String emailStatus,
            @RequestParam(required = false) String whatsappStatus,
            @RequestParam(required = false) String meetingStatus,
            @RequestParam(required = false) String businessChallenges,
            @RequestParam(required = false) String projectInfo,
            @RequestParam(required = false) String timeline,
            @RequestParam(required = false) String reminderH7,
            @RequestParam(required = false) String reminderH3,
            @RequestParam(required = false) String reminderH1,
            @RequestParam(required = false) String reminderHariH,
            @RequestParam(required = false) String confirmationStatus,
            @RequestParam(required = false) String preEventApprovalStatus,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        return eventParticipantRepository.findById(id).map(participant -> {
            Map<String, String> beforeValues = captureParticipantState(participant);
            if (isViewer(currentUser)) {
                if (!canAccessEvent(currentUser, participant)) {
                    return ResponseEntity.status(403).body("Forbidden: Viewer cannot update this event");
                }
                if (!hasOnlyConfirmationStatus(participantStatus, attendanceStatus, notes, participantCategory,
                        callStatus, emailStatus, whatsappStatus, meetingStatus, businessChallenges, projectInfo,
                        timeline, reminderH7, reminderH3, reminderH1, reminderHariH, confirmationStatus, preEventApprovalStatus)) {
                    return ResponseEntity.status(403).body("Forbidden: Viewer can only update confirmation status");
                }
            }
            if (participantStatus != null) {
                try {
                    participant.setParticipantStatus(ParticipantStatus.valueOf(participantStatus));
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body("Invalid participantStatus. Must be a valid ParticipantStatus enum value.");
                }
            }
            if (attendanceStatus != null) {
                try {
                    participant.setAttendanceStatus(AttendanceStatus.valueOf(attendanceStatus));
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body("Invalid attendanceStatus. Must be invited, registered, attended, no_show, or cancelled.");
                }
            }
            if (notes != null) {
                participant.setNotes(notes);
            }
            if (confirmationStatus != null) {
                if (!isValidConfirmationStatus(confirmationStatus)) {
                    return ResponseEntity.badRequest().body("Invalid confirmationStatus. Must be pending, approve, or decline.");
                }
                participant.setConfirmationStatus(confirmationStatus);
            }
            if (preEventApprovalStatus != null) {
                if (!isValidConfirmationStatus(preEventApprovalStatus)) {
                    return ResponseEntity.badRequest().body("Invalid preEventApprovalStatus. Must be pending, approve, or decline.");
                }
                participant.setPreEventApprovalStatus(preEventApprovalStatus);
            }
            if (participantCategory != null) {
                participant.setParticipantCategory(participantCategory);
            }
            if (callStatus != null) {
                participant.setCallStatus(callStatus);
            }
            if (emailStatus != null) {
                participant.setEmailStatus(emailStatus);
            }
            if (whatsappStatus != null) {
                participant.setWhatsappStatus(whatsappStatus);
            }
            if (meetingStatus != null) {
                participant.setMeetingStatus(meetingStatus);
            }
            if (businessChallenges != null) {
                participant.setBusinessChallenges(businessChallenges);
            }
            if (projectInfo != null) {
                participant.setProjectInfo(projectInfo);
            }
            if (timeline != null) {
                participant.setTimeline(timeline);
            }
            if (reminderH7 != null) {
                participant.setReminderH7(reminderH7.trim().isEmpty() ? null : reminderH7);
            }
            if (reminderH3 != null) {
                participant.setReminderH3(reminderH3.trim().isEmpty() ? null : reminderH3);
            }
            if (reminderH1 != null) {
                participant.setReminderH1(reminderH1.trim().isEmpty() ? null : reminderH1);
            }
            if (reminderHariH != null) {
                participant.setReminderHariH(reminderHariH.trim().isEmpty() ? null : reminderHariH);
            }
            EventParticipant savedParticipant = eventParticipantRepository.save(participant);
            List<String> changes = describeParticipantChanges(beforeValues, savedParticipant);
            if (!changes.isEmpty()) {
                String eventName = savedParticipant.getEvent() != null && savedParticipant.getEvent().getName() != null
                        ? savedParticipant.getEvent().getName()
                        : "Tanpa Nama Event";
                auditLogService.recordUserAction(
                        currentUser,
                        "EVENT_PARTICIPANT",
                        "UPDATE",
                        savedParticipant.getId(),
                        participantDisplayName(savedParticipant),
                        "Mengubah peserta '" + participantDisplayName(savedParticipant) + "' di event '" + eventName + "'. Perubahan: " + String.join(", ", changes)
                );
            }
            return ResponseEntity.ok(savedParticipant);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/bulk")
    public ResponseEntity<?> bulkUpdateParticipants(
            @RequestBody BulkUpdateRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can bulk update event participants");
        }
        if (request.getParticipantIds() == null || request.getParticipantIds().isEmpty()) {
            return ResponseEntity.badRequest().body("participantIds is required");
        }

        List<EventParticipant> updatedParticipants = new ArrayList<>();
        List<String> updatedNames = new ArrayList<>();
        int skippedCount = 0;

        for (Long participantId : request.getParticipantIds()) {
            EventParticipant participant = eventParticipantRepository.findById(participantId).orElse(null);
            if (participant == null) {
                skippedCount++;
                continue;
            }
            if (!canAccessEvent(currentUser, participant)) {
                skippedCount++;
                continue;
            }

            if (request.getParticipantStatus() != null) {
                try {
                    participant.setParticipantStatus(ParticipantStatus.valueOf(request.getParticipantStatus()));
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body("Invalid participantStatus");
                }
            }
            if (request.getAttendanceStatus() != null) {
                try {
                    participant.setAttendanceStatus(AttendanceStatus.valueOf(request.getAttendanceStatus()));
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body("Invalid attendanceStatus");
                }
            }
            if (request.getConfirmationStatus() != null) {
                if (!isValidConfirmationStatus(request.getConfirmationStatus())) {
                    return ResponseEntity.badRequest().body("Invalid confirmationStatus");
                }
                participant.setConfirmationStatus(request.getConfirmationStatus());
            }
            if (request.getPreEventApprovalStatus() != null) {
                if (!isValidConfirmationStatus(request.getPreEventApprovalStatus())) {
                    return ResponseEntity.badRequest().body("Invalid preEventApprovalStatus");
                }
                participant.setPreEventApprovalStatus(request.getPreEventApprovalStatus());
                participant.setNotes(setPreEventApprovalStatus(participant.getNotes(), request.getPreEventApprovalStatus()));
            }
            if (request.getReminderH7() != null) {
                participant.setReminderH7(request.getReminderH7().trim().isEmpty() ? null : request.getReminderH7());
            }
            if (request.getReminderH3() != null) {
                participant.setReminderH3(request.getReminderH3().trim().isEmpty() ? null : request.getReminderH3());
            }
            if (request.getReminderH1() != null) {
                participant.setReminderH1(request.getReminderH1().trim().isEmpty() ? null : request.getReminderH1());
            }
            if (request.getReminderHariH() != null) {
                participant.setReminderHariH(request.getReminderHariH().trim().isEmpty() ? null : request.getReminderHariH());
            }
            if (request.getCallStatus() != null) {
                participant.setCallStatus(request.getCallStatus());
            }
            if (request.getEmailStatus() != null) {
                participant.setEmailStatus(request.getEmailStatus());
            }
            if (request.getWhatsappStatus() != null) {
                participant.setWhatsappStatus(request.getWhatsappStatus());
            }
            if (request.getMeetingStatus() != null) {
                participant.setMeetingStatus(request.getMeetingStatus());
            }
            if (request.getParticipantCategory() != null) {
                participant.setParticipantCategory(request.getParticipantCategory());
            }
            if (request.getBusinessChallenges() != null) {
                participant.setBusinessChallenges(request.getBusinessChallenges());
            }
            if (request.getProjectInfo() != null) {
                participant.setProjectInfo(request.getProjectInfo());
            }
            if (request.getTimeline() != null) {
                participant.setTimeline(request.getTimeline());
            }
            if (request.getNotes() != null) {
                participant.setNotes(request.getNotes());
            }
            if (request.getPicName() != null) {
                participant.setNotes(setPic(participant.getNotes(), request.getPicName()));
            }

            EventParticipant savedParticipant = eventParticipantRepository.save(participant);
            updatedParticipants.add(savedParticipant);
            updatedNames.add(participantDisplayName(savedParticipant));
        }

        if (!updatedParticipants.isEmpty()) {
            Event firstEvent = updatedParticipants.get(0).getEvent();
            String eventName = firstEvent != null && firstEvent.getName() != null ? firstEvent.getName() : "Tanpa Nama Event";
            auditLogService.recordUserAction(
                    currentUser,
                    "EVENT_PARTICIPANT",
                    "BULK_UPDATE",
                    firstEvent != null ? firstEvent.getId() : null,
                    eventName,
                    "Bulk update peserta pada event '" + eventName + "' sebanyak " + updatedParticipants.size()
                            + " data, skipped " + skippedCount
                            + ". Field: " + summarizeBulkUpdatedFields(request)
                            + ". Peserta: " + summarizeNames(updatedNames)
            );
        }

        return ResponseEntity.ok(java.util.Map.of(
                "updatedCount", updatedParticipants.size(),
                "skippedCount", skippedCount,
                "items", updatedParticipants
        ));
    }

    @PostMapping("/{id}/activities")
    public ResponseEntity<?> addActivity(
            @PathVariable Long id,
            @RequestBody ActivityRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        return eventParticipantRepository.findById(id).map(participant -> {
            if (isViewer(currentUser)) {
                return ResponseEntity.status(403).body("Forbidden: Viewer can only view activities");
            }
            EventParticipantActivity activity = EventParticipantActivity.builder()
                    .eventParticipant(participant)
                    .activityType(request.getActivityType())
                    .status(request.getStatus())
                    .notes(request.getNotes())
                    .createdBy(currentUser.getUsername())
                    .build();
            
            EventParticipantActivity savedActivity = eventParticipantActivityRepository.save(activity);

            // Sync status to the EventParticipant
            if ("CALL".equalsIgnoreCase(request.getActivityType())) {
                participant.setCallStatus(request.getStatus());
            } else if ("EMAIL".equalsIgnoreCase(request.getActivityType())) {
                participant.setEmailStatus(request.getStatus());
            } else if ("WHATSAPP".equalsIgnoreCase(request.getActivityType())) {
                participant.setWhatsappStatus(request.getStatus());
            } else if ("MEETING".equalsIgnoreCase(request.getActivityType())) {
                participant.setMeetingStatus(request.getStatus());
            }
            eventParticipantRepository.save(participant);

            return ResponseEntity.ok(savedActivity);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/activities")
    public ResponseEntity<?> getActivities(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (isViewer(currentUser)) {
            return ResponseEntity.status(403).body("Forbidden: Viewer cannot view activity logs");
        }

        return eventParticipantRepository.findById(id).map(participant -> {
            if (!canAccessEvent(currentUser, participant)) {
                return ResponseEntity.status(403).body("Forbidden: Viewer cannot view this event");
            }
            return ResponseEntity.ok(eventParticipantActivityRepository.findByEventParticipantIdOrderByCreatedAtDesc(id));
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/event/{eventId}/activities")
    public ResponseEntity<?> getEventActivities(
            @PathVariable Long eventId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (isViewer(currentUser)) {
            return ResponseEntity.status(403).body("Forbidden: Viewer cannot view activity logs");
        }

        if (startDate != null && !startDate.trim().isEmpty() && endDate != null && !endDate.trim().isEmpty()) {
            try {
                LocalDateTime start = LocalDateTime.parse(startDate.trim() + "T00:00:00");
                LocalDateTime end = LocalDateTime.parse(endDate.trim() + "T23:59:59");
                return ResponseEntity.ok(eventParticipantActivityRepository.findByEventIdAndDateRange(eventId, start, end));
            } catch (Exception e) {
                // Fallback if parsing fails
            }
        }

        return ResponseEntity.ok(eventParticipantActivityRepository.findByEventParticipantEventId(eventId));
    }

    @GetMapping("/event/{eventId}/activity-summary")
    public ResponseEntity<?> getEventActivitySummary(
            @PathVariable Long eventId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String pic,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (isViewer(currentUser)) {
            return ResponseEntity.status(403).body("Forbidden: Viewer cannot view activity summary");
        }
        if (!canAccessEvent(currentUser, eventId)) {
            return ResponseEntity.status(403).body("Forbidden: You don't have access to this event");
        }

        String effectivePic = resolveEffectiveActivityPic(currentUser, pic);
        List<EventParticipantActivity> activities;

        if (startDate != null && !startDate.trim().isEmpty() && endDate != null && !endDate.trim().isEmpty()) {
            try {
                LocalDateTime start = LocalDateTime.parse(startDate.trim() + "T00:00:00");
                LocalDateTime end = LocalDateTime.parse(endDate.trim() + "T23:59:59");
                activities = eventParticipantActivityRepository.findByEventIdAndDateRange(eventId, start, end);
            } catch (Exception e) {
                activities = eventParticipantActivityRepository.findByEventParticipantEventId(eventId);
            }
        } else {
            activities = eventParticipantActivityRepository.findByEventParticipantEventId(eventId);
        }

        List<EventParticipantActivity> scopedActivities = activities.stream()
                .filter(activity -> matchesActivityPic(activity, effectivePic))
                .toList();

        long callCount = scopedActivities.stream().filter(activity -> "CALL".equalsIgnoreCase(activity.getActivityType())).count();
        long whatsappCount = scopedActivities.stream().filter(activity -> "WHATSAPP".equalsIgnoreCase(activity.getActivityType())).count();
        long emailCount = scopedActivities.stream().filter(activity -> "EMAIL".equalsIgnoreCase(activity.getActivityType())).count();
        long meetingCount = scopedActivities.stream().filter(activity -> "MEETING".equalsIgnoreCase(activity.getActivityType())).count();

        return ResponseEntity.ok(Map.of(
                "eventId", eventId,
                "pic", effectivePic == null ? "" : effectivePic,
                "startDate", startDate == null ? "" : startDate,
                "endDate", endDate == null ? "" : endDate,
                "totalActivities", scopedActivities.size(),
                "byType", Map.of(
                        "call", callCount,
                        "whatsapp", whatsappCount,
                        "email", emailCount,
                        "meeting", meetingCount
                )
        ));
    }

    @GetMapping("/emails/track/{activityId}")
    public ResponseEntity<byte[]> trackEmailOpen(@PathVariable Long activityId) {
        eventParticipantActivityRepository.findById(activityId).ifPresent(activity -> {
            if ("EMAIL".equalsIgnoreCase(activity.getActivityType()) && !"OPENED".equalsIgnoreCase(activity.getStatus())) {
                activity.setStatus("OPENED");
                eventParticipantActivityRepository.save(activity);

                // Also update the status on the participant itself
                EventParticipant participant = activity.getEventParticipant();
                participant.setEmailStatus("OPENED");
                eventParticipantRepository.save(participant);
            }
        });

        // Return a 1x1 transparent PNG pixel
        byte[] transparentPixel = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
            (byte) 0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54, 0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00,
            0x00, 0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            (byte) 0xAE, 0x42, 0x60, (byte) 0x82
        };

        return ResponseEntity.ok()
                .header("Content-Type", "image/png")
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .body(transparentPixel);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteEventParticipant(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasAnyRole(currentUser, Role.ADMIN, Role.MANAGER)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN or MANAGER can delete event participants");
        }

        return eventParticipantRepository.findById(id).map(participant -> {
            List<EventParticipantActivity> activities = eventParticipantActivityRepository.findByEventParticipantIdOrderByCreatedAtDesc(id);
            if (activities != null && !activities.isEmpty()) {
                eventParticipantActivityRepository.deleteAll(activities);
            }
            String participantName = participantDisplayName(participant);
            String eventName = participant.getEvent() != null && participant.getEvent().getName() != null
                    ? participant.getEvent().getName()
                    : "Tanpa Nama Event";
            eventParticipantRepository.delete(participant);
            auditLogService.recordUserAction(
                    currentUser,
                    "EVENT_PARTICIPANT",
                    "DELETE",
                    id,
                    participantName,
                    "Menghapus peserta '" + participantName + "' dari event '" + eventName + "'"
            );
            return ResponseEntity.ok().build();
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/report/{eventId}")
    public ResponseEntity<?> getEventReport(
            @PathVariable Long eventId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (isViewer(currentUser)) {
            return ResponseEntity.status(403).body("Forbidden: Viewer cannot view activity reports");
        }

        List<EventParticipant> participants = eventParticipantRepository.findByEventId(eventId);
        List<EventParticipantActivity> activities = eventParticipantActivityRepository.findByEventParticipantEventId(eventId);

        long emailSent = activities.stream().filter(a -> "EMAIL".equalsIgnoreCase(a.getActivityType())).count();
        long emailOpened = activities.stream().filter(a -> "EMAIL".equalsIgnoreCase(a.getActivityType()) && "OPENED".equalsIgnoreCase(a.getStatus())).count();
        long emailResponded = activities.stream().filter(a -> "EMAIL".equalsIgnoreCase(a.getActivityType()) && "RESPONDED".equalsIgnoreCase(a.getStatus())).count();

        long callsMade = activities.stream().filter(a -> "CALL".equalsIgnoreCase(a.getActivityType())).count();
        long connectedCalls = activities.stream().filter(a -> "CALL".equalsIgnoreCase(a.getActivityType()) && "CONNECTED".equalsIgnoreCase(a.getStatus())).count();

        long waSent = activities.stream().filter(a -> "WHATSAPP".equalsIgnoreCase(a.getActivityType())).count();
        long waResponded = activities.stream().filter(a -> "WHATSAPP".equalsIgnoreCase(a.getActivityType()) && "RESPONDED".equalsIgnoreCase(a.getStatus())).count();

        long hotParticipants = participants.stream().filter(p -> "HOT".equalsIgnoreCase(p.getParticipantCategory())).count();
        long warmParticipants = participants.stream().filter(p -> "WARM".equalsIgnoreCase(p.getParticipantCategory())).count();
        long meetingsSecured = participants.stream().filter(p -> "CONFIRMED".equalsIgnoreCase(p.getMeetingStatus())).count();

        double emailOpenRate = emailSent > 0 ? ((double) emailOpened / emailSent) * 100 : 0.0;
        double emailResponseRate = emailSent > 0 ? ((double) emailResponded / emailSent) * 100 : 0.0;
        double waResponseRate = waSent > 0 ? ((double) waResponded / waSent) * 100 : 0.0;

        java.util.Map<String, Object> report = new java.util.HashMap<>();
        report.put("totalEmailSent", emailSent);
        report.put("emailOpenRate", emailOpenRate);
        report.put("emailResponseRate", emailResponseRate);
        report.put("totalCallsMade", callsMade);
        report.put("connectedCalls", connectedCalls);
        report.put("whatsappSent", waSent);
        report.put("whatsappResponseRate", waResponseRate);
        report.put("hotParticipants", hotParticipants);
        report.put("warmParticipants", warmParticipants);
        report.put("meetingsSecured", meetingsSecured);

        return ResponseEntity.ok(report);
    }

    @lombok.Data
    public static class ActivityRequest {
        private String activityType;
        private String status;
        private String notes;
    }

    @lombok.Data
    public static class EventParticipantRequest {
        private Long eventId;
        private Long databaseId;
        private List<Long> databaseIds;
        private String participantStatus;
        private String attendanceStatus;
        private String confirmationStatus;
        private String preEventApprovalStatus;
        private String notes;
    }

    @lombok.Data
    public static class BulkUpdateRequest {
        private List<Long> participantIds;
        private String participantStatus;
        private String attendanceStatus;
        private String confirmationStatus;
        private String preEventApprovalStatus;
        private String reminderH7;
        private String reminderH3;
        private String reminderH1;
        private String reminderHariH;
        private String callStatus;
        private String emailStatus;
        private String whatsappStatus;
        private String meetingStatus;
        private String participantCategory;
        private String businessChallenges;
        private String projectInfo;
        private String timeline;
        private String notes;
        private String picName;
    }

    private boolean isViewer(AppUser user) {
        return securityHelper.hasRole(user, Role.USER) && !securityHelper.hasAnyRole(user, Role.ADMIN, Role.MANAGER);
    }

    private boolean canAccessEvent(AppUser user, Long eventId) {
        return securityHelper.hasRole(user, Role.ADMIN)
                || (user.getAllowedEventIds() != null && user.getAllowedEventIds().contains(eventId));
    }

    private boolean canAccessEvent(AppUser user, EventParticipant participant) {
        return !isViewer(user) || (participant.getEvent() != null && user.getAllowedEventIds().contains(participant.getEvent().getId()));
    }

    private String resolveEffectiveActivityPic(AppUser currentUser, String requestedPic) {
        if (currentUser == null) {
            return "";
        }
        if (securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return requestedPic == null ? "" : requestedPic.trim();
        }
        return safe(currentUser.getFullName()).isBlank()
                ? safe(currentUser.getUsername()).trim()
                : safe(currentUser.getFullName()).trim();
    }

    private boolean matchesActivityPic(EventParticipantActivity activity, String pic) {
        if (pic == null || pic.isBlank()) {
            return true;
        }

        Set<String> picAliases = resolveActivityPicAliases(pic);
        if (picAliases.isEmpty()) {
            return true;
        }

        String createdBy = safe(activity.getCreatedBy()).trim().toLowerCase(Locale.ROOT);
        return picAliases.contains(createdBy);
    }

    private Set<String> resolveActivityPicAliases(String pic) {
        Set<String> aliases = new HashSet<>();
        String normalizedPic = safe(pic).trim().toLowerCase(Locale.ROOT);
        if (normalizedPic.isBlank()) {
            return aliases;
        }

        aliases.add(normalizedPic);
        for (AppUser user : userRepository.findAll()) {
            String fullName = safe(user.getFullName()).trim().toLowerCase(Locale.ROOT);
            String username = safe(user.getUsername()).trim().toLowerCase(Locale.ROOT);
            if (normalizedPic.equals(fullName) || normalizedPic.equals(username)) {
                if (!fullName.isBlank()) {
                    aliases.add(fullName);
                }
                if (!username.isBlank()) {
                    aliases.add(username);
                }
            }
        }

        return aliases;
    }

    private String extractPicName(String notes) {
        if (notes == null || notes.isBlank()) {
            return "";
        }
        java.util.regex.Matcher matcher = PIC_PATTERN.matcher(notes);
        if (matcher.find()) {
            String raw = matcher.group();
            return raw.replaceAll("(?i)^\\[PIC:\\s*", "").replaceAll("\\]$", "").trim();
        }
        return "";
    }

    private boolean hasOnlyConfirmationStatus(
            String participantStatus,
            String attendanceStatus,
            String notes,
            String participantCategory,
            String callStatus,
            String emailStatus,
            String whatsappStatus,
            String meetingStatus,
            String businessChallenges,
            String projectInfo,
            String timeline,
            String reminderH7,
            String reminderH3,
            String reminderH1,
            String reminderHariH,
            String confirmationStatus,
            String preEventApprovalStatus) {
        return confirmationStatus != null
                && participantStatus == null
                && attendanceStatus == null
                && notes == null
                && participantCategory == null
                && callStatus == null
                && emailStatus == null
                && whatsappStatus == null
                && meetingStatus == null
                && businessChallenges == null
                && projectInfo == null
                && timeline == null
                && reminderH7 == null
                && reminderH3 == null
                && reminderH1 == null
                && reminderHariH == null
                && preEventApprovalStatus == null;
    }

    private boolean isValidConfirmationStatus(String confirmationStatus) {
        return "pending".equalsIgnoreCase(confirmationStatus)
                || "approve".equalsIgnoreCase(confirmationStatus)
                || "decline".equalsIgnoreCase(confirmationStatus);
    }

    private String setPic(String notes, String picName) {
        String safePic = picName == null ? "" : picName.trim();
        String cleanNotes = notes == null ? "" : notes.trim();
        if (safePic.isEmpty()) {
            return cleanNotes;
        }
        if (cleanNotes.isEmpty()) {
            return "[PIC: " + safePic + "]";
        }
        if (PIC_PATTERN.matcher(cleanNotes).find()) {
            return cleanNotes.replaceAll("(?i)\\[PIC:\\s*[^\\]]+\\]", "[PIC: " + safePic + "]");
        }
        return "[PIC: " + safePic + "] " + cleanNotes;
    }

    private String setPreEventApprovalStatus(String notes, String status) {
        String safeStatus = "approve".equalsIgnoreCase(status) || "decline".equalsIgnoreCase(status) ? status.trim().toLowerCase(Locale.ROOT) : "pending";
        String cleanNotes = (notes == null ? "" : notes)
                .replaceAll("(?i)\\[PreEventApproval:\\s*[^\\]]+\\]", "")
                .replaceAll("\\s+", " ")
                .trim();
        return ("[PreEventApproval: " + safeStatus + "] " + cleanNotes).replaceAll("\\s+", " ").trim();
    }

    private Map<String, String> captureParticipantState(EventParticipant participant) {
        Map<String, String> state = new LinkedHashMap<>();
        state.put("participantStatus", enumName(participant.getParticipantStatus()));
        state.put("attendanceStatus", enumName(participant.getAttendanceStatus()));
        state.put("confirmationStatus", safe(participant.getConfirmationStatus()));
        state.put("preEventApprovalStatus", safe(participant.getPreEventApprovalStatus()));
        state.put("participantCategory", safe(participant.getParticipantCategory()));
        state.put("callStatus", safe(participant.getCallStatus()));
        state.put("emailStatus", safe(participant.getEmailStatus()));
        state.put("whatsappStatus", safe(participant.getWhatsappStatus()));
        state.put("meetingStatus", safe(participant.getMeetingStatus()));
        state.put("businessChallenges", safe(participant.getBusinessChallenges()));
        state.put("projectInfo", safe(participant.getProjectInfo()));
        state.put("timeline", safe(participant.getTimeline()));
        state.put("notes", safe(participant.getNotes()));
        state.put("reminderH7", safe(participant.getReminderH7()));
        state.put("reminderH3", safe(participant.getReminderH3()));
        state.put("reminderH1", safe(participant.getReminderH1()));
        state.put("reminderHariH", safe(participant.getReminderHariH()));
        return state;
    }

    private List<String> describeParticipantChanges(Map<String, String> beforeValues, EventParticipant participant) {
        Map<String, String> afterValues = captureParticipantState(participant);
        Map<String, String> labels = Map.ofEntries(
                Map.entry("participantStatus", "participant status"),
                Map.entry("attendanceStatus", "attendance status"),
                Map.entry("confirmationStatus", "confirmation status"),
                Map.entry("preEventApprovalStatus", "pre-event approval"),
                Map.entry("participantCategory", "participant category"),
                Map.entry("callStatus", "call status"),
                Map.entry("emailStatus", "email status"),
                Map.entry("whatsappStatus", "whatsapp status"),
                Map.entry("meetingStatus", "meeting status"),
                Map.entry("businessChallenges", "business challenges"),
                Map.entry("projectInfo", "project info"),
                Map.entry("timeline", "timeline"),
                Map.entry("notes", "notes"),
                Map.entry("reminderH7", "reminder H-7"),
                Map.entry("reminderH3", "reminder H-3"),
                Map.entry("reminderH1", "reminder H-1"),
                Map.entry("reminderHariH", "reminder Hari-H")
        );

        List<String> changes = new ArrayList<>();
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            String key = entry.getKey();
            String before = safe(beforeValues.get(key));
            String after = safe(afterValues.get(key));
            if (!Objects.equals(before, after)) {
                changes.add(entry.getValue() + " '" + displayValue(before) + "' -> '" + displayValue(after) + "'");
            }
        }
        return changes;
    }

    private String summarizeBulkUpdatedFields(BulkUpdateRequest request) {
        List<String> fields = new ArrayList<>();
        if (request.getParticipantStatus() != null) fields.add("participant status");
        if (request.getAttendanceStatus() != null) fields.add("attendance status");
        if (request.getConfirmationStatus() != null) fields.add("confirmation status");
        if (request.getPreEventApprovalStatus() != null) fields.add("pre-event approval");
        if (request.getReminderH7() != null) fields.add("reminder H-7");
        if (request.getReminderH3() != null) fields.add("reminder H-3");
        if (request.getReminderH1() != null) fields.add("reminder H-1");
        if (request.getReminderHariH() != null) fields.add("reminder Hari-H");
        if (request.getCallStatus() != null) fields.add("call status");
        if (request.getEmailStatus() != null) fields.add("email status");
        if (request.getWhatsappStatus() != null) fields.add("whatsapp status");
        if (request.getMeetingStatus() != null) fields.add("meeting status");
        if (request.getParticipantCategory() != null) fields.add("participant category");
        if (request.getBusinessChallenges() != null) fields.add("business challenges");
        if (request.getProjectInfo() != null) fields.add("project info");
        if (request.getTimeline() != null) fields.add("timeline");
        if (request.getNotes() != null) fields.add("notes");
        if (request.getPicName() != null) fields.add("PIC");
        return fields.isEmpty() ? "tidak ada field terdeteksi" : String.join(", ", fields);
    }

    private String participantDisplayName(EventParticipant participant) {
        if (participant == null || participant.getDatabase() == null) {
            return "Peserta";
        }
        String firstName = safe(participant.getDatabase().getFirstName()).trim();
        String lastName = safe(participant.getDatabase().getLastName()).trim();
        String fullName = (firstName + " " + lastName).trim();
        return fullName.isEmpty() ? "Peserta #" + participant.getId() : fullName;
    }

    private String summarizeNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "-";
        }
        if (names.size() <= 3) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, 3)) + " +" + (names.size() - 3) + " lainnya";
    }

    private String enumName(Enum<?> value) {
        return value == null ? "" : value.name();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String displayValue(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
