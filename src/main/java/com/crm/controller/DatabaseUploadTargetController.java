package com.crm.controller;

import com.crm.domain.AppUser;
import com.crm.domain.DatabaseUploadTarget;
import com.crm.domain.Role;
import com.crm.repository.DatabaseRepository;
import com.crm.repository.DatabaseUploadTargetRepository;
import com.crm.repository.UserRepository;
import com.crm.service.SecurityHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/database-targets")
public class DatabaseUploadTargetController {
    @Autowired private SecurityHelper securityHelper;
    @Autowired private UserRepository userRepository;
    @Autowired private DatabaseRepository databaseRepository;
    @Autowired private DatabaseUploadTargetRepository targetRepository;
    private Clock clock = Clock.systemDefaultZone();

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String month,
            @RequestParam(required = false) String date,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) return error(401, "Unauthorized");
        final YearMonth period;
        final LocalDate day;
        try {
            period = month == null ? YearMonth.now(clock) : parseMonth(month);
            LocalDate today = LocalDate.now(clock);
            day = date == null ? (YearMonth.from(today).equals(period) ? today : period.atDay(1)) : LocalDate.parse(date);
            if (!YearMonth.from(day).equals(period)) return error(400, "Date must be within the selected month");
        }
        catch (DateTimeException ex) { return error(400, "Date must use a valid YYYY-MM-DD"); }
        catch (IllegalArgumentException ex) { return error(400, ex.getMessage()); }

        boolean admin = securityHelper.hasRole(currentUser, Role.ADMIN);
        // Non-admins never receive another person's target or upload statistics.
        List<AppUser> users = admin ? userRepository.findAll().stream()
                .filter(this::canUpload).sorted(Comparator.comparing(AppUser::getUsername)).toList()
                : List.of(currentUser);
        return summary(users, period, day);
    }

    @GetMapping("/me")
    public ResponseEntity<?> myProgress(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) return error(401, "Unauthorized");
        LocalDate today = LocalDate.now(clock);
        return summary(List.of(currentUser), YearMonth.from(today), today);
    }

    private ResponseEntity<?> summary(List<AppUser> users, YearMonth period, LocalDate day) {
        List<Long> ids = users.stream().map(AppUser::getId).toList();
        Map<Long, DatabaseUploadTarget> targets = ids.isEmpty() ? Map.of() : targetRepository
                .findByTargetMonthAndUserIdIn(period.atDay(1), ids).stream()
                .collect(Collectors.toMap(DatabaseUploadTarget::getUserId, target -> target));
        Map<Long, long[]> counts = countUploads(ids, period.atDay(1), period.plusMonths(1).atDay(1));
        Map<Long, long[]> dailyCounts = countUploads(ids, day, day.plusDays(1));
        List<Map<String, Object>> items = users.stream().map(user -> {
            long[] values = counts.getOrDefault(user.getId(), new long[2]);
            long[] daily = dailyCounts.getOrDefault(user.getId(), new long[2]);
            DatabaseUploadTarget target = targets.get(user.getId());
            int targetCount = target == null ? 0 : target.getTargetCount();
            String mode = target == null ? "DAILY" : target.getTargetMode();
            long progress = "DAILY".equals(mode) ? daily[0] + daily[1] : values[0] + values[1];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", user.getId());
            row.put("username", user.getUsername());
            row.put("fullName", user.getFullName() == null ? user.getUsername() : user.getFullName());
            row.put("targetCount", targetCount);
            row.put("targetMode", mode);
            row.put("excelCount", values[0]);
            row.put("manualCount", values[1]);
            row.put("totalCount", values[0] + values[1]);
            row.put("dailyCount", daily[0] + daily[1]);
            row.put("progressCount", progress);
            row.put("remainingCount", Math.max(0, targetCount - progress));
            row.put("monthlyTargetCount", "DAILY".equals(mode) ? (long) targetCount * period.lengthOfMonth() : (long) targetCount);
            return row;
        }).toList();
        return ResponseEntity.ok(Map.of("month", period.toString(), "date", day.toString(),
                "daysInMonth", period.lengthOfMonth(), "timeZone", clock.getZone().getId(), "items", items));
    }

    private Map<Long, long[]> countUploads(List<Long> ids, LocalDate start, LocalDate end) {
        Map<Long, long[]> counts = new HashMap<>();
        if (!ids.isEmpty()) databaseRepository.countUploads(ids, start.atStartOfDay(), end.atStartOfDay())
                .forEach(row -> {
                    long[] values = counts.computeIfAbsent(row.getUserId(), key -> new long[2]);
                    values["excel_import".equals(row.getMethod()) ? 0 : 1] += row.getTotal();
                });
        return counts;
    }

    public record TargetRequest(java.math.BigDecimal targetCount, String targetMode) {
        public TargetRequest(int count) { this(java.math.BigDecimal.valueOf(count), "MONTHLY"); }
        public TargetRequest(java.math.BigDecimal count) { this(count, "MONTHLY"); }
    }

    @PutMapping("/{userId}")
    public ResponseEntity<?> setTarget(@PathVariable Long userId, @RequestParam String month,
            @RequestBody TargetRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) return error(401, "Unauthorized");
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) return error(403, "Only ADMIN can set upload targets");
        final YearMonth period;
        try { period = parseMonth(month); }
        catch (IllegalArgumentException ex) { return error(400, ex.getMessage()); }
        if (request == null || request.targetCount() == null || request.targetCount().signum() < 0) {
            return error(400, "Target must be a non-negative whole number; use 0 to clear the target");
        }
        final int count;
        try { count = request.targetCount().intValueExact(); }
        catch (ArithmeticException ex) { return error(400, "Target must be a whole number between 0 and 2147483647"); }
        String mode = request.targetMode() == null ? "MONTHLY" : request.targetMode();
        if (!Set.of("DAILY", "MONTHLY").contains(mode)) return error(400, "Target mode must be DAILY or MONTHLY");
        AppUser targetUser = userRepository.findById(userId).orElse(null);
        if (targetUser == null) return error(404, "User not found");
        if (!canUpload(targetUser)) return error(400, "This account cannot add or import databases (ADMIN/MANAGER only)");
        targetRepository.setTarget(userId, period.atDay(1), count, mode);
        return ResponseEntity.ok(Map.of("userId", userId, "month", period.toString(), "targetCount", count, "targetMode", mode));
    }

    private boolean canUpload(AppUser user) {
        return securityHelper.hasAnyRole(user, Role.ADMIN, Role.MANAGER);
    }

    static YearMonth parseMonth(String month) {
        if (month == null) return YearMonth.now();
        if (!month.matches("[0-9]{4}-(0[1-9]|1[0-2])")) throw new IllegalArgumentException("Month must use YYYY-MM");
        YearMonth parsed = YearMonth.parse(month);
        if (parsed.getYear() < 1) throw new IllegalArgumentException("Invalid year");
        return parsed;
    }

    private ResponseEntity<?> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}
