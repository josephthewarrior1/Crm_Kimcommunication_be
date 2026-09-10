package com.crm.controller;

import com.crm.domain.AppUser;
import com.crm.domain.Role;
import com.crm.service.DatabaseMergeService;
import com.crm.service.SecurityHelper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/databases/merge")
public class DatabaseMergeController {
    private final DatabaseMergeService merges;
    private final SecurityHelper security;

    public DatabaseMergeController(DatabaseMergeService merges, SecurityHelper security) {
        this.merges = merges;
        this.security = security;
    }

    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody DatabaseMergeService.MergeRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return merges.merge(request, admin(authorization), false);
    }

    @PostMapping
    public Map<String, Object> merge(@RequestBody DatabaseMergeService.MergeRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return merges.merge(request, admin(authorization), true);
    }

    private AppUser admin(String authorization) {
        AppUser user = security.getAuthenticatedUser(authorization);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        if (!security.hasRole(user, Role.ADMIN))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only ADMIN can merge contacts");
        return user;
    }

    // Preserve actionable status codes instead of the application's generic 500 exception handler.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> rejected(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", error.getReason() == null ? "Merge rejected" : error.getReason()));
    }

    @ExceptionHandler({CannotAcquireLockException.class, DataIntegrityViolationException.class})
    public ResponseEntity<?> conflict(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Merge rolled back due to a lock or data constraint. Refresh preview before retrying."));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformedRequest(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid merge request JSON"));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<?> databaseFailure(RuntimeException error) {
        return ResponseEntity.internalServerError().body(Map.of("error", "Merge database operation failed and was rolled back. Check server migration and database logs."));
    }
}
