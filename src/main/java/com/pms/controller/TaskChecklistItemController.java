package com.pms.controller;

import com.pms.domain.AppUser;
import com.pms.domain.TaskChecklistItem;
import com.pms.repository.TaskChecklistItemRepository;
import com.pms.repository.TaskRepository;
import com.pms.service.ProjectPermissionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks/{taskId}/checklist")
public class TaskChecklistItemController {

    private final TaskChecklistItemRepository checklistItems;
    private final TaskRepository tasks;
    private final ProjectPermissionService permissionService;

    public TaskChecklistItemController(TaskChecklistItemRepository checklistItems,
                                       TaskRepository tasks,
                                       ProjectPermissionService permissionService) {
        this.checklistItems = checklistItems;
        this.tasks = tasks;
        this.permissionService = permissionService;
    }

    @GetMapping
    public ResponseEntity<List<TaskChecklistItem>> list(@PathVariable Long taskId) {
        if (tasks.findById(taskId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(checklistItems.findByTaskIdOrderByOrderPositionAsc(taskId));
    }

    @PostMapping
    public ResponseEntity<TaskChecklistItem> create(@PathVariable Long taskId,
                                                     @RequestBody Map<String, Object> body,
                                                     @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser user = permissionService.resolveUser(auth);
        return tasks.findById(taskId).map(task -> {
            if (user != null && !permissionService.canUpdate(task.getProject(), user)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body((TaskChecklistItem) null);
            }
            TaskChecklistItem item = TaskChecklistItem.builder()
                    .task(task)
                    .label((String) body.get("label"))
                    .orderPosition(body.containsKey("orderPosition") ? ((Number) body.get("orderPosition")).intValue() : 0)
                    .completed(false)
                    .build();
            return ResponseEntity.status(HttpStatus.CREATED).body(checklistItems.save(item));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{itemId}")
    public ResponseEntity<TaskChecklistItem> update(@PathVariable Long taskId,
                                                     @PathVariable Long itemId,
                                                     @RequestBody Map<String, Object> body,
                                                     @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser user = permissionService.resolveUser(auth);
        return tasks.findById(taskId).map(task -> {
            if (user != null && !permissionService.canUpdate(task.getProject(), user)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body((TaskChecklistItem) null);
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
    public ResponseEntity<TaskChecklistItem> toggle(@PathVariable Long taskId,
                                                     @PathVariable Long itemId,
                                                     @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser user = permissionService.resolveUser(auth);
        return tasks.findById(taskId).map(task -> {
            if (user != null && !permissionService.canUpdate(task.getProject(), user)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body((TaskChecklistItem) null);
            }
            return checklistItems.findById(itemId).map(existing -> {
                existing.setCompleted(!existing.isCompleted());
                return ResponseEntity.ok(checklistItems.save(existing));
            }).orElseGet(() -> ResponseEntity.notFound().build());
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{itemId}")
    public ResponseEntity<Void> delete(@PathVariable Long taskId,
                                        @PathVariable Long itemId,
                                        @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser user = permissionService.resolveUser(auth);
        return tasks.findById(taskId).map(task -> {
            if (user != null && !permissionService.canDelete(task.getProject(), user)) {
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
