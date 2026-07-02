package com.crm.controller;

import com.crm.domain.*;
import com.crm.repository.*;
import com.crm.service.SecurityHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/event-leads")
public class EventLeadController {

    @Autowired
    private EventLeadRepository eventLeadRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DatabaseRepository databaseRepository;

    @Autowired
    private EventLeadActivityRepository eventLeadActivityRepository;

    @Autowired
    private SecurityHelper securityHelper;

    @GetMapping
    public ResponseEntity<?> getAllEventLeads(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        return ResponseEntity.ok(eventLeadRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<?> createEventLead(
            @RequestBody EventLeadRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
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

        java.util.List<EventLead> savedLeads = new java.util.ArrayList<>();
        for (Long databaseId : databasesToProcess) {
            Database database = databaseRepository.findById(databaseId).orElse(null);
            if (database == null) {
                continue;
            }

            if (eventLeadRepository.findByEventIdAndDatabaseId(request.getEventId(), databaseId).isPresent()) {
                continue;
            }

            EventLead eventLead = EventLead.builder()
                    .event(event)
                    .database(database)
                    .leadStatus(request.getLeadStatus() != null ? LeadStatus.valueOf(request.getLeadStatus()) : LeadStatus.white)
                    .attendanceStatus(request.getAttendanceStatus() != null ? AttendanceStatus.valueOf(request.getAttendanceStatus()) : AttendanceStatus.invited)
                    .confirmationStatus(request.getConfirmationStatus() != null ? request.getConfirmationStatus() : "pending")
                    .notes(request.getNotes())
                    .build();
            savedLeads.add(eventLeadRepository.save(eventLead));
        }

        return ResponseEntity.ok(savedLeads);
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable Long id,
            @RequestParam(required = false) String leadStatus,
            @RequestParam(required = false) String attendanceStatus,
            @RequestParam(required = false) String notes,
            @RequestParam(required = false) String leadCategory,
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
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        return eventLeadRepository.findById(id).map(lead -> {
            if (leadStatus != null) {
                try {
                    lead.setLeadStatus(LeadStatus.valueOf(leadStatus));
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body("Invalid leadStatus. Must be a valid LeadStatus enum value.");
                }
            }
            if (attendanceStatus != null) {
                try {
                    lead.setAttendanceStatus(AttendanceStatus.valueOf(attendanceStatus));
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body("Invalid attendanceStatus. Must be invited, registered, attended, no_show, or cancelled.");
                }
            }
            if (notes != null) {
                lead.setNotes(notes);
            }
            if (confirmationStatus != null) {
                lead.setConfirmationStatus(confirmationStatus);
            }
            if (leadCategory != null) {
                lead.setLeadCategory(leadCategory);
            }
            if (callStatus != null) {
                lead.setCallStatus(callStatus);
            }
            if (emailStatus != null) {
                lead.setEmailStatus(emailStatus);
            }
            if (whatsappStatus != null) {
                lead.setWhatsappStatus(whatsappStatus);
            }
            if (meetingStatus != null) {
                lead.setMeetingStatus(meetingStatus);
            }
            if (businessChallenges != null) {
                lead.setBusinessChallenges(businessChallenges);
            }
            if (projectInfo != null) {
                lead.setProjectInfo(projectInfo);
            }
            if (timeline != null) {
                lead.setTimeline(timeline);
            }
            if (reminderH7 != null) {
                lead.setReminderH7(reminderH7);
            }
            if (reminderH3 != null) {
                lead.setReminderH3(reminderH3);
            }
            if (reminderH1 != null) {
                lead.setReminderH1(reminderH1);
            }
            if (reminderHariH != null) {
                lead.setReminderHariH(reminderHariH);
            }
            return ResponseEntity.ok(eventLeadRepository.save(lead));
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

        return eventLeadRepository.findById(id).map(lead -> {
            EventLeadActivity activity = EventLeadActivity.builder()
                    .eventLead(lead)
                    .activityType(request.getActivityType())
                    .status(request.getStatus())
                    .notes(request.getNotes())
                    .createdBy(currentUser.getUsername())
                    .build();
            
            EventLeadActivity savedActivity = eventLeadActivityRepository.save(activity);

            // Sync status to the EventLead
            if ("CALL".equalsIgnoreCase(request.getActivityType())) {
                lead.setCallStatus(request.getStatus());
            } else if ("EMAIL".equalsIgnoreCase(request.getActivityType())) {
                lead.setEmailStatus(request.getStatus());
            } else if ("WHATSAPP".equalsIgnoreCase(request.getActivityType())) {
                lead.setWhatsappStatus(request.getStatus());
            } else if ("MEETING".equalsIgnoreCase(request.getActivityType())) {
                lead.setMeetingStatus(request.getStatus());
            }
            eventLeadRepository.save(lead);

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

        return ResponseEntity.ok(eventLeadActivityRepository.findByEventLeadIdOrderByCreatedAtDesc(id));
    }

    @GetMapping("/emails/track/{activityId}")
    public ResponseEntity<byte[]> trackEmailOpen(@PathVariable Long activityId) {
        eventLeadActivityRepository.findById(activityId).ifPresent(activity -> {
            if ("EMAIL".equalsIgnoreCase(activity.getActivityType()) && !"OPENED".equalsIgnoreCase(activity.getStatus())) {
                activity.setStatus("OPENED");
                eventLeadActivityRepository.save(activity);

                // Also update the status on the lead itself
                EventLead lead = activity.getEventLead();
                lead.setEmailStatus("OPENED");
                eventLeadRepository.save(lead);
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

    @GetMapping("/report/{eventId}")
    public ResponseEntity<?> getEventReport(
            @PathVariable Long eventId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        List<EventLead> leads = eventLeadRepository.findByEventId(eventId);
        List<EventLeadActivity> activities = eventLeadActivityRepository.findByEventLeadEventId(eventId);

        long emailSent = activities.stream().filter(a -> "EMAIL".equalsIgnoreCase(a.getActivityType())).count();
        long emailOpened = activities.stream().filter(a -> "EMAIL".equalsIgnoreCase(a.getActivityType()) && "OPENED".equalsIgnoreCase(a.getStatus())).count();
        long emailResponded = activities.stream().filter(a -> "EMAIL".equalsIgnoreCase(a.getActivityType()) && "RESPONDED".equalsIgnoreCase(a.getStatus())).count();

        long callsMade = activities.stream().filter(a -> "CALL".equalsIgnoreCase(a.getActivityType())).count();
        long connectedCalls = activities.stream().filter(a -> "CALL".equalsIgnoreCase(a.getActivityType()) && "CONNECTED".equalsIgnoreCase(a.getStatus())).count();

        long waSent = activities.stream().filter(a -> "WHATSAPP".equalsIgnoreCase(a.getActivityType())).count();
        long waResponded = activities.stream().filter(a -> "WHATSAPP".equalsIgnoreCase(a.getActivityType()) && "RESPONDED".equalsIgnoreCase(a.getStatus())).count();

        long hotLeads = leads.stream().filter(l -> "HOT".equalsIgnoreCase(l.getLeadCategory())).count();
        long warmLeads = leads.stream().filter(l -> "WARM".equalsIgnoreCase(l.getLeadCategory())).count();
        long meetingsSecured = leads.stream().filter(l -> "CONFIRMED".equalsIgnoreCase(l.getMeetingStatus())).count();

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
        report.put("hotLeads", hotLeads);
        report.put("warmLeads", warmLeads);
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
    public static class EventLeadRequest {
        private Long eventId;
        private Long databaseId;
        private List<Long> databaseIds;
        private String leadStatus;
        private String attendanceStatus;
        private String confirmationStatus;
        private String notes;
    }
}
