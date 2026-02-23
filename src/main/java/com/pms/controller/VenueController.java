package com.pms.controller;

import com.pms.domain.Venue;
import com.pms.domain.VenueAttachment;
import com.pms.repository.CityRepository;
import com.pms.repository.VenueAttachmentRepository;
import com.pms.repository.VenueRepository;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/venues")
public class VenueController {

    private static final int MAX_ATTACHMENTS_PER_VENUE = 10;

    private final VenueRepository venueRepository;
    private final CityRepository cityRepository;
    private final VenueAttachmentRepository venueAttachmentRepository;
    private final Path venueUploadDir;

    public VenueController(VenueRepository venueRepository, CityRepository cityRepository,
                           VenueAttachmentRepository venueAttachmentRepository,
                           @Value("${storage.local-dir:uploads}") String storageLocalDir) throws IOException {
        this.venueRepository = venueRepository;
        this.cityRepository = cityRepository;
        this.venueAttachmentRepository = venueAttachmentRepository;
        this.venueUploadDir = Paths.get(storageLocalDir, "venue_attachments").toAbsolutePath().normalize();
        Files.createDirectories(this.venueUploadDir);
    }

    @GetMapping
    public List<Venue> list() {
        return venueRepository.findAllByOrderByNameAsc();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Venue> get(@PathVariable Long id) {
        return venueRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Venue> create(@RequestBody Map<String, Object> body) {
        Venue venue = new Venue();
        applyFields(venue, body);
        Venue saved = venueRepository.save(venue);
        return ResponseEntity.created(URI.create("/api/venues/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Venue> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return venueRepository.findById(id).map(existing -> {
            applyFields(existing, body);
            return ResponseEntity.ok(venueRepository.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!venueRepository.existsById(id))
            return ResponseEntity.notFound().build();
        venueRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private void applyFields(Venue venue, Map<String, Object> body) {
        if (body.containsKey("name")) {
            venue.setName((String) body.get("name"));
        }
        if (body.containsKey("cityId")) {
            Object cityIdObj = body.get("cityId");
            if (cityIdObj != null) {
                Long cid = Long.parseLong(cityIdObj.toString());
                cityRepository.findById(cid).ifPresent(venue::setCity);
            } else {
                venue.setCity(null);
            }
        }
        if (body.containsKey("documentData")) {
            venue.setDocumentData((String) body.get("documentData"));
        }

        // Ballroom
        if (body.containsKey("ballroomName")) venue.setBallroomName(str(body.get("ballroomName")));
        if (body.containsKey("ballroomFloor")) venue.setBallroomFloor(str(body.get("ballroomFloor")));
        if (body.containsKey("ballroomLength")) venue.setBallroomLength(decimal(body.get("ballroomLength")));
        if (body.containsKey("ballroomWidth")) venue.setBallroomWidth(decimal(body.get("ballroomWidth")));
        if (body.containsKey("ballroomHeight")) venue.setBallroomHeight(decimal(body.get("ballroomHeight")));

        // Foyer
        if (body.containsKey("foyerLength")) venue.setFoyerLength(decimal(body.get("foyerLength")));
        if (body.containsKey("foyerWidth")) venue.setFoyerWidth(decimal(body.get("foyerWidth")));
        if (body.containsKey("foyerHeight")) venue.setFoyerHeight(decimal(body.get("foyerHeight")));

        // Screen
        if (body.containsKey("screenLength")) venue.setScreenLength(decimal(body.get("screenLength")));
        if (body.containsKey("screenWidth")) venue.setScreenWidth(decimal(body.get("screenWidth")));
        if (body.containsKey("existingScreen")) venue.setExistingScreen(bool(body.get("existingScreen")));

        // IBM Table
        if (body.containsKey("ibmTableLength")) venue.setIbmTableLength(decimal(body.get("ibmTableLength")));
        if (body.containsKey("ibmTableWidth")) venue.setIbmTableWidth(decimal(body.get("ibmTableWidth")));
        if (body.containsKey("ibmTableHeight")) venue.setIbmTableHeight(decimal(body.get("ibmTableHeight")));

        // Round Table
        if (body.containsKey("roundTableDiameter")) venue.setRoundTableDiameter(decimal(body.get("roundTableDiameter")));
        if (body.containsKey("roundTableHeight")) venue.setRoundTableHeight(decimal(body.get("roundTableHeight")));

        // Electrical
        if (body.containsKey("electricityWatt")) venue.setElectricityWatt(integer(body.get("electricityWatt")));
        if (body.containsKey("soundSystemWatt")) venue.setSoundSystemWatt(integer(body.get("soundSystemWatt")));

        // Microphone
        if (body.containsKey("microphoneProvided")) venue.setMicrophoneProvided(bool(body.get("microphoneProvided")));
        if (body.containsKey("microphoneModel")) venue.setMicrophoneModel(str(body.get("microphoneModel")));
        if (body.containsKey("microphoneQuantity")) venue.setMicrophoneQuantity(integer(body.get("microphoneQuantity")));

        // Stage
        if (body.containsKey("totalStageModules")) venue.setTotalStageModules(integer(body.get("totalStageModules")));

        // Generator
        if (body.containsKey("generatorAllowed")) venue.setGeneratorAllowed(bool(body.get("generatorAllowed")));
    }

    // ── Venue Attachments ─────────────────────────────────────────────

    @GetMapping("/{venueId}/attachments")
    public ResponseEntity<List<VenueAttachment>> listAttachments(@PathVariable Long venueId) {
        if (!venueRepository.existsById(venueId))
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok(venueAttachmentRepository.findByVenueIdOrderByCreatedAtDesc(venueId));
    }

    @GetMapping("/{venueId}/attachments/count")
    public ResponseEntity<Map<String, Object>> attachmentCount(@PathVariable Long venueId) {
        if (!venueRepository.existsById(venueId))
            return ResponseEntity.notFound().build();
        long count = venueAttachmentRepository.countByVenueId(venueId);
        return ResponseEntity.ok(Map.of(
                "count", count,
                "max", MAX_ATTACHMENTS_PER_VENUE,
                "remaining", MAX_ATTACHMENTS_PER_VENUE - count));
    }

    @PostMapping(value = "/{venueId}/attachments", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadAttachment(
            @PathVariable Long venueId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "label", required = false) String label,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "category", required = false) String category) {
        return venueRepository.findById(venueId).map(venue -> {
            // Check limit
            long count = venueAttachmentRepository.countByVenueId(venueId);
            if (count >= MAX_ATTACHMENTS_PER_VENUE) {
                return ResponseEntity.badRequest().body(
                        (Object) Map.of("error", "Maximum of " + MAX_ATTACHMENTS_PER_VENUE + " attachments per venue reached."));
            }

            try {
                // Store file
                String original = StringUtils
                        .cleanPath(file.getOriginalFilename() == null ? "file" : file.getOriginalFilename());
                String ext = "";
                int dot = original.lastIndexOf('.');
                if (dot > 0 && dot < original.length() - 1) {
                    ext = original.substring(dot);
                }
                String storedName = UUID.randomUUID().toString().replace("-", "") + ext;
                Path target = venueUploadDir.resolve(storedName);
                Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

                VenueAttachment att = new VenueAttachment();
                att.setVenue(venue);
                att.setLabel(label);
                att.setDescription(description);
                att.setFileName(original);
                att.setFileUrl("/files/venue_attachments/" + storedName);
                att.setFileType(file.getContentType());
                att.setFileSize(file.getSize());
                att.setCategory(category);
                VenueAttachment saved = venueAttachmentRepository.save(att);
                return ResponseEntity.created(
                        URI.create("/api/venues/" + venueId + "/attachments/" + saved.getId())).body((Object) saved);
            } catch (IOException e) {
                return ResponseEntity.internalServerError().body(
                        (Object) Map.of("error", "Failed to store file: " + e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{venueId}/attachments/{attachmentId}")
    public ResponseEntity<Void> deleteAttachment(@PathVariable Long venueId, @PathVariable Long attachmentId) {
        if (!venueRepository.existsById(venueId))
            return ResponseEntity.notFound().build();
        return venueAttachmentRepository.findById(attachmentId).map(att -> {
            // Try to delete the physical file
            if (att.getFileUrl() != null && att.getFileUrl().startsWith("/files/venue_attachments/")) {
                String storedName = att.getFileUrl().replace("/files/venue_attachments/", "");
                try { Files.deleteIfExists(venueUploadDir.resolve(storedName)); } catch (IOException ignored) {}
            }
            venueAttachmentRepository.deleteById(attachmentId);
            return ResponseEntity.noContent().<Void>build();
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Private helpers ──────────────────────────────────────────────

    private String str(Object obj) {
        return obj != null ? obj.toString() : null;
    }

    private BigDecimal decimal(Object obj) {
        if (obj == null) return null;
        try { return new BigDecimal(obj.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    private Integer integer(Object obj) {
        if (obj == null) return null;
        try { return Integer.parseInt(obj.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    private Boolean bool(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Boolean) return (Boolean) obj;
        return Boolean.parseBoolean(obj.toString());
    }
}
