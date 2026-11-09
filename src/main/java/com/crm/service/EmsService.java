package com.crm.service;

import com.crm.domain.*;
import com.crm.repository.*;
import com.crm.util.ContactEmailCategory;
import com.crm.util.ContactPhone;
import org.springframework.web.server.ResponseStatusException;
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

    @Autowired
    private EmsV2Client emsV2Client;

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
        return getUpcomingEvents(EmsSource.LEGACY);
    }

    public Object getUpcomingEvents(EmsSource source) {
        if (source == EmsSource.V2) return emsV2Client.getEvents();
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
        return getEventParticipants(emsEventId, EmsSource.LEGACY);
    }

    public Object getEventParticipants(Long emsEventId, EmsSource source) {
        if (source == EmsSource.V2) return emsV2Client.getEventParticipants(emsEventId);
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

    private record Registration(Map item, Map profile, String email, String salutation, String firstName,
                                String lastName, String phone, String companyName, String jobTitle, String industry) {}

    private Map asMap(Object value) { return value instanceof Map ? (Map) value : null; }

    private Registration registration(Map item) {
            Map user = asMap(item.get("user"));
            Map profile = asMap(item.get("user_profile"));
            if (profile == null) profile = asMap(item.get("profile"));
            Map createdBy = asMap(item.get("created_by"));

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

            if (firstName == null || firstName.isEmpty()) firstName = "";
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


        return new Registration(item, profile, email == null ? "" : email.trim().toLowerCase(Locale.ROOT), salutation,
                firstName, lastName, phone, companyName, jobTitle, industry);
    }

    private record SyncPlan(List<Map> accepted, Map<String, Database> matches, Map<String, String> aliases, Map<String, Object> preview) {}

    public Map<String, Object> previewParticipantsForEvent(Event event) {
        return plan(event).preview();
    }

    private SyncPlan plan(Event event) {
        if (event == null || event.getEmsEventId() == null || event.getEmsEventId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hubungkan event EMS sebelum sync.");
        }
        DatabaseDestination destination = event.getSyncDestination();
        if (destination == null || destination == DatabaseDestination.BOTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pilih tujuan sync Database Perusahaan atau Database Peserta di pengaturan event.");
        }
        Object raw = getEventParticipants(event.getEmsEventId(), event.getEmsSource());
        if (raw instanceof Map && ((Map) raw).get("data") instanceof List) raw = ((Map) raw).get("data");
        if (!(raw instanceof List)) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Data peserta EMS tidak valid.");
        List<Database> contacts = databaseRepository.findAll();
        List<EventParticipant> participants = eventParticipantRepository.findByEventId(event.getId());
        Map<String, Database> matches = new HashMap<>();
        List<Map> accepted = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, String> aliases = new HashMap<>();
        Map<String, String> phoneAliases = new HashMap<>();
        Map<String, Set<String>> incomingNames = new HashMap<>();
        Map<String, Set<String>> incomingPhoneNames = new HashMap<>();
        for (Object item : (List) raw) {
            if (!(item instanceof Map)) continue;
            Registration incoming = registration((Map) item);
            if (!identityName(incoming.firstName() + " " + incoming.lastName()).isBlank()) {
                for (String number : ContactPhone.normalizedNumbers(incoming.phone())) incomingPhoneNames.computeIfAbsent(number, ignored -> new HashSet<>())
                        .add(identityName(incoming.firstName() + " " + incoming.lastName()));
            }
            if (ContactEmailCategory.isValidEmail(incoming.email()) && !incoming.firstName().isBlank()) {
                incomingNames.computeIfAbsent(incoming.email(), ignored -> new HashSet<>())
                        .add(identityName(incoming.firstName() + " " + incoming.lastName()));
            }
        }
        int created = 0, existing = 0, rejected = 0, row = 0;
        for (Object item : (List) raw) {
            row++;
            if (!(item instanceof Map)) {
                rows.add(Map.of("rowNum", row, "name", "", "email", "", "status", "REJECTED", "message", "Data registrasi tidak valid."));
                rejected++;
                continue;
            }
            Registration registration = registration((Map) item);
            String email = registration.email();
            String name = (registration.firstName() + " " + registration.lastName()).trim();
            String key = registrationContactKey(registration);
            String problem = "";
            if (email.isBlank() && isBlank(registration.phone())) problem = "Isi minimal email valid atau HP/WhatsApp peserta.";
            else if (!email.isBlank() && !ContactEmailCategory.isValidEmail(email)) problem = "Email peserta tidak valid.";
            else if (!isBlank(registration.phone()) && !ContactPhone.isUsable(registration.phone())) problem = "Mobile Phone peserta tidak valid.";
            if (problem.isEmpty() && incomingNames.getOrDefault(email, Set.of()).size() > 1
                    && (destination == DatabaseDestination.PARTICIPANT || name.isBlank())) {
                problem = "Email berulang pada nama berbeda di data EMS. Baris ini ditahan untuk ditinjau.";
            }
            if (problem.isEmpty() && name.isBlank() && ContactPhone.normalizedNumbers(registration.phone()).stream()
                    .anyMatch(number -> incomingPhoneNames.getOrDefault(number, Set.of()).size() > 1)) problem = "HP cocok dengan beberapa nama dalam data EMS; tinjau sebelum sync.";
            List<Database> owners = new ArrayList<>();
            if (problem.isEmpty()) {
                Set<String> numbers = ContactPhone.normalizedNumbers(registration.phone());
                List<Database> contactOwners = new ArrayList<>();
                for (Database contact : contacts) {
                    boolean sameEmail = !email.isBlank() && contact.getEmails() != null && contact.getEmails().stream().filter(Objects::nonNull)
                            .anyMatch(address -> email.equals(safe(address.getEmail()).trim().toLowerCase(Locale.ROOT)));
                    Set<String> storedNumbers = ContactPhone.normalizedNumbers(contact.getMobilePhone());
                    storedNumbers.addAll(ContactPhone.normalizedNumbers(contact.getNormalizedPhone()));
                    boolean samePhone = numbers.stream().anyMatch(storedNumbers::contains);
                    if (!sameEmail && !samePhone) continue;
                    contactOwners.add(contact);
                    String retainedName = identityName(safe(contact.getFirstName()) + " " + safe(contact.getLastName()));
                    if (name.isBlank() || retainedName.isBlank() || retainedName.equals(identityName(name))) owners.add(contact);
                    else if (destination == DatabaseDestination.PARTICIPANT || ContactEmailCategory.isPublicPersonalEmail(email)) {
                        problem = "Email atau HP sudah terdaftar pada nama berbeda. Tinjau profil sebelum sync.";
                    }
                }
                if (owners.size() > 1 || owners.size() == 1 && contactOwners.size() > 1
                        && identityName(safe(owners.get(0).getFirstName()) + " " + safe(owners.get(0).getLastName())).isBlank()) {
                    problem = "Kontak cocok dengan beberapa profil. Rapikan duplikat sebelum sync.";
                }
            }
            Database match = owners.size() == 1 ? owners.get(0) : null;
            if (problem.isEmpty() && match != null && Boolean.FALSE.equals(match.getIsActive())) problem = "Kontak nonaktif; tinjau sebelum sync.";
            if (problem.isEmpty() && !isBlank(registration.phone())) {
                try { ContactPhone.merge(match == null ? "" : match.getMobilePhone(), registration.phone()); }
                catch (IllegalArgumentException invalid) { problem = invalid.getMessage(); }
            }
            if (problem.isEmpty() && destination == DatabaseDestination.COMPANY && match != null && match.getCompany() != null
                    && !isBlank(registration.companyName()) && !normalizeText(match.getCompany().getName()).equals(normalizeText(registration.companyName()))) {
                problem = "Kontak sudah terdaftar di perusahaan lain; pindahkan melalui Edit kontak sebelum sync.";
            }
            Map<String, Object> previewRow = new LinkedHashMap<>();
            previewRow.put("rowNum", row); previewRow.put("name", name); previewRow.put("email", email);
            if (!problem.isEmpty()) {
                previewRow.put("status", "REJECTED"); previewRow.put("message", problem); rejected++;
            } else {
                List<String> phoneKeys = ContactPhone.normalizedNumbers(registration.phone()).stream()
                        .map(number -> (incomingPhoneNames.getOrDefault(number, Set.of()).size() > 1 ? identityName(name) : "") + "\u0000" + number).toList();
                String knownEmailKey = aliases.get(key);
                Set<String> knownPhoneKeys = phoneKeys.stream().map(phoneAliases::get).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
                if (knownPhoneKeys.size() > 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nomor mengarah ke beberapa calon profil; tinjau sebelum sync.");
                String knownPhoneKey = knownPhoneKeys.stream().findFirst().orElse(null);
                if (knownEmailKey != null && knownPhoneKey != null && !knownEmailKey.equals(knownPhoneKey)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nama, email, dan nomor mengarah ke beberapa calon profil. Tinjau data EMS sebelum sync.");
                }
                String canonicalKey = knownEmailKey != null ? knownEmailKey : knownPhoneKey != null ? knownPhoneKey : key;
                Database knownMatch = matches.get(canonicalKey);
                if (knownMatch != null && match != null && !Objects.equals(knownMatch.getId(), match.getId())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nama dan nomor cocok dengan beberapa profil lama. Tinjau duplikat sebelum sync.");
                }
                boolean exists = match != null || seen.contains(canonicalKey);
                aliases.put(key, canonicalKey);
                for (String phoneKey : phoneKeys) phoneAliases.put(phoneKey, canonicalKey);
                previewRow.put("status", exists ? "EXISTING" : "NEW");
                String message = exists ? "Profil dipakai kembali; riwayat event dipertahankan." : "Profil baru di tujuan yang dipilih.";
                if (isBlank(registration.phone())) message += " HP/WhatsApp belum diisi; peserta belum bisa ditelepon.";
                previewRow.put("message", message);
                if (match != null) { previewRow.put("existingDatabaseId", match.getId()); matches.put(canonicalKey, match); }
                if (exists) existing++; else created++;
                seen.add(canonicalKey); accepted.add((Map) item);
            }
            rows.add(previewRow);
        }
        List<Object> contactState = contacts.stream().sorted(Comparator.comparing(Database::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(contact -> (Object) Arrays.asList(contact.getId(), contact.getUpdatedAt(), contact.getDatabaseDestination(), contact.getIsActive(),
                        contact.getFirstName(), contact.getLastName(), contact.getMobilePhone(), contact.getNormalizedPhone(), contact.getCompany() == null ? null : contact.getCompany().getId(),
                        contact.getEmails() == null ? List.of() : contact.getEmails().stream().filter(Objects::nonNull).map(email -> safe(email.getEmail())).sorted().toList())).toList();
        List<Object> participantState = participants.stream().sorted(Comparator.comparing(EventParticipant::getId, Comparator.nullsFirst(Comparator.naturalOrder()))).map(participant -> (Object) Arrays.asList(participant.getId(),
                participant.getDatabase() == null ? null : participant.getDatabase().getId(), participant.getUpdatedAt())).toList();
        Map<String, Long> matchedIds = new TreeMap<>();
        matches.forEach((key, contact) -> matchedIds.put(key, contact.getId()));
        String token = fingerprint(Arrays.asList(event.getId(), event.getEmsEventId(), event.getEmsSource(), destination, raw, contactState, participantState, matchedIds, aliases));
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("destination", destination); preview.put("totalRows", rows.size()); preview.put("newCount", created);
        preview.put("existingCount", existing); preview.put("rejectedCount", rejected); preview.put("rows", rows); preview.put("previewToken", token);
        return new SyncPlan(accepted, matches, aliases, preview);
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, entry) -> sorted.put(String.valueOf(key), canonical(entry)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        if (value instanceof Enum<?> || value instanceof java.time.temporal.TemporalAccessor) return value.toString();
        return value;
    }

    private String fingerprint(Object value) {
        try {
            byte[] bytes = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(canonical(value));
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) { throw new IllegalStateException("Tidak dapat memverifikasi preview sync.", failure); }
    }

    @Transactional
    public Map<String, Object> syncParticipantsForEvent(Event event, String previewToken) {
        SyncPlan current = plan(event);
        if (previewToken == null || !Objects.equals(previewToken, current.preview().get("previewToken"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Data atau tujuan sync berubah. Preview ulang sebelum melanjutkan.");
        }
        int count = applyParticipants(event, current);
        Map<String, Object> response = new LinkedHashMap<>(current.preview());
        response.put("count", count); response.put("message", "Sync selesai ke tujuan yang dipilih.");
        return response;
    }

    private int applyParticipants(Event event, SyncPlan plan) {
        List list = plan.accepted();
        Map<String, Database> matchedContacts = new HashMap<>(plan.matches());
        List<EventParticipant> currentEventParticipants = new ArrayList<>(eventParticipantRepository.findByEventId(event.getId()));
        int syncedCount = 0;

        for (Object itemObj : list) {
            if (!(itemObj instanceof Map)) continue;
            Map item = (Map) itemObj;

            Registration registration = registration(item);
            Map profile = registration.profile();
            String email = registration.email(), salutation = registration.salutation();
            String firstName = registration.firstName(), lastName = registration.lastName(), phone = registration.phone();
            String companyName = registration.companyName(), jobTitle = registration.jobTitle(), industry = registration.industry();
            boolean participantDestination = event.getSyncDestination() == DatabaseDestination.PARTICIPANT;

            // Find/Create Company
            Company company = null;
            if (!participantDestination && companyName != null && !companyName.isEmpty()) {
                Optional<Company> compOpt = companyRepository.findByNameIgnoreCase(companyName);
                if (compOpt.isPresent()) {
                    company = compOpt.get();
                    Object rawIndustry = profile == null ? null : profile.get("ems_v2_industry_raw");
                    boolean replaceV2RawIndustry = event.getEmsSource() == EmsSource.V2
                            && rawIndustry instanceof String
                            && rawIndustry.equals(company.getIndustry()) && !rawIndustry.equals(industry);
                    if (industry != null && !industry.isEmpty()
                            && (company.getIndustry() == null || company.getIndustry().isEmpty() || replaceV2RawIndustry)) {
                        company.setIndustry(industry);
                        company = companyRepository.save(company);
                    }
                } else {
                    company = companyRepository.save(Company.builder().name(companyName).industry(industry).build());
                }
            }

            String contactKey = registrationContactKey(registration);
            contactKey = plan.aliases().getOrDefault(contactKey, contactKey);
            Database database = matchedContacts.get(contactKey);
            if (database == null) {
                database = Database.builder().salutation(salutation).firstName(firstName).lastName(lastName)
                        .company(company).jobTitle(participantDestination ? null : jobTitle).mobilePhone(isBlank(phone) ? null : ContactPhone.clean(phone))
                        .normalizedPhone(normalizePhone(phone)).databaseType(DatabaseType.end_user)
                        .source(DatabaseSource.event_registration).isActive(true)
                        .databaseDestination(event.getSyncDestination()).institutionName(participantDestination ? companyName : null).build();
                database = databaseRepository.save(database);
                matchedContacts.put(contactKey, database);
            } else if (participantDestination) {
                database.setDatabaseDestination(DatabaseDestination.effective(database.getDatabaseDestination()).add(DatabaseDestination.PARTICIPANT));
                if (isBlank(database.getFirstName()) && !isBlank(firstName)) database.setFirstName(firstName);
                if (isBlank(database.getLastName()) && !isBlank(lastName)) database.setLastName(lastName);
                if (isBlank(database.getInstitutionName()) && !isBlank(companyName)) database.setInstitutionName(companyName);
                if (!isBlank(phone)) {
                    String merged = ContactPhone.merge(database.getMobilePhone(), phone);
                    database.setMobilePhone(merged); database.setNormalizedPhone(ContactPhone.primary(merged));
                }
                databaseRepository.save(database);
            } else {
                boolean changed = !DatabaseDestination.effective(database.getDatabaseDestination()).includesCompany();
                if (changed) database.setDatabaseDestination(DatabaseDestination.BOTH);
                if (isBlank(database.getFirstName()) && !isBlank(firstName)) { database.setFirstName(firstName); changed = true; }
                changed |= hydrateDatabaseFromEms(database, company, salutation, lastName, phone, jobTitle, profile);
                if (changed) databaseRepository.save(database);
            }
            if (!email.isBlank() && !databaseEmailRepository.existsByDatabaseIdAndEmailIgnoreCase(database.getId(), email)) {
                DatabaseEmail address = DatabaseEmail.builder().database(database).email(email)
                        .emailType(participantDestination ? "personal" : "company").isCorporate(!participantDestination)
                        .isPrimary(database.getEmails() == null || database.getEmails().isEmpty()).build();
                databaseEmailRepository.save(address);
                if (database.getEmails() == null) database.setEmails(new ArrayList<>());
                database.getEmails().add(address);
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
            java.time.LocalDateTime emsCreatedAt = parseEmsDateTime(createdAtObj, event.getEmsSource());

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
                ep.setPreEventApprovalStatus(targetApproval);

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
                        .preEventApprovalStatus(targetApproval)
                        .reminderHariH(isCheckedIn ? "on_location" : null)
                        .notes("[Origin: EMS Sync] " + approvalTag)
                        .createdAt(emsCreatedAt != null ? emsCreatedAt : java.time.LocalDateTime.now())
                        .requestedAt(emsCreatedAt != null ? emsCreatedAt : java.time.LocalDateTime.now())
                        .build();
                EventParticipant epSaved = eventParticipantRepository.save(ep);
                currentEventParticipants.add(epSaved);

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

    private boolean hydrateDatabaseFromEms(Database database, Company company, String salutation, String lastName, String phone, String jobTitle, Map profile) {
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
        if (!isBlank(phone)) {
            String merged = ContactPhone.merge(database.getMobilePhone(), phone);
            String normalized = ContactPhone.primary(merged);
            if (!merged.equals(database.getMobilePhone()) || !normalized.equals(safe(database.getNormalizedPhone()))) {
                database.setMobilePhone(merged); database.setNormalizedPhone(normalized); updated = true;
            }
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

        return updated;
    }

    private boolean shouldUseEmsValue(String current, String emsValue) {
        if (isBlank(emsValue)) return false;
        if (isBlankOrDash(current)) return true;
        return normalizeText(emsValue).contains(normalizeText(current)) && emsValue.trim().length() > current.trim().length();
    }

    private String identityName(String value) {
        return java.text.Normalizer.normalize(safe(value), java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+", " ").trim();
    }

    private String normalizeText(String value) {
        return safe(value).toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    private String registrationContactKey(Registration registration) {
        String identity = registration.email().isBlank() ? "phone:" + ContactPhone.primary(registration.phone()) : registration.email();
        return identity + "\u0000" + identityName(registration.firstName() + " " + registration.lastName());
    }

    private String normalizePhone(String value) {
        return ContactPhone.primary(value);
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

    private java.time.LocalDateTime parseEmsDateTime(Object dateObj, EmsSource source) {
        if (dateObj == null) return null;
        String str = dateObj.toString().trim();
        if (str.isEmpty() || "null".equalsIgnoreCase(str)) return null;
        try {
            if (source == EmsSource.V2) {
                return java.time.Instant.parse(str).atZone(java.time.ZoneId.of("Asia/Jakarta")).toLocalDateTime();
            }
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
