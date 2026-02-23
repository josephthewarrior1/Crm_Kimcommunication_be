package com.pms.controller;

import com.pms.domain.Client;
import com.pms.domain.AppUser;
import com.pms.domain.Role;
import com.pms.repository.ClientRepository;
import com.pms.repository.SessionRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * REST Controller for Client management operations.
 * Provides CRUD operations for client entities with proper authorization.
 * 
 * @author Juan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/clients")
public class ClientController {

    private final ClientRepository clientRepository;
    private final SessionRepository sessions;

    public ClientController(ClientRepository clientRepository, 
                           SessionRepository sessions) {
        this.clientRepository = clientRepository;
        this.sessions = sessions;
    }

    /**
     * Get all clients
     * 
     * @param auth Authorization header
     * @return List of all clients
     */
    @GetMapping
    public List<Client> listClients(@RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null) return clientRepository.findAllOrderByName();
        if (isAdminOrManager(u)) return clientRepository.findAllOrderByName();
        // For now, all authenticated users can see all clients
        // In the future, you might want to implement client-specific access control
        return clientRepository.findAllOrderByName();
    }

    /**
     * Get a specific client by ID
     * 
     * @param id Client ID
     * @param auth Authorization header
     * @return Client entity or 404 if not found
     */
    @GetMapping("/{id}")
    public ResponseEntity<Client> getClient(@PathVariable Long id,
                                          @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null) return ResponseEntity.status(401).build();
        
        return clientRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).build());
    }

    /**
     * Create a new client
     * 
     * @param client Client entity to create
     * @param auth Authorization header
     * @return Created client entity
     */
    @PostMapping
    public ResponseEntity<Client> createClient(@Valid @RequestBody Client client,
                                              @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null || !isAdminOrManager(u)) {
            return ResponseEntity.status(403).build();
        }
        
        // Check if client with same name already exists
        if (clientRepository.findByName(client.getName()).isPresent()) {
            return ResponseEntity.status(409).build(); // Conflict
        }
        
        Client saved = clientRepository.save(client);
        return ResponseEntity.created(URI.create("/api/clients/" + saved.getId())).body(saved);
    }

    /**
     * Update an existing client
     * 
     * @param id Client ID to update
     * @param client Updated client data
     * @param auth Authorization header
     * @return Updated client entity or 404 if not found
     */
    @PutMapping("/{id}")
    public ResponseEntity<Client> updateClient(@PathVariable Long id,
                                               @Valid @RequestBody Client client,
                                               @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null || !isAdminOrManager(u)) {
            return ResponseEntity.status(403).build();
        }
        
        Optional<Client> existingClient = clientRepository.findById(id);
        if (existingClient.isEmpty()) {
            return ResponseEntity.status(404).build();
        }
        
        Client existing = existingClient.get();
        
        // Check if name conflict exists (excluding current client)
        if (clientRepository.existsByNameAndIdNot(client.getName(), id)) {
            return ResponseEntity.status(409).build(); // Conflict
        }
        
        client.setId(existing.getId());
        client.setCreatedAt(existing.getCreatedAt()); // Preserve creation date
        Client savedClient = clientRepository.save(client);
        return ResponseEntity.ok(savedClient);
    }

    /**
     * Delete a client
     * 
     * @param id Client ID to delete
     * @param auth Authorization header
     * @return 204 No Content on success, 404 if not found
     */
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

    /**
     * Search clients by name
     * 
     * @param name Search term for client name
     * @param auth Authorization header
     * @return List of matching clients
     */
    @GetMapping("/search")
    public List<Client> searchClients(@RequestParam String name,
                                     @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null) return List.of();
        
        return clientRepository.findByNameContainingIgnoreCase(name);
    }

    /**
     * Get clients by industry
     * 
     * @param industry Industry to filter by
     * @param auth Authorization header
     * @return List of clients in the specified industry
     */
    @GetMapping("/industry/{industry}")
    public List<Client> getClientsByIndustry(@PathVariable String industry,
                                            @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null) return List.of();
        
        return clientRepository.findByIndustryName(industry);
    }

    /**
     * Get clients by country
     * 
     * @param country Country to filter by
     * @param auth Authorization header
     * @return List of clients in the specified country
     */
    @GetMapping("/country/{country}")
    public List<Client> getClientsByCountry(@PathVariable String country,
                                           @RequestHeader(value = "Authorization", required = false) String auth) {
        AppUser u = currentUser(auth);
        if (u == null) return List.of();
        
        return clientRepository.findByCountry(country);
    }

    // --- Helper methods for authorization ---

    /**
     * Get current user from authorization header
     * 
     * @param auth Authorization header
     * @return Current user or null if not authenticated
     */
    private AppUser currentUser(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        String token = auth.substring(7);
        return sessions.findByTokenAndRevokedFalse(token)
                .map(session -> session.getUser())
                .orElse(null);
    }

    /**
     * Check if user has admin or manager role
     * 
     * @param user User to check
     * @return true if user is admin or manager
     */
    private boolean isAdminOrManager(AppUser user) {
        return user.getRoles().contains(Role.ADMIN) || user.getRoles().contains(Role.MANAGER);
    }
}
