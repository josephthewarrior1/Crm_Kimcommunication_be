package com.pms.controller;

import com.pms.domain.Client;
import com.pms.domain.Project;
import com.pms.domain.ProjectBrandAlliance;
import com.pms.repository.ClientRepository;
import com.pms.repository.ProjectBrandAllianceRepository;
import com.pms.repository.ProjectRepository;
import org.springframework.http.ResponseEntity;
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

    public ProjectBrandAllianceController(ProjectRepository projectRepository,
            ProjectBrandAllianceRepository allianceRepository,
            ClientRepository clientRepository) {
        this.projectRepository = projectRepository;
        this.allianceRepository = allianceRepository;
        this.clientRepository = clientRepository;
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
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @PutMapping
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

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", alliance.getId());
            m.put("clientId", client.getId());
            m.put("clientName", client.getName());
            m.put("fundingAmount", alliance.getFundingAmount());
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

    public static class AllianceRequest {
        public Long id;
        public Long clientId;
        public String clientName;
        public BigDecimal fundingAmount;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getClientId() { return clientId; }
        public void setClientId(Long clientId) { this.clientId = clientId; }
        public String getClientName() { return clientName; }
        public void setClientName(String clientName) { this.clientName = clientName; }
        public BigDecimal getFundingAmount() { return fundingAmount; }
        public void setFundingAmount(BigDecimal fundingAmount) { this.fundingAmount = fundingAmount; }
    }
}
