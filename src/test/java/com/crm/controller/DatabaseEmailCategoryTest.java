package com.crm.controller;

import com.crm.domain.AppUser;
import com.crm.domain.Company;
import com.crm.domain.Database;
import com.crm.domain.DatabaseDestination;
import com.crm.domain.DatabaseEmail;
import com.crm.domain.FlaggedIdentity;
import com.crm.domain.FlagStatus;
import com.crm.domain.Group;
import com.crm.domain.PositionLevel;
import com.crm.domain.Role;
import com.crm.repository.CompanyRepository;
import com.crm.repository.DatabaseEmailRepository;
import com.crm.repository.DatabaseRepository;
import com.crm.repository.FlaggedIdentityRepository;
import com.crm.service.SecurityHelper;
import com.crm.service.SuspiciousIdentityService;
import com.crm.util.ContactEmailCategory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseEmailCategoryTest {
    private final DatabaseController controller = new DatabaseController();
    private final DatabaseRepository databases = mock(DatabaseRepository.class);
    private final FlaggedIdentityRepository flags = mock(FlaggedIdentityRepository.class);
    private final DatabaseEmailRepository emails = mock(DatabaseEmailRepository.class);

    @BeforeEach void setup() {
        var security = mock(SecurityHelper.class);
        var user = AppUser.builder().id(1L).build();
        when(security.getAuthenticatedUser("test")).thenReturn(user);
        when(security.hasAnyRole(user, Role.ADMIN, Role.MANAGER)).thenReturn(true);
        when(flags.findAll()).thenReturn(List.of());
        when(databases.save(any())).thenAnswer(call -> call.getArgument(0));
        when(emails.save(any())).thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.setField(controller, "databaseRepository", databases);
        ReflectionTestUtils.setField(controller, "databaseEmailRepository", emails);
        ReflectionTestUtils.setField(controller, "companyRepository", mock(CompanyRepository.class));
        ReflectionTestUtils.setField(controller, "flaggedIdentityRepository", flags);
        ReflectionTestUtils.setField(controller, "securityHelper", security);
        ReflectionTestUtils.setField(controller, "suspiciousIdentityService", mock(SuspiciousIdentityService.class));
    }

    @Test void explicitDestinationNeverChangesWithEmailDomainAndJsonOmissionRemainsDistinguishable() throws Exception {
        var mapper = new ObjectMapper();
        assertEquals(DatabaseDestination.COMPANY, DatabaseDestination.effective(null));
        assertEquals("\"participant\"", mapper.writeValueAsString(DatabaseDestination.PARTICIPANT));
        assertEquals(DatabaseDestination.PARTICIPANT, mapper.readValue("\"participant\"", DatabaseDestination.class));
        assertNull(mapper.readValue("{}", Database.class).getDatabaseDestination());
        assertEquals(DatabaseDestination.BOTH, DatabaseDestination.COMPANY.add(DatabaseDestination.PARTICIPANT));

        for (String address : List.of("student@gmail.com", "student@campus.ac.id", "member@company.example")) {
            Database participant = participant(1, "Student", address);
            assertTrue(ContactEmailCategory.isParticipantOnly(participant));
            assertFalse((boolean) ReflectionTestUtils.invokeMethod(controller, "isIncomplete", participant));
            assertEquals(DatabaseDestination.PARTICIPANT, participant.getDatabaseDestination());
        }
        Database legacyGmail = Database.builder().firstName("Legacy").emails(List.of(email("legacy@gmail.com", true))).build();
        assertFalse(ContactEmailCategory.isParticipantOnly(legacyGmail));
        assertTrue((boolean) ReflectionTestUtils.invokeMethod(controller, "isIncomplete", legacyGmail));
        assertNull(legacyGmail.getDatabaseDestination());

        for (String invalid : new String[]{"", "student@gmail", "student@@gmail.com", "a @gmail.com",
                "a@gmail.com,b@campus.ac.id", null}) {
            Database participant = participant(1, "Student", invalid);
            assertFalse(ContactEmailCategory.isValidEmail(invalid));
            assertTrue(ContactEmailCategory.isParticipantOnly(participant));
            assertTrue((boolean) ReflectionTestUtils.invokeMethod(controller, "isIncomplete", participant));
        }
        Database corporate = corporate(2);
        corporate.setPositionLevel(PositionLevel.UNKNOWN);
        assertFalse((boolean) ReflectionTestUtils.invokeMethod(controller, "isIncomplete", corporate));
        corporate.setPositionLevel(null);
        assertTrue((boolean) ReflectionTestUtils.invokeMethod(controller, "isIncomplete", corporate));
    }

    @Test void scopedListsCountsAnalyticsFacetsAndExportKeepOneProfileForBothMemberships() {
        Database corporate = corporate(1);
        Database student = participant(2, "Student", "student@gmail.com");
        student.setCompany(Company.builder().id(20L).name("Example School").industry("Education").build());
        Database campus = participant(3, "Campus", "viewer@campus.ac.id");
        Database employee = participant(4, "Employee", "member@employer.example");
        Database unnamed = participant(5, " ", "unnamed@outlook.com");
        Database legacyGmail = Database.builder().id(6L).firstName("Legacy").isActive(true)
                .emails(List.of(email("legacy@gmail.com", true))).build();
        Database both = participant(7, "Both memberships", "both@campus.ac.id");
        both.setDatabaseDestination(DatabaseDestination.BOTH);
        Database inactive = participant(8, "Inactive", "inactive@gmail.com");
        inactive.setIsActive(false);
        Database flagged = participant(9, "Flagged", "flagged@gmail.com");
        Database noEmail = Database.builder().id(10L).firstName("Draft").isActive(true).build();
        when(databases.findAll()).thenReturn(List.of(corporate, student, campus, employee, unnamed,
                legacyGmail, both, inactive, flagged, noEmail));
        when(flags.findAll()).thenReturn(List.of(FlaggedIdentity.builder().database(flagged).status(FlagStatus.confirmed).build()));

        Map<?, ?> first = list("participant", "all", 1, 1, null);
        Map<?, ?> second = list("PARTICIPANT", "all", 2, 1, null);
        assertEquals(5, first.get("total"));
        assertEquals(List.of(student), first.get("items"));
        assertEquals(List.of(campus), second.get("items"));
        assertEquals(first.get("analytics"), second.get("analytics"));
        assertEquals(Map.of("all", 5L, "clean", 4L, "dirty", 1L), first.get("summary"));
        assertEquals(Map.of("company", 4L, "participant", 5L), first.get("destinations"));
        assertQualityCounts(first, 5, 4, 1);
        assertEquals(List.of(student, campus, employee, both), list("participant", "clean", 1, 10, null).get("items"));
        assertEquals(List.of(unnamed), list("participant", "dirty", 1, 10, null).get("items"));
        assertEquals(List.of(corporate), list("company", "clean", 1, 10, null).get("items"));
        assertEquals(List.of(legacyGmail, both, noEmail), list("company", "dirty", 1, 10, null).get("items"));
        assertEquals(Map.of("all", 4L, "clean", 1L, "dirty", 3L), list("company", "all", 1, 10, null).get("summary"));
        assertEquals(8, list(null, "all", 1, 10, null).get("total"));
        assertQualityCounts(list(null, "all", 1, 10, null), 8, 4, 4);
        Map<?, ?> searched = list("participant", "all", 1, 10, "Campus");
        assertEquals(List.of(campus, both), searched.get("items"));
        assertEquals(first.get("summary"), searched.get("summary"));
        assertEquals(first.get("destinations"), searched.get("destinations"));
        assertQualityCounts(searched, 2, 2, 0);
        assertEquals(List.of(student, campus, employee, unnamed, both), exported("participant", "all"));
        assertEquals(List.of(student, campus, employee, both), exported("participant", "clean"));
        assertEquals(List.of(corporate, legacyGmail, both, noEmail), exported("company", "all"));
        Map<?, ?> options = (Map<?, ?>) controller.getDatabaseFilterOptions(
                null, null, null, null, null, null, null, "all", "participant", "test").getBody();
        assertEquals(List.of("Education"), options.get("industries"));
        assertEquals(List.of(Map.of("id", 20L, "name", "Example School")), options.get("companies"));
        verify(databases, never()).save(any());
    }

    @Test void ordinaryEditsPreserveMembershipAndParticipantMetadataOnlyChangesExplicitly() {
        Database contact = participant(42, "Before", "student@campus.ac.id");
        contact.setDatabaseDestination(DatabaseDestination.BOTH);
        contact.setInstitutionName("Example School");
        contact.setParticipantCategory("mahasiswa");
        when(databases.findById(42L)).thenReturn(Optional.of(contact));
        assertEquals(200, controller.updateDatabase(42L, Database.builder().firstName("After").build(),
                null, null, false, "test").getStatusCode().value());
        assertEquals(DatabaseDestination.BOTH, contact.getDatabaseDestination());
        assertEquals("Example School", contact.getInstitutionName());
        assertEquals("mahasiswa", contact.getParticipantCategory());
        assertEquals(400, controller.updateDatabase(42L, Database.builder().firstName("Rejected")
                .participantCategory("invented").build(), null, null, false, "test").getStatusCode().value());
        assertEquals("After", contact.getFirstName());
        assertEquals(200, controller.updateDatabase(42L, Database.builder().institutionName("")
                .participantCategory("umum").build(), null, null, false, "test").getStatusCode().value());
        assertEquals("", contact.getInstitutionName());
        assertEquals("umum", contact.getParticipantCategory());
        assertEquals(DatabaseDestination.BOTH, contact.getDatabaseDestination());
    }

    @Test void campusAndCompanyEmailAreAcceptedForParticipantsButPublicCompanyAddressIsRejectedInCompanyDestination() {
        Database company = corporate(1);
        Database participant = participant(2, "Student", "old@campus.ac.id");
        when(databases.findById(1L)).thenReturn(Optional.of(company));
        when(databases.findById(2L)).thenReturn(Optional.of(participant));
        assertEquals(400, controller.addDatabaseEmail(1L, email("new@gmail.com", true), "test").getStatusCode().value());
        assertEquals(200, controller.addDatabaseEmail(1L, email("secondary@gmail.com", false), "test").getStatusCode().value());
        assertEquals(200, controller.addDatabaseEmail(2L, email("new@campus.ac.id", false), "test").getStatusCode().value());
        assertEquals(200, controller.addDatabaseEmail(2L, email("new@employer.example", true), "test").getStatusCode().value());
        assertEquals(DatabaseDestination.PARTICIPANT, participant.getDatabaseDestination());

        DatabaseEmail stored = email("old@company.example", true);
        stored.setId(11L); stored.setDatabase(company);
        when(emails.findById(11L)).thenReturn(Optional.of(stored));
        assertEquals(400, controller.updateDatabaseEmail(1L, 11L, email("changed@gmail.com", true), "test").getStatusCode().value());
        assertEquals("old@company.example", stored.getEmail());
        assertEquals(404, controller.updateDatabaseEmail(2L, 11L, email("changed@campus.ac.id", false), "test").getStatusCode().value());
        stored.setDatabase(participant);
        assertEquals(200, controller.updateDatabaseEmail(2L, 11L, email("changed@campus.ac.id", false), "test").getStatusCode().value());
        assertEquals(DatabaseDestination.PARTICIPANT, participant.getDatabaseDestination());
    }

    private Map<?, ?> list(String destination, String tab, int page, int size, String search) {
        return (Map<?, ?>) controller.getDatabasesList(search, null, null, null, null, null, null,
                tab, "id", "asc", page, size, destination, "test").getBody();
    }

    private List<?> exported(String destination, String tab) {
        return (List<?>) ((Map<?, ?>) controller.exportDatabases(null, null, null, null, null, null, null,
                tab, "id", "asc", destination, "test").getBody()).get("items");
    }

    private static void assertQualityCounts(Map<?, ?> result, int total, long clean, long dirty) {
        Map<?, ?> analytics = (Map<?, ?>) result.get("analytics");
        assertEquals(total, ((Number) analytics.get("total")).intValue());
        assertEquals(clean, ((Number) analytics.get("clean")).longValue());
        assertEquals(dirty, ((Number) analytics.get("dirty")).longValue());
    }

    @Test void participantPhoneOnlyIsCleanUnnamedIsDirtyAndEmailSearchStillFindsUnnamedProfiles() {
        Database phoneOnly = Database.builder().databaseDestination(DatabaseDestination.PARTICIPANT).firstName("Phone Person")
                .mobilePhone("081200001111, 081300002222").build();
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(controller, "participantIncomplete", phoneOnly));
        Database unnamed = participant(42, "", "unnamed@campus.ac.id");
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(controller, "participantIncomplete", unnamed));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(controller, "matchesSearch", unnamed, "unnamed@campus.ac.id"));
        assertEquals(DatabaseDestination.PARTICIPANT, unnamed.getDatabaseDestination());
    }

    private static Database participant(long id, String name, String address) {
        return Database.builder().id(id).databaseDestination(DatabaseDestination.PARTICIPANT)
                .firstName(name).isActive(true).emails(List.of(email(address, true))).build();
    }

    private static DatabaseEmail email(String address, boolean corporate) {
        return DatabaseEmail.builder().email(address).isCorporate(corporate).build();
    }

    private static Database corporate(long id) {
        Company company = Company.builder().id(10L).name("Example Company").brandName("Example")
                .group(Group.builder().id(1L).name("Example Group").build())
                .address("Example Address").officePhone("021123").city("Jakarta")
                .industry("Technology").website("https://company.example").build();
        return Database.builder().id(id).isActive(true).databaseDestination(DatabaseDestination.COMPANY)
                .company(company).salutation("Mr").firstName("Corporate " + id).lastName("Member")
                .positionLevel(PositionLevel.STAFF).jobTitle("Officer").mobilePhone("08123456789")
                .emails(List.of(email("member@company.example", true))).build();
    }
}
