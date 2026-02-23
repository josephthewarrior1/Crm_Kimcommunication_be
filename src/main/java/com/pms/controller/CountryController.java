package com.pms.controller;

import com.pms.domain.Country;
import com.pms.repository.CountryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/countries")
public class CountryController {

    private final CountryRepository countryRepository;

    public CountryController(CountryRepository countryRepository) {
        this.countryRepository = countryRepository;
    }

    @GetMapping
    public List<Country> list() {
        return countryRepository.findAllByOrderByNameAsc();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Country> get(@PathVariable Long id) {
        return countryRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Country> create(@RequestBody Country country) {
        country.setId(null);
        Country saved = countryRepository.save(country);
        return ResponseEntity.created(URI.create("/api/countries/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Country> update(@PathVariable Long id, @RequestBody Country country) {
        return countryRepository.findById(id).map(existing -> {
            if (country.getName() != null) existing.setName(country.getName());
            if (country.getCode() != null) existing.setCode(country.getCode());
            return ResponseEntity.ok(countryRepository.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!countryRepository.existsById(id))
            return ResponseEntity.notFound().build();
        countryRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
