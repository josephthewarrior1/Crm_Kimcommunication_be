package com.crm.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Read-only EMS-API adapter. The only upstream write is IAM authentication. */
@Service
public class EmsV2Client {
    @Value("${EMS_V2_API_URL:}") private String apiUrl;
    @Value("${EMS_V2_IAM_URL:}") private String iamUrl;
    @Value("${EMS_V2_ADMIN_EMAIL:}") private String adminEmail;
    @Value("${EMS_V2_ADMIN_PASSWORD:}") private String adminPassword;
    @Value("${EMS_V2_ACCESS_TOKEN:}") private String configuredToken;

    private final RestTemplate http;
    private String cachedToken;
    private Instant tokenExpiresAt = Instant.EPOCH;
    private static final Set<String> EVENT_STATUSES = Set.of("DRAFT", "PUBLISHED", "ONGOING", "COMPLETED", "CANCELLED");
    private static final Set<String> REGISTRATION_STATUSES = Set.of("PENDING", "ACCEPTED", "DECLINED");
    private static final Set<String> ATTENDANCE_STATUSES = Set.of("PRESENT", "ABSENT", "CHECKED_OUT");
    private static final List<String> INDUSTRY_KEYS = List.of("industry", "company_industry", "business_sector");

    public EmsV2Client() {
        this(new RestTemplate(requestFactory()));
    }

    EmsV2Client(RestTemplate http) {
        this.http = http;
    }

