package com.crm.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.crm.domain.*;
import com.crm.repository.*;
import com.crm.service.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExcelImportControllerTest {
    private ExcelImportController controller;
    private DatabaseRepository databases;
    private DatabaseEmailRepository emails;
    private CompanyRepository companies;
    private GroupRepository groups;
    private final Company company = Company.builder().id(10L).name("Example PT").address("Alamat lama").build();

    @BeforeEach
    void setup() {
        controller = new ExcelImportController();
        databases = mock(DatabaseRepository.class);
        emails = mock(DatabaseEmailRepository.class);
        companies = mock(CompanyRepository.class);
        groups = mock(GroupRepository.class);
        SecurityHelper security = mock(SecurityHelper.class);
        AppUser user = AppUser.builder().id(1L).build();
        when(security.getAuthenticatedUser("test")).thenReturn(user);
        when(security.hasAnyRole(user, Role.ADMIN, Role.MANAGER)).thenReturn(true);
        ReflectionTestUtils.setField(controller, "databaseRepository", databases);
        ReflectionTestUtils.setField(controller, "databaseEmailRepository", emails);
        ReflectionTestUtils.setField(controller, "companyRepository", companies);
        ReflectionTestUtils.setField(controller, "groupRepository", groups);
        ReflectionTestUtils.setField(controller, "securityHelper", security);
        ReflectionTestUtils.setField(controller, "auditLogService", mock(AuditLogService.class));
        ReflectionTestUtils.setField(controller, "suspiciousIdentityService", mock(SuspiciousIdentityService.class));
        when(companies.findAll()).thenReturn(new ArrayList<>(List.of(company)));
        when(companies.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(groups.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(emails.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(databases.save(any())).thenAnswer(invocation -> {
            Database contact = invocation.getArgument(0);
            if (contact.getId() == null) contact.setId(100L);
            return contact;
        });
    }

    @Test
    void personalEmailColumnOnlyAcceptsPublicPersonalDomains() {
        assertEquals(List.of(), ExcelImportController.getCorporateEmailsInPersonalColumn("person@gmail.com; other@yahoo.co.id"));
        assertEquals(List.of("person@company.co.id"), ExcelImportController.getCorporateEmailsInPersonalColumn("person@company.co.id"));
        assertEquals(List.of("staff@company.com"), ExcelImportController.getCorporateEmailsInPersonalColumn("owner@outlook.com, staff@company.com"));
    }

    @Test
    void onlyNewImportedContactsGetUploadAttribution() throws Exception {
        assertEquals(200, controller.importDatabases(file(row("New", "081234567899", "office@example.com", "")), "test").getStatusCode().value());
        verify(databases).save(argThat(saved -> "New".equals(saved.getFirstName())
                && Long.valueOf(1L).equals(saved.getCreatedByUserId()) && "excel_import".equals(saved.getEntryMethod())));
        clearInvocations(databases);
        Database existing = contact(1L, "Andi", "Person", "081234567890");
        existing.setCreatedByUserId(9L);
        existing.setEntryMethod("manual");
        when(databases.findAll()).thenReturn(List.of(existing));
        when(databases.findById(1L)).thenReturn(Optional.of(existing));
        assertEquals(200, controller.importDatabases(file(row("Andi", "081234567890", "office@example.com", "")), "test").getStatusCode().value());
        assertEquals(9L, existing.getCreatedByUserId());
        assertEquals("manual", existing.getEntryMethod());
        // Legacy contacts remain unattributed even when imported again.
        existing.setCreatedByUserId(null); existing.setEntryMethod(null);
        assertEquals(200, controller.importDatabases(file(row("Andi", "081234567890", "office@example.com", "")), "test").getStatusCode().value());
        assertNull(existing.getCreatedByUserId());assertNull(existing.getEntryMethod());
    }

    @Test
    void sharedCompanyEmailAndOfficePhoneAreAllowedIncludingPublicDomains() throws Exception {
        for (String shared : List.of("sales@example.com", "office@gmail.com")) {
            String[] a = row("Andi", "081234567890", shared, "andi@gmail.com");
            String[] b = row("Budi", "081234567891", shared, "budi@gmail.com");
            var preview = preview(file(a, b));
            assertEquals(2, preview.getNewCount());
            assertEquals(0, preview.getConflictCount());
        }
    }

    @Test
    void officePhoneFormattingDoesNotBlockPreviewOrImport() throws Exception {
        for (String equivalent : List.of("(022) 6030798", "+62 22 6030798", "62226030798", "022-6030798", "022.6030798")) {
            String[] a = row("Andi", "081234567890", "office@example.com", "");
            String[] b = row("Budi", "081234567891", "office@example.com", "");
            a[11] = "22 6030798";
            b[11] = equivalent;
            var upload = file(a, b);
            assertEquals(0, preview(upload).getConflictCount(), equivalent);
            assertEquals(200, controller.importDatabases(upload, "test").getStatusCode().value(), equivalent);
        }
        assertEquals("62226030798", company.getOfficePhone());
    }

    @Test
    void importTidiesEquivalentExistingOfficePhoneWithoutReplacingAnotherNumber() throws Exception {
        String[] data = row("Andi", "081234567890", "office@example.com", "");
        data[11] = "22 6030798";
        for (String existing : List.of("22 6030798", "(022) 6030798", "+62 22 6030798", "62226030798")) {
            company.setOfficePhone(existing);
            assertEquals(200, controller.importDatabases(file(data), "test").getStatusCode().value());
            assertEquals("62226030798", company.getOfficePhone());
        }
        company.setOfficePhone("(021) 123456");
        assertEquals(200, controller.importDatabases(file(data), "test").getStatusCode().value());
        assertEquals("(021) 123456", company.getOfficePhone());
    }

    @Test
    void newCompanyStoresCanonicalOfficePhone() throws Exception {
        when(companies.findAll()).thenReturn(new ArrayList<>());
        String[] data = row("Andi", "081234567890", "office@example.com", "");
        data[11] = "22 6030798";
        assertEquals(200, controller.importDatabases(file(data), "test").getStatusCode().value());
        verify(companies).save(argThat(saved -> "62226030798".equals(saved.getOfficePhone())));
    }

    @Test
    void officePhoneNormalizationStillRejectsDifferentNumbersAndIndustry() throws Exception {
        String[] a = row("Andi", "081234567890", "office@example.com", "");
        String[] b = row("Budi", "081234567891", "office@example.com", "");
        a[11] = "22 6030798";
        for (String different : List.of("(022) 6030799", "(022) 6030798 ext 12", "022 6030798 / 022 6030799")) {
            b[11] = different;
            var upload = file(a, b);
            assertEquals(2, preview(upload).getConflictCount(), different);
            assertEquals(400, controller.importDatabases(upload, "test").getStatusCode().value(), different);
        }
        b[11] = "(022) 6030798";
        a[15] = "Education - K12";
        b[15] = "Education - Higher";
        var result = preview(file(a, b));
        assertEquals(2, result.getConflictCount());
        assertTrue(result.getRows().get(0).getMessage().contains("Industry"));
        assertFalse(result.getRows().get(0).getMessage().contains("Office Phone"));
        verify(databases, never()).save(any());
    }

    @Test
    void personalEmailAndNormalizedMobileDuplicatesBlockPreviewAndImport() throws Exception {
        var file = file(row("Andi", "081234567890", "office@example.com", "same@gmail.com"),
                row("Budi", "+62 812-3456-7890", "office@example.com", "SAME@gmail.com"));
        var preview = preview(file);
        assertEquals(2, preview.getConflictCount());
        assertTrue(preview.getRows().get(0).getMessage().contains("Mobile Phone"));
        assertTrue(preview.getRows().get(0).getMessage().contains("Personal Email"));
        assertEquals(400, controller.importDatabases(file, "test").getStatusCode().value());
        verify(databases, never()).save(any());
        verify(companies, never()).save(any());
        verify(groups, never()).save(any());
    }

    @Test
    void checksAllPhoneOwnersEvenWhenFirstOwnerIsTheUpdateTarget() throws Exception {
        Database target = contact(1L, "Andi", "Person", "081234567890");
        Database other = contact(2L, "Budi", "Person", "+62 812-3456-7890");
        // Legacy records have no normalized_phone: compare normalized raw numbers too.
        when(databases.findAll()).thenReturn(List.of(target, other));
        var preview = preview(file(row("Andi", "081234567890", "office@example.com", "")));
        assertEquals("CONFLICT", preview.getRows().get(0).getStatus());
        assertTrue(preview.getRows().get(0).getMessage().contains("ID 2"));
    }

    @Test
    void emailCannotBypassPersonalUniquenessThroughCompanyColumn() throws Exception {
        Database owner = contact(1L, "Andi", "Person", "081234567890");
        owner.getEmails().add(email(owner, "owner@gmail.com", false, true));
        when(databases.findAll()).thenReturn(List.of(owner));
        assertEquals(1, preview(file(row("Budi", "081234567891", "owner@gmail.com", ""))).getConflictCount());

        when(databases.findAll()).thenReturn(List.of());
        var preview = preview(file(row("Andi", "081234567890", "office@example.com", "owner@gmail.com"),
                row("Budi", "081234567891", "owner@gmail.com", "")));
        assertEquals(2, preview.getConflictCount());
    }

    @Test
    void malformedEmailsAndCorporatePersonalEmailsAreRejected() throws Exception {
        for (String personal : List.of("not-an-email", "name@@gmail.com", "name@company.com")) {
            assertEquals(1, preview(file(row("Andi", "081234567890", "office@example.com", personal))).getConflictCount());
        }
    }

    @Test
    void splitNamesAndWhitespaceResolveSameContactAndAmbiguityIsRejected() throws Exception {
        Database target = contact(1L, "Andi Person", "Name", "081234567890");
        when(databases.findAll()).thenReturn(List.of(target));
        String[] data = row("  Andi  ", "081234567890", "office@example.com", "");
        data[6] = "Person  Name";
        var preview = preview(file(data));
        assertEquals(1, preview.getDuplicateCount());
        assertEquals(1L, preview.getRows().get(0).getExistingDatabaseId());
        when(databases.findAll()).thenReturn(List.of(target, contact(2L, "Andi", "Person Name", "081234567891")));
        assertEquals(1, preview(file(data)).getConflictCount());
    }

    @Test
    void personalEmailSupportsCompanyMoveButDoesNotOverwriteDifferentPerson() throws Exception {
        Database target = contact(1L, "Andi", "Person", "081234567890");
        target.setCompany(Company.builder().name("Previous PT").build());
        target.getEmails().add(email(target, "andi@gmail.com", false, true));
        when(databases.findAll()).thenReturn(List.of(target));
        assertEquals(1, preview(file(row("Andi", "081234567890", "office@example.com", "andi@gmail.com"))).getDuplicateCount());
        assertEquals(1, preview(file(row("Budi", "081234567891", "office@example.com", "andi@gmail.com"))).getConflictCount());
    }

    @Test
    void repeatingContactOrContradictoryCompanyRowsAreRejected() throws Exception {
        String[] a = row("Andi", "081234567890", "office@example.com", "");
        String[] b = row("Andi", "081234567891", "other@example.com", "");
        assertEquals(2, preview(file(a, b)).getConflictCount());
        b[5] = "Budi";
        b[10] = "Alamat berbeda";
        assertEquals(2, preview(file(a, b)).getConflictCount());
    }

    @Test
    void normalizedCompanyNameReusesMasterAndDoesNotCreateAnUnusedGroup() throws Exception {
        Group existingGroup = Group.builder().id(20L).name("Existing Holding").build();
        company.setGroup(existingGroup);
        String[] data = row("Andi", "081234567890", "office@example.com", "");
        data[3] = "PT. Example";
        assertEquals(200, controller.importDatabases(file(data), "test").getStatusCode().value());
        verify(companies).save(company);
        verify(groups, never()).save(any());
        assertSame(existingGroup, company.getGroup());

        when(companies.findAll()).thenReturn(List.of(company, Company.builder().id(11L).name("PT Example").build()));
        assertEquals(1, preview(file(data)).getConflictCount());
    }

    @Test
    void movingAnEmailToAnotherTypeRepairsTheRemainingPrimary() throws Exception {
        Database target = contact(1L, "Andi", "Person", "081234567890");
        target.getEmails().add(email(target, "office@gmail.com", false, true));
        target.getEmails().add(email(target, "retained@gmail.com", false, false));
        when(databases.findAll()).thenReturn(List.of(target));
        when(databases.findById(1L)).thenReturn(Optional.of(target));
        assertEquals(200, controller.importDatabases(file(row("Andi", "081234567890", "office@gmail.com", "")), "test")
                .getStatusCode().value());
        assertEquals(1, target.getEmails().stream().filter(e -> e.getIsCorporate() && e.getIsPrimary()).count());
        assertEquals(1, target.getEmails().stream().filter(e -> !e.getIsCorporate() && e.getIsPrimary()).count());
    }

    @Test
    void mergePreservesOldValuesCompanyAndInactiveStatusAndHasOnePrimaryPerType() throws Exception {
        Database target = contact(1L, "Andi", "Person", "081234567890");
        target.setJobTitle("Manager lama");
        target.setIsActive(false);
        target.getEmails().add(email(target, "old@example.com", true, true));
        target.getEmails().add(email(target, "old@gmail.com", false, true));
        when(databases.findAll()).thenReturn(List.of(target));
        when(databases.findById(1L)).thenReturn(Optional.of(target));
        String[] data = row("Andi", "", "new@example.com; other@example.com", "new@gmail.com");
        data[9] = "";
        var file = file(data);
        assertEquals(1, preview(file).getDuplicateCount());
        assertEquals(200, controller.importDatabases(file, "test").getStatusCode().value());
        assertEquals("Manager lama", target.getJobTitle());
        assertEquals("081234567890", target.getMobilePhone());
        assertFalse(target.getIsActive());
        assertEquals("Alamat lama", company.getAddress());
        assertEquals(5, target.getEmails().size());
        assertEquals(1, target.getEmails().stream().filter(e -> e.getIsCorporate() && e.getIsPrimary()).count());
        assertEquals(1, target.getEmails().stream().filter(e -> !e.getIsCorporate() && e.getIsPrimary()).count());
    }

    @Test
    void blankNamesAreNotSilentlySkippedAndBadHeadersAreRejected() throws Exception {
        String[] data = row("", "081234567890", "office@example.com", "");
        data[6] = "";
        assertEquals(1, preview(file(data)).getIncompleteCount());
        try (Workbook workbook = new XSSFWorkbook()) {
            workbook.createSheet().createRow(0).createCell(0).setCellValue("Wrong header");
            assertEquals(400, controller.previewImport(upload(workbook)).getStatusCode().value());
        }
    }

    @Test
    void oldXlsFormatIsSupported() throws Exception {
        try (Workbook workbook = new HSSFWorkbook()) {
            populate(workbook, row("Andi", "081234567890", "office@example.com", ""));
            assertEquals(1, preview(upload(workbook)).getNewCount());
        }
    }

    @Test
    void failureAfterEarlierWritesRollsBackTheImportTransaction() throws Exception {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        ProxyFactory factory = new ProxyFactory(controller);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        ExcelImportController proxy = (ExcelImportController) factory.getProxy();
        doAnswer(invocation -> {
            Database contact = invocation.getArgument(0);
            if ("Budi".equals(contact.getFirstName())) throw new IllegalStateException("forced failure");
            contact.setId(100L);
            return contact;
        }).when(databases).save(any());
        var result = proxy.importDatabases(file(row("Andi", "081234567890", "office@example.com", ""),
                row("Budi", "081234567891", "office@example.com", "")), "test");
        assertEquals(500, result.getStatusCode().value());
        assertTrue(manager.rolledBack);
        assertFalse(manager.committed);
        verify(databases, times(2)).save(any());
    }

    private Database contact(long id, String first, String last, String phone) {
        return Database.builder().id(id).firstName(first).lastName(last).mobilePhone(phone)
                .company(company).emails(new ArrayList<>()).build();
    }

    private DatabaseEmail email(Database target, String address, boolean corporate, boolean primary) {
        return DatabaseEmail.builder().database(target).email(address).isCorporate(corporate)
                .emailType(corporate ? "company" : "personal").isPrimary(primary).build();
    }

    private String[] row(String first, String mobile, String companyEmail, String personalEmail) {
        return new String[]{"1", "Example Group", "Example", "Example PT", "Mr", first, "Person",
                "Manager", "IT", "IT Manager", "Alamat Excel", "021123456", mobile, companyEmail,
                personalEmail, "Technology", "", "", "", "", "Jakarta", "", "https://example.com"};
    }

    private MockMultipartFile file(String[]... rows) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            populate(workbook, rows);
            return upload(workbook);
        }
    }

    @SuppressWarnings("unchecked")
    private void populate(Workbook workbook, String[]... rows) {
        Sheet sheet = workbook.createSheet();
        List<String> headers = (List<String>) ReflectionTestUtils.getField(ExcelImportController.class, "HEADERS");
        List<List<String>> all = new ArrayList<>();
        all.add(headers);
        Arrays.stream(rows).map(Arrays::asList).forEach(all::add);
        for (int r = 0; r < all.size(); r++) {
            Row row = sheet.createRow(r);
            for (int c = 0; c < all.get(r).size(); c++) row.createCell(c).setCellValue(all.get(r).get(c));
        }
    }

    private MockMultipartFile upload(Workbook workbook) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbook.write(output);
        return new MockMultipartFile("file", "test.xlsx", "application/octet-stream", output.toByteArray());
    }

    private ExcelImportController.ImportPreviewResponse preview(MockMultipartFile file) {
        var response = controller.previewImport(file);
        assertEquals(200, response.getStatusCode().value(), () -> String.valueOf(response.getBody()));
        return (ExcelImportController.ImportPreviewResponse) response.getBody();
    }

    private static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        boolean committed;
        boolean rolledBack;
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object transaction, TransactionDefinition definition) {}
        protected void doCommit(DefaultTransactionStatus status) { committed = true; }
        protected void doRollback(DefaultTransactionStatus status) { rolledBack = true; }
    }
}
