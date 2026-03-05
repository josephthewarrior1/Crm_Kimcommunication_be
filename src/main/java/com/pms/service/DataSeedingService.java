package com.pms.service;

import com.pms.domain.*;
import com.pms.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Data Seeding Service for Development Environment
 * 
 * This service creates initial data for development and testing purposes.
 * It runs automatically when the application starts and only seeds data
 * if the database is empty or in development mode.
 * 
 * @author Juan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Order(1)
public class DataSeedingService implements CommandLineRunner {

    private final UserRepository userRepository;
    private final ClientRepository clientRepository;
    private final ProjectRepository projectRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final SessionRepository sessionRepository;
    private final IndustryRepository industryRepository;
    // private final ProjectRoleRepository roleRepository;

    private final Argon2PasswordEncoder passwordEncoder = new Argon2PasswordEncoder(16, 32, 1, 1 << 14, 3);

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        log.info("Starting data seeding process...");

        // Only seed if database is empty
        if (userRepository.count() > 0) {
            log.info("Database already has data, skipping seeding");
            return;
        }

        try {
            seedUsers();
            seedClients();
            seedTeamMembers();
            seedProjects();
            log.info("Data seeding completed successfully");
        } catch (Exception e) {
            log.error("Error during data seeding", e);
            throw e;
        }
    }

    /**
     * Create initial users for the system
     */
    private void seedUsers() {
        log.info("Seeding users...");

        // Admin user
        AppUser admin = AppUser.builder()
                .name("System Administrator")
                .email("admin@pms.com")
                .username("admin")
                .passwordHash(passwordEncoder.encode("admin123"))
                .roles(Set.of(Role.ADMIN))
                .active(true)
                .approved(true)
                .dob(LocalDate.of(1990, 1, 1))
                .build();
        userRepository.save(admin);
        log.info("Created admin user: {}", admin.getEmail());

        // Manager user
        AppUser manager = AppUser.builder()
                .name("Project Manager")
                .email("manager@pms.com")
                .username("manager")
                .passwordHash(passwordEncoder.encode("manager123"))
                .roles(Set.of(Role.MANAGER))
                .active(true)
                .approved(true)
                .dob(LocalDate.of(1985, 5, 15))
                .build();
        userRepository.save(manager);
        log.info("Created manager user: {}", manager.getEmail());

        // Regular users
        AppUser user1 = AppUser.builder()
                .name("John Developer")
                .email("john@pms.com")
                .username("john")
                .passwordHash(passwordEncoder.encode("user123"))
                .roles(Set.of(Role.USER))
                .active(true)
                .approved(true)
                .dob(LocalDate.of(1992, 3, 20))
                .build();
        userRepository.save(user1);
        log.info("Created user: {}", user1.getEmail());

        AppUser user2 = AppUser.builder()
                .name("Jane Designer")
                .email("jane@pms.com")
                .username("jane")
                .passwordHash(passwordEncoder.encode("user123"))
                .roles(Set.of(Role.USER))
                .active(true)
                .approved(true)
                .dob(LocalDate.of(1988, 7, 10))
                .build();
        userRepository.save(user2);
        log.info("Created user: {}", user2.getEmail());

        AppUser user3 = AppUser.builder()
                .name("Mike Tester")
                .email("mike@pms.com")
                .username("mike")
                .passwordHash(passwordEncoder.encode("user123"))
                .roles(Set.of(Role.USER))
                .active(true)
                .approved(true)
                .dob(LocalDate.of(1995, 11, 5))
                .build();
        userRepository.save(user3);
        log.info("Created user: {}", user3.getEmail());
        AppUser user4 = AppUser.builder()
                .name("Sabrina")
                .email("sabrina.finance@pms.com")
                .username("sabrina")
                .passwordHash(passwordEncoder.encode("sabrina123"))
                .roles(Set.of(Role.USER))
                .active(true)
                .approved(true)
                .dob(LocalDate.of(1995, 11, 5))
                .build();
        userRepository.save(user3);
        log.info("Created user: {}", user3.getEmail());
        AppUser user5 = AppUser.builder()
                .name("Renny")
                .email("renny.finance@pms.com")
                .username("renny")
                .passwordHash(passwordEncoder.encode("renny123"))
                .roles(Set.of(Role.USER))
                .active(true)
                .approved(true)
                .dob(LocalDate.of(1995, 11, 5))
                .build();
        userRepository.save(user3);
        log.info("Created user: {}", user3.getEmail());
    }

    /**
     * Create initial clients
     */
    private void seedClients() {
        log.info("Seeding clients...");

        // Create industries first
        Industry techIndustry = industryRepository.save(Industry.builder().name("Technology").build());
        Industry consultingIndustry = industryRepository.save(Industry.builder().name("Consulting").build());
        Industry startupIndustry = industryRepository.save(Industry.builder().name("Startup").build());
        Industry enterpriseIndustry = industryRepository.save(Industry.builder().name("Enterprise Software").build());

        Client client1 = Client.builder()
                .name("Acme Corporation")
                .industry(techIndustry)
                .country("United States")
                .build();
        clientRepository.save(client1);
        log.info("Created client: {}", client1.getName());

        Client client2 = Client.builder()
                .name("Global Solutions Ltd")
                .industry(consultingIndustry)
                .country("United Kingdom")
                .build();
        clientRepository.save(client2);
        log.info("Created client: {}", client2.getName());

        Client client3 = Client.builder()
                .name("TechStart Inc")
                .industry(startupIndustry)
                .country("Canada")
                .build();
        clientRepository.save(client3);
        log.info("Created client: {}", client3.getName());

        Client client4 = Client.builder()
                .name("Enterprise Systems")
                .industry(enterpriseIndustry)
                .country("Germany")
                .build();
        clientRepository.save(client4);
        log.info("Created client: {}", client4.getName());
    }

    /**
     * Create team members
     */
    private void seedTeamMembers() {
        log.info("Seeding team members...");

        // Get users for team members
        AppUser john = userRepository.findByEmail("john@pms.com").orElse(null);
        AppUser jane = userRepository.findByEmail("jane@pms.com").orElse(null);
        AppUser mike = userRepository.findByEmail("mike@pms.com").orElse(null);

        if (john != null) {
            TeamMember member1 = TeamMember.builder()
                    .name("John Developer")
                    .role("Senior Developer")
                    .email("john@pms.com")
                    .phone("+1-555-0101")
                    .location("New York, NY")
                    .status(MemberStatus.ACTIVE)
                    .user(john)
                    .build();
            teamMemberRepository.save(member1);
            log.info("Created team member: {}", member1.getName());
        }

        if (jane != null) {
            TeamMember member2 = TeamMember.builder()
                    .name("Jane Designer")
                    .role("UI/UX Designer")
                    .email("jane@pms.com")
                    .phone("+1-555-0102")
                    .location("San Francisco, CA")
                    .status(MemberStatus.ACTIVE)
                    .user(jane)
                    .build();
            teamMemberRepository.save(member2);
            log.info("Created team member: {}", member2.getName());
        }

        if (mike != null) {
            TeamMember member3 = TeamMember.builder()
                    .name("Mike Tester")
                    .role("QA Engineer")
                    .email("mike@pms.com")
                    .phone("+1-555-0103")
                    .location("Austin, TX")
                    .status(MemberStatus.ACTIVE)
                    .user(mike)
                    .build();
            teamMemberRepository.save(member3);
            log.info("Created team member: {}", member3.getName());
        }
    }

    /**
     * Create sample projects
     */
    private void seedProjects() {
        log.info("Seeding projects...");

        // Get clients and team members
        Client acme = clientRepository.findByName("Acme Corporation").orElse(null);
        Client global = clientRepository.findByName("Global Solutions Ltd").orElse(null);

        if (acme != null) {
            Project project1 = Project.builder()
                    .name("Website Redesign")
                    .description("Complete redesign of the corporate website with modern UI/UX")
                    .status(ProjectStatus.IN_PROGRESS)
                    .target(500)
                    .progress(25)
                    .startDate(LocalDate.now().minusDays(30))
                    .endDate(LocalDate.now().plusDays(60))
                    .budget(new BigDecimal("50000.0"))
                    .client(acme.getName()) // Legacy field
                    .clientEntity(acme) // New relationship
                    .build();
            projectRepository.save(project1);
            log.info("Created project: {}", project1.getName());
        }

        if (global != null) {
            Project project2 = Project.builder()
                    .name("Mobile App Development")
                    .description("Development of a cross-platform mobile application")
                    .status(ProjectStatus.IN_PROGRESS)
                    .target(100)
                    .progress(0)
                    .startDate(LocalDate.now().plusDays(7))
                    .endDate(LocalDate.now().plusDays(120))
                    .budget(new BigDecimal("75000.0"))
                    .client(global.getName()) // Legacy field
                    .clientEntity(global) // New relationship
                    .build();
            projectRepository.save(project2);
            log.info("Created project: {}", project2.getName());
        }
    }

}