    static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        return factory;
    }

    public List<Map<String, Object>> getEvents() {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Map<String, Object> row : rows(get("/api/events?archived=false"))) {
            Long id = id(row.get("id"));
            if (!seen.add(id)) throw invalid("duplicate event ID");
            known(row, "status", EVENT_STATUSES);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", id);
            event.put("name", requiredText(row, "name"));
            result.add(event);
        }
        return result;
    }

    public List<Map<String, Object>> getEventParticipants(Long eventId) {
        if (eventId == null || eventId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EMS v2 event ID must be positive");
        }
        String eventPath = "/api/events/" + eventId;
        List<Map<String, Object>> registrations = rows(get(eventPath + "/registrations"));
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> registration : registrations) {
            Long id = id(registration.get("id"));
            if (byId.put(id, registration) != null) throw invalid("duplicate registration ID");
            known(registration, "status", REGISTRATION_STATUSES);
            known(registration, "source", Set.of("PUBLIC", "ONSITE", "IMPORT"));
        }
        Map<Long, Map<String, Object>> attendance = new HashMap<>();
        for (Map<String, Object> row : rows(get(eventPath + "/attendance"))) {
            Long id = id(row.get("registrationId"));
            if (!byId.containsKey(id) || attendance.put(id, row) != null) {
                throw invalid("unmatched or duplicate attendance ID");
            }
            if ("DECLINED".equals(byId.get(id).get("status"))) throw invalid("declined registration in attendance");
            known(row, "status", ATTENDANCE_STATUSES);
            known(row, "source", Set.of("SCAN", "MANUAL"));
            for (String field : List.of("checkedInAt", "checkedOutAt", "updatedAt")) timestamp(row, field, false);
        }
        Map<String, Map<String, String>> industryOptions = industryOptions(eventPath, registrations);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, Map<String, Object>> entry : byId.entrySet()) {
            Map<String, Object> registration = entry.getValue();
            Map<String, Object> attendanceRow = attendance.get(entry.getKey());
            if (!"DECLINED".equals(registration.get("status")) && attendanceRow == null) {
                throw invalid("missing attendance row");
            }
            result.add(normalize(entry.getKey(), registration, attendanceRow, industryOptions));
        }
        return result;
    }

    private Map<String, Map<String, String>> industryOptions(String eventPath, List<Map<String, Object>> registrations) {
        if (registrations.stream().noneMatch(row -> industryKey(object(row.get("formData"))) != null)) return Map.of();
        // The page endpoint is read-only; registration-form can seed defaults upstream on GET.
        Map<String, Object> page = object(get(eventPath + "/page"));
        Map<String, Map<String, String>> result = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> field : rows(page.get("formFields"))) {
            String key = requiredText(field, "fieldKey");
            if (!INDUSTRY_KEYS.contains(key)) continue;
            if (!seen.add(key)) throw invalid("duplicate industry field");
            if (!"DROPDOWN".equals(requiredText(field, "fieldType"))) continue;
            Map<String, String> options = new HashMap<>();
            for (Map<String, Object> option : rows(field.get("options"))) {
                String value = requiredText(option, "value");
                String label = requiredText(option, "label");
                if (options.putIfAbsent(value, label) != null) throw invalid("duplicate industry option value");
            }
            result.put(key, options);
        }
        return result;
    }

    private static String industryKey(Map<String, Object> form) {
        for (String key : INDUSTRY_KEYS) if (text(form, key) != null) return key;
        return null;
    }

    private Map<String, Object> normalize(Long id, Map<String, Object> row, Map<String, Object> attendance,
                                          Map<String, Map<String, String>> industryOptions) {
        Map<String, Object> form = object(row.get("formData"));
        String salutation = text(form, "salutation");
        String name = text(form, "full_name");
        if (name == null) {
            name = text(row, "displayName");
            // Only remove a title when this registration explicitly records the same salutation.
            if (name != null && salutation != null && name.regionMatches(true, 0, salutation + " ", 0, salutation.length() + 1)) {
                name = name.substring(salutation.length()).trim();
            }
        }
        if (name == null || name.isBlank()) throw invalid("registration has no name");
        String email = text(row, "email");
        if (email == null) email = text(form, "company_email");
        String createdAt = timestamp(row, "createdAt", true);
        String updatedAt = timestamp(row, "updatedAt", false);
        String status = (String) row.get("status");
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("registered_name", name);
        put(profile, "registered_salutation", salutation);
        put(profile, "registered_email", email);
        put(profile, "phone", text(form, "phone"));
        // Custom fields have no global schema: use explicit keys, never infer from a free-text label.
        put(profile, "company", first(form, "company", "company_name", "companyName", "organization", "organisation"));
        put(profile, "job_title", first(form, "job_title", "jobTitle", "position"));
        String industryKey = industryKey(form);
        if (industryKey != null) {
            String rawIndustry = text(form, industryKey);
            String industry = industryOptions.getOrDefault(industryKey, Map.of()).getOrDefault(rawIndustry, rawIndustry);
            put(profile, "industry", industry);
            if (!industry.equals(rawIndustry)) put(profile, "ems_v2_industry_raw", rawIndustry);
        }
        put(profile, "job_level", first(form, "job_level", "position_level"));
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("id", id);
        normalized.put("user_profile", profile);
        normalized.put("registration_code", requiredText(row, "referenceCode"));
        normalized.put("status", status);
        normalized.put("created_at", createdAt);
        if ("ACCEPTED".equals(status)) normalized.put("verified_at", updatedAt != null ? updatedAt : createdAt);
        if ("DECLINED".equals(status)) normalized.put("declined_at", updatedAt != null ? updatedAt : createdAt);
        if (attendance != null) {
            // A manual ABSENT override keeps historical timestamps in EMS. Respect its current
            // status; PRESENT/CHECKED_OUT need an actual check-in before CRM marks attendance.
            String checkIn = timestamp(attendance, "checkedInAt", false);
            if (!"ABSENT".equals(attendance.get("status"))) put(normalized, "checked_in_at", checkIn);
            normalized.put("ems_v2_attendance_status", attendance.get("status"));
        }
        return normalized;
    }

    private Object get(String path) {
        String base = baseUrl(apiUrl, "EMS_V2_API_URL");
        String token = accessToken();
        try {
            return exchange(base + path, HttpMethod.GET, null, token);
        } catch (HttpClientErrorException.Unauthorized unauthorized) {
            if (configuredToken != null && !configuredToken.isBlank()) throw invalid("authentication rejected");
            synchronized (this) {
                if (Objects.equals(cachedToken, token)) tokenExpiresAt = Instant.EPOCH;
            }
            try {
                return exchange(base + path, HttpMethod.GET, null, accessToken());
            } catch (RestClientException failure) {
                throw invalid("upstream request failed");
            }
        } catch (RestClientException failure) {
            throw invalid("upstream request failed");
        }
    }

    private synchronized String accessToken() {
        if (configuredToken != null && (configuredToken.contains("\r") || configuredToken.contains("\n"))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "EMS_V2_ACCESS_TOKEN must be a single line");
        }
        if (configuredToken != null && !configuredToken.isBlank()) {
            String token = configuredToken.trim().replaceFirst("(?i)^Bearer\\s+", "").trim();
            if (token.isEmpty() || "Bearer".equalsIgnoreCase(token) || token.chars().anyMatch(Character::isWhitespace)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "EMS_V2_ACCESS_TOKEN is invalid");
            }
            return token;
        }
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)) return cachedToken;
        String base = baseUrl(iamUrl, "EMS_V2_IAM_URL");
        if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "EMS v2 IAM credentials are not configured");
        }
        Map<String, Object> login;
        try {
            login = object(exchange(base + "/api/auth/login", HttpMethod.POST,
                    Map.of("email", adminEmail.trim(), "password", adminPassword), null));
        } catch (RestClientException failure) {
            throw invalid("IAM login failed");
        }
        String token = requiredText(login, "accessToken");
        if (!"Bearer".equalsIgnoreCase(requiredText(login, "tokenType"))) throw invalid("unsupported IAM token type");
        long expiresIn = id(login.get("expiresIn"));
        try {
            tokenExpiresAt = Instant.now().plusSeconds(Math.max(1, expiresIn - 30));
        } catch (RuntimeException malformed) {
            throw invalid("invalid IAM token lifetime");
        }
        cachedToken = token;
        return token;
    }

    private Object exchange(String url, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (token != null) headers.setBearerAuth(token);
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = http.exchange(URI.create(url), method, new HttpEntity<>(body, headers), Map.class);
        if (!response.getStatusCode().is2xxSuccessful()) throw invalid("upstream did not return success");
        Map<String, Object> envelope = object(response.getBody());
        if (envelope.get("error") != null || !envelope.containsKey("data") || envelope.get("data") == null) {
            throw invalid("invalid API envelope");
        }
        return envelope.get("data");
    }

    private static String baseUrl(String value, String setting) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, setting + " is not configured");
        }
        try {
            URI uri = URI.create(value.trim());
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            return value.trim().replaceAll("/+$", "");
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, setting + " is invalid");
        }
    }

    private static List<Map<String, Object>> rows(Object value) {
        if (!(value instanceof List<?> list)) throw invalid("expected array data");
        return list.stream().map(EmsV2Client::object).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw invalid("expected object data");
        }
        return (Map<String, Object>) map;
    }

    private static Long id(Object value) {
        try {
            if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof BigInteger)) {
                throw new IllegalArgumentException();
            }
            long parsed = new BigInteger(value.toString()).longValueExact();
            if (parsed <= 0) throw new IllegalArgumentException();
            return parsed;
        } catch (IllegalArgumentException | ArithmeticException malformed) {
            throw invalid("invalid positive numeric ID or lifetime");
        }
    }

    private static void known(Map<String, Object> row, String key, Set<String> allowed) {
        if (!(row.get(key) instanceof String value) || !allowed.contains(value)) throw invalid("unknown " + key);
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) return null;
        if (!(value instanceof String text)) throw invalid("non-text " + key);
        return text.isBlank() ? null : text.trim();
    }

    private static String requiredText(Map<String, Object> row, String key) {
        String value = text(row, key);
        if (value == null) throw invalid("missing " + key);
        return value;
    }

    private static String first(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            String value = text(row, key);
            if (value != null) return value;
        }
        return null;
    }

    private static String timestamp(Map<String, Object> row, String key, boolean required) {
        String value = required ? requiredText(row, key) : text(row, key);
        if (value == null) return null;
        try { return Instant.parse(value).toString(); }
        catch (DateTimeParseException malformed) { throw invalid("invalid timestamp " + key); }
    }

    private static void put(Map<String, Object> row, String key, String value) {
        if (value != null) row.put(key, value);
    }

    private static ResponseStatusException invalid(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "EMS v2: " + detail);
    }
}
