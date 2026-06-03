package com.pms.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.Vendor;
import com.pms.repository.VendorRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/vendors")
public class VendorController {

    private final VendorRepository vendorRepository;
    private final ObjectMapper objectMapper;

    public VendorController(VendorRepository vendorRepository, ObjectMapper objectMapper) {
        this.vendorRepository = vendorRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return vendorRepository.findAllByOrderByNameAsc().stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        return vendorRepository.findById(id)
                .map(vendor -> ResponseEntity.ok(toResponse(vendor)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Vendor vendor = new Vendor();
        applyFields(vendor, body);
        Vendor saved = vendorRepository.save(vendor);
        return ResponseEntity.created(URI.create("/api/vendors/" + saved.getId())).body(toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return vendorRepository.findById(id).map(existing -> {
            applyFields(existing, body);
            return ResponseEntity.ok(toResponse(vendorRepository.save(existing)));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!vendorRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        vendorRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private void applyFields(Vendor vendor, Map<String, Object> body) {
        vendor.setName(str(body.get("name")));
        vendor.setOwner(str(body.get("owner")));
        vendor.setPicName(str(body.get("picName")));
        vendor.setPicPhone(str(body.get("picPhone")));
        vendor.setPicEmail(str(body.get("picEmail")));
        vendor.setCategoryTypesJson(writeList(stringList(body.get("categoryTypes"))));
    }

    private Map<String, Object> toResponse(Vendor vendor) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", vendor.getId());
        response.put("name", vendor.getName());
        response.put("owner", vendor.getOwner());
        response.put("picName", vendor.getPicName());
        response.put("picPhone", vendor.getPicPhone());
        response.put("picEmail", vendor.getPicEmail());
        response.put("categoryTypes", readList(vendor.getCategoryTypesJson()));
        response.put("createdAt", vendor.getCreatedAt());
        response.put("updatedAt", vendor.getUpdatedAt());
        return response;
    }

    private List<String> stringList(Object obj) {
        if (obj == null) {
            return List.of();
        }
        if (obj instanceof List<?> list) {
            return list.stream()
                    .map(item -> item == null ? null : item.toString().trim())
                    .filter(item -> item != null && !item.isBlank())
                    .distinct()
                    .collect(Collectors.toList());
        }
        String raw = obj.toString().trim();
        if (raw.isBlank()) {
            return List.of();
        }
        return readList(raw);
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String writeList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String str(Object obj) {
        if (obj == null) {
            return null;
        }
        String value = obj.toString().trim();
        return value.isEmpty() ? null : value;
    }
}
