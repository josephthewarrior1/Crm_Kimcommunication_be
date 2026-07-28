package com.crm.service;

import com.crm.domain.*;
import com.crm.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class EmsService {

    @Value("${EMS_API_URL:https://api.admin.event.kimcommunication.com}")
    private String emsApiUrl;

    @Value("${EMS_ADMIN_EMAIL:admin_staff@kim-marketing.com}")
    private String emsAdminEmail;

    @Value("${EMS_ADMIN_PASSWORD:!QAZkim123}")
    private String emsAdminPassword;

    @Autowired
    private DatabaseRepository databaseRepository;

    @Autowired
    private DatabaseEmailRepository databaseEmailRepository;

    @Autowired
    private EventParticipantRepository eventParticipantRepository;

    @Autowired
    private CompanyRepository companyRepository;

    private final RestTemplate restTemplate = new RestTemplate();

    private String getEmsAccessToken() {
        try {
            String loginUrl = emsApiUrl + "/api/admin/v1/login";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

            Map<String, String> loginBody = new HashMap<>();
            loginBody.put("email", emsAdminEmail);
            loginBody.put("password", emsAdminPassword);

            HttpEntity<Map<String, String>> loginRequest = new HttpEntity<>(loginBody, headers);
            ResponseEntity<Map> loginResponse = restTemplate.postForEntity(loginUrl, loginRequest, Map.class);

            if (loginResponse.getStatusCode().is2xxSuccessful() && loginResponse.getBody() != null) {
                Map data = (Map) loginResponse.getBody().get("data");
                if (data != null && data.containsKey("access_token")) {
                    return (String) data.get("access_token");
                }
            }
        } catch (Exception e) {
            System.err.println("EMS login error: " + e.getMessage());
        }
        return null;
    }

    public Object getUpcomingEvents() {
        try {
            String token = getEmsAccessToken();
            if (token == null) return Collections.emptyList();

            String eventsUrl = emsApiUrl + "/api/admin/v1/events_upcoming";
            HttpHeaders eventsHeaders = new HttpHeaders();
            eventsHeaders.setBearerAuth(token);
            eventsHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

            HttpEntity<Void> eventsRequest = new HttpEntity<>(eventsHeaders);
            ResponseEntity<Map> eventsResponse = restTemplate.exchange(eventsUrl, HttpMethod.GET, eventsRequest, Map.class);

            if (eventsResponse.getStatusCode().is2xxSuccessful() && eventsResponse.getBody() != null) {
                Object eventsData = eventsResponse.getBody().get("data");
                return eventsData != null ? eventsData : eventsResponse.getBody();
            }
            return Collections.emptyList();
        } catch (Exception e) {
            System.err.println("Error fetching EMS events: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    public Object getEventParticipants(Long emsEventId) {
        try {
            String token = getEmsAccessToken();
            if (token == null) return Collections.emptyList();

            String url = emsApiUrl + "/api/admin/v1/events/" + emsEventId + "/registers";
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object data = response.getBody().get("data");
                return data != null ? data : response.getBody();
            }
            return Collections.emptyList();
        } catch (Exception e) {
            System.err.println("Error fetching EMS event participants: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Transactional
    public int syncParticipantsForEvent(com.crm.domain.Event event) {
        if (event == null || event.getEmsEventId() == null || event.getEmsEventId() <= 0) {
            return 0;
        }

        Object raw = getEventParticipants(event.getEmsEventId());
        if (raw instanceof Map && ((Map) raw).get("data") instanceof List) {
            raw = ((Map) raw).get("data");
        }
        if (!(raw instanceof List)) {
            return 0;
        }

        List list = (List) raw;
        int syncedCount = 0;

        for (Object itemObj : list) {
            if (!(itemObj instanceof Map)) continue;
            Map item = (Map) itemObj;

            Map user = (Map) item.get("user");
            Map profile = (Map) item.get("user_profile");
            if (profile == null) profile = (Map) item.get("profile");
            Map createdBy = (Map) item.get("created_by");

            // Extract Email
            String email = null;
            if (createdBy != null && createdBy.get("email") != null) email = createdBy.get("email").toString().trim();
            else if (user != null && user.get("email") != null) email = user.get("email").toString().trim();
            else if (profile != null && profile.get("email") != null) email = profile.get("email").toString().trim();
            else if (item.get("email") != null) email = item.get("email").toString().trim();

            if (email == null || email.isEmpty()) continue;

            // Extract Salutation & Names
            String salutation = null;
            if (profile != null && profile.get("registered_salutation") != null) {
                salutation = profile.get("registered_salutation").toString().trim();
            } else if (profile != null && profile.get("salutation") != null) {
                salutation = profile.get("salutation").toString().trim();
            } else if (item.get("salutation") != null) {
                salutation = item.get("salutation").toString().trim();
            }

            String firstName = null;
            String lastName = null;

            if (profile != null && profile.get("registered_name") != null) {
                firstName = profile.get("registered_name").toString().trim();
            }
            if (profile != null && profile.get("registered_last_name") != null) {
                lastName = profile.get("registered_last_name").toString().trim();
            }

            if ((firstName == null || firstName.isEmpty()) && createdBy != null) {
                if (createdBy.get("name") != null) firstName = createdBy.get("name").toString().trim();
                if (createdBy.get("last_name") != null) lastName = createdBy.get("last_name").toString().trim();
            }

            if ((firstName == null || firstName.isEmpty()) && user != null) {
                if (user.get("name") != null) firstName = user.get("name").toString().trim();
                if (user.get("last_name") != null) lastName = user.get("last_name").toString().trim();
            }

            if (firstName == null || firstName.isEmpty()) firstName = "Registrant";
            if (lastName == null) lastName = "";

            // Extract Phone
            String phone = null;
            if (profile != null && profile.get("phone") != null) phone = profile.get("phone").toString().trim();
            else if (user != null && user.get("phone") != null) phone = user.get("phone").toString().trim();

            // Extract Company & Job Title
            String companyName = null;
            String jobTitle = null;
            if (profile != null) {
                if (profile.get("company") != null) companyName = profile.get("company").toString().trim();
                if (profile.get("position") != null) jobTitle = profile.get("position").toString().trim();
                else if (profile.get("job_title") != null) jobTitle = profile.get("job_title").toString().trim();
            }

            // Find/Create Company
            Company company = null;
            if (companyName != null && !companyName.isEmpty()) {
                Optional<Company> compOpt = companyRepository.findByNameIgnoreCase(companyName);
                if (compOpt.isPresent()) {
                    company = compOpt.get();
                } else {
                    company = companyRepository.save(Company.builder().name(companyName).build());
                }
            }

            // Find or create Database record
            Optional<DatabaseEmail> existingEmailOpt = databaseEmailRepository.findByEmail(email.toLowerCase());
            Database database;
            if (existingEmailOpt.isPresent()) {
                database = existingEmailOpt.get().getDatabase();
                boolean updated = false;
                if (company != null && database.getCompany() == null) {
                    database.setCompany(company);
                    updated = true;
                }
                if (salutation != null && !salutation.isEmpty() && (database.getSalutation() == null || database.getSalutation().isEmpty())) {
                    database.setSalutation(salutation);
                    updated = true;
                }
                if (updated) {
                    databaseRepository.save(database);
                }
            } else {
                database = Database.builder()
                        .salutation(salutation)
                        .firstName(firstName)
                        .lastName(lastName)
                        .company(company)
                        .jobTitle(jobTitle)
                        .mobilePhone(phone)
                        .databaseType(DatabaseType.end_user)
                        .isActive(true)
                        .build();
                database = databaseRepository.save(database);

                DatabaseEmail dbEmail = DatabaseEmail.builder()
                        .database(database)
                        .email(email.toLowerCase())
                        .isPrimary(true)
                        .build();
                databaseEmailRepository.save(dbEmail);
            }

            // Determine status based on EMS registration_code, verified_at, declined_at, and checked_in_at
            Object checkedInAt = item.get("checked_in_at");
            Object verifiedAt = item.get("verified_at");
            Object declinedAt = item.get("declined_at");
            Object regCode = item.get("registration_code");

            boolean isCheckedIn = checkedInAt != null && !checkedInAt.toString().isEmpty() && !"null".equalsIgnoreCase(checkedInAt.toString());
            boolean isVerified = verifiedAt != null && !verifiedAt.toString().isEmpty() && !"null".equalsIgnoreCase(verifiedAt.toString());
            boolean isDeclined = declinedAt != null && !declinedAt.toString().isEmpty() && !"null".equalsIgnoreCase(declinedAt.toString());
            boolean hasRegCode = regCode != null && !regCode.toString().isEmpty() && !"null".equalsIgnoreCase(regCode.toString());

            AttendanceStatus attendanceStatus;
            if (isCheckedIn) {
                attendanceStatus = AttendanceStatus.attended;
            } else if (hasRegCode) {
                attendanceStatus = AttendanceStatus.registered;
            } else {
                attendanceStatus = AttendanceStatus.registered;
            }

            String confirmationStatus;
            ParticipantStatus participantStatus;

            if (isDeclined) {
                confirmationStatus = "declined";
                participantStatus = ParticipantStatus.unable_to_attend;
            } else if (isCheckedIn) {
                confirmationStatus = "approve";
                participantStatus = ParticipantStatus.confirm;
            } else {
                confirmationStatus = "approve";
                participantStatus = ParticipantStatus.registered;
            }

            // Link EventParticipant
            Optional<EventParticipant> existingParticipantOpt = eventParticipantRepository.findByEventIdAndDatabaseId(event.getId(), database.getId());
            if (existingParticipantOpt.isPresent()) {
                EventParticipant ep = existingParticipantOpt.get();
                ep.setAttendanceStatus(attendanceStatus);
                ep.setParticipantStatus(participantStatus);
                ep.setConfirmationStatus(confirmationStatus);
                String currentNotes = ep.getNotes();
                if (currentNotes == null || currentNotes.isEmpty()) {
                    ep.setNotes("[Origin: EMS Sync]");
                } else if (!currentNotes.contains("[Origin: EMS Sync]") && !currentNotes.contains("[EMS]")) {
                    ep.setNotes("[Origin: EMS Sync] " + currentNotes);
                }
                if (isCheckedIn) {
                    ep.setReminderHariH("on_location");
                }
                eventParticipantRepository.save(ep);
            } else {
                EventParticipant ep = EventParticipant.builder()
                        .event(event)
                        .database(database)
                        .attendanceStatus(attendanceStatus)
                        .participantStatus(participantStatus)
                        .confirmationStatus(confirmationStatus)
                        .reminderHariH(isCheckedIn ? "on_location" : null)
                        .notes("[Origin: EMS Sync]")
                        .build();
                eventParticipantRepository.save(ep);
            }
            syncedCount++;
        }
        return syncedCount;
    }
}
