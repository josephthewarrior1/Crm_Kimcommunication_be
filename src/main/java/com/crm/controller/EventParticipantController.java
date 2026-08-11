package com.crm.controller;

import com.crm.domain.*;
import com.crm.repository.*;
import com.crm.service.SecurityHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/event-participants")
public class EventParticipantController {

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
            return ResponseEntity.ok(eventParticipantRepository.save(participant));
        }).orElse(ResponseEntity.notFound().build());
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
            eventParticipantRepository.delete(participant);
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

    private boolean isViewer(AppUser user) {
        return securityHelper.hasRole(user, Role.USER) && !securityHelper.hasAnyRole(user, Role.ADMIN, Role.MANAGER);
    }

    private boolean canAccessEvent(AppUser user, EventParticipant participant) {
        return !isViewer(user) || (participant.getEvent() != null && user.getAllowedEventIds().contains(participant.getEvent().getId()));
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
}
