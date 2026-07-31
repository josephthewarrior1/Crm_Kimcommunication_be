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

    @Autowired
    private EventParticipantActivityRepository eventParticipantActivityRepository;

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
        List<EventParticipant> currentEventParticipants = eventParticipantRepository.findByEventId(event.getId());
        int syncedCount = 0;

        for (Object itemObj : list) {
            if (!(itemObj instanceof Map)) continue;
            Map item = (Map) itemObj;

            Map user = (Map) item.get("user");
            Map profile = (Map) item.get("user_profile");
            if (profile == null) profile = (Map) item.get("profile");
            Map createdBy = (Map) item.get("created_by");

            // Extract Email robustly
            String email = extractEmailFromEmsItem(item, user, profile, createdBy);

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

            // Extract Company, Job Title & Industry
            String companyName = null;
            String jobTitle = null;
            String industry = null;
            if (profile != null) {
                if (profile.get("company") != null) companyName = profile.get("company").toString().trim();
                if (profile.get("position") != null) jobTitle = profile.get("position").toString().trim();
                else if (profile.get("job_title") != null) jobTitle = profile.get("job_title").toString().trim();
                if (profile.get("industry") != null) industry = profile.get("industry").toString().trim();
                else if (profile.get("company_industry") != null) industry = profile.get("company_industry").toString().trim();
                else if (profile.get("business_sector") != null) industry = profile.get("business_sector").toString().trim();
            }
            if (industry == null || industry.isEmpty()) {
                if (item.get("industry") != null) industry = item.get("industry").toString().trim();
                else if (item.get("company_industry") != null) industry = item.get("company_industry").toString().trim();
            }

            // Find/Create Company
            Company company = null;
            if (companyName != null && !companyName.isEmpty()) {
                Optional<Company> compOpt = companyRepository.findByNameIgnoreCase(companyName);
                if (compOpt.isPresent()) {
                    company = compOpt.get();
                    if ((company.getIndustry() == null || company.getIndustry().isEmpty()) && industry != null && !industry.isEmpty()) {
                        company.setIndustry(industry);
                        company = companyRepository.save(company);
                    }
                } else {
                    company = companyRepository.save(Company.builder().name(companyName).industry(industry).build());
                }
            }

            // Find or create Database record
            Database database;
            Optional<DatabaseEmail> existingEmailOpt = (email != null && !email.isEmpty()) 
                    ? databaseEmailRepository.findByEmail(email.toLowerCase()) 
                    : Optional.empty();

            if (existingEmailOpt.isPresent()) {
                DatabaseEmail existingDbEmail = existingEmailOpt.get();
                database = existingDbEmail.getDatabase();
                if (existingDbEmail.getEmailType() == null || existingDbEmail.getIsCorporate() == null) {
                    boolean isPersonal = isPersonalEmail(email);
                    existingDbEmail.setEmailType(isPersonal ? "personal" : "company");
                    existingDbEmail.setIsCorporate(!isPersonal);
                    databaseEmailRepository.save(existingDbEmail);
                }
                hydrateDatabaseFromEms(database, company, salutation, lastName, phone, jobTitle, profile);
            } else {
                database = findMatchingEventDatabase(currentEventParticipants, firstName, lastName, phone);
                if (database != null) {
                    hydrateDatabaseFromEms(database, company, salutation, lastName, phone, jobTitle, profile);
                } else {
                    database = Database.builder()
                            .salutation(salutation)
                            .firstName(firstName)
                            .lastName(lastName)
                            .company(company)
                            .jobTitle(jobTitle)
                            .mobilePhone(phone)
                            .databaseType(DatabaseType.end_user)
                            .source(DatabaseSource.event_registration)
                            .isActive(true)
                            .build();
                    database = databaseRepository.save(database);
                }

                if (email != null && !email.isEmpty() && !databaseEmailRepository.findByEmail(email.toLowerCase()).isPresent()) {
                    boolean isPersonal = isPersonalEmail(email);
                    DatabaseEmail dbEmail = DatabaseEmail.builder()
                            .database(database)
                            .email(email.toLowerCase())
                            .emailType(isPersonal ? "personal" : "company")
                            .isCorporate(!isPersonal)
                            .isPrimary(database.getEmails() == null || database.getEmails().isEmpty())
                            .build();
                    databaseEmailRepository.save(dbEmail);
                    if (database.getEmails() == null) database.setEmails(new ArrayList<>());
                    database.getEmails().add(dbEmail);
                }
            }

            // Determine status based on EMS registration_code, verified_at, declined_at, and checked_in_at
            Object checkedInAt = item.get("checked_in_at");
            Object verifiedAt = item.get("verified_at");
            Object declinedAt = item.get("declined_at");
            Object regCode = item.get("registration_code");
            Object statusObj = item.get("status");
            Object paymentStatusObj = item.get("payment_status");

            Object createdAtObj = item.get("created_at");
            if (createdAtObj == null && profile != null) createdAtObj = profile.get("created_at");
            if (createdAtObj == null) createdAtObj = verifiedAt;
            java.time.LocalDateTime emsCreatedAt = parseEmsDateTime(createdAtObj);

            boolean isCheckedIn = checkedInAt != null && !checkedInAt.toString().isEmpty() && !"null".equalsIgnoreCase(checkedInAt.toString());
            boolean isVerified = verifiedAt != null && !verifiedAt.toString().isEmpty() && !"null".equalsIgnoreCase(verifiedAt.toString());
            boolean isDeclined = (declinedAt != null && !declinedAt.toString().isEmpty() && !"null".equalsIgnoreCase(declinedAt.toString()))
                    || "declined".equalsIgnoreCase(String.valueOf(statusObj))
                    || "decline".equalsIgnoreCase(String.valueOf(statusObj))
                    || "declined".equalsIgnoreCase(String.valueOf(paymentStatusObj))
                    || "decline".equalsIgnoreCase(String.valueOf(paymentStatusObj));
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
                confirmationStatus = "decline";
                participantStatus = ParticipantStatus.unable_to_attend;
            } else if (isCheckedIn) {
                confirmationStatus = "approve";
                participantStatus = ParticipantStatus.confirm;
            } else if (isVerified) {
                confirmationStatus = "approve";
                participantStatus = ParticipantStatus.registered;
            } else {
                confirmationStatus = "pending";
                participantStatus = ParticipantStatus.registered;
            }

            // Check if database or participant is flagged as declined / inactive
            boolean isDeclinedOrInactive = (database.getIsActive() != null && !database.getIsActive());

            // Link EventParticipant
            Optional<EventParticipant> existingParticipantOpt = eventParticipantRepository.findByEventIdAndDatabaseId(event.getId(), database.getId());
            if (existingParticipantOpt.isPresent()) {
                EventParticipant ep = existingParticipantOpt.get();
                ep.setAttendanceStatus(attendanceStatus);
                ep.setParticipantStatus(participantStatus);
                
                String targetApproval = isDeclined ? "decline" : confirmationStatus;
                ep.setConfirmationStatus(targetApproval);

                String approvalTag = "[PreEventApproval: " + targetApproval + "]";

                String currentNotes = ep.getNotes();
                if (currentNotes == null || currentNotes.isEmpty()) {
                    ep.setNotes("[Origin: EMS Sync] " + approvalTag);
                } else {
                    String updatedNotes = currentNotes;
                    if (!updatedNotes.contains("[Origin: EMS Sync]") && !updatedNotes.contains("[EMS]")) {
                        updatedNotes = "[Origin: EMS Sync] " + updatedNotes;
                    }
                    if (updatedNotes.contains("[PreEventApproval:")) {
                        updatedNotes = updatedNotes.replaceAll("\\[PreEventApproval:\\s*([^\\]]+)\\]", approvalTag);
                    } else {
                        updatedNotes = approvalTag + " " + updatedNotes;
                    }
                    ep.setNotes(updatedNotes);
                }
                if (isCheckedIn) {
                    ep.setReminderHariH("on_location");
                }
                if (emsCreatedAt != null) {
                    ep.setCreatedAt(emsCreatedAt);
                    ep.setRequestedAt(emsCreatedAt);
                }
                eventParticipantRepository.save(ep);

                if (eventParticipantActivityRepository.findByEventParticipantIdOrderByCreatedAtDesc(ep.getId()).isEmpty()) {
                    EventParticipantActivity initAct = EventParticipantActivity.builder()
                            .eventParticipant(ep)
                            .activityType("SYSTEM")
                            .status("REGISTERED")
                            .notes("Initial sync from EMS")
                            .createdBy("EMS Sync")
                            .build();
                    eventParticipantActivityRepository.save(initAct);
                }
            } else {
                String targetApproval = isDeclined ? "decline" : confirmationStatus;
                String approvalTag = "[PreEventApproval: " + targetApproval + "]";

                EventParticipant ep = EventParticipant.builder()
                        .event(event)
                        .database(database)
                        .attendanceStatus(attendanceStatus)
                        .participantStatus(participantStatus)
                        .confirmationStatus(targetApproval)
                        .reminderHariH(isCheckedIn ? "on_location" : null)
                        .notes("[Origin: EMS Sync] " + approvalTag)
                        .createdAt(emsCreatedAt != null ? emsCreatedAt : java.time.LocalDateTime.now())
                        .requestedAt(emsCreatedAt != null ? emsCreatedAt : java.time.LocalDateTime.now())
                        .build();
                EventParticipant epSaved = eventParticipantRepository.save(ep);

                EventParticipantActivity initAct = EventParticipantActivity.builder()
                        .eventParticipant(epSaved)
                        .activityType("SYSTEM")
                        .status("REGISTERED")
                        .notes("Initial sync from EMS")
                        .createdBy("EMS Sync")
                        .build();
                eventParticipantActivityRepository.save(initAct);
            }
            syncedCount++;
        }
        return syncedCount;
    }

    private Database findMatchingEventDatabase(List<EventParticipant> participants, String firstName, String lastName, String phone) {
        String targetName = normalizeText((safe(firstName) + " " + safe(lastName)).trim());
        String targetPhone = normalizePhone(phone);

        for (EventParticipant participant : participants) {
            Database db = participant.getDatabase();
            if (db == null) continue;

            String dbPhone = normalizePhone(db.getMobilePhone());
            if (!targetPhone.isEmpty() && !dbPhone.isEmpty() && targetPhone.equals(dbPhone)) {
                return db;
            }

            String dbName = normalizeText((safe(db.getFirstName()) + " " + safe(db.getLastName())).replace(" -", "").trim());
            if (!targetName.isEmpty() && targetName.equals(dbName)) {
                return db;
            }
        }

        return null;
    }

    private void hydrateDatabaseFromEms(Database database, Company company, String salutation, String lastName, String phone, String jobTitle, Map profile) {
        boolean updated = false;

        if (company != null && database.getCompany() == null) {
            database.setCompany(company);
            updated = true;
        }
        if (isBlank(database.getSalutation()) && !isBlank(salutation)) {
            database.setSalutation(salutation);
            updated = true;
        }
        if (isBlankOrDash(database.getLastName()) && !isBlank(lastName)) {
            database.setLastName(lastName);
            updated = true;
        }
        if (isBlankOrDash(database.getMobilePhone()) && !isBlank(phone)) {
            database.setMobilePhone(phone);
            database.setNormalizedPhone(normalizePhone(phone));
            updated = true;
        }
        if (shouldUseEmsValue(database.getJobTitle(), jobTitle)) {
            database.setJobTitle(jobTitle);
            updated = true;
        }

        String jobLevel = null;
        if (profile != null && profile.get("job_level") != null) {
            jobLevel = profile.get("job_level").toString();
        }
        if ((database.getPositionLevel() == null || database.getPositionLevel() == PositionLevel.UNKNOWN) && !isBlank(jobLevel)) {
            database.setPositionLevel(PositionLevel.fromValue(jobLevel));
            updated = true;
        }

        if (updated) {
            databaseRepository.save(database);
        }
    }

    private boolean shouldUseEmsValue(String current, String emsValue) {
        if (isBlank(emsValue)) return false;
        if (isBlankOrDash(current)) return true;
        return normalizeText(emsValue).contains(normalizeText(current)) && emsValue.trim().length() > current.trim().length();
    }

    private String normalizeText(String value) {
        return safe(value).toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    private String normalizePhone(String value) {
        return safe(value).replaceAll("\\D", "");
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value.trim());
    }

    private boolean isBlankOrDash(String value) {
        return isBlank(value) || "-".equals(value.trim()) || "unknown".equalsIgnoreCase(value.trim());
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String extractEmailFromEmsItem(Map item, Map user, Map profile, Map createdBy) {
        List<String> candidates = new ArrayList<>();

        if (profile != null) {
            if (profile.get("registered_email") != null) candidates.add(profile.get("registered_email").toString().trim());
            if (profile.get("email") != null) candidates.add(profile.get("email").toString().trim());
            if (profile.get("work_email") != null) candidates.add(profile.get("work_email").toString().trim());
            if (profile.get("company_email") != null) candidates.add(profile.get("company_email").toString().trim());
            if (profile.get("personal_email") != null) candidates.add(profile.get("personal_email").toString().trim());
        }

        if (item != null) {
            if (item.get("registered_email") != null) candidates.add(item.get("registered_email").toString().trim());
            if (item.get("email") != null) candidates.add(item.get("email").toString().trim());
        }

        if (user != null) {
            if (user.get("email") != null) candidates.add(user.get("email").toString().trim());
        }

        if (createdBy != null) {
            if (createdBy.get("email") != null) candidates.add(createdBy.get("email").toString().trim());
        }

        for (String c : candidates) {
            if (c != null && !c.isEmpty() && !"null".equalsIgnoreCase(c) && c.contains("@")) {
                return c;
            }
        }
        return null;
    }

    private boolean isPersonalEmail(String email) {
        if (email == null || !email.contains("@")) return false;
        String domain = email.substring(email.lastIndexOf("@") + 1).toLowerCase().trim();
        Set<String> publicDomains = new HashSet<>(Arrays.asList(
            "gmail.com", "yahoo.com", "yahoo.co.id", "hotmail.com", "outlook.com",
            "icloud.com", "ymail.com", "live.com", "rocketmail.com", "aol.com", "me.com", "msn.com"
        ));
        return publicDomains.contains(domain);
    }

    private java.time.LocalDateTime parseEmsDateTime(Object dateObj) {
        if (dateObj == null) return null;
        String str = dateObj.toString().trim();
        if (str.isEmpty() || "null".equalsIgnoreCase(str)) return null;
        try {
            if (str.contains("T")) {
                str = str.replace("Z", "");
                if (str.contains(".")) {
                    str = str.substring(0, str.indexOf("."));
                }
                return java.time.LocalDateTime.parse(str, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } else {
                return java.time.LocalDateTime.parse(str, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
        } catch (Exception e) {
            return null;
        }
    }
}
