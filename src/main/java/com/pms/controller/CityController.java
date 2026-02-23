package com.pms.controller;

import com.pms.domain.City;
import com.pms.repository.CityRepository;
import com.pms.repository.CountryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/cities")
public class CityController {

    private final CityRepository cityRepository;
    private final CountryRepository countryRepository;

    public CityController(CityRepository cityRepository, CountryRepository countryRepository) {
        this.cityRepository = cityRepository;
        this.countryRepository = countryRepository;
    }

    @GetMapping
    public List<City> list(@RequestParam(required = false) Long countryId) {
        if (countryId != null) {
            return cityRepository.findByCountryId(countryId);
        }
        return cityRepository.findAllByOrderByNameAsc();
    }

    @GetMapping("/{id}")
    public ResponseEntity<City> get(@PathVariable Long id) {
        return cityRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<City> create(@RequestBody java.util.Map<String, Object> body) {
        City city = new City();
        city.setName((String) body.get("name"));

        Object countryIdObj = body.get("countryId");
        if (countryIdObj != null) {
            Long cid = Long.parseLong(countryIdObj.toString());
            countryRepository.findById(cid).ifPresent(city::setCountry);
        }

        City saved = cityRepository.save(city);
        return ResponseEntity.created(URI.create("/api/cities/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<City> update(@PathVariable Long id, @RequestBody java.util.Map<String, Object> body) {
        return cityRepository.findById(id).map(existing -> {
            if (body.containsKey("name")) {
                existing.setName((String) body.get("name"));
            }
            if (body.containsKey("countryId")) {
                Object countryIdObj = body.get("countryId");
                if (countryIdObj != null) {
                    Long cid = Long.parseLong(countryIdObj.toString());
                    countryRepository.findById(cid).ifPresent(existing::setCountry);
                } else {
                    existing.setCountry(null);
                }
            }
            return ResponseEntity.ok(cityRepository.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!cityRepository.existsById(id))
            return ResponseEntity.notFound().build();
        cityRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
