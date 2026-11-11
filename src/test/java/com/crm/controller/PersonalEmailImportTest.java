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
        when(databases.tryLockImportDestination(anyInt())).thenReturn(true);
        when(databases.findForExcelImport(any(DatabaseDestination.class))).thenAnswer(call -> databases.findAll());
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

    @Test void participantImportCreatesSeparateProfilePreservingCompanyBranchAndEmailOwnership() throws Exception {
        var company = Company.builder().id(10L).name("Example PT").industry("Technology").build();
        var branch = CompanyBranch.builder().id(20L).companyId(10L).name("Jakarta").address("Original").build();
        var existing = person(1L, "Nadia", "nadia@company.example", true);
        existing.setDatabaseDestination(DatabaseDestination.COMPANY);
        existing.setMobilePhone("081200001111, 081300002222");
        existing.setCompany(company); existing.setBranch(branch); existing.setJobTitle("Director"); saved.add(existing);
        var row = personal("Nadia", "nadia@company.example", 14); row[3] = "Campus participant institution"; row[9] = "Student";
        row[12] = "081200001111, 081300002222";
        var upload = file(row);
        assertEquals(1, preview(upload).getNewCount());
        assertEquals(1, imported(upload).get("newCount"));
        assertSame(company, existing.getCompany()); assertSame(branch, existing.getBranch());
        assertEquals("Director", existing.getJobTitle()); assertEquals("Technology", company.getIndustry());
        assertEquals(DatabaseDestination.COMPANY, existing.getDatabaseDestination()); assertNull(existing.getInstitutionName());
        assertTrue(existing.getEmails().get(0).getIsCorporate());
        Database participant = saved.get(1);
        assertNotEquals(existing.getId(), participant.getId());
        assertEquals(DatabaseDestination.PARTICIPANT, participant.getDatabaseDestination());
        assertNull(participant.getCompany()); assertNull(participant.getBranch());
        assertEquals("Campus participant institution", participant.getInstitutionName());
        assertEquals("081200001111, 081300002222", participant.getMobilePhone());
        assertFalse(participant.getEmails().get(0).getIsCorporate());
        assertEquals(0, imported(upload).get("newCount"));
        assertEquals(2, saved.size()); verify(companies, never()).save(any()); verify(branches, never()).save(any());
    }

    @Test void explicitCompanyImportCreatesSeparateProfileWithoutChangingParticipant() throws Exception {
        var company = Company.builder().id(10L).name("Example PT").build();
        when(companies.findAll()).thenReturn(List.of(company)); when(companies.save(any())).thenAnswer(call -> call.getArgument(0));
        var existing = person(1L, "Nadia", "nadia@company.example", false);
        existing.setLastName("Person"); existing.setInstitutionName("Original Campus"); saved.add(existing);
        var upload = file(corporate("Nadia", "nadia@company.example"));
        var result = (ExcelImportController.ImportPreviewResponse) controller.previewImport(upload, "company").getBody();
        assertEquals(0, result.getConflictCount()); assertEquals(1, result.getNewCount());
        assertEquals(200, controller.importDatabases(upload, "company", "test").getStatusCode().value());
        assertEquals(DatabaseDestination.PARTICIPANT, existing.getDatabaseDestination()); assertEquals(2, saved.size());
        assertNull(existing.getCompany()); assertEquals("Original Campus", existing.getInstitutionName());
        assertEquals(DatabaseDestination.COMPANY, saved.get(1).getDatabaseDestination()); assertSame(company, saved.get(1).getCompany());
        assertNotEquals(existing.getId(), saved.get(1).getId());
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

    @Test void explicitCompanyImportKeepsCampusAndWorkParticipantProfilesSeparateAndRepeatsReuseCompany() throws Exception {
        when(companies.save(any())).thenAnswer(call -> { Company company = call.getArgument(0); if (company.getId() == null) company.setId(55L); return company; });
        for (String domain : List.of("campus.ac.id", "company.example")) {
            saved.clear(); String address = "nadia@" + domain;
            Database existing = person(1L, "Nadia", address, false);
            existing.setLastName("Person"); existing.setInstitutionName("Original Campus"); saved.add(existing);
            var upload = file(corporate("Nadia", address));
            var result = (ExcelImportController.ImportPreviewResponse) controller.previewImport(upload, "company").getBody();
            assertEquals(0, result.getConflictCount(), domain); assertNull(result.getRows().get(0).getExistingDatabaseId(), domain);
            assertEquals(200, controller.importDatabases(upload, "company", "test").getStatusCode().value(), domain);
            assertEquals(2, saved.size()); assertNull(existing.getCompany());
            assertEquals(DatabaseDestination.PARTICIPANT, existing.getDatabaseDestination()); assertEquals("Original Campus", existing.getInstitutionName());
            assertEquals(DatabaseDestination.COMPANY, saved.get(1).getDatabaseDestination()); assertNotNull(saved.get(1).getCompany());
            assertEquals(200, controller.importDatabases(upload, "company", "test").getStatusCode().value(), domain);
            assertEquals(2, saved.size());
        }
    }

    @Test void ambiguousOppositeParticipantEmailOwnersDoNotBlockCompanyImport() throws Exception {
        when(companies.save(any())).thenAnswer(call -> { Company company = call.getArgument(0); if (company.getId() == null) company.setId(55L); return company; });
        for (long id : List.of(1L, 2L)) {
            Database existing = person(id, id == 1 ? "Other" : "Another", "nadia@campus.ac.id", id == 1L);
            existing.setLastName("Person"); existing.setMobilePhone("08123456789"); saved.add(existing);
        }
        var upload = file(corporate("Nadia", "nadia@campus.ac.id"));
        var result = (ExcelImportController.ImportPreviewResponse) controller.previewImport(upload, "company").getBody();
        assertEquals(0, result.getConflictCount()); assertEquals(1, result.getNewCount());
        assertEquals(200, controller.importDatabases(upload, "company", "test").getStatusCode().value());
        assertEquals(3, saved.size());
        assertTrue(saved.subList(0, 2).stream().allMatch(profile -> profile.getDatabaseDestination() == DatabaseDestination.PARTICIPANT));
        assertEquals(DatabaseDestination.COMPANY, saved.get(2).getDatabaseDestination());
    }

    @Test void fullParticipantTemplateCreatesSeparateProfileDespiteSharedEmailAndCompanySpelling() throws Exception {
        for (String domain : List.of("campus.ac.id", "company.example")) {
            saved.clear();
            String address = "nadia@" + domain;
            Company originalCompany = Company.builder().id(30L).name("Original Institute, PT").industry("Education").build();
            Database existing = person(1L, "Nadia", address, false);
            existing.setDatabaseDestination(DatabaseDestination.COMPANY);
            existing.setLastName("Person"); existing.setCompany(originalCompany); saved.add(existing);
            var upload = file(corporate("Nadia", address));
            assertNull(preview(upload).getRows().get(0).getExistingDatabaseId());
            assertEquals(1, imported(upload).get("newCount"));
            assertEquals(2, saved.size()); assertSame(originalCompany, existing.getCompany());
            assertEquals(DatabaseDestination.COMPANY, existing.getDatabaseDestination()); assertNull(existing.getInstitutionName());
            assertNull(saved.get(1).getCompany()); assertEquals("Example PT", saved.get(1).getInstitutionName());
            assertEquals(0, imported(upload).get("newCount")); assertEquals(2, saved.size());
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

    @Test void exactStudent20TemplatePreservesEveryIndependentFieldAndBlankUpdatesRetainThem() throws Exception {
        String[] values = {"1", "Campus Holding", "Campus Brand", "Campus Institute", "Ms", "Nadia", "Person", "Manager", "Student Services", "Coordinator",
                "Campus Address", "021-111 ext 7", "081200001111, 081300002222", "nadia@campus.ac.id", "Education", "Revenue band", "Employee band", "Central Campus", "12345", "https://campus.invalid"};
        var upload = studentFile(values);
        assertEquals(1, preview(upload).getNewCount()); assertEquals(1, imported(upload).get("newCount"));
        Database contact = saved.get(0);
        assertEquals("Campus Institute", contact.getInstitutionName()); assertEquals("Ms", contact.getSalutation());
        assertEquals(com.crm.domain.PositionLevel.MANAGERIAL_HEAD, contact.getPositionLevel());
        assertEquals("Student Services", contact.getSpecialityDivision()); assertEquals("Coordinator", contact.getJobTitle());
        assertEquals("Campus Holding", contact.getOrganizationGroupName()); assertEquals("Campus Brand", contact.getOrganizationBrandName());
        assertEquals("Campus Address", contact.getOrganizationAddress()); assertEquals("021-111 ext 7", contact.getOrganizationOfficePhone());
        assertEquals("Education", contact.getOrganizationIndustry()); assertEquals("Revenue band", contact.getOrganizationSizeRevenue());
        assertEquals("Employee band", contact.getOrganizationSizeEmployee()); assertEquals("Central Campus", contact.getOrganizationBranchName());
        assertEquals("12345", contact.getOrganizationPostalCode()); assertEquals("https://campus.invalid", contact.getOrganizationWebsite());
        assertEquals("081200001111, 081300002222", contact.getMobilePhone());
        assertEquals("nadia@campus.ac.id", contact.getEmails().get(0).getEmail()); assertEquals("personal", contact.getEmails().get(0).getEmailType());
        assertNull(contact.getCompany()); assertNull(contact.getBranch());
        String[] blankUpdate = new String[20]; Arrays.fill(blankUpdate, ""); blankUpdate[13] = values[13];
        assertEquals(1, imported(studentFile(blankUpdate)).get("updatedCount")); assertEquals(1, saved.size());
        assertEquals("Campus Institute", contact.getInstitutionName()); assertEquals("Campus Holding", contact.getOrganizationGroupName());
        assertEquals("Student Services", contact.getSpecialityDivision()); assertEquals("https://campus.invalid", contact.getOrganizationWebsite());
        verify(companies, never()).save(any()); verify(branches, never()).save(any()); verify(groups, never()).save(any());
    }

    @Test void studentOrganizationFieldBoundsAreCheckedBeforeAnyWrites() throws Exception {
        for (int column : List.of(1, 2, 11, 14, 15, 16, 17, 18)) {
            String[] values = new String[20]; Arrays.fill(values, ""); values[13] = "student@campus.ac.id";
            int limit = column == 11 ? 50 : column == 18 ? 20 : column >= 14 && column <= 16 ? 100 : 255;
            values[column] = "x".repeat(limit + 1);
            assertEquals(1, preview(studentFile(values)).getConflictCount(), "column " + column);
            assertEquals(400, controller.importDatabases(studentFile(values), "participant", "test").getStatusCode().value());
        }
        verify(databases, never()).save(any()); verify(emails, never()).save(any());
    }

    private static MockMultipartFile studentFile(String[]... rows) throws Exception {
        String[] headers = {"No", "Nama Group/Holding Company", "Nama Brand", "Company Name", "Salutation", "First Name", "Last Name", "Position", "Division", "Jobtitle",
                "Address", "Office Phone", "Mobile Phone", "Personal Email Address", "Industry", "Company Size (Revenue)", "Company Size (Employee)", "Cabang/Kantor", "Postal Code", "Company Website"};
        try (Workbook workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(); Row header = sheet.createRow(0);
            for (int column = 0; column < headers.length; column++) header.createCell(column).setCellValue(headers[column]);
            for (int index = 0; index < rows.length; index++) {
                Row row = sheet.createRow(index + 1);
                for (int column = 0; column < rows[index].length; column++) row.createCell(column).setCellValue(rows[index][column]);
            }
            workbook.write(out); return new MockMultipartFile("file", "student20.xlsx", "application/octet-stream", out.toByteArray());
        }
    }

    private static String[] corporate(String name, String address) {
        String[] row = personal(name, address, 13);
        row[1] = "Example Group"; row[2] = "Example"; row[3] = "Example PT"; row[4] = "Ms";
        row[6] = "Person"; row[7] = "unknown"; row[9] = "Staff"; row[10] = "Original";
        row[11] = "021123"; row[12] = "08123456789"; row[15] = "Technology"; row[20] = "Jakarta"; row[22] = "https://example.test";
        return row;
    }

    private static Database person(long id, String firstName, String address, boolean corporate) {
        Database contact = Database.builder().id(id).firstName(firstName).databaseDestination(DatabaseDestination.PARTICIPANT).isActive(true).emails(new ArrayList<>()).build();
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
