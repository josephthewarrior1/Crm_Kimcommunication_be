package com.crm.service;

import com.crm.domain.AppUser;
import com.crm.domain.Role;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** PostgreSQL-only maintenance operation. Never invokes the cascading contact DELETE controller. */
@Service
public class DatabaseMergeService {
    public record MergeRequest(Long targetId, Long sourceId, Map<String, String> fieldChoices,
                               Map<String, String> emailTypes, String previewToken) {}
    private static final List<String> FIELDS = List.of("company_id", "salutation", "first_name", "last_name",
            "position_level", "speciality_division", "job_title", "mobile_phone", "linkedin_url", "database_type", "source");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]+$");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public DatabaseMergeService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    @Transactional(timeout = 30)
    public Map<String, Object> merge(MergeRequest request, AppUser actor, boolean apply) {
        require(actor != null && actor.getId() != null && actor.getRoles() != null && actor.getRoles().contains(Role.ADMIN),
                HttpStatus.FORBIDDEN, "Only ADMIN can merge contacts");
        require(request != null && request.targetId() != null && request.sourceId() != null
                && request.targetId() > 0 && request.sourceId() > 0 && !request.targetId().equals(request.sourceId()),
                HttpStatus.BAD_REQUEST, "Two different positive targetId/sourceId values are required");
        Map<String, String> choices = request.fieldChoices() == null ? Map.of() : request.fieldChoices();
        Map<String, String> types = request.emailTypes() == null ? Map.of() : request.emailTypes();
        choices.forEach((field, value) -> require(FIELDS.contains(field) && Set.of("target", "source").contains(value == null ? "" : value),
                HttpStatus.BAD_REQUEST, "Invalid field choice: " + field));
        types.forEach((email, type) -> require(email != null && email.equals(email.trim().toLowerCase(Locale.ROOT))
                        && Set.of("personal", "company").contains(type == null ? "" : type),
                HttpStatus.BAD_REQUEST, "Email overrides require a lowercase address and personal/company type"));
        if (apply) require(request.previewToken() != null && request.previewToken().matches("[a-f0-9]{64}"),
                HttpStatus.BAD_REQUEST, "Preview the exact request first and supply its previewToken");

        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        jdbc.execute("SET LOCAL statement_timeout = '20s'");
        // ponytail: infrequent ADMIN maintenance briefly serializes writes, including legacy writers
        // without row locks. Replace with coordinated row locks if merge throughput becomes material.
        jdbc.execute("LOCK TABLE databases, companies, database_emails, event_participants, "
                + "event_participant_activities, flagged_identities, removal_requests IN SHARE ROW EXCLUSIVE MODE");
        if (apply) {
            List<Map<String, Object>> previous = jdbc.queryForList(
                    "SELECT result::text AS result FROM database_merge_audits WHERE preview_token=? AND actor_id=? AND target_id=? AND source_id=?",
                    request.previewToken(), actor.getId(), request.targetId(), request.sourceId());
            if (!previous.isEmpty()) return decode((String) previous.get(0).get("result"));
        }
        guardReferences();
        long targetId = request.targetId(), sourceId = request.sourceId();
        List<Map<String, Object>> contacts = rows("SELECT * FROM databases WHERE id IN (?,?) ORDER BY id", targetId, sourceId);
        require(contacts.size() == 2, HttpStatus.NOT_FOUND, "Source or target contact no longer exists");
        Map<String, Object> target = contacts.stream().filter(c -> number(c.get("id")) == targetId).findFirst().orElseThrow();
        Map<String, Object> source = contacts.stream().filter(c -> number(c.get("id")) == sourceId).findFirst().orElseThrow();
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("contacts", contacts);
        List<Map<String, Object>> companies = rows("SELECT * FROM companies WHERE id IN (SELECT company_id FROM databases WHERE id IN (?,?)) ORDER BY id", targetId, sourceId);
        before.put("companies", companies);
        List<Map<String, Object>> emails = rows("SELECT * FROM database_emails WHERE database_id IN (?,?) ORDER BY id", targetId, sourceId);
        before.put("emails", emails);
        List<Map<String, Object>> events = rows("SELECT * FROM event_participants WHERE database_id IN (?,?) ORDER BY id", targetId, sourceId);
        before.put("events", events);
        before.put("activities", rows("SELECT * FROM event_participant_activities WHERE event_participant_id IN (SELECT id FROM event_participants WHERE database_id IN (?,?)) ORDER BY id", targetId, sourceId));
        List<Map<String, Object>> flags = rows("SELECT * FROM flagged_identities WHERE database_id IN (?,?) ORDER BY id", targetId, sourceId);
        List<Map<String, Object>> removals = rows("SELECT * FROM removal_requests WHERE database_id IN (?,?) ORDER BY id", targetId, sourceId);
        before.put("flags", flags);
        before.put("removals", removals);
        require(samePerson(target, source, emails), HttpStatus.CONFLICT,
                "Identity mismatch: normalized name plus matching phone, personal email or individual LinkedIn required");
        if (target.get("company_id") != null && source.get("company_id") != null
                && number(target.get("company_id")) != number(source.get("company_id"))) {
            require(companies.size() == 2 && !companyKey(companies.get(0).get("name")).isEmpty()
                            && companyKey(companies.get(0).get("name")).equals(companyKey(companies.get(1).get("name"))),
                    HttpStatus.CONFLICT, "Different companies: resolve employment/company identity before merging");
        }
        require(source.get("created_by_user_id") == null || Objects.equals(source.get("created_by_user_id"), target.get("created_by_user_id")),
                HttpStatus.CONFLICT, "Source has upload attribution: choose the credited record as target; different creators require manual review");
        Set<Long> eventIds = new HashSet<>();
        for (Map<String, Object> event : events) require(eventIds.add(number(event.get("event_id"))), HttpStatus.CONFLICT,
                "Both contacts belong to the same event; reconcile their registrations before merging");

        Map<String, Object> merged = new LinkedHashMap<>(target);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (String field : FIELDS) {
            Object a = target.get(field), b = source.get(field);
            String choice = choices.get(field);
            if (choice != null) {
                Object selected = choice.equals("source") ? b : a;
                require(!blank(field, selected) || (blank(field, a) && blank(field, b)), HttpStatus.BAD_REQUEST,
                        "Cannot discard a nonempty value using field choice " + field);
                merged.put(field, selected);
            } else if (blank(field, a)) {
                if (!blank(field, b)) merged.put(field, b);
            } else if (!blank(field, b) && !equivalent(field, a, b)) {
                conflicts.add(Map.of("field", field, "target", a, "source", b));
            }
        }
        boolean active = Boolean.TRUE.equals(target.get("is_active")) && Boolean.TRUE.equals(source.get("is_active"))
                && flags.stream().noneMatch(f -> "confirmed".equals(f.get("status")))
                && removals.stream().noneMatch(r -> !"rejected".equals(r.get("status")));
        merged.put("is_active", active);
        String phone = phone(merged.get("mobile_phone"));
        merged.put("normalized_phone", phone.isEmpty() ? null : "+" + phone);
        List<Map<String, Object>> plannedEmails = mergeEmails(emails, targetId, types);
        for (Map<String, Object> email : plannedEmails) {
            if ("personal".equals(email.get("email_type"))) {
                // Read tokenized legacy values too; a third contact must not silently retain this personal address.
                List<Map<String, Object>> others = rows("SELECT * FROM database_emails WHERE database_id NOT IN (?,?) AND position(? in lower(email)) > 0 ORDER BY id",
                        targetId, sourceId, email.get("email"));
                require(others.stream().noneMatch(e -> tokens(e).contains(email.get("email"))), HttpStatus.CONFLICT,
                        "Personal email also belongs to another contact: " + email.get("email"));
            }
        }
        String token = hash(Map.of("before", before, "targetId", targetId, "sourceId", sourceId, "fieldChoices", choices, "emailTypes", types));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetId", targetId); result.put("sourceId", sourceId);
        result.put("previewToken", token); result.put("conflicts", conflicts);
        result.put("contact", merged); result.put("emails", plannedEmails);
        result.put("eventsPreserved", events.size()); result.put("flagsPreserved", flags.size()); result.put("removalRequestsPreserved", removals.size());
        result.put("status", "preview");
        result.put("warnings", List.of("Target attribution/createdAt are retained; discarded values remain in the merge backup.",
                "Email types are inherited, not guessed from domains; use emailTypes for legacy mislabelled addresses."));
        if (!apply) return result;
        require(token.equals(request.previewToken()), HttpStatus.CONFLICT, "Preview is stale or choices changed; preview again");
        require(conflicts.isEmpty(), HttpStatus.CONFLICT, "Resolve all field conflicts with fieldChoices, then preview again");

        // Delete/reinsert only the backed-up email set inside this transaction, preserving one original ID
        // per address. This avoids unique-index collisions while splitting legacy multi-address rows.
        jdbc.update("DELETE FROM database_emails WHERE database_id IN (?,?)", targetId, sourceId);
        for (Map<String, Object> email : plannedEmails) insertEmail(email);
        for (String field : FIELDS) jdbc.update("UPDATE databases SET " + field + "=? WHERE id=?", merged.get(field), targetId);
        jdbc.update("UPDATE databases SET is_active=?, normalized_phone=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                active, merged.get("normalized_phone"), targetId);
        for (String table : List.of("event_participants", "flagged_identities", "removal_requests"))
            jdbc.update("UPDATE " + table + " SET database_id=? WHERE database_id=?", targetId, sourceId);
        require(jdbc.update("DELETE FROM databases WHERE id=?", sourceId) == 1, HttpStatus.CONFLICT, "Source deletion failed");
        result.put("status", "merged");
        result.put("contact", rows("SELECT * FROM databases WHERE id=?", targetId).get(0));
        result.put("emails", rows("SELECT * FROM database_emails WHERE database_id=? ORDER BY id", targetId));
        jdbc.update("INSERT INTO database_merge_audits(actor_id,target_id,source_id,preview_token,before_snapshot,result) VALUES (?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb))",
                actor.getId(), targetId, sourceId, token, encode(before), encode(result));
        return result;
    }

    private void guardReferences() {
        List<Map<String, Object>> refs = jdbc.queryForList("SELECT child.relname AS child, parent.relname AS parent, "
                + "c.conkey = ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid=child.oid AND attname='database_id')]::smallint[] AS known_column FROM pg_constraint c "
                + "JOIN pg_class child ON child.oid=c.conrelid JOIN pg_class parent ON parent.oid=c.confrelid "
                + "JOIN pg_namespace ns ON ns.oid=parent.relnamespace WHERE c.contype='f' AND ns.nspname=current_schema() "
                + "AND parent.relname IN ('databases','database_emails')");
        for (Map<String, Object> ref : refs) require(Boolean.TRUE.equals(ref.get("known_column")) && "databases".equals(ref.get("parent"))
                        && Set.of("database_emails", "event_participants", "flagged_identities", "removal_requests").contains(ref.get("child")),
                HttpStatus.CONFLICT, "Unsupported dependent table; merge aborted: " + ref.get("child"));
    }

    private List<Map<String, Object>> mergeEmails(List<Map<String, Object>> emails, long targetId, Map<String, String> overrides) {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        Set<Long> usedIds = new HashSet<>();
        List<Map<String, Object>> ordered = new ArrayList<>(emails);
        ordered.sort(Comparator.comparing(e -> number(e.get("database_id")) != targetId));
        for (Map<String, Object> original : ordered) {
            List<String> values = tokens(original);
            require(!values.isEmpty(), HttpStatus.CONFLICT, "Empty email row must be corrected before merging");
            for (String value : values) {
                require(EMAIL.matcher(value).matches(), HttpStatus.CONFLICT, "Invalid email must be corrected before merge: " + value);
                String type = "personal".equalsIgnoreCase(text(original.get("email_type"))) ? "personal" : "company";
                Map<String, Object> found = unique.get(value);
                if (found != null) {
                    if (type.equals("personal")) found.put("email_type", "personal");
                    found.put("is_primary", Boolean.TRUE.equals(found.get("is_primary")) || Boolean.TRUE.equals(original.get("is_primary")));
                    found.put("is_verified", Boolean.TRUE.equals(found.get("is_verified")) || (values.size() == 1 && Boolean.TRUE.equals(original.get("is_verified"))));
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>(original);
                long id = number(original.get("id"));
                if (!usedIds.add(id)) item.put("id", null);
                item.put("database_id", targetId); item.put("email", value); item.put("email_type", type);
                item.put("is_verified", values.size() == 1 && Boolean.TRUE.equals(original.get("is_verified")));
                item.put("domain", value.substring(value.indexOf('@') + 1));
                unique.put(value, item);
            }
        }
        require(unique.keySet().containsAll(overrides.keySet()), HttpStatus.BAD_REQUEST, "emailTypes contains an address not present in either contact");
        boolean primary = false;
        for (Map.Entry<String, Map<String, Object>> entry : unique.entrySet()) {
            Map<String, Object> item = entry.getValue();
            if (overrides.containsKey(entry.getKey())) item.put("email_type", overrides.get(entry.getKey()));
            item.put("is_corporate", "company".equals(item.get("email_type")));
            boolean usePrimary = !primary && Boolean.TRUE.equals(item.get("is_primary"));
            item.put("is_primary", usePrimary); primary |= usePrimary;
        }
        return new ArrayList<>(unique.values());
    }

    private void insertEmail(Map<String, Object> e) {
        if (e.get("id") == null) {
            jdbc.update("INSERT INTO database_emails(database_id,email,email_type,is_primary,is_verified,is_corporate,domain,created_at) VALUES (?,?,?,?,?,?,?,CAST(? AS timestamp))",
                    e.get("database_id"),e.get("email"),e.get("email_type"),e.get("is_primary"),e.get("is_verified"),e.get("is_corporate"),e.get("domain"),e.get("created_at"));
        } else {
            jdbc.update("INSERT INTO database_emails(id,database_id,email,email_type,is_primary,is_verified,is_corporate,domain,created_at) VALUES (?,?,?,?,?,?,?,?,CAST(? AS timestamp))",
                    e.get("id"),e.get("database_id"),e.get("email"),e.get("email_type"),e.get("is_primary"),e.get("is_verified"),e.get("is_corporate"),e.get("domain"),e.get("created_at"));
        }
    }

    static List<String> tokens(Map<String, Object> e) {
        String raw = text(e.get("email")).trim().toLowerCase(Locale.ROOT);
        return raw.isEmpty() ? List.of() : Arrays.stream(raw.split("[\\s,;]+")).filter(s -> !s.isBlank()).distinct().toList();
    }
    static boolean samePerson(Map<String, Object> a, Map<String, Object> b, List<Map<String, Object>> emails) {
        String name = nameKey(text(a.get("first_name")) + " " + text(a.get("last_name")));
        if (name.isEmpty() || !name.equals(nameKey(text(b.get("first_name")) + " " + text(b.get("last_name"))))) return false;
        String phone = phone(a.get("mobile_phone"));
        if (!phone.isEmpty() && phone.equals(phone(b.get("mobile_phone")))) return true;
        String linked = linkedIn(a.get("linkedin_url"));
        if (!linked.isEmpty() && linked.equals(linkedIn(b.get("linkedin_url")))) return true;
        Set<String> personal = new HashSet<>();
        for (Map<String, Object> e : emails) if ("personal".equalsIgnoreCase(text(e.get("email_type")))) personal.addAll(tokens(e));
        Set<String> first = new HashSet<>(), second = new HashSet<>();
        for (Map<String, Object> e : emails) (number(e.get("database_id")) == number(a.get("id")) ? first : second).addAll(tokens(e));
        first.retainAll(second); first.retainAll(personal);
        return !first.isEmpty();
    }
    static String nameKey(String value) {
        String norm = value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        String[] words = norm.split(" ");
        if (words.length % 2 == 0) {
            String first = String.join(" ", Arrays.copyOfRange(words, 0, words.length / 2));
            if (first.equals(String.join(" ", Arrays.copyOfRange(words, words.length / 2, words.length)))) return first;
        }
        return norm;
    }
    private static String companyKey(Object value) {
        return text(value).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").replaceAll("\\b(pt|tbk|persero)\\b", "").replaceAll("\\s+", "");
    }
    static String phone(Object value) {
        String digits = text(value).replaceAll("[^0-9]", "");
        if (digits.startsWith("0062")) digits = digits.substring(2);
        else if (digits.startsWith("0")) digits = "62" + digits.substring(1);
        else if (digits.startsWith("8")) digits = "62" + digits;
        return digits.matches("62[0-9]{7,13}") ? digits : "";
    }
    private static boolean blank(String field, Object value) {
        if (value == null) return true;
        return Set.of("", "-", "--", "unknown", "n/a", "null", "tidak ada linked", "tidak ada").contains(text(value).trim().toLowerCase(Locale.ROOT));
    }
    private static boolean equivalent(String field, Object a, Object b) {
        if (field.equals("mobile_phone")) return !phone(a).isEmpty() && phone(a).equals(phone(b));
        if (field.equals("linkedin_url")) return !linkedIn(a).isEmpty() && linkedIn(a).equals(linkedIn(b));
        return text(a).trim().equalsIgnoreCase(text(b).trim());
    }
    private static String linkedIn(Object value) {
        try {
            java.net.URI uri = java.net.URI.create(text(value).trim());
            String host = uri.getHost();
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || host == null
                    || !(host.equalsIgnoreCase("linkedin.com") || host.toLowerCase(Locale.ROOT).endsWith(".linkedin.com"))
                    || uri.getUserInfo() != null || !uri.getPath().matches("/in/[^/]+/?")) return "";
            return uri.getPath().replaceAll("/+$", "").toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) { return ""; }
    }
    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.query("SELECT row_to_json(row_data)::text FROM (" + sql + ") row_data", (rs, n) -> decode(rs.getString(1)), args);
    }
    private Map<String, Object> decode(String value) {
        try { return json.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception e) { throw new IllegalStateException("Cannot read merge snapshot", e); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize merge snapshot", e); }
    }
    private String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("Cannot hash merge snapshot", e); }
    }
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static String text(Object value) { return value == null ? "" : value.toString(); }
    private static void require(boolean condition, HttpStatus status, String reason) {
        if (!condition) throw new ResponseStatusException(status, reason);
    }
}
