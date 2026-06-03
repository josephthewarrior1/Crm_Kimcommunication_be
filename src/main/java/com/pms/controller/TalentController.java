package com.pms.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.City;
import com.pms.domain.Talent;
import com.pms.domain.Vendor;
import com.pms.repository.CityRepository;
import com.pms.repository.TalentRepository;
import com.pms.repository.VendorRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/talents")
public class TalentController {

    private static final Set<String> ALLOWED_CATEGORIES = Set.of("mc", "usher", "sales promotion", "cosplayer", "dancer");

    private final TalentRepository talentRepository;
    private final VendorRepository vendorRepository;
    private final CityRepository cityRepository;
    private final ObjectMapper objectMapper;
    private final Path photoUploadDir;
    private final Path cvUploadDir;

    public TalentController(
            TalentRepository talentRepository,
            VendorRepository vendorRepository,
            CityRepository cityRepository,
            ObjectMapper objectMapper,
            @Value("${storage.local-dir:uploads}") String storageLocalDir
    ) throws IOException {
        this.talentRepository = talentRepository;
        this.vendorRepository = vendorRepository;
        this.cityRepository = cityRepository;
        this.objectMapper = objectMapper;
        this.photoUploadDir = Paths.get(storageLocalDir, "talent_photos").toAbsolutePath().normalize();
        this.cvUploadDir = Paths.get(storageLocalDir, "talent_cvs").toAbsolutePath().normalize();
        Files.createDirectories(this.photoUploadDir);
        Files.createDirectories(this.cvUploadDir);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return talentRepository.findAllByOrderByNameAsc().stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        return talentRepository.findById(id)
                .map(talent -> ResponseEntity.ok(toResponse(talent)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> create(
            @RequestParam Map<String, String> params,
            @RequestParam(value = "photo", required = false) MultipartFile photo,
            @RequestParam(value = "cv", required = false) MultipartFile cv
    ) {
        try {
            Talent talent = new Talent();
            applyFields(talent, params, photo, cv);
            Talent saved = talentRepository.save(talent);
            return ResponseEntity.created(URI.create("/api/talents/" + saved.getId())).body(toResponse(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to store file: " + e.getMessage()));
        }
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @RequestParam Map<String, String> params,
            @RequestParam(value = "photo", required = false) MultipartFile photo,
            @RequestParam(value = "cv", required = false) MultipartFile cv
    ) {
        return talentRepository.findById(id).map(existing -> {
            try {
                applyFields(existing, params, photo, cv);
                Talent saved = talentRepository.save(existing);
                return ResponseEntity.ok((Object) toResponse(saved));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body((Object) Map.of("error", e.getMessage()));
            } catch (IOException e) {
                return ResponseEntity.internalServerError().body((Object) Map.of("error", "Failed to store file: " + e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return talentRepository.findById(id).map(talent -> {
            deleteStoredFile(talent.getPhotoUrl(), photoUploadDir, "/files/talent_photos/");
            deleteStoredFile(talent.getCvUrl(), cvUploadDir, "/files/talent_cvs/");
            talentRepository.delete(talent);
            return ResponseEntity.noContent().<Void>build();
        }).orElse(ResponseEntity.notFound().build());
    }

    private void applyFields(Talent talent, Map<String, String> params, MultipartFile photo, MultipartFile cv) throws IOException {
        talent.setVendor(resolveVendor(params.get("vendorId")));
        talent.setCategoriesJson(writeList(sanitizeCategories(readList(params.get("categories")))));
        talent.setName(required(params.get("name"), "Talent name is required"));
        talent.setDescription(str(params.get("description")));
        talent.setIdNumber(str(params.get("idNumber")));
        talent.setPhoneCode(str(params.get("phoneCode")));
        talent.setPhone(str(params.get("phone")));
        talent.setEmail(str(params.get("email")));
        talent.setSocialMediaLink(str(params.get("socialMediaLink")));
        talent.setCity(resolveCity(params.get("cityId")));
        talent.setSex(str(params.get("sex")));
        talent.setHeightCm(integer(params.get("heightCm")));
        talent.setLanguagesJson(writeList(readList(params.get("languages"))));
        talent.setRate(decimal(params.get("rate")));
        talent.setHalfdayRate(decimal(params.get("halfdayRate")));
        talent.setFulldayRate(decimal(params.get("fulldayRate")));
        talent.setBankName(str(params.get("bankName")));
        talent.setAccountNumber(str(params.get("accountNumber")));
        talent.setPic(str(params.get("pic")));
        talent.setInfoSource(str(params.get("infoSource")));
        talent.setGadgetProficient(bool(params.get("gadgetProficient")));
        talent.setRemarks(str(params.get("remarks")));

        if (bool(params.get("removePhoto"))) {
            deleteStoredFile(talent.getPhotoUrl(), photoUploadDir, "/files/talent_photos/");
            talent.setPhotoFileName(null);
            talent.setPhotoUrl(null);
        }
        if (bool(params.get("removeCv"))) {
            deleteStoredFile(talent.getCvUrl(), cvUploadDir, "/files/talent_cvs/");
            talent.setCvFileName(null);
            talent.setCvUrl(null);
        }

        if (photo != null && !photo.isEmpty()) {
            deleteStoredFile(talent.getPhotoUrl(), photoUploadDir, "/files/talent_photos/");
            StoredFile storedPhoto = storeFile(photo, photoUploadDir, "/files/talent_photos/");
            talent.setPhotoFileName(storedPhoto.originalName());
            talent.setPhotoUrl(storedPhoto.url());
        }

        if (cv != null && !cv.isEmpty()) {
            deleteStoredFile(talent.getCvUrl(), cvUploadDir, "/files/talent_cvs/");
            StoredFile storedCv = storeFile(cv, cvUploadDir, "/files/talent_cvs/");
            talent.setCvFileName(storedCv.originalName());
            talent.setCvUrl(storedCv.url());
        }
    }

    private Map<String, Object> toResponse(Talent talent) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", talent.getId());
        response.put("vendor", talent.getVendor() == null ? null : Map.of(
                "id", talent.getVendor().getId(),
                "name", talent.getVendor().getName()
        ));
        response.put("categories", readList(talent.getCategoriesJson()));
        response.put("name", talent.getName());
        response.put("description", talent.getDescription());
        response.put("idNumber", talent.getIdNumber());
        response.put("phoneCode", talent.getPhoneCode());
        response.put("phone", talent.getPhone());
        response.put("email", talent.getEmail());
        response.put("socialMediaLink", talent.getSocialMediaLink());
        response.put("city", talent.getCity() == null ? null : Map.of(
                "id", talent.getCity().getId(),
                "name", talent.getCity().getName()
        ));
        response.put("sex", talent.getSex());
        response.put("heightCm", talent.getHeightCm());
        response.put("photoFileName", talent.getPhotoFileName());
        response.put("photoUrl", talent.getPhotoUrl());
        response.put("languages", readList(talent.getLanguagesJson()));
        response.put("rate", talent.getRate());
        response.put("halfdayRate", talent.getHalfdayRate());
        response.put("fulldayRate", talent.getFulldayRate());
        response.put("bankName", talent.getBankName());
        response.put("accountNumber", talent.getAccountNumber());
        response.put("pic", talent.getPic());
        response.put("infoSource", talent.getInfoSource());
        response.put("gadgetProficient", Boolean.TRUE.equals(talent.getGadgetProficient()));
        response.put("cvFileName", talent.getCvFileName());
        response.put("cvUrl", talent.getCvUrl());
        response.put("remarks", talent.getRemarks());
        response.put("createdAt", talent.getCreatedAt());
        response.put("updatedAt", talent.getUpdatedAt());
        return response;
    }

    private Vendor resolveVendor(String vendorId) {
        Long id = longValue(vendorId);
        if (id == null) {
            return null;
        }
        return vendorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Vendor not found"));
    }

    private City resolveCity(String cityId) {
        Long id = longValue(cityId);
        if (id == null) {
            return null;
        }
        return cityRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("City not found"));
    }

    private List<String> sanitizeCategories(List<String> categories) {
        return categories.stream()
                .map(value -> value == null ? null : value.trim().toLowerCase())
                .filter(value -> value != null && !value.isBlank())
                .filter(ALLOWED_CATEGORIES::contains)
                .distinct()
                .collect(Collectors.toList());
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

    private StoredFile storeFile(MultipartFile file, Path directory, String urlPrefix) throws IOException {
        String original = StringUtils.cleanPath(file.getOriginalFilename() == null ? "file" : file.getOriginalFilename());
        String ext = "";
        int dot = original.lastIndexOf('.');
        if (dot > 0 && dot < original.length() - 1) {
            ext = original.substring(dot);
        }
        String storedName = UUID.randomUUID().toString().replace("-", "") + ext;
        Files.copy(file.getInputStream(), directory.resolve(storedName), StandardCopyOption.REPLACE_EXISTING);
        return new StoredFile(original, urlPrefix + storedName);
    }

    private void deleteStoredFile(String fileUrl, Path directory, String prefix) {
        if (fileUrl != null && fileUrl.startsWith(prefix)) {
            String storedName = fileUrl.replace(prefix, "");
            try {
                Files.deleteIfExists(directory.resolve(storedName));
            } catch (IOException ignored) {
            }
        }
    }

    private String required(String value, String errorMessage) {
        String next = str(value);
        if (next == null) {
            throw new IllegalArgumentException(errorMessage);
        }
        return next;
    }

    private String str(Object obj) {
        if (obj == null) {
            return null;
        }
        String value = obj.toString().trim();
        return value.isEmpty() ? null : value;
    }

    private Long longValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid numeric id");
        }
    }

    private Integer integer(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer value");
        }
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid decimal value");
        }
    }

    private boolean bool(String value) {
        return value != null && ("true".equalsIgnoreCase(value) || "1".equals(value) || "on".equalsIgnoreCase(value));
    }

    private record StoredFile(String originalName, String url) {}
}
