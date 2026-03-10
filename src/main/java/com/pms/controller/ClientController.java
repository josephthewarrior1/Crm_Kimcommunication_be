package com.pms.controller;

import com.pms.domain.Client;
import com.pms.domain.Country;
import com.pms.domain.Industry;
import com.pms.domain.AppUser;
import com.pms.domain.Role;
import com.pms.repository.ClientRepository;
import com.pms.repository.CountryRepository;
import com.pms.repository.IndustryRepository;
import com.pms.repository.SessionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/clients")
public class ClientController {

    private final ClientRepository clientRepository;
    private final CountryRepository countryRepository;
    private final IndustryRepository industryRepository;
    private final SessionRepository sessions;

    public ClientController(ClientRepository clientRepository,
                           CountryRepository countryRepository,
                           IndustryRepository industryRepository,
                           SessionRepository sessions) {
        this.clientRepository = clientRepository;
        this.countryRepository = countryRepository;
        this.industryRepository = industryRepository;
        this.sessions = sessions;
    }

    @GetMapping
    public List<Client> listClients(@RequestHeader(value = "Authorization", required = false) String auth) {
        return clientRepository.findAllOrderByName();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Client> getClient(@PathVariable Long id,
                                          @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null) return ResponseEntity.status(401).build();
        return clientRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).build());
    }

    @PostMapping
    public ResponseEntity<?> createClient(@RequestBody Map<String, Object> body,
                                          @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null || !isAdminOrManager(u)) {
            return ResponseEntity.status(403).build();
        }

        String name = body.get("name") != null ? body.get("name").toString().trim() : null;
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Client name is required"));
        }

        if (clientRepository.findByName(name).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "A client with this name already exists"));
        }

        Client client = new Client();
        client.setName(name);
        resolveIndustry(body, client);
        resolveCountry(body, client);

        Client saved = clientRepository.save(client);
        return ResponseEntity.created(URI.create("/api/clients/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateClient(@PathVariable Long id,
                                          @RequestBody Map<String, Object> body,
                                          @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null || !isAdminOrManager(u)) {
            return ResponseEntity.status(403).build();
        }

        Optional<Client> existingOpt = clientRepository.findById(id);
        if (existingOpt.isEmpty()) {
            return ResponseEntity.status(404).build();
        }

        Client existing = existingOpt.get();
        String name = body.get("name") != null ? body.get("name").toString().trim() : null;
        if (name != null && !name.isBlank()) {
            if (clientRepository.existsByNameAndIdNot(name, id)) {
                return ResponseEntity.status(409).body(Map.of("error", "A client with this name already exists"));
            }
            existing.setName(name);
        }

        resolveIndustry(body, existing);
        resolveCountry(body, existing);

        Client saved = clientRepository.save(existing);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteClient(@PathVariable Long id,
                                             @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null || !isAdminOrManager(u)) {
            return ResponseEntity.status(403).build();
        }
        if (!clientRepository.existsById(id)) {
            return ResponseEntity.status(404).build();
        }
        clientRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/search")
    public List<Client> searchClients(@RequestParam String name,
                                     @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null) return List.of();
        return clientRepository.findByNameContainingIgnoreCase(name);
    }

    @GetMapping("/industry/{industry}")
    public List<Client> getClientsByIndustry(@PathVariable String industry,
                                            @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null) return List.of();
        return clientRepository.findByIndustryName(industry);
    }

    @GetMapping("/country/{country}")
    public List<Client> getClientsByCountry(@PathVariable String country,
                                           @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null) return List.of();
        return clientRepository.findByCountryName(country);
    }

    // --- Helper methods ---

    private void resolveIndustry(Map<String, Object> body, Client client) {
        Object industryObj = body.get("industry");
        Object industryIdObj = body.get("industryId");

        if (industryIdObj != null) {
            Long industryId = Long.valueOf(industryIdObj.toString());
            industryRepository.findById(industryId).ifPresent(client::setIndustry);
        } else if (industryObj instanceof Map) {
            Object iid = ((Map<?, ?>) industryObj).get("id");
            if (iid != null) {
                industryRepository.findById(Long.valueOf(iid.toString())).ifPresent(client::setIndustry);
            }
        } else if (industryObj instanceof String) {
            String industryName = ((String) industryObj).trim();
            if (!industryName.isEmpty()) {
                Industry industry = industryRepository.findByName(industryName)
                        .orElseGet(() -> industryRepository.save(Industry.builder().name(industryName).build()));
                client.setIndustry(industry);
            } else {
                client.setIndustry(null);
            }
        }
    }

    private void resolveCountry(Map<String, Object> body, Client client) {
        Object countryObj = body.get("country");
        Object countryIdObj = body.get("countryId");

        if (countryIdObj != null) {
            Long countryId = Long.valueOf(countryIdObj.toString());
            countryRepository.findById(countryId).ifPresent(client::setCountry);
        } else if (countryObj instanceof Map) {
            Object cid = ((Map<?, ?>) countryObj).get("id");
            if (cid != null) {
                countryRepository.findById(Long.valueOf(cid.toString())).ifPresent(client::setCountry);
            }
        } else if (countryObj instanceof String) {
            String countryName = ((String) countryObj).trim();
            if (!countryName.isEmpty()) {
                Country country = countryRepository.findByName(countryName)
                        .orElseGet(() -> countryRepository.save(Country.builder().name(countryName).build()));
                client.setCountry(country);
            } else {
                client.setCountry(null);
            }
        }
    }

    private AppUser currentUser(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        String token = auth.substring(7);
        return sessions.findByTokenAndRevokedFalse(token)
                .map(session -> session.getUser())
                .orElse(null);
    }

    private boolean isAdminOrManager(AppUser user) {
        return user.getRoles().contains(Role.ADMIN) || user.getRoles().contains(Role.MANAGER);
    }
}
