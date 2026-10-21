package com.crm.tagtog;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.List;

@RestController
public class TagtogController {
    private final TagtogService service;
    public TagtogController(TagtogService service) { this.service = service; }
    public record ExchangeRequest(String code, String codeVerifier, String redirectUri) {}
    public record LinkRequest(Long crmUserId) {}

    @PostMapping("/api/tagtog/exchange")
    public ResponseEntity<?> exchange(@RequestBody ExchangeRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.exchange(request.code(), request.codeVerifier(), request.redirectUri()));
    }
    @GetMapping("/api/tagtog/admin/users")
    public List<Map<String, Object>> users(@RequestHeader(value = "Authorization", required = false) String auth) {
        service.requireAdmin(auth); return service.users();
    }
    @GetMapping("/api/tagtog/admin/links")
    public List<Map<String, Object>> links(@RequestHeader(value = "Authorization", required = false) String auth) {
        service.requireAdmin(auth); return service.links();
    }
    @PutMapping("/api/tagtog/admin/links/{subject}")
    public ResponseEntity<?> link(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @PathVariable String subject, @RequestBody LinkRequest request) {
        service.requireAdmin(auth); service.link(subject, request.crmUserId(), auth); return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/api/tagtog/admin/links/{subject}")
    public ResponseEntity<?> unlink(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String subject) {
        service.requireAdmin(auth); service.unlink(subject); return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(TagtogException.class)
    public ResponseEntity<?> error(TagtogException ex) {
        return ResponseEntity.status(ex.status).header("Cache-Control", "no-store").body(Map.of("error", ex.code, "message", ex.getMessage()));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> conflict() {
        return ResponseEntity.status(409).body(Map.of("error", "CRM_LINK_CONFLICT", "message", "CRM account mapping conflicts with another identity"));
    }
}
