package com.pms.controller;

import com.pms.domain.ClientContact;
import com.pms.repository.ClientContactRepository;
import com.pms.repository.ClientRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
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
                    .phoneCode(body.get("phoneCode") != null ? String.valueOf(body.get("phoneCode")).trim() : null)
                    .phone(body.get("phone") != null ? String.valueOf(body.get("phone")).trim() : null)
                    .jobTitle(body.get("jobTitle") != null ? String.valueOf(body.get("jobTitle")).trim() : null)
                    .birthdate(parseBirthdate(resolveBirthdateValue(body)))
                    .religion(body.get("religion") != null ? String.valueOf(body.get("religion")).trim() : null)
                    .hobbies(body.get("hobbies") != null ? String.valueOf(body.get("hobbies")).trim() : null)
                    .familyMembers(body.get("familyMembers") != null ? String.valueOf(body.get("familyMembers")).trim() : null)
                    .homeAddress(body.get("homeAddress") != null ? String.valueOf(body.get("homeAddress")).trim() : null)
                    .notes(body.get("notes") != null ? String.valueOf(body.get("notes")).trim() : null)
                    .primary(body.get("isPrimary") instanceof Boolean b ? b : false)
                    .client(client)
                    .build();
            if (contact.getName().isEmpty()) {
                return ResponseEntity.badRequest().body((Object) Map.of("error", "Name is required"));
            }
            Object birthdateValue = resolveBirthdateValue(body);
            if (hasInvalidBirthdateValue(birthdateValue, contact.getBirthdate())) {
                return ResponseEntity.badRequest().body((Object) Map.of("error", "Birthdate must be in YYYY-MM-DD format"));
            }
            contactRepository.save(contact);
            return ResponseEntity.ok((Object) toResponse(contact, clientId));
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
            if (body.containsKey("phoneCode"))
                contact.setPhoneCode(body.get("phoneCode") != null ? String.valueOf(body.get("phoneCode")).trim() : null);
            if (body.containsKey("phone"))
                contact.setPhone(body.get("phone") != null ? String.valueOf(body.get("phone")).trim() : null);
            if (body.containsKey("jobTitle"))
                contact.setJobTitle(body.get("jobTitle") != null ? String.valueOf(body.get("jobTitle")).trim() : null);
            if (body.containsKey("birthdate") || body.containsKey("birthday")) {
                Object birthdateValue = resolveBirthdateValue(body);
                LocalDate birthdate = parseBirthdate(birthdateValue);
                if (hasInvalidBirthdateValue(birthdateValue, birthdate)) {
                    return ResponseEntity.badRequest().body((Object) Map.of("error", "Birthdate must be in YYYY-MM-DD format"));
                }
                contact.setBirthdate(birthdate);
            }
            if (body.containsKey("religion"))
                contact.setReligion(body.get("religion") != null ? String.valueOf(body.get("religion")).trim() : null);
            if (body.containsKey("hobbies"))
                contact.setHobbies(body.get("hobbies") != null ? String.valueOf(body.get("hobbies")).trim() : null);
            if (body.containsKey("familyMembers"))
                contact.setFamilyMembers(body.get("familyMembers") != null ? String.valueOf(body.get("familyMembers")).trim() : null);
            if (body.containsKey("homeAddress"))
                contact.setHomeAddress(body.get("homeAddress") != null ? String.valueOf(body.get("homeAddress")).trim() : null);
            if (body.containsKey("notes"))
                contact.setNotes(body.get("notes") != null ? String.valueOf(body.get("notes")).trim() : null);
            if (body.containsKey("isPrimary"))
                contact.setPrimary(body.get("isPrimary") instanceof Boolean b ? b : false);
            contactRepository.save(contact);
            return ResponseEntity.ok((Object) toResponse(contact, clientId));
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

    private Map<String, Object> toResponse(ClientContact contact, Long clientId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", contact.getId());
        response.put("name", contact.getName());
        response.put("email", contact.getEmail());
        response.put("phoneCode", contact.getPhoneCode());
        response.put("phone", contact.getPhone());
        response.put("jobTitle", contact.getJobTitle());
        response.put("birthdate", contact.getBirthdate());
        response.put("religion", contact.getReligion());
        response.put("hobbies", contact.getHobbies());
        response.put("familyMembers", contact.getFamilyMembers());
        response.put("homeAddress", contact.getHomeAddress());
        response.put("notes", contact.getNotes());
        response.put("isPrimary", contact.isPrimary());
        response.put("clientId", clientId);
        return response;
    }

    private Object resolveBirthdateValue(Map<String, Object> body) {
        return body.containsKey("birthdate") ? body.get("birthdate") : body.get("birthday");
    }

    private LocalDate parseBirthdate(Object value) {
        if (value == null) {
            return null;
        }

        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }

        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private boolean hasInvalidBirthdateValue(Object rawValue, LocalDate parsedValue) {
        return rawValue != null
                && !String.valueOf(rawValue).trim().isEmpty()
                && parsedValue == null;
    }
}
