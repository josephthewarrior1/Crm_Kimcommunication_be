package com.crm.controller;

import com.crm.domain.AppUser;
import com.crm.domain.Role;
import com.crm.service.DataCleanerService;
import com.crm.service.SecurityHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/data-cleaner")
public class DataCleanerController {

    @Autowired
    private DataCleanerService dataCleanerService;

    @Autowired
    private SecurityHelper securityHelper;

    @GetMapping("/preview")
    public ResponseEntity<?> preview(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN can run data cleaner");
        }
        return ResponseEntity.ok(dataCleanerService.preview());
    }

    @GetMapping("/group-audit")
    public ResponseEntity<?> groupAudit(
            @RequestParam(value = "onlyIssues", defaultValue = "false") boolean onlyIssues,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN can run data cleaner");
        }
        return ResponseEntity.ok(dataCleanerService.groupAudit(onlyIssues));
    }

    @PostMapping("/apply")
    public ResponseEntity<?> apply(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN can run data cleaner");
        }
        return ResponseEntity.ok(dataCleanerService.apply());
    }

    @PostMapping("/merge-groups")
    public ResponseEntity<?> mergeGroups(
            @RequestBody MergeGroupsRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        AppUser currentUser = securityHelper.getAuthenticatedUser(authHeader);
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!securityHelper.hasRole(currentUser, Role.ADMIN)) {
            return ResponseEntity.status(403).body("Forbidden: Only ADMIN can run data cleaner");
        }
        return ResponseEntity.ok(dataCleanerService.mergeGroups(request.targetGroupId(), request.sourceGroupIds()));
    }

    public record MergeGroupsRequest(Long targetGroupId, List<Long> sourceGroupIds) {}
}
