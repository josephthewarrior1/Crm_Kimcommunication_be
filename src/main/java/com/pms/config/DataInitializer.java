package com.pms.config;

import com.pms.domain.*;
import com.pms.repository.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Configuration
public class DataInitializer {

        @Bean
        @ConditionalOnProperty(name = "app.data.seed", havingValue = "true", matchIfMissing = true)
        CommandLineRunner seedData(ProjectRepository projects,
                        WorkflowStageRepository stages,
                        ProjectEventRepository events,
                        ProjectDocumentRepository documents,
                        ContactRepository contacts,
                        TeamMemberRepository members,
                        UserRepository users,
                        SessionRepository sessions) {
                return args -> {
                        // Avoid reseeding if DB already has projects (for persistent PostgreSQL)
                        if (projects.count() > 0)
                                return;

                        // Admin user is handled by AdminInitializer (always runs)
                        // Team members (Indonesia)
                        TeamMember andi = members.save(TeamMember.builder()
                                        .name("Andi Pratama")
                                        .role("Project Manager")
                                        .email("andi.pratama@kim.co.id")
                                        .phone("+62 812-3456-7890")
                                        .location("Jakarta, Indonesia")
                                        .avatar("/avatars/andi.jpg")
                                        .status(MemberStatus.ACTIVE)
                                        .build());

                        TeamMember siti = members.save(TeamMember.builder()
                                        .name("Siti Rahma")
                                        .role("Event Coordinator")
                                        .email("siti.rahma@kim.co.id")
                                        .phone("+62 811-2000-3000")
                                        .location("Bandung, Indonesia")
                                        .avatar("/avatars/siti.jpg")
                                        .status(MemberStatus.ACTIVE)
                                        .build());

                        TeamMember bagus = members.save(TeamMember.builder()
                                        .name("Bagus Wijaya")
                                        .role("Logistics Manager")
                                        .email("bagus.wijaya@kim.co.id")
                                        .phone("+62 813-8888-9999")
                                        .location("Surabaya, Indonesia")
                                        .avatar("/avatars/bagus.jpg")
                                        .status(MemberStatus.ACTIVE)
                                        .build());

                        // Create internal AppUsers for team members (if not exists) and link
                        var encoder = new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16, 32, 1,
                                        1 << 14, 3);
                        AppUser andiU = users.findByEmail("andi.pratama@kim.co.id")
                                        .orElseGet(() -> users.save(AppUser.builder()
                                                        .name("Andi Pratama")
                                                        .email("andi.pratama@kim.co.id")
                                                        .username("andi")
                                                        .passwordHash(encoder.encode("password"))
                                                        .roles(java.util.Set.of(Role.MANAGER))
                                                        .active(true)
                                                        .approved(true)
                                                        .build()));
                        AppUser sitiU = users.findByEmail("siti.rahma@kim.co.id")
                                        .orElseGet(() -> users.save(AppUser.builder()
                                                        .name("Siti Rahma")
                                                        .email("siti.rahma@kim.co.id")
                                                        .username("siti")
                                                        .passwordHash(encoder.encode("password"))
                                                        .roles(java.util.Set.of(Role.USER))
                                                        .active(true)
                                                        .approved(true)
                                                        .build()));
                        AppUser bagusU = users.findByEmail("bagus.wijaya@kim.co.id")
                                        .orElseGet(() -> users.save(AppUser.builder()
                                                        .name("Bagus Wijaya")
                                                        .email("bagus.wijaya@kim.co.id")
                                                        .username("bagus")
                                                        .passwordHash(encoder.encode("password"))
                                                        .roles(java.util.Set.of(Role.USER))
                                                        .active(true)
                                                        .approved(true)
                                                        .build()));

                        andi.setUser(andiU);
                        siti.setUser(sitiU);
                        bagus.setUser(bagusU);
                        members.saveAll(java.util.List.of(andi, siti, bagus));

                        // Projects (clients: HP, Intel, NVIDIA, Telkom)
                        Project hpLaunch = projects.save(Project.builder()
                                        .name("HP Indonesia Product Launch Jakarta 2025")
                                        .description("Produk notebook komersial terbaru HP diluncurkan untuk pasar Indonesia. Venue: Jakarta Convention Center.")
                                        .status(ProjectStatus.IN_PROGRESS)
                                        .size(Size.LARGE)
                                        .progress(60)
                                        .startDate(LocalDate.of(2025, 3, 1))
                                        .endDate(LocalDate.of(2025, 5, 10))
                                        .budget(new BigDecimal("950000000"))
                                        .client("HP Indonesia")
                                        .build());

                        Project intelDev = projects.save(Project.builder()
                                        .name("Intel Developer Conference SEA 2025")
                                        .description("Konferensi developer Intel se-Asia Tenggara. Venue: ICE BSD City.")
                                        .status(ProjectStatus.APPROVAL_PENDING)
                                        .size(Size.MEDIUM)
                                        .progress(35)
                                        .startDate(LocalDate.of(2025, 6, 20))
                                        .endDate(LocalDate.of(2025, 6, 22))
                                        .budget(new BigDecimal("650000000"))
                                        .client("Intel Indonesia")
                                        .build());

                        Project nvidiaAi = projects.save(Project.builder()
                                        .name("NVIDIA AI Summit Jakarta 2025")
                                        .description("Summit AI dengan keynote, demo GPU, dan sesi partner. Venue: Kempinski Jakarta.")
                                        .status(ProjectStatus.IN_PROGRESS)
                                        .size(Size.LARGE)
                                        .progress(50)
                                        .startDate(LocalDate.of(2025, 8, 5))
                                        .endDate(LocalDate.of(2025, 8, 6))
                                        .budget(new BigDecimal("780000000"))
                                        .client("NVIDIA SEA")
                                        .build());

                        Project telkomDay = projects.save(Project.builder()
                                        .name("Telkom Digital Innovation Day 2025")
                                        .description("Pameran solusi digital dan inovasi Telkom.")
                                        .status(ProjectStatus.IN_PROGRESS)
                                        .size(Size.MEDIUM)
                                        .progress(40)
                                        .startDate(LocalDate.of(2025, 9, 12))
                                        .endDate(LocalDate.of(2025, 9, 12))
                                        .budget(new BigDecimal("420000000"))
                                        .client("Telkom Indonesia")
                                        .build());

                        // Assign team members
                        hpLaunch.getTeamMembers().add(andi);
                        hpLaunch.getTeamMembers().add(siti);
                        intelDev.getTeamMembers().add(siti);
                        nvidiaAi.getTeamMembers().add(bagus);
                        // Add project users for access control
                        hpLaunch.getUsers().add(andiU);
                        hpLaunch.getUsers().add(sitiU);
                        intelDev.getUsers().add(sitiU);
                        nvidiaAi.getUsers().add(bagusU);
                        projects.saveAll(List.of(hpLaunch, intelDev, nvidiaAi, telkomDay));

                        // Stages for HP Launch
                        stages.save(WorkflowStage.builder()
                                        .name("Planning")
                                        .description("Initial planning and budgeting")
                                        .status(StageStatus.COMPLETED)
                                        .dueDate(LocalDate.of(2025, 3, 15))
                                        .pic("Andi Pratama")
                                        .deliverables(List.of("Budget plan", "Vendor list"))
                                        .dependencies(List.of())
                                        .project(hpLaunch)
                                        .build());
                        stages.save(WorkflowStage.builder()
                                        .name("Vendor Contracts")
                                        .description("Secure contracts with vendors and venue")
                                        .status(StageStatus.IN_PROGRESS)
                                        .dueDate(LocalDate.of(2025, 4, 1))
                                        .pic("Siti Rahma")
                                        .deliverables(List.of("AV vendor", "Catering contract"))
                                        .dependencies(List.of("Planning"))
                                        .project(hpLaunch)
                                        .build());

                        // Events
                        events.save(ProjectEvent.builder()
                                        .title("Kickoff Meeting with HP Indonesia")
                                        .date(LocalDate.of(2025, 3, 5))
                                        .type(EventType.MEETING)
                                        .status(EventStatus.CONFIRMED)
                                        .project(hpLaunch)
                                        .build());
                        events.save(ProjectEvent.builder()
                                        .title("HP Commercial Notebook Launch Day")
                                        .date(LocalDate.of(2025, 5, 10))
                                        .type(EventType.EVENT)
                                        .status(EventStatus.PENDING)
                                        .project(hpLaunch)
                                        .build());

                        // Documents
                        documents.save(ProjectDocument.builder()
                                        .name("Master Budget HP Launch.xlsx")
                                        .type("budget")
                                        .url("/docs/master-budget.xlsx")
                                        .uploadedAt(LocalDateTime.now().minusDays(10))
                                        .status("approved")
                                        .project(hpLaunch)
                                        .build());
                        documents.save(ProjectDocument.builder()
                                        .name("Venue Contract JCC.pdf")
                                        .type("contract")
                                        .url("/docs/jcc-contract.pdf")
                                        .uploadedAt(LocalDateTime.now().minusDays(3))
                                        .status("pending")
                                        .project(hpLaunch)
                                        .build());

                        // Contacts
                        contacts.save(Contact.builder()
                                        .name("Budi Santoso")
                                        .role("Head of Marketing")
                                        .company("HP Indonesia")
                                        .category(ContactCategory.CLIENT)
                                        .email("budi.santoso@hp.com")
                                        .phone("+62 21-555-1234")
                                        .address("Jalan Jendral Sudirman, Jakarta")
                                        .notes("Primary client contact")
                                        .isActive(true)
                                        .project(hpLaunch)
                                        .build());
                        contacts.save(Contact.builder()
                                        .name("Dewi Kurnia")
                                        .role("Venue Manager")
                                        .company("Jakarta Convention Center")
                                        .category(ContactCategory.VENDOR)
                                        .email("dewi.kurnia@jcc.co.id")
                                        .phone("+62 21-570-1234")
                                        .address("Senayan, Jakarta")
                                        .notes("Layout finalization 2 weeks before event")
                                        .isActive(true)
                                        .project(hpLaunch)
                                        .build());
                };
        }
}
