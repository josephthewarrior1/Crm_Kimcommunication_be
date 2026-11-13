package com.crm.service;

import com.crm.domain.*;
import com.crm.repository.*;
import com.crm.util.ContactEmailCategory;
import com.crm.util.ContactPhone;
import com.crm.util.EventApprovalPolicy;
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

    @Value("${EMS_ADMIN_EMAIL:}")
    private String emsAdminEmail;

    @Value("${EMS_ADMIN_PASSWORD:}")
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
        if (isBlank(emsAdminEmail) || isBlank(emsAdminPassword)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Konfigurasi login EMS lama belum lengkap. Isi EMS_ADMIN_EMAIL dan EMS_ADMIN_PASSWORD di server.");
        }
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
        String token = getEmsAccessToken();
        try {
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
        String token = getEmsAccessToken();
        try {
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

    private record IdentityReview(String message, boolean clientDecline) {}
    private record SyncPlan(List<Map> accepted, Map<String, Database> matches, Map<String, String> aliases,
                            Map<String, IdentityReview> identityReviews, Map<String, Map<String, String>> contactSnapshots,
                            Map<String, String> effectiveApprovals, Set<String> sharedEmails, Map<String, Object> preview) {}

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
        List<Database> contacts = databaseRepository.findAll().stream()
                .filter(contact -> DatabaseDestination.effective(contact.getDatabaseDestination()) == destination).toList();
        List<EventParticipant> participants = eventParticipantRepository.findByEventId(event.getId()).stream()
                .filter(participant -> participant.getDatabase() != null
                        && DatabaseDestination.effective(participant.getDatabase().getDatabaseDestination()) == destination).toList();
        Map<String, Database> matches = new HashMap<>();
        List<Map> accepted = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, String> aliases = new HashMap<>();
        Map<Integer, String> plannedKeys = new HashMap<>();
        List<Integer> acceptedRows = new ArrayList<>();
        Map<String, String> phoneAliases = new HashMap<>();
        Map<String, String> companyNames = new HashMap<>();
        Map<String, Set<String>> incomingNames = new HashMap<>();
        Map<String, Set<String>> incomingPhoneNames = new HashMap<>();
        Set<String> incompleteSharedEmails = new HashSet<>();
        Map<String, Set<String>> plannedSharedPhones = new HashMap<>();
        Map<String, IdentityReview> identityReviews = new HashMap<>();
        for (Object item : (List) raw) {
            if (!(item instanceof Map)) continue;
            Registration incoming = registration((Map) item);
            if (!identityName(incoming.firstName() + " " + incoming.lastName()).isBlank()) {
                for (String number : ContactPhone.normalizedNumbers(incoming.phone())) incomingPhoneNames.computeIfAbsent(number, ignored -> new HashSet<>())
                        .add(identityName(incoming.firstName() + " " + incoming.lastName()));
            }
            boolean publicCompanyEmail = destination == DatabaseDestination.COMPANY && ContactEmailCategory.isPublicPersonalEmail(incoming.email());
            if (ContactEmailCategory.isValidEmail(incoming.email()) && (!incoming.firstName().isBlank() || publicCompanyEmail)) {
                incomingNames.computeIfAbsent(incoming.email(), ignored -> new HashSet<>())
                        .add(identityName(incoming.firstName() + " " + incoming.lastName()));
            }
            if (publicCompanyEmail && (identityName(incoming.firstName() + " " + incoming.lastName()).isBlank()
                    || ContactPhone.normalizedNumbers(incoming.phone()).isEmpty())) incompleteSharedEmails.add(incoming.email());
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
            else if (!email.isBlank() && (!ContactEmailCategory.isValidEmail(email) || email.length() > 255)) problem = "Email peserta tidak valid.";
            else if (!isBlank(registration.phone()) && !ContactPhone.isUsable(registration.phone())) problem = "Mobile Phone peserta tidak valid.";
            if (problem.isEmpty()) {
                Map<String, String> snapshot = contactSnapshot(event, registration);
                for (Map.Entry<String, String> field : snapshot.entrySet()) {
                    int limit = Set.of("positionLevel", "specialityDivision").contains(field.getKey()) ? 100
                            : Set.of("salutation", "mobilePhone").contains(field.getKey()) ? 50 : 255;
                    if (field.getValue().codePointCount(0, field.getValue().length()) > limit) { problem = field.getKey() + " melebihi panjang yang diizinkan (" + limit + ")."; break; }
                }
            }
            if (problem.isEmpty() && destination == DatabaseDestination.PARTICIPANT) problem = participantFieldProblem(registration);
            if (problem.isEmpty() && incomingNames.getOrDefault(email, Set.of()).size() > 1
                    && (destination == DatabaseDestination.PARTICIPANT || name.isBlank())) {
                problem = "Email berulang pada nama berbeda di data EMS. Baris ini ditahan untuk ditinjau.";
            }
            if (problem.isEmpty() && ContactPhone.normalizedNumbers(registration.phone()).stream()
                    .anyMatch(number -> incomingPhoneNames.getOrDefault(number, Set.of()).size() > 1)) problem = "HP cocok dengan beberapa nama dalam data EMS; tinjau sebelum sync.";
            List<Database> owners = new ArrayList<>();
            List<Map<String, Object>> crmMatches = new ArrayList<>();
            IdentityReview identityReview = new IdentityReview("", false);
            Set<String> mailboxNames = new HashSet<>(incomingNames.getOrDefault(email, Set.of()));
            boolean publicCompanyEmail = destination == DatabaseDestination.COMPANY && ContactEmailCategory.isPublicPersonalEmail(email);
            if (problem.isEmpty()) {
                Set<String> numbers = ContactPhone.normalizedNumbers(registration.phone());
                List<Database> contactOwners = new ArrayList<>();
                List<Database> conflictingOwners = new ArrayList<>();
                for (Database contact : contacts) {
                    boolean sameEmail = !email.isBlank() && contact.getEmails() != null && contact.getEmails().stream().filter(Objects::nonNull)
                            .anyMatch(address -> email.equals(safe(address.getEmail()).trim().toLowerCase(Locale.ROOT)));
                    Set<String> storedNumbers = ContactPhone.normalizedNumbers(contact.getMobilePhone());
                    storedNumbers.addAll(ContactPhone.normalizedNumbers(contact.getNormalizedPhone()));
                    boolean samePhone = numbers.stream().anyMatch(storedNumbers::contains);
                    if (!sameEmail && !samePhone) continue;
                    contactOwners.add(contact);
                    List<String> matchedBy = new ArrayList<>();
                    if (sameEmail) matchedBy.add("email");
                    if (samePhone) matchedBy.add("phone");
                    Map<String, Object> candidate = new LinkedHashMap<>();
                    candidate.put("databaseId", contact.getId());
                    candidate.put("name", (safe(contact.getFirstName()) + " " + safe(contact.getLastName())).trim());
                    candidate.put("mobilePhone", safe(contact.getMobilePhone()));
                    candidate.put("companyName", destination == DatabaseDestination.PARTICIPANT ? safe(contact.getInstitutionName())
                            : contact.getCompany() == null ? "" : safe(contact.getCompany().getName()));
                    candidate.put("matchedBy", matchedBy);
                    crmMatches.add(candidate);
                    String retainedName = identityName(safe(contact.getFirstName()) + " " + safe(contact.getLastName()));
                    if (sameEmail) mailboxNames.add(retainedName);
                    if (name.isBlank() || retainedName.isBlank() || retainedName.equals(identityName(name))) owners.add(contact);
                    else if (samePhone || destination == DatabaseDestination.PARTICIPANT
                            || ContactEmailCategory.isPublicPersonalEmail(email) && !(publicCompanyEmail && sameEmail
                            && !numbers.isEmpty() && !storedNumbers.isEmpty())) {
                        problem = "Email atau HP sudah terdaftar pada nama berbeda. Tinjau profil sebelum sync.";
                        conflictingOwners.add(contact);
                    }
                }
                if (owners.size() > 1 || owners.size() == 1 && contactOwners.size() > 1
                        && identityName(safe(owners.get(0).getFirstName()) + " " + safe(owners.get(0).getLastName())).isBlank()) {
                    problem = "Kontak cocok dengan beberapa profil. Rapikan duplikat sebelum sync.";
                }
                if (destination == DatabaseDestination.COMPANY) {
                    List<Database> exact = contactOwners.stream().filter(contact -> !identityName(name).isBlank()
                            && identityName(name).equals(identityName(safe(contact.getFirstName()) + " " + safe(contact.getLastName())))
                            && sameRegistrationEmail(contact, email) && sameRegistrationPhone(contact, numbers)).toList();
                    Database resolved = exact.size() == 1 ? exact.get(0) : null;
                    boolean repeatedName = false;
                    if (resolved == null && contactOwners.size() == 1 && !identityName(name).isBlank()) {
                        Database candidate = contactOwners.get(0);
                        String storedName = safe(candidate.getFirstName()) + " " + safe(candidate.getLastName());
                        repeatedName = !identityName(name).equals(identityName(storedName))
                                && repeatedWholeName(name, storedName) && sameRegistrationPhone(candidate, numbers);
                        if (repeatedName) resolved = candidate;
                    }
                    if (resolved != null) {
                        Database selected = resolved;
                        List<Database> phoneAliasesForReview = contactOwners.stream()
                                .filter(contact -> !Objects.equals(contact.getId(), selected.getId()) && sameRegistrationPhone(contact, numbers)).toList();
                        if ((repeatedName || !phoneAliasesForReview.isEmpty())
                                && conflictingOwners.stream().allMatch(contact -> sameRegistrationPhone(contact, numbers))
                                && owners.stream().allMatch(contact -> Objects.equals(contact.getId(), selected.getId())
                                    || !identityName(safe(contact.getFirstName()) + " " + safe(contact.getLastName())).isBlank()
                                        && sameRegistrationPhone(contact, numbers))) {
                            owners = new ArrayList<>(List.of(selected));
                            problem = "";
                            if (repeatedName) identityReview = new IdentityReview(
                                    "Nama berulang pada profil CRM; nama dan HP cocok. Profil asli dipertahankan.", false);
                        }
                    }
                }
                // EMS can correct a stale display name only when its identity has one owner.
                if (contactOwners.size() == 1) {
                    Database candidate = contactOwners.get(0);
                    boolean uniquePersonalEmail = incomingNames.getOrDefault(email, Set.of()).size() <= 1
                            && hasPersonalRegistrationEmail(candidate, email);
                    if (sameRegistrationPhone(candidate, numbers) || uniquePersonalEmail) {
                        owners = new ArrayList<>(List.of(candidate));
                        problem = "";
                        if (incomingNames.getOrDefault(email, Set.of()).size() <= 1) {
                            mailboxNames.clear();
                            mailboxNames.add(identityName(name));
                        }
                    } else if (numbers.isEmpty() && owners.isEmpty()) {
                        problem = "Email kantor bersama belum cukup untuk menentukan pemilik nama berbeda. Isi HP yang benar lalu tinjau profil.";
                    }
                }
                boolean emailOwned = contactOwners.stream().anyMatch(contact -> sameRegistrationEmail(contact, email));
                boolean phoneOwned = contactOwners.stream().anyMatch(contact -> sameRegistrationPhone(contact, numbers));
                if (emailOwned && phoneOwned && contactOwners.stream().noneMatch(contact -> sameRegistrationEmail(contact, email) && sameRegistrationPhone(contact, numbers))) {
                    problem = "Email dan HP mengarah ke pemilik profil yang berbeda. Periksa identitas sebelum menimpa data kontak.";
                    owners.clear();
                }
            }
            Database match = owners.size() == 1 ? owners.get(0) : null;
            boolean sharedCompanyEmail = publicCompanyEmail && mailboxNames.size() > 1;
            if (problem.isEmpty() && sharedCompanyEmail && incompleteSharedEmails.contains(email)) {
                problem = "Email bersama memerlukan nama dan HP yang jelas untuk masing-masing orang. Tinjau sebelum sync.";
            }
            if (problem.isEmpty() && match != null && Boolean.FALSE.equals(match.getIsActive())) problem = "Kontak nonaktif; tinjau sebelum sync.";
            Database selectedOwner = match;
            if (problem.isEmpty() && !email.isBlank() && (match == null || !sameRegistrationEmail(match, email))
                    && contacts.stream().anyMatch(contact -> (selectedOwner == null || !Objects.equals(contact.getId(), selectedOwner.getId()))
                        && hasPersonalRegistrationEmail(contact, email))) {
                problem = "Email pribadi sudah dimiliki profil lain. Periksa identitas pemilik email sebelum sync.";
            }
            if (problem.isEmpty() && !isBlank(registration.phone())) {
                try { ContactPhone.merge("", registration.phone()); }
                catch (IllegalArgumentException invalid) { problem = invalid.getMessage(); }
            }
            Map<String, Object> previewRow = new LinkedHashMap<>();
            previewRow.put("rowNum", row); previewRow.put("name", name); previewRow.put("email", email);
            previewRow.put("phone", safe(registration.phone()));
            previewRow.put("emsStatus", Objects.toString(((Map) item).get("status"), ""));
            previewRow.put("emsApprovalStatus", emsApprovalStatus(event, (Map) item));
            previewRow.put("crmMatches", crmMatches);
            if (match != null && (crmMatches.size() == 1 || problem.isEmpty())) addCrmApprovalPreview(previewRow, match, participants);
            previewRow.put("companyReviewRequired", false);
            previewRow.put("identityReviewMessage", "");
            previewRow.put("clientDeclineReviewRequired", false);
            previewRow.put("crmCompanyName", match != null && match.getCompany() != null ? safe(match.getCompany().getName()) : "");
            previewRow.put("emsCompanyName", safe(registration.companyName()));
            if (!problem.isEmpty()) {
                if (!crmMatches.isEmpty()) problem += " Profil CRM terkait: " + crmMatches.stream()
                        .map(candidate -> (isBlank((String) candidate.get("name")) ? "Nama belum diisi" : candidate.get("name"))
                                + " (#" + candidate.get("databaseId") + ")")
                        .collect(java.util.stream.Collectors.joining(", "))
                        + ". Periksa identitas pemilik email/HP di CRM dan data EMS, lalu muat ulang preview.";
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
                Database retained = match != null ? match : knownMatch;
                if (retained != null) addCrmApprovalPreview(previewRow, retained, participants);
                if (retained != null && (sharedCompanyEmail || plannedSharedPhones.containsKey(canonicalKey))) {
                    Set<String> storedNumbers = ContactPhone.normalizedNumbers(retained.getMobilePhone());
                    storedNumbers.addAll(ContactPhone.normalizedNumbers(retained.getNormalizedPhone()));
                    if (sharedCompanyEmail && (identityName(safe(retained.getFirstName()) + " " + safe(retained.getLastName())).isBlank()
                            || Collections.disjoint(storedNumbers, ContactPhone.normalizedNumbers(registration.phone())))
                            || plannedSharedPhones.containsKey(canonicalKey) && Collections.disjoint(storedNumbers, plannedSharedPhones.get(canonicalKey))) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nama pada email bersama belum cocok dengan HP profil. Tinjau sebelum sync.");
                    }
                }
                if (sharedCompanyEmail && match == null && knownMatch == null) {
                    Set<String> numbers = ContactPhone.normalizedNumbers(registration.phone());
                    Set<String> overlap = plannedSharedPhones.computeIfAbsent(canonicalKey, ignored -> new HashSet<>(numbers));
                    overlap.retainAll(numbers);
                    if (overlap.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Nama dan email bersama memiliki beberapa HP tanpa profil yang pasti. Tinjau sebelum sync.");
                }
                if (knownMatch != null && match != null && !Objects.equals(knownMatch.getId(), match.getId())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nama dan nomor cocok dengan beberapa profil lama. Tinjau duplikat sebelum sync.");
                }
                if (destination == DatabaseDestination.COMPANY && retained != null) {
                    // Retained phones still belong to this profile even if EMS later changes its phone.
                    Set<String> relatedPhones = ContactPhone.normalizedNumbers(registration.phone());
                    relatedPhones.addAll(ContactPhone.normalizedNumbers(retained.getMobilePhone()));
                    relatedPhones.addAll(ContactPhone.normalizedNumbers(retained.getNormalizedPhone()));
                    List<Database> relatedAliases = contacts.stream().filter(contact -> !Objects.equals(contact.getId(), retained.getId())
                            && sameRegistrationPhone(contact, relatedPhones)).toList();
                    if (!relatedAliases.isEmpty()) {
                        Set<Long> aliasIds = relatedAliases.stream().map(Database::getId).collect(java.util.stream.Collectors.toSet());
                        boolean clientDecline = participants.stream().anyMatch(participant -> aliasIds.contains(participant.getDatabase().getId())
                                && Set.of("decline", "declined").contains(safe(participant.getConfirmationStatus()).trim().toLowerCase(Locale.ROOT)));
                        String message = "HP pada profil yang dipilih juga ditemukan pada profil lain: "
                                + relatedAliases.stream().map(contact -> (safe(contact.getFirstName()) + " " + safe(contact.getLastName())).trim())
                                    .distinct().sorted().collect(java.util.stream.Collectors.joining(", ")) + ". Profil tidak digabung.";
                        if (clientDecline) message += " Ada keputusan Client Decline pada profil terkait; tertahan dari Pre-Event dan Reminder sampai ditinjau.";
                        identityReview = mergeIdentityReview(identityReview, new IdentityReview(message, clientDecline));
                    }
                }
                boolean exists = match != null || seen.contains(canonicalKey);
                String crmCompanyName = retained != null
                        ? retained.getCompany() == null ? "" : safe(retained.getCompany().getName())
                        : companyNames.getOrDefault(canonicalKey, safe(registration.companyName()));
                companyNames.putIfAbsent(canonicalKey, crmCompanyName);
                boolean reviewCompany = companyReviewRequired(destination, crmCompanyName, registration.companyName());
                identityReview = mergeIdentityReview(identityReviews.get(canonicalKey), identityReview);
                identityReviews.put(canonicalKey, identityReview);
                previewRow.put("identityReviewMessage", identityReview.message());
                previewRow.put("clientDeclineReviewRequired", identityReview.clientDecline());
                previewRow.put("companyReviewRequired", reviewCompany);
                previewRow.put("crmCompanyName", crmCompanyName);
                aliases.put(key, canonicalKey);
                for (String phoneKey : phoneKeys) phoneAliases.put(phoneKey, canonicalKey);
                previewRow.put("status", exists ? "EXISTING" : "NEW");
                String message = exists ? "Profil dipakai kembali; riwayat event dipertahankan." : "Profil baru di tujuan yang dipilih.";
                message += " Approval setelah sync: " + emsApprovalStatus(event, (Map) item) + ".";
                if (sharedCompanyEmail) message += " Email bersama; profil dipisahkan berdasarkan nama dan HP.";
                if (reviewCompany) message += " Perusahaan di registrasi berbeda; perlu ditinjau. Profil dan perusahaan CRM dipertahankan.";
                if (isBlank(registration.phone())) message += " HP/WhatsApp belum diisi; peserta belum bisa ditelepon.";
                previewRow.put("message", message);
                if (match != null) { previewRow.put("existingDatabaseId", match.getId()); matches.put(canonicalKey, match); }
                if (exists) existing++; else created++;
                plannedKeys.put(row, canonicalKey); acceptedRows.add(row);
                seen.add(canonicalKey); accepted.add((Map) item);
            }
            rows.add(previewRow);
        }
        // Several EMS registrations may map to one contact; never choose approval by row order.
        java.util.function.Function<Integer, String> profileKey = number -> {
            String key = plannedKeys.get(number);
            Database owner = matches.get(key);
            return owner == null ? "new:" + key : "stored:" + owner.getId();
        };
        Map<String, Set<String>> profileApprovals = new HashMap<>();
        Map<String, List<Integer>> profileRows = new HashMap<>();
        Map<Integer, Map> sourceRows = new HashMap<>();
        for (int i = 0; i < accepted.size(); i++) {
            profileApprovals.computeIfAbsent(profileKey.apply(acceptedRows.get(i)), ignored -> new HashSet<>())
                    .add(emsApprovalStatus(event, accepted.get(i)));
            profileRows.computeIfAbsent(profileKey.apply(acceptedRows.get(i)), ignored -> new ArrayList<>()).add(acceptedRows.get(i));
            sourceRows.put(acceptedRows.get(i), accepted.get(i));
        }
        Map<String, String> effectiveApprovals = new HashMap<>();
        for (Map.Entry<String, List<Integer>> group : profileRows.entrySet()) {
            if (Set.of("approve", "pending").equals(profileApprovals.get(group.getKey()))
                    && group.getValue().stream().filter(number -> "approve".equals(emsApprovalStatus(event, sourceRows.get(number)))).count() == 1
                    && sameDuplicateRegistrationIdentity(group.getValue(), sourceRows)) effectiveApprovals.put(group.getKey(), "approve");
        }
        Set<String> approvalConflicts = profileApprovals.entrySet().stream().filter(entry -> entry.getValue().size() > 1)
                .filter(entry -> !effectiveApprovals.containsKey(entry.getKey()))
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        for (Map<String, Object> previewRow : rows) {
            int number = (Integer) previewRow.get("rowNum");
            if (!plannedKeys.containsKey(number) || !approvalConflicts.contains(profileKey.apply(number))) continue;
            if ("NEW".equals(previewRow.get("status"))) created--; else existing--;
            rejected++;
            previewRow.put("status", "REJECTED");
            previewRow.put("companyReviewRequired", false);
            previewRow.put("identityReviewMessage", "");
            previewRow.put("clientDeclineReviewRequired", false);
            List<Integer> relatedRows = profileRows.get(profileKey.apply(number));
            List<String> statuses = profileApprovals.get(profileKey.apply(number)).stream().sorted().toList();
            previewRow.put("approvalConflictRows", relatedRows);
            previewRow.put("approvalConflictStatuses", statuses);
            previewRow.put("message", "Beberapa registrasi untuk kontak yang sama memiliki approval EMS berbeda. Baris "
                    + relatedRows.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "))
                    + " mengarah ke profil yang sama dengan status " + String.join(", ", statuses)
                    + ". Periksa registrasi mana yang benar atau perbaiki identitas yang bertabrakan di EMS, lalu muat ulang preview. Baris ini belum disinkronkan ke CRM.");
        }
        Map<String, Map<String, String>> contactSnapshots = new HashMap<>();
        Set<String> contactConflicts = new HashSet<>();
        for (Map.Entry<String, List<Integer>> group : profileRows.entrySet()) {
            if (approvalConflicts.contains(group.getKey())) continue;
            List<Integer> numbers = group.getValue().stream().filter(number -> !effectiveApprovals.containsKey(group.getKey())
                    || "approve".equals(emsApprovalStatus(event, sourceRows.get(number)))).toList();
            Map<Integer, Map<String, String>> snapshots = new HashMap<>();
            numbers.forEach(number -> snapshots.put(number, contactSnapshot(event, registration(sourceRows.get(number)))));
            boolean sameData = snapshots.values().stream().map(this::comparableContactSnapshot).distinct().count() == 1;
            List<Integer> latest = new ArrayList<>(numbers);
            if (!sameData) {
                Map<Integer, java.time.LocalDateTime> dates = new HashMap<>();
                numbers.forEach(number -> dates.put(number, contactTimestamp(event, sourceRows.get(number))));
                if (dates.values().stream().anyMatch(Objects::isNull)) { contactConflicts.add(group.getKey()); continue; }
                java.time.LocalDateTime newest = dates.values().stream().max(Comparator.naturalOrder()).orElseThrow();
                latest.removeIf(number -> !newest.equals(dates.get(number)));
                if (latest.stream().map(snapshots::get).map(this::comparableContactSnapshot).distinct().count() > 1) {
                    contactConflicts.add(group.getKey()); continue;
                }
            }
            // Equivalent source snapshots use a stable choice, never API row order.
            Map<String, String> snapshot = latest.stream().map(snapshots::get)
                    .min(Comparator.comparing(this::fingerprint)).orElseThrow();
            contactSnapshots.put(group.getKey(), snapshot);
        }
        for (Map<String, Object> previewRow : rows) {
            int number = (Integer) previewRow.get("rowNum");
            if ("REJECTED".equals(previewRow.get("status")) || !plannedKeys.containsKey(number)) continue;
            if (contactConflicts.contains(profileKey.apply(number))) {
                if ("NEW".equals(previewRow.get("status"))) created--; else existing--;
                rejected++; previewRow.put("status", "REJECTED");
                previewRow.put("companyReviewRequired", false); previewRow.put("identityReviewMessage", "");
                previewRow.put("clientDeclineReviewRequired", false);
                previewRow.put("message", "Beberapa registrasi mengarah ke profil yang sama tetapi data kontak berbeda tanpa urutan pembaruan yang pasti. Periksa waktu pembaruan dan data EMS, lalu muat ulang preview.");
            }
        }
        for (int index = accepted.size() - 1; index >= 0; index--) {
            String profile = profileKey.apply(acceptedRows.get(index));
            if (approvalConflicts.contains(profile) || contactConflicts.contains(profile)) accepted.remove(index);
        }
        Set<String> changedContacts = new HashSet<>();
        for (Map<String, Object> previewRow : rows) {
            if ("REJECTED".equals(previewRow.get("status"))) continue;
            String plannedKey = plannedKeys.get((Integer) previewRow.get("rowNum"));
            String resolvedApproval = effectiveApprovals.get(profileKey.apply((Integer) previewRow.get("rowNum")));
            if (resolvedApproval != null) {
                previewRow.put("preEventApprovalAfterSync", resolvedApproval);
                String acceptedRowsLabel = profileRows.get(profileKey.apply((Integer) previewRow.get("rowNum"))).stream()
                        .filter(number -> "approve".equals(emsApprovalStatus(event, sourceRows.get(number))))
                        .map(number -> "#" + number).collect(java.util.stream.Collectors.joining(", "));
                previewRow.put("message", Objects.toString(previewRow.get("message"), "")
                        .replace("Approval setelah sync: pending.", "Approval setelah sync: approve.")
                        + " Pendaftaran ganda dengan nama, HP, perusahaan, dan jabatan yang sama; registrasi Accepted baris "
                        + acceptedRowsLabel + " menjadi acuan CRM. Registrasi Pending tetap Pending di EMS; satu profil peserta CRM dipertahankan.");
            }
            IdentityReview review = identityReviews.get(plannedKey);
            Database owner = matches.get(plannedKey);
            String clientApproval = owner == null ? "pending" : participants.stream()
                    .filter(participant -> Objects.equals(participant.getDatabase().getId(), owner.getId()))
                    .map(EventParticipant::getConfirmationStatus).filter(Objects::nonNull).findFirst().orElse("pending");
            if (owner != null && !EventApprovalPolicy.isClientApproved(clientApproval)) {
                EventParticipant historicalHold = participants.stream()
                        .filter(participant -> Objects.equals(participant.getDatabase().getId(), owner.getId())
                                && Boolean.TRUE.equals(participant.getRawEmsClientDeclineReviewRequired())).findFirst().orElse(null);
                if (historicalHold != null) {
                    String message = historicalHold.getRawEmsIdentityReviewMessage();
                    review = mergeIdentityReview(review, new IdentityReview(isBlank(message)
                            ? "Keputusan Client Decline terkait sebelum pembaruan kontak tetap perlu ditinjau." : message, true));
                    identityReviews.put(plannedKey, review);
                }
            }
            String reviewMessage = EventApprovalPolicy.identityReviewMessage(review.message(), review.clientDecline(), clientApproval);
            previewRow.put("identityReviewMessage", reviewMessage);
            previewRow.put("clientDeclineReviewRequired", review.clientDecline() && !EventApprovalPolicy.isClientApproved(clientApproval));
            if (!reviewMessage.isBlank()) previewRow.put("message", previewRow.get("message") + " " + reviewMessage);
            if (owner != null) {
                String profile = profileKey.apply((Integer) previewRow.get("rowNum"));
                Set<String> emails = profileRows.get(profile).stream().map(sourceRows::get).map(this::registration)
                        .map(Registration::email).filter(address -> !address.isBlank()).collect(java.util.stream.Collectors.toCollection(TreeSet::new));
                List<Map<String, String>> updates = contactUpdates(owner, contactSnapshots.get(profile), emails);
                previewRow.put("contactUpdates", updates);
                if (!updates.isEmpty()) {
                    changedContacts.add(profile);
                    previewRow.put("message", previewRow.get("message") + " Data kontak yang terisi mengikuti snapshot EMS terbaru; kolom kosong dan riwayat tetap dipertahankan.");
                }
            }
        }
        List<Object> contactState = contacts.stream().sorted(Comparator.comparing(Database::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(contact -> (Object) Arrays.asList(contact.getId(), contact.getUpdatedAt(), contact.getDatabaseDestination(), contact.getIsActive(),
                        contact.getFirstName(), contact.getLastName(), contact.getMobilePhone(), contact.getNormalizedPhone(), contact.getCompany() == null ? null : contact.getCompany().getId(),
                        contact.getCompany() == null ? null : contact.getCompany().getName(),
                        contact.getInstitutionName(), contact.getSalutation(), contact.getPositionLevel(), contact.getSpecialityDivision(), contact.getJobTitle(),
                        contact.getOrganizationGroupName(), contact.getOrganizationBrandName(), contact.getOrganizationAddress(), contact.getOrganizationOfficePhone(),
                        contact.getOrganizationIndustry(), contact.getOrganizationSizeRevenue(), contact.getOrganizationSizeEmployee(), contact.getOrganizationBranchName(),
                        contact.getOrganizationPostalCode(), contact.getOrganizationWebsite(),
                        contact.getEmails() == null ? List.of() : contact.getEmails().stream().filter(Objects::nonNull)
                                .map(email -> Arrays.asList(email.getId(), safe(email.getEmail()), email.getEmailType(), email.getIsCorporate(), email.getIsPrimary()))
                                .sorted(Comparator.comparing(Objects::toString)).toList())).toList();
        List<Object> participantState = participants.stream().sorted(Comparator.comparing(EventParticipant::getId, Comparator.nullsFirst(Comparator.naturalOrder()))).map(participant -> (Object) Arrays.asList(participant.getId(),
                participant.getDatabase() == null ? null : participant.getDatabase().getId(), participant.getUpdatedAt(),
                participant.getEmsApprovalStatus(), participant.getPreEventApprovalStatus(), participant.getConfirmationStatus(),
                participant.getEmsCompanyName(), participant.getEmsCompanyReviewRequired(),
                participant.getRawEmsIdentityReviewMessage(), participant.getRawEmsClientDeclineReviewRequired())).toList();
        Map<String, Long> matchedIds = new TreeMap<>();
        matches.forEach((key, contact) -> matchedIds.put(key, contact.getId()));
        String token = fingerprint(Arrays.asList(event.getId(), event.getEmsEventId(), event.getEmsSource(), destination, raw, contactState, participantState, matchedIds, aliases, effectiveApprovals));
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("destination", destination); preview.put("totalRows", rows.size()); preview.put("newCount", created);
        preview.put("existingCount", existing); preview.put("rejectedCount", rejected);
        preview.put("contactUpdateCount", changedContacts.size());
        preview.put("uniqueContactCount", (int) rows.stream().filter(item -> !"REJECTED".equals(item.get("status")))
                .map(item -> profileKey.apply((Integer) item.get("rowNum"))).distinct().count());
        preview.put("companyReviewCount", (int) rows.stream().filter(item -> Boolean.TRUE.equals(item.get("companyReviewRequired"))).count());
        preview.put("identityReviewCount", (int) rows.stream().filter(item -> !safe((String) item.get("identityReviewMessage")).isBlank()).count());
        preview.put("clientDeclineReviewCount", (int) rows.stream().filter(item -> Boolean.TRUE.equals(item.get("clientDeclineReviewRequired"))).count());
        preview.put("rows", rows); preview.put("previewToken", token);
        Set<String> sharedEmails = new HashSet<>();
        Map<String, Set<String>> emailProfiles = new HashMap<>();
        for (Map item : accepted) {
            Registration source = registration(item);
            String key = aliases.getOrDefault(registrationContactKey(source), registrationContactKey(source));
            Database owner = matches.get(key);
            emailProfiles.computeIfAbsent(source.email(), ignored -> new HashSet<>()).add(owner == null ? "new:" + key : "stored:" + owner.getId());
        }
        emailProfiles.forEach((address, profiles) -> {
            if (profiles.size() > 1 || contacts.stream().anyMatch(contact -> contact.getEmails() != null && contact.getEmails().stream()
                    .anyMatch(stored -> address.equalsIgnoreCase(safe(stored.getEmail())) && "company".equalsIgnoreCase(stored.getEmailType())))) sharedEmails.add(address);
        });
        // Create a new profile from its Accepted registration before adding Pending duplicate email aliases.
        accepted.sort(Comparator.comparingInt(item -> {
            Registration source = registration(item);
            String key = aliases.getOrDefault(registrationContactKey(source), registrationContactKey(source));
            Database owner = matches.get(key);
            String profile = owner == null ? "new:" + key : "stored:" + owner.getId();
            return effectiveApprovals.containsKey(profile) && !"approve".equals(emsApprovalStatus(event, item)) ? 1 : 0;
        }));
        return new SyncPlan(accepted, matches, aliases, identityReviews, contactSnapshots, effectiveApprovals, sharedEmails, preview);
    }

    private boolean sameDuplicateRegistrationIdentity(List<Integer> rows, Map<Integer, Map> sourceRows) {
        Registration first = registration(sourceRows.get(rows.get(0)));
        String name = identityName(first.firstName() + " " + first.lastName());
        Set<String> phones = ContactPhone.normalizedNumbers(first.phone());
        String company = identityName(safe(first.companyName())), title = identityName(safe(first.jobTitle()));
        if (name.isBlank() || phones.isEmpty() || company.isBlank() || title.isBlank()) return false;
        return rows.stream().map(sourceRows::get).map(this::registration).allMatch(source ->
                name.equals(identityName(source.firstName() + " " + source.lastName()))
                        && phones.equals(ContactPhone.normalizedNumbers(source.phone()))
                        && company.equals(identityName(safe(source.companyName())))
                        && title.equals(identityName(safe(source.jobTitle()))));
    }

    private Map<String, String> contactSnapshot(Event event, Registration source) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (!isBlank(source.firstName())) {
            fields.put("firstName", source.firstName().trim());
            if (event.getEmsSource() == EmsSource.V2 && source.profile() != null
                    && !source.profile().containsKey("registered_last_name")) fields.put("lastName", "");
        }
        if (!isBlank(source.lastName())) fields.put("lastName", source.lastName().trim());
        if (!isBlank(source.salutation())) fields.put("salutation", source.salutation().trim());
        if (!isBlank(source.jobTitle())) fields.put("jobTitle", source.jobTitle().trim());
        if (!isBlank(source.phone())) fields.put("mobilePhone", ContactPhone.clean(source.phone()));
        if (source.profile() != null) {
            String level = firstProfileText(source.profile(), "job_level", "position_level");
            String division = firstProfileText(source.profile(), "division", "speciality_division", "department");
            if (!isBlank(level)) fields.put("positionLevel", PositionLevel.fromValue(level).getValue());
            if (!isBlank(division)) fields.put("specialityDivision", division);
        }
        return fields;
    }

    private String firstProfileText(Map profile, String... names) {
        for (String name : names) if (profile.get(name) != null && !isBlank(profile.get(name).toString())) return profile.get(name).toString().trim();
        return "";
    }

    private Map<String, String> comparableContactSnapshot(Map<String, String> source) {
        Map<String, String> values = new TreeMap<>(source);
        if (values.containsKey("firstName") || values.containsKey("lastName")) {
            values.put("name", identityName(values.getOrDefault("firstName", "") + " " + values.getOrDefault("lastName", "")));
            values.remove("firstName"); values.remove("lastName");
        }
        if (values.containsKey("mobilePhone")) values.put("mobilePhone", String.join(",", new TreeSet<>(ContactPhone.normalizedNumbers(values.get("mobilePhone")))));
        return values;
    }

    private java.time.LocalDateTime contactTimestamp(Event event, Map item) {
        java.time.LocalDateTime updated = parseEmsDateTime(item.get("updated_at"), event.getEmsSource());
        if (updated != null) return updated;
        return parseEmsDateTime(item.get("created_at"), event.getEmsSource());
    }

    private Map<String, String> storedContactSnapshot(Database contact) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("firstName", safe(contact.getFirstName())); fields.put("lastName", safe(contact.getLastName()));
        fields.put("salutation", safe(contact.getSalutation())); fields.put("jobTitle", safe(contact.getJobTitle()));
        fields.put("mobilePhone", safe(contact.getMobilePhone()));
        fields.put("positionLevel", contact.getPositionLevel() == null ? "" : contact.getPositionLevel().getValue());
        fields.put("specialityDivision", safe(contact.getSpecialityDivision()));
        return fields;
    }

    private Map<String, String> contactAfter(Database contact, Map<String, String> source) {
        Map<String, String> before = storedContactSnapshot(contact), after = new LinkedHashMap<>(before);
        if (source != null) after.putAll(source);
        if (identityName(before.get("firstName") + " " + before.get("lastName"))
                .equals(identityName(after.get("firstName") + " " + after.get("lastName")))) {
            after.put("firstName", before.get("firstName")); after.put("lastName", before.get("lastName"));
        }
        return after;
    }

    private List<Map<String, String>> contactUpdates(Database contact, Map<String, String> source, Set<String> incomingEmails) {
        Map<String, String> before = storedContactSnapshot(contact), after = contactAfter(contact, source);
        List<Map<String, String>> changes = new ArrayList<>();
        String oldName = (before.get("firstName") + " " + before.get("lastName")).trim();
        String newName = (after.get("firstName") + " " + after.get("lastName")).trim();
        if (!oldName.equals(newName)) changes.add(Map.of("field", "name", "before", oldName, "after", newName));
        for (String field : List.of("salutation", "jobTitle", "mobilePhone", "positionLevel", "specialityDivision")) {
            if (!before.get(field).equals(after.get(field))) changes.add(Map.of("field", field, "before", before.get(field), "after", after.get(field)));
        }
        Set<String> addresses = contact.getEmails() == null ? new TreeSet<>() : contact.getEmails().stream().filter(Objects::nonNull)
                .map(address -> safe(address.getEmail()).trim().toLowerCase(Locale.ROOT)).filter(address -> !address.isBlank())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        String oldEmails = String.join(", ", addresses);
        addresses.addAll(incomingEmails);
        String newEmails = String.join(", ", addresses);
        if (!oldEmails.equals(newEmails)) changes.add(Map.of("field", "email", "before", oldEmails, "after", newEmails));
        return changes;
    }

    private boolean hasPersonalRegistrationEmail(Database contact, String email) {
        return !email.isBlank() && contact.getEmails() != null && contact.getEmails().stream().filter(Objects::nonNull)
                .anyMatch(address -> email.equalsIgnoreCase(safe(address.getEmail()).trim())
                        && ("personal".equalsIgnoreCase(address.getEmailType()) || !"company".equalsIgnoreCase(address.getEmailType())
                            && !Boolean.TRUE.equals(address.getIsCorporate()) && ContactEmailCategory.isPublicPersonalEmail(email)));
    }

    private void addCrmApprovalPreview(Map<String, Object> row, Database contact, List<EventParticipant> participants) {
        participants.stream().filter(participant -> Objects.equals(participant.getDatabase().getId(), contact.getId()))
                .findFirst().ifPresent(participant -> {
                    row.put("crmPreEventApprovalStatus", participant.getPreEventApprovalStatus());
                    row.put("crmClientApprovalStatus", participant.getConfirmationStatus());
                });
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
        databaseRepository.flush();
        Map<String, Object> response = new LinkedHashMap<>(current.preview());
        response.put("count", count); response.put("message", "Sync selesai ke tujuan yang dipilih.");
        return response;
    }

    private int applyParticipants(Event event, SyncPlan plan) {
        List list = plan.accepted();
        Map<String, Database> matchedContacts = new HashMap<>(plan.matches());
        List<EventParticipant> currentEventParticipants = new ArrayList<>(eventParticipantRepository.findByEventId(event.getId()).stream()
                .filter(participant -> participant.getDatabase() != null
                        && DatabaseDestination.effective(participant.getDatabase().getDatabaseDestination()) == event.getSyncDestination()).toList());
        int syncedCount = 0;
        Set<String> updatedProfiles = new HashSet<>();
        Set<Long> changedPhoneProfiles = new HashSet<>();

        for (Object itemObj : list) {
            if (!(itemObj instanceof Map)) continue;
            Map item = (Map) itemObj;

            Registration registration = registration(item);
            Map profile = registration.profile();
            String email = registration.email(), salutation = registration.salutation();
            String firstName = registration.firstName(), lastName = registration.lastName(), phone = registration.phone();
            String companyName = registration.companyName(), jobTitle = registration.jobTitle(), industry = registration.industry();
            boolean participantDestination = event.getSyncDestination() == DatabaseDestination.PARTICIPANT;

            String contactKey = registrationContactKey(registration);
            contactKey = plan.aliases().getOrDefault(contactKey, contactKey);
            IdentityReview identityReview = plan.identityReviews().getOrDefault(contactKey, new IdentityReview("", false));
            Database database = matchedContacts.get(contactKey);
            Database plannedOwner = plan.matches().get(contactKey);
            String profileKey = plannedOwner == null ? "new:" + contactKey : "stored:" + plannedOwner.getId();
            if (database == null) {
                Company company = null;
                if (!participantDestination && !isBlank(companyName)) {
                    company = companyRepository.findByNameIgnoreCase(companyName).orElse(null);
                    if (company == null) company = companyRepository.save(Company.builder().name(companyName).industry(industry).build());
                }
                database = Database.builder().firstName("").lastName("")
                        .company(company).databaseType(DatabaseType.end_user)
                        .source(DatabaseSource.event_registration).isActive(true)
                        .databaseDestination(event.getSyncDestination()).institutionName(participantDestination ? companyName : null).build();
                if (participantDestination) hydrateParticipantOrganization(database, registration);
                database = databaseRepository.save(database);
                matchedContacts.put(contactKey, database);
            }
            if (updatedProfiles.add(profileKey)) {
                Map<String, String> source = plan.contactSnapshots().get(profileKey);
                Map<String, String> after = contactAfter(database, source);
                boolean derivedPhoneChanged = source != null && source.containsKey("mobilePhone")
                        && !safe(database.getNormalizedPhone()).equals(ContactPhone.primary(after.get("mobilePhone")));
                if (source != null && (!storedContactSnapshot(database).equals(after) || derivedPhoneChanged)) {
                    if (!safe(database.getFirstName()).equals(after.get("firstName"))) database.setFirstName(after.get("firstName"));
                    if (!safe(database.getLastName()).equals(after.get("lastName"))) database.setLastName(after.get("lastName"));
                    if (source.containsKey("salutation")) database.setSalutation(after.get("salutation"));
                    if (source.containsKey("jobTitle")) database.setJobTitle(after.get("jobTitle"));
                    if (source.containsKey("mobilePhone")) {
                        if (!safe(database.getMobilePhone()).equals(after.get("mobilePhone"))
                                || !safe(database.getNormalizedPhone()).equals(ContactPhone.primary(after.get("mobilePhone")))) changedPhoneProfiles.add(database.getId());
                        database.setMobilePhone(after.get("mobilePhone")); database.setNormalizedPhone(ContactPhone.primary(after.get("mobilePhone")));
                    }
                    if (source.containsKey("positionLevel")) database.setPositionLevel(PositionLevel.fromValue(source.get("positionLevel")));
                    if (source.containsKey("specialityDivision")) database.setSpecialityDivision(after.get("specialityDivision"));
                    databaseRepository.save(database);
                }
            }
            // Retain old email aliases; only a new, validated source address is added.
            if (!email.isBlank() && !databaseEmailRepository.existsByDatabaseIdAndEmailIgnoreCase(database.getId(), email)) {
                boolean personal = participantDestination || ContactEmailCategory.isPublicPersonalEmail(email) && !plan.sharedEmails().contains(email);
                DatabaseEmail address = DatabaseEmail.builder().database(database).email(email)
                        .emailType(personal ? "personal" : "company").isCorporate(!personal && !ContactEmailCategory.isPublicPersonalEmail(email))
                        .isPrimary(database.getEmails() == null || database.getEmails().isEmpty()).build();
                databaseEmailRepository.save(address);
                if (database.getEmails() == null) database.setEmails(new ArrayList<>());
                database.getEmails().add(address);
            }

            if (plan.effectiveApprovals().containsKey(profileKey) && "pending".equals(emsApprovalStatus(event, item))) {
                // The duplicate contributes its validated email, while Accepted owns event approval and attendance.
                syncedCount++;
                continue;
            }

            // EMS owns registration approval and attendance; client approval stays with CRM.
            Object checkedInAt = item.get("checked_in_at");
            Object verifiedAt = item.get("verified_at");

            Object createdAtObj = item.get("created_at");
            if (createdAtObj == null && profile != null) createdAtObj = profile.get("created_at");
            if (createdAtObj == null) createdAtObj = verifiedAt;
            java.time.LocalDateTime emsCreatedAt = parseEmsDateTime(createdAtObj, event.getEmsSource());

            boolean isCheckedIn = hasEmsTimestamp(checkedInAt);
            String preEventApproval = plan.effectiveApprovals().getOrDefault(profileKey, emsApprovalStatus(event, item));
            boolean isDeclined = "decline".equals(preEventApproval);
            AttendanceStatus attendanceStatus = isDeclined ? AttendanceStatus.cancelled
                    : isCheckedIn ? AttendanceStatus.attended : AttendanceStatus.registered;
            ParticipantStatus participantStatus = isDeclined ? ParticipantStatus.unable_to_attend
                    : isCheckedIn ? ParticipantStatus.confirm : ParticipantStatus.registered;

            // Link EventParticipant
            Optional<EventParticipant> existingParticipantOpt = eventParticipantRepository.findByEventIdAndDatabaseId(event.getId(), database.getId());
            if (existingParticipantOpt.isPresent()) {
                EventParticipant ep = existingParticipantOpt.get();
                ep.setAttendanceStatus(attendanceStatus);
                ep.setParticipantStatus(participantStatus);
                
                ep.setEmsApprovalStatus(preEventApproval);
                ep.setEmsIdentityReviewMessage(identityReview.message().isBlank() ? null : identityReview.message());
                ep.setEmsClientDeclineReviewRequired(identityReview.clientDecline());
                ep.setPreEventApprovalStatus(preEventApproval);
                ep.setEmsCompanyName(companyName);
                ep.setEmsCompanyReviewRequired(companyReviewRequired(event.getSyncDestination(),
                        database.getCompany() == null ? "" : database.getCompany().getName(), companyName));
                ep.setNotes(emsSyncNotes(ep.getNotes(), preEventApproval, isDeclined));
                if (isCheckedIn && !isDeclined) {
                    ep.setReminderHariH("on_location");
                } else if ("on_location".equals(ep.getReminderHariH())) {
                    ep.setReminderHariH(null);
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
                EventParticipant ep = EventParticipant.builder()
                        .event(event)
                        .database(database)
                        .attendanceStatus(attendanceStatus)
                        .participantStatus(participantStatus)
                        .confirmationStatus("pending")
                        .emsApprovalStatus(preEventApproval)
                        .emsIdentityReviewMessage(identityReview.message().isBlank() ? null : identityReview.message())
                        .emsClientDeclineReviewRequired(identityReview.clientDecline())
                        .preEventApprovalStatus(preEventApproval)
                        .emsCompanyName(companyName)
                        .emsCompanyReviewRequired(companyReviewRequired(event.getSyncDestination(),
                                database.getCompany() == null ? "" : database.getCompany().getName(), companyName))
                        .reminderHariH(isCheckedIn && !isDeclined ? "on_location" : null)
                        .notes(emsSyncNotes("", preEventApproval, isDeclined))
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
        Map<EventParticipant, String> retainedHolds = new LinkedHashMap<>();
        Set<Long> reviewEvents = new HashSet<>();
        for (Long databaseId : changedPhoneProfiles) for (EventParticipant participant : eventParticipantRepository.findByDatabaseId(databaseId)) {
            if (participant.getEvent() != null) reviewEvents.add(participant.getEvent().getId());
        }
        for (Long eventId : reviewEvents) for (EventParticipant participant : eventParticipantRepository.findByEventId(eventId)) {
            if (Boolean.TRUE.equals(participant.getRawEmsClientDeclineReviewRequired())
                    && !EventApprovalPolicy.isClientApproved(participant.getConfirmationStatus())) retainedHolds.put(participant, participant.getRawEmsIdentityReviewMessage());
        }
        reviewEvents.forEach(eventId -> EmsIdentityReview.refresh(eventParticipantRepository, eventId));
        retainedHolds.forEach((participant, message) -> {
            if (!EventApprovalPolicy.isClientApproved(participant.getConfirmationStatus())) {
                participant.setEmsClientDeclineReviewRequired(true);
                participant.setEmsIdentityReviewMessage(isBlank(message) ? "Keputusan Client Decline terkait sebelum pembaruan kontak tetap perlu ditinjau." : message);
                eventParticipantRepository.save(participant);
            }
        });
        return syncedCount;
    }

    private boolean companyReviewRequired(DatabaseDestination destination, String crmCompanyName, String emsCompanyName) {
        return destination == DatabaseDestination.COMPANY && !isBlank(emsCompanyName)
                && !normalizeText(crmCompanyName).equals(normalizeText(emsCompanyName));
    }

    private boolean hasEmsTimestamp(Object value) {
        return value != null && !value.toString().isBlank() && !"null".equalsIgnoreCase(value.toString().trim());
    }

    private String emsApprovalStatus(Event event, Map item) {
        String status = String.valueOf(item.get("status")).trim().toLowerCase(Locale.ROOT);
        String payment = String.valueOf(item.get("payment_status")).trim().toLowerCase(Locale.ROOT);
        if (hasEmsTimestamp(item.get("declined_at")) || Set.of("decline", "declined").contains(status)
                || Set.of("decline", "declined").contains(payment)) return "decline";
        if (Set.of("accepted", "approve", "approved").contains(status)) return "approve";
        // Explicit PENDING/unknown states always win over retained verification/check-in dates.
        if (!status.isBlank() && !"null".equals(status)) return "pending";
        if (event.getEmsSource() == EmsSource.V2) return "pending";
        return parseEmsDateTime(item.get("verified_at"), event.getEmsSource()) != null ? "approve" : "pending";
    }

    private String emsSyncNotes(String notes, String preEventApproval, boolean declined) {
        String updated = safe(notes).replaceAll("(?i)\\[EMS Declined\\]\\s*", "").trim();
        if (!updated.contains("[Origin: EMS Sync]") && !updated.contains("[EMS]")) updated = "[Origin: EMS Sync] " + updated;
        String approvalTag = "[PreEventApproval: " + preEventApproval + "]";
        if (java.util.regex.Pattern.compile("(?i)\\[PreEventApproval:").matcher(updated).find()) {
            updated = updated.replaceAll("(?i)\\[PreEventApproval:\\s*([^\\]]+)\\]", approvalTag);
        } else {
            updated = approvalTag + " " + updated;
        }
        return (declined ? "[EMS Declined] " + updated : updated).trim();
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

    private String registrationValue(Registration registration, String... keys) {
        for (Map source : List.of(registration.profile() == null ? Map.of() : registration.profile(), registration.item())) {
            for (String key : keys) {
                Object value = source.get(key);
                if ((value instanceof String || value instanceof Number) && !isBlank(value.toString())) return value.toString().trim();
            }
        }
        return "";
    }

    private Map<String, String> participantOrganization(Registration registration) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("group", registrationValue(registration, "organization_group_name", "group_name", "holding_company"));
        fields.put("brand", registrationValue(registration, "organization_brand_name", "brand_name"));
        fields.put("address", registrationValue(registration, "organization_address", "address", "company_address"));
        fields.put("officePhone", registrationValue(registration, "organization_office_phone", "office_phone", "company_phone"));
        fields.put("industry", isBlank(registration.industry()) ? registrationValue(registration, "organization_industry") : registration.industry());
        fields.put("revenue", registrationValue(registration, "organization_size_revenue", "company_size_revenue", "company_size_revenue_amount"));
        fields.put("employee", registrationValue(registration, "organization_size_employee", "company_size_employee"));
        fields.put("branch", registrationValue(registration, "organization_branch_name", "branch_name", "branch"));
        fields.put("postal", registrationValue(registration, "organization_postal_code", "postal_code"));
        fields.put("website", registrationValue(registration, "organization_website", "company_website", "website"));
        return fields;
    }

    private String participantFieldProblem(Registration registration) {
        Map<String, String> fields = participantOrganization(registration);
        for (String key : List.of("group", "brand", "branch", "industry", "revenue", "employee", "officePhone", "postal")) {
            int limit = switch (key) { case "industry", "revenue", "employee" -> 100; case "officePhone" -> 50; case "postal" -> 20; default -> 255; };
            String value = fields.get(key);
            if (value.codePointCount(0, value.length()) > limit) return "Data organisasi peserta (" + key + ") maksimal " + limit + " karakter.";
        }
        for (String value : List.of(safe(registration.companyName()), safe(registration.salutation()), registration.firstName(),
                registration.lastName(), safe(registration.jobTitle()), registrationValue(registration, "division", "speciality_division", "department"))) {
            if (value.codePointCount(0, value.length()) > 255) return "Nama, instansi, dan data profesi peserta maksimal 255 karakter.";
        }
        return "";
    }

    private void hydrateParticipantOrganization(Database database, Registration registration) {
        Map<String, String> fields = participantOrganization(registration);
        database.setCompany(null); database.setBranch(null);
        if (isBlank(database.getOrganizationGroupName()) && !fields.get("group").isBlank()) database.setOrganizationGroupName(fields.get("group"));
        if (isBlank(database.getOrganizationBrandName()) && !fields.get("brand").isBlank()) database.setOrganizationBrandName(fields.get("brand"));
        if (isBlank(database.getOrganizationAddress()) && !fields.get("address").isBlank()) database.setOrganizationAddress(fields.get("address"));
        if (isBlank(database.getOrganizationOfficePhone()) && !fields.get("officePhone").isBlank()) database.setOrganizationOfficePhone(fields.get("officePhone"));
        if (isBlank(database.getOrganizationIndustry()) && !fields.get("industry").isBlank()) database.setOrganizationIndustry(fields.get("industry"));
        if (isBlank(database.getOrganizationSizeRevenue()) && !fields.get("revenue").isBlank()) database.setOrganizationSizeRevenue(fields.get("revenue"));
        if (isBlank(database.getOrganizationSizeEmployee()) && !fields.get("employee").isBlank()) database.setOrganizationSizeEmployee(fields.get("employee"));
        if (isBlank(database.getOrganizationBranchName()) && !fields.get("branch").isBlank()) database.setOrganizationBranchName(fields.get("branch"));
        if (isBlank(database.getOrganizationPostalCode()) && !fields.get("postal").isBlank()) database.setOrganizationPostalCode(fields.get("postal"));
        if (isBlank(database.getOrganizationWebsite()) && !fields.get("website").isBlank()) database.setOrganizationWebsite(fields.get("website"));
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

    private boolean sameRegistrationEmail(Database contact, String email) {
        return !email.isBlank() && contact.getEmails() != null && contact.getEmails().stream().filter(Objects::nonNull)
                .anyMatch(address -> email.equals(safe(address.getEmail()).trim().toLowerCase(Locale.ROOT)));
    }

    private IdentityReview mergeIdentityReview(IdentityReview first, IdentityReview second) {
        if (first == null) return second;
        Set<String> messages = new TreeSet<>(Arrays.asList(first.message().split("\n")));
        messages.addAll(Arrays.asList(second.message().split("\n")));
        messages.remove("");
        return new IdentityReview(String.join("\n", messages), first.clientDecline() || second.clientDecline());
    }

    private boolean sameRegistrationPhone(Database contact, Set<String> numbers) {
        Set<String> stored = ContactPhone.normalizedNumbers(contact.getMobilePhone());
        stored.addAll(ContactPhone.normalizedNumbers(contact.getNormalizedPhone()));
        return !Collections.disjoint(stored, numbers);
    }

    private boolean repeatedWholeName(String incoming, String stored) {
        // Only collapse a repeated whole name, preserving punctuation and all other identity rules.
        String first = identityName(incoming), second = identityName(stored);
        return !first.isBlank() && !second.isBlank()
                && (first.equals(second + " " + second) || second.equals(first + " " + first));
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
