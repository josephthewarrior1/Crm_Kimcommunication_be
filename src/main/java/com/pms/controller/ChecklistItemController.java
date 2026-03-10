package com.pms.controller;

import com.pms.domain.AppUser;
import com.pms.domain.ChecklistItem;
import com.pms.repository.ChecklistItemRepository;
import com.pms.repository.WorkflowStageRepository;
import com.pms.service.ProjectPermissionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stages/{stageId}/checklist")
public class ChecklistItemController {

    private final ChecklistItemRepository checklistItems;
    private final WorkflowStageRepository stages;
    private final ProjectPermissionService permissionService;

    public ChecklistItemController(ChecklistItemRepository checklistItems,
                                   WorkflowStageRepository stages,
                                   ProjectPermissionService permissionService) {
        this.checklistItems = checklistItems;
        this.stages = stages;
        this.permissionService = permissionService;
    }

    @GetMapping
    public ResponseEntity<List<ChecklistItem>> list(@PathVariable Long stageId) {
        if (stages.findById(stageId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(checklistItems.findByStageIdOrderByOrderPositionAsc(stageId));
    }

    @PostMapping
    public ResponseEntity<ChecklistItem> create(@PathVariable Long stageId,
                                                 @RequestBody Map<String, Object> body,
                                                 @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser user = permissionService.resolveUser(auth);
        return stages.findById(stageId).map(stage -> {
            if (user != null && !permissionService.canUpdate(stage.getProject(), user)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body((ChecklistItem) null);
            }
            ChecklistItem item = ChecklistItem.builder()
                    .stage(stage)
                    .label((String) body.get("label"))
                    .orderPosition(body.containsKey("orderPosition") ? ((Number) body.get("orderPosition")).intValue() : 0)
                    .completed(false)
                    .build();
            return ResponseEntity.status(HttpStatus.CREATED).body(checklistItems.save(item));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{itemId}")
    public ResponseEntity<ChecklistItem> update(@PathVariable Long stageId,
                                                 @PathVariable Long itemId,
                                                 @RequestBody Map<String, Object> body,
                                                 @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser user = permissionService.resolveUser(auth);
        return stages.findById(stageId).map(stage -> {
            if (user != null && !permissionService.canUpdate(stage.getProject(), user)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body((ChecklistItem) null);
            }
            return checklistItems.findById(itemId).map(existing -> {
                if (body.containsKey("label")) {
                    existing.setLabel((String) body.get("label"));
                }
                if (body.containsKey("orderPosition")) {
                    existing.setOrderPosition(((Number) body.get("orderPosition")).intValue());
                }
                return ResponseEntity.ok(checklistItems.save(existing));
            }).orElseGet(() -> ResponseEntity.notFound().build());
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{itemId}/toggle")
    public ResponseEntity<ChecklistItem> toggle(@PathVariable Long stageId,
                                                 @PathVariable Long itemId,
                                                 @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser user = permissionService.resolveUser(auth);
        return stages.findById(stageId).map(stage -> {
            if (user != null && !permissionService.canUpdate(stage.getProject(), user)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body((ChecklistItem) null);
            }
            return checklistItems.findById(itemId).map(existing -> {
                existing.setCompleted(!existing.isCompleted());
                return ResponseEntity.ok(checklistItems.save(existing));
            }).orElseGet(() -> ResponseEntity.notFound().build());
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{itemId}")
    public ResponseEntity<Void> delete(@PathVariable Long stageId,
                                        @PathVariable Long itemId,
                                        @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser user = permissionService.resolveUser(auth);
        return stages.findById(stageId).map(stage -> {
            if (user != null && !permissionService.canDelete(stage.getProject(), user)) {
                return new ResponseEntity<Void>(HttpStatus.FORBIDDEN);
            }
            if (checklistItems.findById(itemId).isEmpty()) {
                return new ResponseEntity<Void>(HttpStatus.NOT_FOUND);
            }
            checklistItems.deleteById(itemId);
            return new ResponseEntity<Void>(HttpStatus.NO_CONTENT);
        }).orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }
}
