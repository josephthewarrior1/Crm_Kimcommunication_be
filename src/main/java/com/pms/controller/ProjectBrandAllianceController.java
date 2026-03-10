package com.pms.controller;

import com.pms.domain.Client;
import com.pms.domain.FundingSource;
import com.pms.domain.Project;
import com.pms.domain.ProjectBrandAlliance;
import com.pms.repository.ClientRepository;
import com.pms.repository.FundingSourceRepository;
import com.pms.repository.ProjectBrandAllianceRepository;
import com.pms.repository.ProjectRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/brand-alliances")
public class ProjectBrandAllianceController {

    private final ProjectRepository projectRepository;
    private final ProjectBrandAllianceRepository allianceRepository;
    private final ClientRepository clientRepository;
    private final FundingSourceRepository fundingSourceRepository;

    public ProjectBrandAllianceController(ProjectRepository projectRepository,
            ProjectBrandAllianceRepository allianceRepository,
            ClientRepository clientRepository,
            FundingSourceRepository fundingSourceRepository) {
        this.projectRepository = projectRepository;
        this.allianceRepository = allianceRepository;
        this.clientRepository = clientRepository;
        this.fundingSourceRepository = fundingSourceRepository;
    }

    @GetMapping
    public ResponseEntity<?> list(@PathVariable Long projectId) {
        if (!projectRepository.existsById(projectId)) {
            return ResponseEntity.notFound().build();
        }
        List<ProjectBrandAlliance> alliances = allianceRepository.findByProjectId(projectId);
        List<Map<String, Object>> result = alliances.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("clientId", a.getClient().getId());
            m.put("clientName", a.getClient().getName());
            m.put("fundingAmount", a.getFundingAmount());

            // Include funding sources
            List<FundingSource> sources = fundingSourceRepository.findByAllianceId(a.getId());
            m.put("fundingSources", sources.stream().map(s -> {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("id", s.getId());
                sm.put("source", s.getSource());
                sm.put("amount", s.getAmount());
                return sm;
            }).collect(Collectors.toList()));

            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @PutMapping
    @Transactional
    public ResponseEntity<?> bulkUpsert(@PathVariable Long projectId,
            @RequestBody List<AllianceRequest> requests) {
        Optional<Project> optProject = projectRepository.findById(projectId);
        if (optProject.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Project project = optProject.get();

        // Collect IDs that should remain
        Set<Long> incomingIds = requests.stream()
                .map(r -> r.id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Delete alliances no longer in the list
        List<ProjectBrandAlliance> existing = allianceRepository.findByProjectId(projectId);
        for (ProjectBrandAlliance a : existing) {
            if (!incomingIds.contains(a.getId())) {
                fundingSourceRepository.deleteByAllianceId(a.getId());
                allianceRepository.delete(a);
            }
        }

        // Upsert each alliance
        List<Map<String, Object>> result = new ArrayList<>();
        for (AllianceRequest req : requests) {
            Client client = resolveClient(req);
            if (client == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Client is required for each brand alliance"));
            }

            ProjectBrandAlliance alliance;
            if (req.id != null) {
                alliance = allianceRepository.findById(req.id).orElse(null);
                if (alliance == null || !alliance.getProject().getId().equals(projectId)) {
                    alliance = new ProjectBrandAlliance();
                    alliance.setProject(project);
                }
            } else {
                alliance = new ProjectBrandAlliance();
                alliance.setProject(project);
            }

            alliance.setClient(client);
            alliance.setFundingAmount(req.fundingAmount != null ? req.fundingAmount : BigDecimal.ZERO);
            allianceRepository.save(alliance);

            // Handle funding sources
            fundingSourceRepository.deleteByAllianceId(alliance.getId());
            List<Map<String, Object>> savedSources = new ArrayList<>();
            if (req.fundingSources != null) {
                for (FundingSourceRequest fsr : req.fundingSources) {
                    if (fsr.source == null || fsr.source.isBlank()) continue;
                    FundingSource fs = FundingSource.builder()
                            .alliance(alliance)
                            .source(fsr.source.trim())
                            .amount(fsr.amount != null ? fsr.amount : BigDecimal.ZERO)
                            .build();
                    fundingSourceRepository.save(fs);
                    Map<String, Object> sm = new LinkedHashMap<>();
                    sm.put("id", fs.getId());
                    sm.put("source", fs.getSource());
                    sm.put("amount", fs.getAmount());
                    savedSources.add(sm);
                }
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", alliance.getId());
            m.put("clientId", client.getId());
            m.put("clientName", client.getName());
            m.put("fundingAmount", alliance.getFundingAmount());
            m.put("fundingSources", savedSources);
            result.add(m);
        }

        return ResponseEntity.ok(result);
    }

    private Client resolveClient(AllianceRequest req) {
        if (req.clientId != null) {
            return clientRepository.findById(req.clientId).orElse(null);
        }
        if (req.clientName != null && !req.clientName.isBlank()) {
            return clientRepository.findByName(req.clientName.trim())
                    .orElseGet(() -> {
                        Client c = new Client();
                        c.setName(req.clientName.trim());
                        return clientRepository.save(c);
                    });
        }
        return null;
    }

    public static class FundingSourceRequest {
        public Long id;
        public String source;
        public BigDecimal amount;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
    }

    public static class AllianceRequest {
        public Long id;
        public Long clientId;
        public String clientName;
        public BigDecimal fundingAmount;
        public List<FundingSourceRequest> fundingSources;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getClientId() { return clientId; }
        public void setClientId(Long clientId) { this.clientId = clientId; }
        public String getClientName() { return clientName; }
        public void setClientName(String clientName) { this.clientName = clientName; }
        public BigDecimal getFundingAmount() { return fundingAmount; }
        public void setFundingAmount(BigDecimal fundingAmount) { this.fundingAmount = fundingAmount; }
        public List<FundingSourceRequest> getFundingSources() { return fundingSources; }
        public void setFundingSources(List<FundingSourceRequest> fundingSources) { this.fundingSources = fundingSources; }
    }
}
