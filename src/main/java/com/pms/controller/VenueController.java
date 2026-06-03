package com.pms.controller;

import com.pms.domain.Venue;
import com.pms.domain.VenueAttachment;
import com.pms.domain.VenueRoom;
import com.pms.repository.CityRepository;
import com.pms.repository.VenueAttachmentRepository;
import com.pms.repository.VenueRepository;
import com.pms.repository.VenueRoomRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/venues")
public class VenueController {

    private static final int MAX_ATTACHMENTS_PER_VENUE = 10;
    private static final int MAX_ATTACHMENTS_PER_ROOM = 10;

    private final VenueRepository venueRepository;
    private final CityRepository cityRepository;
    private final VenueAttachmentRepository venueAttachmentRepository;
    private final VenueRoomRepository venueRoomRepository;
    private final Path venueUploadDir;

    public VenueController(VenueRepository venueRepository, CityRepository cityRepository,
                           VenueAttachmentRepository venueAttachmentRepository,
                           VenueRoomRepository venueRoomRepository,
                           @Value("${storage.local-dir:uploads}") String storageLocalDir) throws IOException {
        this.venueRepository = venueRepository;
        this.cityRepository = cityRepository;
        this.venueAttachmentRepository = venueAttachmentRepository;
        this.venueRoomRepository = venueRoomRepository;
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

    @GetMapping("/{venueId}/rooms")
    public ResponseEntity<List<VenueRoom>> listRooms(@PathVariable Long venueId) {
        if (!venueRepository.existsById(venueId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(venueRoomRepository.findByVenue_IdOrderByIdAsc(venueId));
    }

    @GetMapping("/{venueId}/rooms/{roomId}")
    public ResponseEntity<VenueRoom> getRoom(@PathVariable Long venueId, @PathVariable Long roomId) {
        return venueRoomRepository.findById(roomId)
                .filter(room -> roomBelongsToVenue(room, venueId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Venue> create(@RequestBody Map<String, Object> body) {
        Venue venue = new Venue();
        applyFields(venue, body);
        Venue saved = venueRepository.saveAndFlush(venue);
        if (body.containsKey("rooms")) {
            syncRooms(saved, body.get("rooms"));
            saved = venueRepository.findById(saved.getId()).orElse(saved);
        }
        return ResponseEntity.created(URI.create("/api/venues/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Venue> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return venueRepository.findById(id).map(existing -> {
            applyFields(existing, body);
            Venue saved = venueRepository.saveAndFlush(existing);
            if (body.containsKey("rooms")) {
                syncRooms(saved, body.get("rooms"));
                saved = venueRepository.findById(saved.getId()).orElse(saved);
            }
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{venueId}/rooms")
    public ResponseEntity<VenueRoom> createRoom(@PathVariable Long venueId, @RequestBody Map<String, Object> body) {
        return venueRepository.findById(venueId).map(venue -> {
            VenueRoom room = new VenueRoom();
            room.setVenue(venue);
            applyRoomFields(room, body);
            VenueRoom savedRoom = venueRoomRepository.saveAndFlush(room);
            return ResponseEntity
                    .created(URI.create("/api/venues/" + venueId + "/rooms/" + savedRoom.getId()))
                    .body(savedRoom);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{venueId}/rooms/{roomId}")
    public ResponseEntity<VenueRoom> updateRoom(@PathVariable Long venueId, @PathVariable Long roomId, @RequestBody Map<String, Object> body) {
        return venueRoomRepository.findById(roomId)
                .filter(room -> roomBelongsToVenue(room, venueId))
                .map(room -> {
                    applyRoomFields(room, body);
                    return ResponseEntity.ok(venueRoomRepository.saveAndFlush(room));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{venueId}/rooms/{roomId}")
    public ResponseEntity<Void> deleteRoom(@PathVariable Long venueId, @PathVariable Long roomId) {
        return venueRoomRepository.findById(roomId)
                .filter(room -> roomBelongsToVenue(room, venueId))
                .map(room -> {
                    List<VenueAttachment> attachments = venueAttachmentRepository.findByRoomIdOrderByCreatedAtDesc(roomId);
                    attachments.forEach(this::deleteStoredAttachmentFile);
                    venueAttachmentRepository.deleteAll(attachments);
                    venueRoomRepository.delete(room);
                    venueRoomRepository.flush();
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
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

        // Address & Location
        if (body.containsKey("googleMapsLink")) venue.setGoogleMapsLink(str(body.get("googleMapsLink")));
        if (body.containsKey("province")) venue.setProvince(str(body.get("province")));
        if (body.containsKey("address")) venue.setAddress(str(body.get("address")));

        // Ballroom
        if (body.containsKey("ballroomName")) venue.setBallroomName(str(body.get("ballroomName")));
        if (body.containsKey("ballroomFloor")) venue.setBallroomFloor(str(body.get("ballroomFloor")));
        if (body.containsKey("ballroomLength")) venue.setBallroomLength(decimal(body.get("ballroomLength")));
        if (body.containsKey("ballroomWidth")) venue.setBallroomWidth(decimal(body.get("ballroomWidth")));
        if (body.containsKey("ballroomHeight")) venue.setBallroomHeight(decimal(body.get("ballroomHeight")));
        if (body.containsKey("ballroomUnit")) venue.setBallroomUnit(str(body.get("ballroomUnit")));

        // Foyer
        if (body.containsKey("foyerLength")) venue.setFoyerLength(decimal(body.get("foyerLength")));
        if (body.containsKey("foyerWidth")) venue.setFoyerWidth(decimal(body.get("foyerWidth")));
        if (body.containsKey("foyerHeight")) venue.setFoyerHeight(decimal(body.get("foyerHeight")));
        if (body.containsKey("foyerUnit")) venue.setFoyerUnit(str(body.get("foyerUnit")));

        // Screen
        if (body.containsKey("screenLength")) venue.setScreenLength(decimal(body.get("screenLength")));
        if (body.containsKey("screenWidth")) venue.setScreenWidth(decimal(body.get("screenWidth")));
        if (body.containsKey("existingScreen")) venue.setExistingScreen(bool(body.get("existingScreen")));
        if (body.containsKey("screenUnit")) venue.setScreenUnit(str(body.get("screenUnit")));

        // IBM Table
        if (body.containsKey("ibmTableLength")) venue.setIbmTableLength(decimal(body.get("ibmTableLength")));
        if (body.containsKey("ibmTableWidth")) venue.setIbmTableWidth(decimal(body.get("ibmTableWidth")));
        if (body.containsKey("ibmTableHeight")) venue.setIbmTableHeight(decimal(body.get("ibmTableHeight")));

        // Round Table
        if (body.containsKey("roundTableDiameter")) venue.setRoundTableDiameter(decimal(body.get("roundTableDiameter")));
        if (body.containsKey("roundTableHeight")) venue.setRoundTableHeight(decimal(body.get("roundTableHeight")));
        if (body.containsKey("tableUnit")) venue.setTableUnit(str(body.get("tableUnit")));

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
        return ResponseEntity.ok(venueAttachmentRepository.findByVenueIdAndRoomIsNullOrderByCreatedAtDesc(venueId));
    }

    @GetMapping("/{venueId}/attachments/count")
    public ResponseEntity<Map<String, Object>> attachmentCount(@PathVariable Long venueId) {
        if (!venueRepository.existsById(venueId))
            return ResponseEntity.notFound().build();
        long count = venueAttachmentRepository.countByVenueIdAndRoomIsNull(venueId);
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
            long count = venueAttachmentRepository.countByVenueIdAndRoomIsNull(venueId);
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
        return venueAttachmentRepository.findById(attachmentId).filter(att -> att.getRoom() == null).map(att -> {
            deleteStoredAttachmentFile(att);
            venueAttachmentRepository.deleteById(attachmentId);
            return ResponseEntity.noContent().<Void>build();
        }).orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{venueId}/attachments/{attachmentId}")
    public ResponseEntity<?> updateAttachmentMeta(@PathVariable Long venueId,
                                                   @PathVariable Long attachmentId,
                                                   @RequestBody Map<String, Object> body) {
        if (!venueRepository.existsById(venueId))
            return ResponseEntity.notFound().build();
        return venueAttachmentRepository.findById(attachmentId).filter(att -> att.getRoom() == null).map(att -> {
            if (body.containsKey("label")) att.setLabel(str(body.get("label")));
            if (body.containsKey("description")) att.setDescription(str(body.get("description")));
            if (body.containsKey("category")) att.setCategory(str(body.get("category")));
            VenueAttachment saved = venueAttachmentRepository.save(att);
            return ResponseEntity.ok((Object) saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping(value = "/{venueId}/attachments/{attachmentId}/replace", consumes = "multipart/form-data")
    public ResponseEntity<?> replaceAttachment(@PathVariable Long venueId,
                                               @PathVariable Long attachmentId,
                                               @RequestParam("file") MultipartFile file) {
        if (!venueRepository.existsById(venueId))
            return ResponseEntity.notFound().build();
        return venueAttachmentRepository.findById(attachmentId).filter(att -> att.getRoom() == null).map(att -> {
            try {
                // Delete old physical file
                deleteStoredAttachmentFile(att);

                // Store new file
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

                // Update entity
                att.setFileName(original);
                att.setFileUrl("/files/venue_attachments/" + storedName);
                att.setFileType(file.getContentType());
                att.setFileSize(file.getSize());
                VenueAttachment saved = venueAttachmentRepository.save(att);
                return ResponseEntity.ok((Object) saved);
            } catch (IOException e) {
                return ResponseEntity.internalServerError().body(
                        (Object) Map.of("error", "Failed to replace file: " + e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{venueId}/rooms/{roomId}/attachments")
    public ResponseEntity<List<VenueAttachment>> listRoomAttachments(@PathVariable Long venueId, @PathVariable Long roomId) {
        return venueRoomRepository.findById(roomId)
                .filter(room -> roomBelongsToVenue(room, venueId))
                .map(room -> ResponseEntity.ok(venueAttachmentRepository.findByRoomIdOrderByCreatedAtDesc(roomId)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{venueId}/rooms/{roomId}/attachments/count")
    public ResponseEntity<Map<String, Object>> roomAttachmentCount(@PathVariable Long venueId, @PathVariable Long roomId) {
        return venueRoomRepository.findById(roomId)
                .filter(room -> roomBelongsToVenue(room, venueId))
                .map(room -> {
                    long count = venueAttachmentRepository.countByRoomId(roomId);
                    return ResponseEntity.ok(Map.<String, Object>of(
                            "count", count,
                            "max", MAX_ATTACHMENTS_PER_ROOM,
                            "remaining", MAX_ATTACHMENTS_PER_ROOM - count));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping(value = "/{venueId}/rooms/{roomId}/attachments", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadRoomAttachment(
            @PathVariable Long venueId,
            @PathVariable Long roomId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "label", required = false) String label,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "category", required = false) String category) {
        return venueRoomRepository.findById(roomId).filter(room -> roomBelongsToVenue(room, venueId)).map(room -> {
            long count = venueAttachmentRepository.countByRoomId(roomId);
            if (count >= MAX_ATTACHMENTS_PER_ROOM) {
                return ResponseEntity.badRequest().body(
                        (Object) Map.of("error", "Maximum of " + MAX_ATTACHMENTS_PER_ROOM + " attachments per room reached."));
            }

            try {
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
                att.setVenue(room.getVenue());
                att.setRoom(room);
                att.setLabel(label);
                att.setDescription(description);
                att.setFileName(original);
                att.setFileUrl("/files/venue_attachments/" + storedName);
                att.setFileType(file.getContentType());
                att.setFileSize(file.getSize());
                att.setCategory(category);
                VenueAttachment saved = venueAttachmentRepository.save(att);
                return ResponseEntity.created(
                        URI.create("/api/venues/" + venueId + "/rooms/" + roomId + "/attachments/" + saved.getId())).body((Object) saved);
            } catch (IOException e) {
                return ResponseEntity.internalServerError().body(
                        (Object) Map.of("error", "Failed to store file: " + e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{venueId}/rooms/{roomId}/attachments/{attachmentId}")
    public ResponseEntity<Void> deleteRoomAttachment(@PathVariable Long venueId, @PathVariable Long roomId, @PathVariable Long attachmentId) {
        if (!venueRoomRepository.findById(roomId).filter(room -> roomBelongsToVenue(room, venueId)).isPresent())
            return ResponseEntity.notFound().build();
        return venueAttachmentRepository.findById(attachmentId)
                .filter(att -> att.getRoom() != null && Objects.equals(att.getRoom().getId(), roomId))
                .map(att -> {
                    deleteStoredAttachmentFile(att);
                    venueAttachmentRepository.deleteById(attachmentId);
                    return ResponseEntity.noContent().<Void>build();
                }).orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{venueId}/rooms/{roomId}/attachments/{attachmentId}")
    public ResponseEntity<?> updateRoomAttachmentMeta(@PathVariable Long venueId,
                                                      @PathVariable Long roomId,
                                                      @PathVariable Long attachmentId,
                                                      @RequestBody Map<String, Object> body) {
        if (!venueRoomRepository.findById(roomId).filter(room -> roomBelongsToVenue(room, venueId)).isPresent()) {
            return ResponseEntity.notFound().build();
        }
        return venueAttachmentRepository.findById(attachmentId)
                .filter(att -> att.getRoom() != null && Objects.equals(att.getRoom().getId(), roomId))
                .map(att -> {
                    if (body.containsKey("label")) att.setLabel(str(body.get("label")));
                    if (body.containsKey("description")) att.setDescription(str(body.get("description")));
                    VenueAttachment saved = venueAttachmentRepository.save(att);
                    return ResponseEntity.ok((Object) saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping(value = "/{venueId}/rooms/{roomId}/attachments/{attachmentId}/replace", consumes = "multipart/form-data")
    public ResponseEntity<?> replaceRoomAttachment(@PathVariable Long venueId,
                                                   @PathVariable Long roomId,
                                                   @PathVariable Long attachmentId,
                                                   @RequestParam("file") MultipartFile file) {
        if (!venueRoomRepository.findById(roomId).filter(room -> roomBelongsToVenue(room, venueId)).isPresent())
            return ResponseEntity.notFound().build();
        return venueAttachmentRepository.findById(attachmentId)
                .filter(att -> att.getRoom() != null && Objects.equals(att.getRoom().getId(), roomId))
                .map(att -> {
                    try {
                        deleteStoredAttachmentFile(att);
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

                        att.setFileName(original);
                        att.setFileUrl("/files/venue_attachments/" + storedName);
                        att.setFileType(file.getContentType());
                        att.setFileSize(file.getSize());
                        VenueAttachment saved = venueAttachmentRepository.save(att);
                        return ResponseEntity.ok((Object) saved);
                    } catch (IOException e) {
                        return ResponseEntity.internalServerError().body(
                                (Object) Map.of("error", "Failed to replace file: " + e.getMessage()));
                    }
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

    private boolean roomBelongsToVenue(VenueRoom room, Long venueId) {
        return room.getVenue() != null && Objects.equals(room.getVenue().getId(), venueId);
    }

    private void deleteStoredAttachmentFile(VenueAttachment attachment) {
        if (attachment.getFileUrl() != null && attachment.getFileUrl().startsWith("/files/venue_attachments/")) {
            String storedName = attachment.getFileUrl().replace("/files/venue_attachments/", "");
            try { Files.deleteIfExists(venueUploadDir.resolve(storedName)); } catch (IOException ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private void syncRooms(Venue venue, Object roomsObj) {
        if (!(roomsObj instanceof List<?> incomingList)) {
            venueRoomRepository.deleteAll(venueRoomRepository.findByVenue_Id(venue.getId()));
            venue.getRooms().clear();
            return;
        }

        Map<Long, VenueRoom> existingById = venueRoomRepository.findByVenue_Id(venue.getId()).stream()
                .filter(room -> room.getId() != null)
                .collect(Collectors.toMap(VenueRoom::getId, room -> room, (a, b) -> a, LinkedHashMap::new));

        List<VenueRoom> nextRooms = new ArrayList<>();
        Set<Long> incomingIds = incomingList.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<?, ?>) item)
                .map(rawMap -> longValue(rawMap.get("id")))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        existingById.values().stream()
                .filter(room -> room.getId() != null && !incomingIds.contains(room.getId()))
                .forEach(venueRoomRepository::delete);

        for (Object item : incomingList) {
            if (!(item instanceof Map<?, ?> rawMap)) {
                continue;
            }

            Map<String, Object> roomBody = rawMap.entrySet().stream()
                    .filter(entry -> entry.getKey() != null)
                    .collect(Collectors.toMap(
                            entry -> entry.getKey().toString(),
                            Map.Entry::getValue,
                            (a, b) -> b,
                            LinkedHashMap::new));

            Long roomId = longValue(roomBody.get("id"));
            VenueRoom room = roomId != null ? existingById.getOrDefault(roomId, new VenueRoom()) : new VenueRoom();
            room.setVenue(venue);
            applyRoomFields(room, roomBody);
            nextRooms.add(venueRoomRepository.save(room));
        }

        venueRoomRepository.flush();
        venue.setRooms(nextRooms);
    }

    private void applyRoomFields(VenueRoom room, Map<String, Object> body) {
        room.setRoomName(str(body.get("roomName")));
        room.setRoomFloor(str(body.get("roomFloor")));
        room.setRoomLength(decimal(body.get("roomLength")));
        room.setRoomWidth(decimal(body.get("roomWidth")));
        room.setRoomHeight(decimal(body.get("roomHeight")));
        room.setRoomUnit(str(body.get("roomUnit")));
        room.setFoyerLength(decimal(body.get("foyerLength")));
        room.setFoyerWidth(decimal(body.get("foyerWidth")));
        room.setFoyerHeight(decimal(body.get("foyerHeight")));
        room.setFoyerUnit(str(body.get("foyerUnit")));
        room.setScreenLength(decimal(body.get("screenLength")));
        room.setScreenWidth(decimal(body.get("screenWidth")));
        room.setExistingScreen(bool(body.get("existingScreen")));
        room.setScreenUnit(str(body.get("screenUnit")));
        room.setIbmTableLength(decimal(body.get("ibmTableLength")));
        room.setIbmTableWidth(decimal(body.get("ibmTableWidth")));
        room.setIbmTableHeight(decimal(body.get("ibmTableHeight")));
        room.setRoundTableDiameter(decimal(body.get("roundTableDiameter")));
        room.setRoundTableHeight(decimal(body.get("roundTableHeight")));
        room.setTableUnit(str(body.get("tableUnit")));
        room.setElectricityWatt(integer(body.get("electricityWatt")));
        room.setSoundSystemWatt(integer(body.get("soundSystemWatt")));
        room.setMicrophoneProvided(bool(body.get("microphoneProvided")));
        room.setMicrophoneModel(str(body.get("microphoneModel")));
        room.setMicrophoneQuantity(integer(body.get("microphoneQuantity")));
        room.setTotalStageModules(integer(body.get("totalStageModules")));
        room.setGeneratorAllowed(bool(body.get("generatorAllowed")));
    }

    private Long longValue(Object obj) {
        if (obj == null) return null;
        try {
            return Long.parseLong(obj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Resolves a shortened Google Maps URL (e.g. maps.app.goo.gl/...) by following
     * redirects and returning the final full URL.
     */
    @GetMapping("/resolve-maps-url")
    public ResponseEntity<?> resolveMapsUrl(@RequestParam("url") String shortUrl) {
        if (shortUrl == null || shortUrl.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "URL is required"));
        }
        try {
            String resolved = followRedirects(shortUrl.trim(), 5);
            return ResponseEntity.ok(Map.of("resolvedUrl", resolved));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not resolve URL: " + e.getMessage()));
        }
    }

    private String followRedirects(String urlStr, int maxRedirects) throws IOException {
        String current = urlStr;
        for (int i = 0; i < maxRedirects; i++) {
            URL url = new URL(current);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) break;
                current = location;
            } else {
                conn.disconnect();
                break;
            }
        }
        return current;
    }
}
