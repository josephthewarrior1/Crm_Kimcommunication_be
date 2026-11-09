package com.crm.controller;

import com.crm.domain.AppUser;
import com.crm.domain.Company;
import com.crm.domain.CompanyBranch;
import com.crm.domain.Database;
import com.crm.domain.DatabaseEmail;
import com.crm.domain.DatabaseDestination;
import com.crm.domain.Role;
import com.crm.repository.*;
import com.crm.service.AuditLogService;
import com.crm.service.SecurityHelper;
import com.crm.service.SuspiciousIdentityService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonalEmailImportTest {
    private final ExcelImportController controller = new ExcelImportController();
    private final DatabaseRepository databases = mock(DatabaseRepository.class);
    private final DatabaseEmailRepository emails = mock(DatabaseEmailRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final GroupRepository groups = mock(GroupRepository.class);
    private final CompanyBranchRepository branches = mock(CompanyBranchRepository.class);
    private final List<Database> saved = new ArrayList<>();

    @BeforeEach void setup() {
        var security = mock(SecurityHelper.class);
        var user = AppUser.builder().id(1L).build();
        when(security.getAuthenticatedUser("test")).thenReturn(user);
        when(security.hasAnyRole(user, Role.ADMIN, Role.MANAGER)).thenReturn(true);
        ReflectionTestUtils.setField(controller, "databaseRepository", databases);
        ReflectionTestUtils.setField(controller, "databaseEmailRepository", emails);
        ReflectionTestUtils.setField(controller, "companyRepository", companies);
        ReflectionTestUtils.setField(controller, "companyBranchRepository", branches);
        ReflectionTestUtils.setField(controller, "groupRepository", groups);
        ReflectionTestUtils.setField(controller, "securityHelper", security);
        ReflectionTestUtils.setField(controller, "auditLogService", mock(AuditLogService.class));
        ReflectionTestUtils.setField(controller, "suspiciousIdentityService", mock(SuspiciousIdentityService.class));
        when(databases.findAll()).thenAnswer(call -> new ArrayList<>(saved));
        when(databases.findById(anyLong())).thenAnswer(call -> saved.stream()
                .filter(contact -> contact.getId().equals(call.getArgument(0))).findFirst());
        when(databases.save(any())).thenAnswer(call -> {
            Database contact = call.getArgument(0);
            if (contact.getId() == null) { contact.setId(100L + saved.size()); saved.add(contact); }
            return contact;
        });
        when(emails.findAllByEmailIgnoreCase(anyString())).thenAnswer(call -> saved.stream()
                .flatMap(contact -> contact.getEmails() == null ? java.util.stream.Stream.empty() : contact.getEmails().stream())
                .filter(email -> email.getEmail().equalsIgnoreCase(call.getArgument(0))).toList());
        when(emails.save(any())).thenAnswer(call -> call.getArgument(0));
        when(companies.findAll()).thenReturn(List.of());
        when(branches.findAll()).thenReturn(List.of());
    }

    @Test void selectedParticipantDestinationAcceptsPublicCampusAndWorkEmailsWithoutCompany() throws Exception {
        var rows = new ArrayList<String[]>();
        for (String domain : List.of("gmail.com", "yahoo.co.id", "outlook.com", "student.example.ac.id", "company.example")) {
            for (int column : List.of(13, 14)) {
                String[] row = personal("Person" + rows.size(), "person" + rows.size() + "@" + domain, column);
                row[3] = "Example School";
                rows.add(row);
            }
        }
        var upload = file(rows.toArray(String[][]::new));
        assertEquals(10, preview(upload).getNewCount());
        assertEquals(10, imported(upload).get("newCount"));
        assertEquals(10, saved.size());
        assertTrue(saved.stream().allMatch(contact -> contact.getCompany() == null && contact.getBranch() == null
                && contact.getDatabaseDestination() == DatabaseDestination.PARTICIPANT
                && "Example School".equals(contact.getInstitutionName())));
        verify(companies, never()).save(any()); verify(groups, never()).save(any()); verify(branches, never()).save(any());
    }

    @Test void destinationIsRequiredAndNeverInferredFromDomain() throws Exception {
        var campus = file(personal("Student", "student@campus.ac.id", 14));
        assertEquals(400, controller.previewImport(campus, null).getStatusCode().value());
        assertEquals(400, controller.previewImport(campus, "both").getStatusCode().value());
        assertEquals(1, preview(campus).getNewCount());
        var companyResponse = controller.previewImport(campus, "company");
        var companyPreview = (ExcelImportController.ImportPreviewResponse) companyResponse.getBody();
        assertEquals(1, companyPreview.getConflictCount());
        var gmail = file(corporate("Student", "student@gmail.com"));
        var rejected = (ExcelImportController.ImportPreviewResponse) controller.previewImport(gmail, "company").getBody();
        assertTrue(rejected.getRows().get(0).getMessage().contains("Email pribadi masuk melalui Database Peserta"));
        assertEquals(400, controller.importDatabases(gmail, "company", "test").getStatusCode().value());
        verify(databases, never()).save(any());
    }

    @Test void sameNameDistinctAddressesDoNotMergeAndRepeatsReuseOneProfile() throws Exception {
        var upload = file(personal("Ayu", "first@campus.ac.id", 14), personal("Ayu", "second@gmail.com", 14));
        assertEquals(2, imported(upload).get("newCount"));
        assertEquals(2, preview(upload).getDuplicateCount());
        assertEquals(0, imported(upload).get("newCount"));
        assertEquals(2, saved.size());
    }

    @Test void sharedParticipantIdentityAndInvalidInputsCannotBeImported() throws Exception {
        var duplicate = file(personal("Ayu", "same@campus.ac.id", 13), personal("Budi", "same@campus.ac.id", 14));
        assertEquals(2, preview(duplicate).getConflictCount());
        assertEquals(400, controller.importDatabases(duplicate, "participant", "test").getStatusCode().value());
        var invalid = file(personal("", "missing-name@gmail.com", 14), personal("Ayu", "bad@@gmail.com", 14));
        assertEquals(0, preview(invalid).getIncompleteCount());
        assertEquals(1, preview(invalid).getNewCount());
        assertEquals(1, preview(invalid).getConflictCount());
        verify(databases, never()).save(any());
    }

    @Test void participantImportPreservesExistingCompanyBranchProfessionalFieldsAndEmailOwnership() throws Exception {
        var company = Company.builder().id(10L).name("Example PT").industry("Technology").build();
        var branch = CompanyBranch.builder().id(20L).companyId(10L).name("Jakarta").address("Original").build();
        var existing = person(1L, "Nadia", "nadia@company.example", true);
        existing.setCompany(company); existing.setBranch(branch); existing.setJobTitle("Director"); saved.add(existing);
        var row = personal("Nadia", "nadia@company.example", 14); row[3] = "Campus participant institution"; row[9] = "Student";
        var upload = file(row);
        assertEquals(1, preview(upload).getDuplicateCount());
        assertEquals(1, imported(upload).get("updatedCount"));
        assertSame(company, existing.getCompany()); assertSame(branch, existing.getBranch());
        assertEquals("Director", existing.getJobTitle()); assertEquals("Technology", company.getIndustry());
        assertEquals(DatabaseDestination.BOTH, existing.getDatabaseDestination());
        assertEquals("Campus participant institution", existing.getInstitutionName());
        assertTrue(existing.getEmails().get(0).getIsCorporate());
        assertEquals(0, imported(upload).get("newCount"));
        assertEquals(1, saved.size()); verify(companies, never()).save(any()); verify(branches, never()).save(any());
    }

    @Test void explicitCompanyImportAddsMembershipToTheConfirmedParticipantIdentity() throws Exception {
        var company = Company.builder().id(10L).name("Example PT").build();
        when(companies.findAll()).thenReturn(List.of(company)); when(companies.save(any())).thenAnswer(call -> call.getArgument(0));
        var existing = person(1L, "Nadia", "nadia@company.example", true);
        existing.setDatabaseDestination(DatabaseDestination.PARTICIPANT); existing.setLastName("Person"); existing.setCompany(company); saved.add(existing);
        var upload = file(corporate("Nadia", "nadia@company.example"));
        var result = (ExcelImportController.ImportPreviewResponse) controller.previewImport(upload, "company").getBody();
        assertEquals(0, result.getConflictCount());
        assertEquals(200, controller.importDatabases(upload, "company", "test").getStatusCode().value());
        assertEquals(DatabaseDestination.BOTH, existing.getDatabaseDestination()); assertEquals(1, saved.size());
    }

    @Test void simpleParticipantTemplateKeepsCategoryAndInstitutionWithoutFabricatedCompany() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet();
            String[] headers = {"No", "First Name", "Last Name", "Email", "Mobile Phone", "Kategori Peserta", "Sekolah/Kampus/Instansi"};
            Row header = sheet.createRow(0); for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            Row row = sheet.createRow(1); row.createCell(1).setCellValue("Ayu"); row.createCell(3).setCellValue("ayu@student.example.ac.id");
            row.createCell(5).setCellValue("mahasiswa"); row.createCell(6).setCellValue("Example Campus");
            var out = new ByteArrayOutputStream(); workbook.write(out);
            var upload = new MockMultipartFile("file", "participants.xlsx", "application/octet-stream", out.toByteArray());
            assertEquals(1, preview(upload).getNewCount()); assertEquals(1, imported(upload).get("newCount"));
            assertEquals("mahasiswa", saved.get(0).getParticipantCategory()); assertEquals("Example Campus", saved.get(0).getInstitutionName());
            assertNull(saved.get(0).getCompany()); assertNull(saved.get(0).getMobilePhone());
            assertEquals(400, controller.previewImport(upload, "company").getStatusCode().value());
        }
    }

    @Test void ambiguousExistingParticipantIdentityRequiresReview() throws Exception {
        saved.add(person(1L, "Ayu", "ayu@campus.ac.id", false));
        saved.add(person(2L, "Ayu", "ayu@campus.ac.id", true));
        var result = preview(file(personal("Ayu", "ayu@campus.ac.id", 14)));
        assertEquals(1, result.getConflictCount());
        assertTrue(result.getRows().get(0).getMessage().contains("ambigu"));
        verify(databases, never()).save(any());
    }

    @Test void explicitCompanyImportReusesParticipantWithCampusOrWorkEmailWithoutDuplicatingProfile() throws Exception {
        when(companies.save(any())).thenAnswer(call -> { Company company = call.getArgument(0); if (company.getId() == null) company.setId(55L); return company; });
        for (String domain : List.of("campus.ac.id", "company.example")) {
            saved.clear(); String address = "nadia@" + domain;
            Database existing = person(1L, "Nadia", address, false);
            existing.setLastName("Person"); existing.setDatabaseDestination(DatabaseDestination.PARTICIPANT);
            existing.setInstitutionName("Original Campus"); saved.add(existing);
            var upload = file(corporate("Nadia", address));
            var result = (ExcelImportController.ImportPreviewResponse) controller.previewImport(upload, "company").getBody();
            assertEquals(0, result.getConflictCount(), domain); assertEquals(1L, result.getRows().get(0).getExistingDatabaseId(), domain);
            assertEquals(200, controller.importDatabases(upload, "company", "test").getStatusCode().value(), domain);
            assertEquals(1, saved.size()); assertNotNull(existing.getCompany());
            assertEquals(DatabaseDestination.BOTH, existing.getDatabaseDestination()); assertEquals("Original Campus", existing.getInstitutionName());
        }
    }

    @Test void ambiguousParticipantEmailOwnerRejectsCompanyImportBeforeAnyWrites() throws Exception {
        for (long id : List.of(1L, 2L)) {
            Database existing = person(id, "Nadia", "nadia@campus.ac.id", id == 1L);
            existing.setLastName("Person"); existing.setDatabaseDestination(DatabaseDestination.PARTICIPANT); saved.add(existing);
        }
        var upload = file(corporate("Nadia", "nadia@campus.ac.id"));
        var result = (ExcelImportController.ImportPreviewResponse) controller.previewImport(upload, "company").getBody();
        assertEquals(1, result.getConflictCount());
        assertTrue(result.getRows().get(0).getMessage().contains("ambigu"));
        assertEquals(400, controller.importDatabases(upload, "company", "test").getStatusCode().value());
        verify(databases, never()).save(any()); verify(companies, never()).save(any());
    }

    @Test void fullParticipantTemplateReusesEmailOwnerDespiteDifferentCompanySpelling() throws Exception {
        for (String domain : List.of("campus.ac.id", "company.example")) {
            saved.clear();
            String address = "nadia@" + domain;
            Company originalCompany = Company.builder().id(30L).name("Original Institute, PT").industry("Education").build();
            Database existing = person(1L, "Nadia", address, false);
            existing.setLastName("Person"); existing.setCompany(originalCompany); saved.add(existing);
            var upload = file(corporate("Nadia", address)); // Incoming Company Name is Example PT.
            assertEquals(1L, preview(upload).getRows().get(0).getExistingDatabaseId());
            assertEquals(1, imported(upload).get("updatedCount"));
            assertEquals(1, saved.size()); assertSame(originalCompany, existing.getCompany());
            assertEquals(DatabaseDestination.BOTH, existing.getDatabaseDestination());
            assertEquals("Example PT", existing.getInstitutionName());
            assertEquals(0, imported(upload).get("newCount"));
        }
        verify(companies, never()).save(any()); verify(groups, never()).save(any()); verify(branches, never()).save(any());
    }

    @Test void participantImportAcceptsPhoneOnlyAndUnnamedRowsWithQualityScopedToParticipantView() throws Exception {
        String[] phone = personal("Phone Person", "", 14); phone[12] = "081200001111, 081300002222";
        String[] unnamed = personal("", "nameless@example.invalid", 14);
        var upload = file(phone, unnamed); assertEquals(2, preview(upload).getNewCount());
        assertEquals(2, imported(upload).get("newCount"));
        var quality = new DatabaseController();
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(quality, "participantIncomplete", saved.get(0)));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(quality, "participantIncomplete", saved.get(1)));
        assertTrue(saved.get(1).getFirstName().isBlank()); assertNull(saved.get(0).getCompany());
        assertEquals(0, imported(upload).get("newCount")); assertEquals(2, saved.size());
    }

    private static String[] corporate(String name, String address) {
        String[] row = personal(name, address, 13);
        row[1] = "Example Group"; row[2] = "Example"; row[3] = "Example PT"; row[4] = "Ms";
        row[6] = "Person"; row[7] = "unknown"; row[9] = "Staff"; row[10] = "Original";
        row[11] = "021123"; row[12] = "08123456789"; row[15] = "Technology"; row[20] = "Jakarta"; row[22] = "https://example.test";
        return row;
    }

    private static Database person(long id, String firstName, String address, boolean corporate) {
        Database contact = Database.builder().id(id).firstName(firstName).isActive(true).emails(new ArrayList<>()).build();
        contact.getEmails().add(DatabaseEmail.builder().database(contact).email(address)
                .emailType(corporate ? "company" : "personal").isCorporate(corporate).isPrimary(true).build());
        return contact;
    }

    private static String[] personal(String name, String address, int column) {
        String[] row = new String[23]; Arrays.fill(row, "");
        row[0] = "1"; row[5] = name; row[column] = address;
        return row;
    }

    @SuppressWarnings("unchecked")
    private MockMultipartFile file(String[]... rows) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet();
            List<String> headers = (List<String>) ReflectionTestUtils.getField(ExcelImportController.class, "HEADERS");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) header.createCell(i).setCellValue(headers.get(i));
            if (Arrays.stream(rows).anyMatch(row -> row.length > headers.size())) header.createCell(23).setCellValue("Cabang/Kantor");
            for (int i = 0; i < rows.length; i++) {
                Row row = sheet.createRow(i + 1);
                for (int j = 0; j < rows[i].length; j++) row.createCell(j).setCellValue(rows[i][j] == null ? "" : rows[i][j]);
            }
            var out = new ByteArrayOutputStream(); workbook.write(out);
            return new MockMultipartFile("file", "anonymous-personal.xlsx", "application/octet-stream", out.toByteArray());
        }
    }

    private ExcelImportController.ImportPreviewResponse preview(MockMultipartFile file) {
        var response = controller.previewImport(file, "participant");
        assertEquals(200, response.getStatusCode().value(), String.valueOf(response.getBody()));
        return (ExcelImportController.ImportPreviewResponse) response.getBody();
    }

    private Map<?, ?> imported(MockMultipartFile file) {
        var response = controller.importDatabases(file, "participant", "test");
        assertEquals(200, response.getStatusCode().value(), String.valueOf(response.getBody()));
        return (Map<?, ?>) response.getBody();
    }
}
