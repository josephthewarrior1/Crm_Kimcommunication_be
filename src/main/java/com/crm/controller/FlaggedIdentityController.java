package com.crm.controller;

import com.crm.domain.FlaggedIdentity;
import com.crm.repository.FlaggedIdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;
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
}
