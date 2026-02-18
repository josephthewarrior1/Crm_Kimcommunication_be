package com.pms.controller;

import com.pms.domain.ClientContact;
import com.pms.repository.ClientContactRepository;
import com.pms.repository.ClientRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/clients/{clientId}/contacts")
public class ClientContactController {

    private final ClientContactRepository contactRepository;
    private final ClientRepository clientRepository;

    public ClientContactController(ClientContactRepository contactRepository,
                                   ClientRepository clientRepository) {
        this.contactRepository = contactRepository;
        this.clientRepository = clientRepository;
    }

    @GetMapping
    public List<ClientContact> list(@PathVariable Long clientId) {
        return contactRepository.findByClientIdOrderByName(clientId);
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable Long clientId, @RequestBody Map<String, Object> body) {
        return clientRepository.findById(clientId).map(client -> {
            ClientContact contact = ClientContact.builder()
                    .name(String.valueOf(body.getOrDefault("name", "")).trim())
                    .email(body.get("email") != null ? String.valueOf(body.get("email")).trim() : null)
                    .phone(body.get("phone") != null ? String.valueOf(body.get("phone")).trim() : null)
                    .jobTitle(body.get("jobTitle") != null ? String.valueOf(body.get("jobTitle")).trim() : null)
                    .primary(body.get("isPrimary") instanceof Boolean b ? b : false)
                    .client(client)
                    .build();
            if (contact.getName().isEmpty()) {
                return ResponseEntity.badRequest().body((Object) Map.of("error", "Name is required"));
            }
            contactRepository.save(contact);
            return ResponseEntity.ok((Object) Map.of(
                    "id", contact.getId(),
                    "name", contact.getName(),
                    "email", contact.getEmail(),
                    "phone", contact.getPhone(),
                    "jobTitle", contact.getJobTitle(),
                    "isPrimary", contact.isPrimary(),
                    "clientId", clientId
            ));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{contactId}")
    public ResponseEntity<?> update(@PathVariable Long clientId,
                                    @PathVariable Long contactId,
                                    @RequestBody Map<String, Object> body) {
        return contactRepository.findById(contactId).map(contact -> {
            if (!contact.getClient().getId().equals(clientId)) {
                return ResponseEntity.notFound().build();
            }
            if (body.containsKey("name"))
                contact.setName(String.valueOf(body.get("name")).trim());
            if (body.containsKey("email"))
                contact.setEmail(body.get("email") != null ? String.valueOf(body.get("email")).trim() : null);
            if (body.containsKey("phone"))
                contact.setPhone(body.get("phone") != null ? String.valueOf(body.get("phone")).trim() : null);
            if (body.containsKey("jobTitle"))
                contact.setJobTitle(body.get("jobTitle") != null ? String.valueOf(body.get("jobTitle")).trim() : null);
            if (body.containsKey("isPrimary"))
                contact.setPrimary(body.get("isPrimary") instanceof Boolean b ? b : false);
            contactRepository.save(contact);
            return ResponseEntity.ok().build();
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{contactId}")
    public ResponseEntity<?> delete(@PathVariable Long clientId, @PathVariable Long contactId) {
        return contactRepository.findById(contactId).map(contact -> {
            if (!contact.getClient().getId().equals(clientId)) {
                return ResponseEntity.notFound().build();
            }
            contactRepository.delete(contact);
            return ResponseEntity.noContent().build();
        }).orElse(ResponseEntity.notFound().build());
    }
}
