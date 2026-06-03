package com.pms.controller;

import com.pms.domain.City;
import com.pms.repository.CityRepository;
import com.pms.repository.CountryRepository;
import com.pms.repository.ProvinceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cities")
public class CityController {

    private final CityRepository cityRepository;
    private final CountryRepository countryRepository;
    private final ProvinceRepository provinceRepository;

    public CityController(CityRepository cityRepository, CountryRepository countryRepository, ProvinceRepository provinceRepository) {
        this.cityRepository = cityRepository;
        this.countryRepository = countryRepository;
        this.provinceRepository = provinceRepository;
    }

    @GetMapping
    public List<City> list(@RequestParam(required = false) Long countryId, @RequestParam(required = false) Long provinceId) {
        if (provinceId != null) {
            return cityRepository.findByProvinceIdOrderByNameAsc(provinceId);
        }
        if (countryId != null) {
            return cityRepository.findByCountryIdOrderByNameAsc(countryId);
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
    public ResponseEntity<City> create(@RequestBody Map<String, Object> body) {
        City city = new City();
        applyFields(city, body);

        City saved = cityRepository.save(city);
        return ResponseEntity.created(URI.create("/api/cities/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<City> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return cityRepository.findById(id).map(existing -> {
            applyFields(existing, body);
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

    private void applyFields(City city, Map<String, Object> body) {
        if (body.containsKey("name")) {
            city.setName(str(body.get("name")));
        }
        if (body.containsKey("countryId")) {
            Object countryIdObj = body.get("countryId");
            if (countryIdObj != null) {
                Long countryId = Long.parseLong(countryIdObj.toString());
                countryRepository.findById(countryId).ifPresent(city::setCountry);
            } else {
                city.setCountry(null);
            }
        }
        if (body.containsKey("provinceId")) {
            Object provinceIdObj = body.get("provinceId");
            if (provinceIdObj != null) {
                Long provinceId = Long.parseLong(provinceIdObj.toString());
                provinceRepository.findById(provinceId).ifPresent(province -> {
                    city.setProvince(province);
                    if (province.getCountry() != null) {
                        city.setCountry(province.getCountry());
                    }
                });
            } else {
                city.setProvince(null);
            }
        }
        if (body.containsKey("latitude")) {
            city.setLatitude(decimal(body.get("latitude")));
        }
        if (body.containsKey("longitude")) {
            city.setLongitude(decimal(body.get("longitude")));
        }
    }

    private String str(Object obj) {
        if (obj == null) return null;
        String value = obj.toString().trim();
        return value.isEmpty() ? null : value;
    }

    private BigDecimal decimal(Object obj) {
        if (obj == null) return null;
        String value = obj.toString().trim();
        if (value.isEmpty()) return null;
        return new BigDecimal(value);
    }
}
