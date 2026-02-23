package com.pms.repository;

import com.pms.domain.Client;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Client entity operations.
 * Provides standard CRUD operations and custom query methods for client management.
 * 
 * @author Juan
 * @version 1.0
 */
public interface ClientRepository extends JpaRepository<Client, Long> {
    
    /**
     * Find a client by its exact name (case-sensitive)
     * 
     * @param name the client name to search for
     * @return Optional containing the client if found, empty otherwise
     */
    Optional<Client> findByName(String name);
    
    /**
     * Find clients by industry name
     *
     * @param name the industry name to filter by
     * @return list of clients in the specified industry
     */
    List<Client> findByIndustryName(String name);
    
    /**
     * Find clients by country
     * 
     * @param country the country to filter by
     * @return list of clients in the specified country
     */
    List<Client> findByCountry(String country);
    
    /**
     * Search clients by name containing the given text (case-insensitive)
     * 
     * @param name the text to search for in client names
     * @return list of clients whose names contain the search text
     */
    @Query("SELECT c FROM Client c WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :name, '%'))")
    List<Client> findByNameContainingIgnoreCase(@Param("name") String name);
    
    /**
     * Find all clients ordered by name alphabetically
     * 
     * @return list of all clients sorted by name
     */
    @Query("SELECT c FROM Client c ORDER BY c.name ASC")
    List<Client> findAllOrderByName();
    
    /**
     * Check if a client with the given name exists (excluding a specific client ID)
     * Useful for validation during updates
     * 
     * @param name the name to check
     * @param excludeId the client ID to exclude from the check
     * @return true if a client with this name exists (excluding the specified ID)
     */
    @Query("SELECT COUNT(c) > 0 FROM Client c WHERE c.name = :name AND c.id != :excludeId")
    boolean existsByNameAndIdNot(@Param("name") String name, @Param("excludeId") Long excludeId);
}
