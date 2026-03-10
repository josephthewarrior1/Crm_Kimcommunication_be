package com.pms.controller;

import com.pms.domain.City;
import com.pms.domain.Client;
import com.pms.domain.ClientContact;
import com.pms.domain.Venue;
import com.pms.repository.CityRepository;
import com.pms.repository.ClientContactRepository;
import com.pms.repository.ClientRepository;
import com.pms.repository.VenueRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/validate")
public class ValidationController {

    private final ClientRepository clientRepository;
    private final VenueRepository venueRepository;
    private final CityRepository cityRepository;
    private final ClientContactRepository clientContactRepository;

    public ValidationController(ClientRepository clientRepository,
                                VenueRepository venueRepository,
                                CityRepository cityRepository,
                                ClientContactRepository clientContactRepository) {
        this.clientRepository = clientRepository;
        this.venueRepository = venueRepository;
        this.cityRepository = cityRepository;
        this.clientContactRepository = clientContactRepository;
    }

    @GetMapping("/fuzzy-match")
    public ResponseEntity<Map<String, Object>> fuzzyMatch(
            @RequestParam String type,
            @RequestParam String value) {

        Map<String, Object> result = new HashMap<>();
        String trimmed = value.trim();

        if (trimmed.isEmpty()) {
            result.put("match", false);
            result.put("suggestion", null);
            return ResponseEntity.ok(result);
        }

        String suggestion = null;

        switch (type.toLowerCase()) {
            case "client":
                suggestion = findClientMatch(trimmed);
                break;
            case "venue":
                suggestion = findVenueMatch(trimmed);
                break;
            case "city":
                suggestion = findCityMatch(trimmed);
                break;
            case "contact":
                suggestion = findContactMatch(trimmed);
                break;
            default:
                result.put("match", false);
                result.put("suggestion", null);
                return ResponseEntity.ok(result);
        }

        result.put("match", suggestion != null);
        result.put("suggestion", suggestion);
        return ResponseEntity.ok(result);
    }

    private String findClientMatch(String input) {
        List<Client> clients = clientRepository.findAllOrderByName();
        for (Client client : clients) {
            if (client.getName().trim().equalsIgnoreCase(input)) {
                return client.getName();
            }
        }
        return null;
    }

    private String findVenueMatch(String input) {
        List<Venue> venues = venueRepository.findAllByOrderByNameAsc();
        for (Venue venue : venues) {
            if (venue.getName().trim().equalsIgnoreCase(input)) {
                return venue.getName();
            }
        }
        return null;
    }

    private String findCityMatch(String input) {
        List<City> cities = cityRepository.findAllByOrderByNameAsc();
        for (City city : cities) {
            if (city.getName().trim().equalsIgnoreCase(input)) {
                return city.getName();
            }
        }
        return null;
    }

    private String findContactMatch(String input) {
        List<ClientContact> contacts = clientContactRepository.findAll();
        for (ClientContact contact : contacts) {
            if (contact.getName().trim().equalsIgnoreCase(input)) {
                return contact.getName();
            }
        }
        return null;
    }
}
