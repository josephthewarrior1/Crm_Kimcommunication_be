package com.pms.controller;

import com.pms.domain.Industry;
import com.pms.repository.IndustryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/industries")
public class IndustryController {

    private final IndustryRepository industryRepository;

    public IndustryController(IndustryRepository industryRepository) {
        this.industryRepository = industryRepository;
    }

    @GetMapping
    public List<Industry> list() {
        return industryRepository.findAllByOrderByNameAsc();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Industry> get(@PathVariable Long id) {
        return industryRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Industry> create(@RequestBody Industry industry) {
        industry.setId(null);
        Industry saved = industryRepository.save(industry);
        return ResponseEntity.created(URI.create("/api/industries/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Industry> update(@PathVariable Long id, @RequestBody Industry industry) {
        return industryRepository.findById(id).map(existing -> {
            if (industry.getName() != null) existing.setName(industry.getName());
            return ResponseEntity.ok(industryRepository.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!industryRepository.existsById(id))
            return ResponseEntity.notFound().build();
        industryRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
