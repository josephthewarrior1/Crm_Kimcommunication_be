package com.pms.controller;

import com.pms.domain.*;
import com.pms.repository.ClientContactRepository;
import com.pms.repository.ClientRepository;
import com.pms.repository.UserRepository;
import com.pms.service.ProjectPermissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/client-users")
public class ClientUserController {

    private final UserRepository userRepository;
    private final ClientRepository clientRepository;
    private final ClientContactRepository contactRepository;
    private final ProjectPermissionService permissionService;
    private final org.springframework.security.crypto.argon2.Argon2PasswordEncoder encoder;

    public ClientUserController(UserRepository userRepository,
                                 ClientRepository clientRepository,
                                 ClientContactRepository contactRepository,
                                 ProjectPermissionService permissionService) {
        this.userRepository = userRepository;
        this.clientRepository = clientRepository;
        this.contactRepository = contactRepository;
        this.permissionService = permissionService;
        this.encoder = new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16, 32, 1, 1 << 14, 3);
    }

    @GetMapping
    public ResponseEntity<?> listClientUsers(
            @RequestHeader(value = "Authorization", required = false) String auth) {

        AppUser u = permissionService.resolveUser(auth);
        if (u == null || !permissionService.isAdminOrManager(u))
            return ResponseEntity.status(403).build();

        List<AppUser> clientUsers = userRepository.findByEmploymentType(EmploymentType.CLIENT);
        List<Map<String, Object>> result = clientUsers.stream().map(cu -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", cu.getId());
            map.put("name", cu.getName());
            map.put("email", cu.getEmail());
            map.put("username", cu.getUsername());
            map.put("clientId", cu.getClientEntity() != null ? cu.getClientEntity().getId() : null);
            map.put("clientName", cu.getClientEntity() != null ? cu.getClientEntity().getName() : null);
            map.put("active", cu.isActive());
            map.put("approved", cu.isApproved());
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<?> createClientUser(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        AppUser u = permissionService.resolveUser(auth);
        if (u == null || !permissionService.isAdminOrManager(u))
            return ResponseEntity.status(403).build();

        String name = (String) body.get("name");
        String email = (String) body.get("email");
        String password = (String) body.get("password");
        String requestedUsername = body.get("username") != null ? body.get("username").toString().trim() : null;
        Object clientIdObj = body.get("clientId");

        if (name == null || name.isBlank() || email == null || email.isBlank() || password == null || password.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "name, email, and password are required"));

        if (userRepository.existsByEmail(email.toLowerCase()))
            return ResponseEntity.badRequest().body(Map.of("error", "Email already in use"));

        // Use provided username or generate from email
        String username;
        if (requestedUsername != null && !requestedUsername.isBlank()) {
            if (userRepository.existsByUsername(requestedUsername))
                return ResponseEntity.badRequest().body(Map.of("error", "Username already taken"));
            username = requestedUsername;
        } else {
            username = email.toLowerCase().split("@")[0];
            int suffix = 1;
            while (userRepository.existsByUsername(username)) {
                username = email.toLowerCase().split("@")[0] + suffix++;
            }
        }

        AppUser clientUser = AppUser.builder()
                .name(name.trim())
                .email(email.toLowerCase().trim())
                .username(username)
                .passwordHash(encoder.encode(password))
                .roles(new HashSet<>(Set.of(Role.USER)))
                .employmentType(EmploymentType.CLIENT)
                .active(true)
                .approved(true)
                .build();

        if (clientIdObj != null) {
            Long clientId = Long.valueOf(clientIdObj.toString());
            clientRepository.findById(clientId).ifPresent(clientUser::setClientEntity);
        }

        AppUser saved = userRepository.save(clientUser);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", saved.getId());
        result.put("name", saved.getName());
        result.put("email", saved.getEmail());
        result.put("username", saved.getUsername());
        result.put("clientId", saved.getClientEntity() != null ? saved.getClientEntity().getId() : null);
        result.put("active", saved.isActive());
        result.put("approved", saved.isApproved());

        return ResponseEntity.status(201).body(result);
    }

    @PostMapping("/from-contact/{contactId}")
    public ResponseEntity<?> convertContactToUser(
            @PathVariable Long contactId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        AppUser u = permissionService.resolveUser(auth);
        if (u == null || !permissionService.isAdminOrManager(u))
            return ResponseEntity.status(403).build();

        Optional<ClientContact> contactOpt = contactRepository.findById(contactId);
        if (contactOpt.isEmpty())
            return ResponseEntity.notFound().build();

        ClientContact contact = contactOpt.get();

        // Check if contact already has a user account
        if (contact.getUser() != null)
            return ResponseEntity.badRequest().body(Map.of("error", "Contact already has a user account",
                    "userId", contact.getUser().getId()));

        if (contact.getEmail() == null || contact.getEmail().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Contact has no email address"));

        if (userRepository.existsByEmail(contact.getEmail().toLowerCase()))
            return ResponseEntity.badRequest().body(Map.of("error", "Email already in use by another user"));

        String password = body.get("password");
        if (password == null || password.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "password is required"));

        // Use provided username or generate from email
        String requestedUsername = body.get("username") != null ? body.get("username").trim() : null;
        String username;
        if (requestedUsername != null && !requestedUsername.isBlank()) {
            if (userRepository.existsByUsername(requestedUsername))
                return ResponseEntity.badRequest().body(Map.of("error", "Username already taken"));
            username = requestedUsername;
        } else {
            username = contact.getEmail().toLowerCase().split("@")[0];
            int suffix = 1;
            while (userRepository.existsByUsername(username)) {
                username = contact.getEmail().toLowerCase().split("@")[0] + suffix++;
            }
        }

        AppUser clientUser = AppUser.builder()
                .name(contact.getName())
                .email(contact.getEmail().toLowerCase().trim())
                .username(username)
                .passwordHash(encoder.encode(password))
                .roles(new HashSet<>(Set.of(Role.USER)))
                .employmentType(EmploymentType.CLIENT)
                .active(true)
                .approved(true)
                .clientEntity(contact.getClient())
                .build();

        AppUser saved = userRepository.save(clientUser);

        // Link the contact back to the user
        contact.setUser(saved);
        contactRepository.save(contact);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", saved.getId());
        result.put("name", saved.getName());
        result.put("email", saved.getEmail());
        result.put("username", saved.getUsername());
        result.put("clientId", saved.getClientEntity() != null ? saved.getClientEntity().getId() : null);
        result.put("clientName", saved.getClientEntity() != null ? saved.getClientEntity().getName() : null);

        return ResponseEntity.status(201).body(result);
    }
}
