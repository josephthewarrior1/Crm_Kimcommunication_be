package com.pms.controller;

import com.pms.domain.Province;
import com.pms.repository.CountryRepository;
import com.pms.repository.ProvinceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/provinces")
public class ProvinceController {

    private final ProvinceRepository provinceRepository;
    private final CountryRepository countryRepository;

    public ProvinceController(ProvinceRepository provinceRepository, CountryRepository countryRepository) {
        this.provinceRepository = provinceRepository;
        this.countryRepository = countryRepository;
    }

    @GetMapping
    public List<Province> list(@RequestParam(required = false) Long countryId) {
        if (countryId != null) {
            return provinceRepository.findByCountryIdOrderByNameAsc(countryId);
        }
        return provinceRepository.findAllByOrderByNameAsc();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Province> get(@PathVariable Long id) {
        return provinceRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Province> create(@RequestBody Map<String, Object> body) {
        Province province = new Province();
        applyFields(province, body);
        Province saved = provinceRepository.save(province);
        return ResponseEntity.created(URI.create("/api/provinces/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Province> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return provinceRepository.findById(id).map(existing -> {
            applyFields(existing, body);
            return ResponseEntity.ok(provinceRepository.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!provinceRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        provinceRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private void applyFields(Province province, Map<String, Object> body) {
        if (body.containsKey("name")) {
            province.setName(str(body.get("name")));
        }
        if (body.containsKey("code")) {
            province.setCode(str(body.get("code")));
        }
        if (body.containsKey("countryId")) {
            Object countryIdObj = body.get("countryId");
            if (countryIdObj != null) {
                Long countryId = Long.parseLong(countryIdObj.toString());
                countryRepository.findById(countryId).ifPresent(province::setCountry);
            } else {
                province.setCountry(null);
            }
        }
    }

    private String str(Object obj) {
        if (obj == null) return null;
        String value = obj.toString().trim();
        return value.isEmpty() ? null : value;
    }
}
