package com.crm.controller;

import com.crm.domain.FlaggedIdentity;
import com.crm.repository.FlaggedIdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/flagged-identities")
public class FlaggedIdentityController {

    @Autowired
    private FlaggedIdentityRepository flaggedIdentityRepository;

    @GetMapping
    public List<FlaggedIdentity> getAllFlaggedIdentities() {
        return flaggedIdentityRepository.findAll();
    }

    @PostMapping
    public FlaggedIdentity createFlaggedIdentity(@RequestBody FlaggedIdentity flaggedIdentity) {
        return flaggedIdentityRepository.save(flaggedIdentity);
    }

    @PutMapping("/{id}")
    public ResponseEntity<FlaggedIdentity> updateFlaggedIdentity(@PathVariable Long id, @RequestBody FlaggedIdentity details) {
        return flaggedIdentityRepository.findById(id).map(existing -> {
            existing.setNameUsed(details.getNameUsed());
            existing.setEmailUsed(details.getEmailUsed());
            existing.setPhoneUsed(details.getPhoneUsed());
            existing.setFlagReason(details.getFlagReason());
            existing.setEvidenceNotes(details.getEvidenceNotes());
            existing.setStatus(details.getStatus());
            if (details.getContact() != null) {
                existing.setContact(details.getContact());
            } else {
                existing.setContact(null);
            }
            if (details.getEvent() != null) {
                existing.setEvent(details.getEvent());
            } else {
                existing.setEvent(null);
            }
            return ResponseEntity.ok(flaggedIdentityRepository.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteFlaggedIdentity(@PathVariable Long id) {
        if (flaggedIdentityRepository.existsById(id)) {
            flaggedIdentityRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
