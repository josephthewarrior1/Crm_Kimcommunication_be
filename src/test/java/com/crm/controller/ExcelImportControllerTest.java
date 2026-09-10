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
    void importsOnlySixtyCleanRowsOutOfOneHundredAndReportsOriginalRowNumbers() throws Exception {
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String[] data = row("Person" + i, "08123456" + String.format("%04d", i), "office@example.com", "");
            if (i >= 60) {
                data[9] = "";
                data[1] = "Skipped Holding";
                data[3] = "Skipped Company PT";
            }
            rows.add(data);
        }
        var upload = file(rows.toArray(String[][]::new));
        assertEquals(60, preview(upload).getNewCount());
        assertEquals(40, preview(upload).getIncompleteCount());
        var response = controller.importDatabases(upload, "test");
        assertEquals(200, response.getStatusCode().value());
        var body = (java.util.Map<?, ?>) response.getBody();
        assertEquals(100, body.get("totalRows"));
        assertEquals(60, body.get("count"));
        assertEquals(60, body.get("newCount"));
        assertEquals(0, body.get("updatedCount"));
        assertEquals(40, body.get("skippedCount"));
        var skipped = (List<ExcelImportController.RowPreview>) body.get("skippedRows");
        assertEquals(62, skipped.get(0).getRowNum());
        assertEquals(101, skipped.get(39).getRowNum());
        assertTrue(skipped.stream().allMatch(r -> r.getMessage().contains("Job Title")));
        verify(databases, times(60)).save(any());
        verify(databases, never()).save(argThat(d -> Integer.parseInt(d.getFirstName().substring(6)) >= 60));
        verify(companies, never()).save(argThat(c -> "Skipped Company PT".equals(c.getName())));
        verify(groups, never()).save(argThat(g -> "Skipped Holding".equals(g.getName())));
    }

    @Test
    void conflictedCompanySkipsEveryRowIncludingLaterMatchingRowsButOtherCompanyImports() throws Exception {
        String[] first = row("First", "081234567891", "office@example.com", "");
        String[] second = row("Second", "081234567892", "office@example.com", "");
        String[] third = row("Third", "081234567893", "office@example.com", "");
        second[15] = "Manufacturing";
        String[] clean = row("Clean", "081234567894", "office@example.com", "");
        clean[3] = "Other Company PT";
        var upload = file(first, second, third, clean);
        assertEquals(3, preview(upload).getConflictCount());
        var body = (java.util.Map<?, ?>) controller.importDatabases(upload, "test").getBody();
        assertEquals(1, body.get("newCount"));
        assertEquals(3, body.get("skippedCount"));
        verify(databases).save(argThat(d -> "Clean".equals(d.getFirstName())));
        verify(companies, never()).save(company);
    }

    @Test
    void personalDuplicatesAreBothSkippedWithoutBlockingAnIndependentCleanRow() throws Exception {
        var upload = file(row("First", "081234567890", "office@example.com", "same@gmail.com"),
                row("Second", "081234567890", "office@example.com", "same@gmail.com"),
                row("Clean", "081234567891", "office@example.com", ""));
        var body = (java.util.Map<?, ?>) controller.importDatabases(upload, "test").getBody();
        assertEquals(1, body.get("count"));
        assertEquals(2, body.get("skippedCount"));
        verify(databases).save(argThat(d -> "Clean".equals(d.getFirstName())));
        verify(emails, never()).save(argThat(e -> "same@gmail.com".equals(e.getEmail())));
    }

    @Test
    void blankUpdateNeedsRealRetainedDataAndCleanUpdatesAreCountedSeparately() throws Exception {
        Database existing = contact(1L, "Andi", "Person", "081234567890");
        when(databases.findAll()).thenReturn(List.of(existing));
        when(databases.findById(1L)).thenReturn(Optional.of(existing));
        String[] update = row("Andi", "", "office@example.com", "");
        update[9] = "";
        assertEquals(1, preview(file(update)).getIncompleteCount());
        existing.setJobTitle("Existing Job");
        assertEquals(1, preview(file(update)).getDuplicateCount());
        update[20] = "";
        assertEquals(1, preview(file(update)).getIncompleteCount());
        company.setCity("Jakarta");
        String[] dirty = row("Dirty", "081234567892", "office@example.com", "bad-email");
        var body = (java.util.Map<?, ?>) controller.importDatabases(file(update,
                row("New", "081234567891", "office@example.com", ""), dirty), "test").getBody();
        assertEquals(2, body.get("count"));
        assertEquals(1, body.get("newCount"));
        assertEquals(1, body.get("updatedCount"));
        assertEquals(1, body.get("skippedCount"));
        assertEquals("Existing Job", existing.getJobTitle());
        assertEquals("081234567890", existing.getMobilePhone());
    }

    @Test
    void revalidatesAtImportAndAllDirtyFilesHaveNoWrites() throws Exception {
        var upload = file(row("Clean", "081234567890", "office@example.com", ""));
        assertEquals(1, preview(upload).getNewCount());
        when(databases.findAll()).thenReturn(List.of(contact(1L, "Other", "Person", "081234567890")));
        var response = controller.importDatabases(upload, "test");
        assertEquals(400, response.getStatusCode().value());
        var body = (java.util.Map<?, ?>) response.getBody();
        assertEquals(0, body.get("count"));
        assertEquals(1, body.get("skippedCount"));
        verify(databases, never()).save(any());
        verify(companies, never()).save(any());
        verify(groups, never()).save(any());
        verify(emails, never()).save(any());
    }

    @Test
    void movingTheOnlyCompanyEmailToPersonalCannotMakeAnUpdateDirty() throws Exception {
        Database existing = contact(1L, "Andi", "Person", "081234567890");
        existing.getEmails().add(email(existing, "andi@gmail.com", true, true));
        when(databases.findAll()).thenReturn(List.of(existing));
        var upload = file(row("Andi", "081234567890", "", "andi@gmail.com"));
        var result = preview(upload);
        assertEquals(1, result.getIncompleteCount());
        assertTrue(result.getRows().get(0).getMessage().contains("Company Email"));
        assertEquals(400, controller.importDatabases(upload, "test").getStatusCode().value());
        verify(databases, never()).save(any());
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

    @Test
    void sameNameAndPhoneUpdateCompanyFromExcelWhilePreservingContactIdentity() throws Exception {
        Database yogita = contact(89L, "Yogita", "Yasmine", "+6281904164306");
        yogita.setIsActive(false);
        yogita.setCreatedByUserId(9L);
        yogita.setEntryMethod("manual");
        yogita.setLinkedinUrl("https://www.linkedin.com/in/yogita");
        var oldEmail = email(yogita, "old@example.com", true, true);
        yogita.getEmails().add(oldEmail);
        Company destination = Company.builder().id(20L).name("MNC Asia Holding Tbk")
                .address("Alamat master").build();
        when(companies.findAll()).thenReturn(List.of(company, destination));
        when(databases.findAll()).thenReturn(List.of(yogita));
        when(databases.findById(89L)).thenReturn(Optional.of(yogita));
        String[] data = row("Yogita", "081904164306", "yogitayasmine@mncgroup.com", "yogitayasmine@gmail.com");
        data[6] = "Yasmine";
        data[3] = destination.getName();
        data[9] = "New Job";
        var upload = file(data);
        var result = preview(upload);
        assertEquals(1, result.getDuplicateCount());
        assertEquals(0, result.getConflictCount());
        assertEquals(89L, result.getRows().get(0).getExistingDatabaseId());
        verify(databases, never()).save(any());
        assertSame(company, yogita.getCompany());
        var response = controller.importDatabases(upload, "test");
        assertEquals(200, response.getStatusCode().value());
        var body = (java.util.Map<?, ?>) response.getBody();
        assertEquals(1, body.get("updatedCount"));
        assertEquals(0, body.get("newCount"));
        verify(databases).save(same(yogita));
        assertEquals(89L, yogita.getId());
        assertSame(destination, yogita.getCompany());
        assertEquals("New Job", yogita.getJobTitle());
        assertEquals("Alamat master", destination.getAddress());
        assertEquals("Alamat lama", company.getAddress());
        assertEquals("https://www.linkedin.com/in/yogita", yogita.getLinkedinUrl());
        assertEquals(9L, yogita.getCreatedByUserId());
        assertEquals("manual", yogita.getEntryMethod());
        assertFalse(yogita.getIsActive());
        assertTrue(yogita.getEmails().contains(oldEmail));
        assertTrue(yogita.getEmails().stream().anyMatch(e -> "yogitayasmine@gmail.com".equals(e.getEmail())));
    }

    @Test
    void companyMoveUsesNormalizedPhoneAndRejectsMultipleMatchingContacts() throws Exception {
        Database target = contact(1L, "Andi", "Person", null);
        target.setNormalizedPhone("+6281234567890");
        when(databases.findAll()).thenReturn(List.of(target));
        String[] data = row("Andi", "081234567890", "office@example.com", "");
        data[3] = "New Employer PT";
        assertEquals(1L, preview(file(data)).getRows().get(0).getExistingDatabaseId());
        Database duplicate = contact(2L, "Andi", "Person", "+62 812-3456-7890");
        when(databases.findAll()).thenReturn(List.of(target, duplicate));
        var result = preview(file(data));
        assertEquals(1, result.getConflictCount());
        assertTrue(result.getRows().get(0).getMessage().contains("Target update ambigu"));
        assertEquals(400, controller.importDatabases(file(data), "test").getStatusCode().value());
        verify(databases, never()).save(any());
    }

    @Test
    void companyMoveCannotMatchDifferentNamesOrMissingPhoneIdentity() throws Exception {
        Database target = contact(1L, "Andi", "Person", "081234567890");
        when(databases.findAll()).thenReturn(List.of(target));
        String[] data = row("Budi", "081234567890", "office@example.com", "");
        data[3] = "New Employer PT";
        assertEquals(1, preview(file(data)).getConflictCount());
        assertEquals(400, controller.importDatabases(file(data), "test").getStatusCode().value());
        target.setMobilePhone(null);
        data[5] = "Andi";
        data[12] = "";
        assertNull(preview(file(data)).getRows().get(0).getExistingDatabaseId());
        assertEquals(400, controller.importDatabases(file(data), "test").getStatusCode().value());
        verify(databases, never()).save(any());
    }

    @Test
    void oversizedCompanyNameIsSkippedBeforeAnyWritesWhileCleanRowsImport() throws Exception {
        String[] dirty = row("Kristiyanto", "081234567890", "office@example.com", "");
        dirty[3] = "x".repeat(330);
        var upload = file(dirty, row("Clean", "081234567891", "office@example.com", ""));
        var result = preview(upload);
        assertEquals(1, result.getConflictCount());
        assertEquals(2, result.getRows().get(0).getRowNum());
        assertTrue(result.getRows().get(0).getMessage().contains("Company Name berisi 330 karakter; maksimal 255"));
        var response = controller.importDatabases(upload, "test");
        assertEquals(200, response.getStatusCode().value());
        var body = (java.util.Map<?, ?>) response.getBody();
        assertEquals(1, body.get("count"));
        assertEquals(1, body.get("skippedCount"));
        verify(databases).save(argThat(d -> "Clean".equals(d.getFirstName())));
        verify(companies, never()).save(argThat(c -> dirty[3].equals(c.getName())));
    }

    @Test
    void companyLengthLimitsAcceptBoundaryAndLongTextButRejectOverflow() throws Exception {
        for (int[] field : new int[][]{{1,255},{2,255},{3,255},{11,50},{15,100},{16,100},{17,100},{20,100},{21,20}}) {
            String[] data = row("Andi", "081234567890", "office@example.com", "");
            data[field[0]] = "1".repeat(field[1]);
            if (field[0] == 11) data[11] = "62" + "1".repeat(48);
            assertEquals(0, preview(file(data)).getConflictCount(), "Boundary column " + field[0]);
            data[field[0]] += "1";
            assertEquals(1, preview(file(data)).getConflictCount(), "Overflow column " + field[0]);
        }
        String[] data = row("Andi", "081234567890", "office@example.com", "");
        data[2] = "\uD83D\uDE00".repeat(255);
        data[10] = "Long address ".repeat(100);
        data[18] = "Long hardware description ".repeat(100);
        assertEquals(0, preview(file(data)).getConflictCount());
    }

    @Test
    void longWebsiteSurvivesPreviewAndImportWithoutTruncation() throws Exception {
        String[] data = row("Kristiyanto", "081234567890", "office@example.com", "");
        data[3] = "Ivonesia Solusi Data PT";
        data[22] = "https://ivosights.com/ripple10?utm_campaign=" + "x".repeat(286);
        assertEquals(330, data[22].length());
        var upload = file(data);
        assertEquals(0, preview(upload).getConflictCount());
        assertEquals(1, preview(upload).getNewCount());
        var response = controller.importDatabases(upload, "test");
        assertEquals(200, response.getStatusCode().value());
        verify(companies).save(argThat(c -> data[3].equals(c.getName()) && data[22].equals(c.getWebsite())));
        verify(databases).save(argThat(c -> data[22].equals(c.getCompany().getWebsite())));
        assertEquals("TEXT", Company.class.getDeclaredField("website")
                .getAnnotation(jakarta.persistence.Column.class).columnDefinition());
    }

    @Test
    void postalConflictNamesTheDifferentRowAndExplainsWhyMatchingRowsAreHeld() throws Exception {
        List<String[]> data = new ArrayList<>();
        for (int i = 0; i < 47; i++) {
            String[] same = row("Person" + i, "08123456" + String.format("%04d", i), "office@example.com", "");
            same[21] = "12920";
            data.add(same);
        }
        String[] nurul = row("Nurul", "081234569999", "office@example.com", "");
        nurul[6] = "Pratiwi";
        nurul[21] = "12940";
        data.add(nurul);
        for (boolean outlierFirst : List.of(false, true)) {
            if (outlierFirst) { data.remove(nurul); data.add(0, nurul); }
            var upload = file(data.toArray(String[][]::new));
            var result = preview(upload);
            assertEquals(48, result.getConflictCount());
            var different = result.getRows().stream().filter(r -> r.getFirstName().equals("Nurul")).findFirst().orElseThrow();
            var same = result.getRows().stream().filter(r -> r.getFirstName().equals("Person0")).findFirst().orElseThrow();
            assertTrue(different.getMessage().startsWith("Periksa nilai berbeda: Postal Code baris ini '12940'"));
            assertTrue(same.getMessage().startsWith("Ikut tertahan karena perbedaan data perusahaan: Postal Code baris ini '12920'"));
            assertTrue(same.getMessage().contains("'12920' dipakai 47 baris"));
            assertTrue(same.getMessage().contains("#" + different.getRowNum() + " Nurul Pratiwi"));
            assertEquals(400, controller.importDatabases(upload, "test").getStatusCode().value());
        }
        verify(databases, never()).save(any());
        verify(companies, never()).save(any());
    }

    @Test
    void equalSizedPostalGroupsDoNotLabelEitherValueAsTheOutlier() throws Exception {
        String[] first = row("First", "081234567890", "office@example.com", "");
        String[] second = row("Second", "081234567891", "office@example.com", "");
        String[] blank = row("Blank", "081234567892", "office@example.com", "");
        first[21] = "12920";
        second[21] = "12940";
        var result = preview(file(first, second, blank));
        assertEquals(3, result.getConflictCount());
        assertTrue(result.getRows().stream().noneMatch(r -> r.getMessage().contains("Periksa nilai berbeda")));
        assertTrue(result.getRows().get(2).getMessage().contains("Postal Code baris ini kosong"));
        assertTrue(result.getRows().get(0).getMessage().contains("#3 Second Person"));
    }

    @Test
    void everySharedCompanyColumnIdentifiesTheDifferentValueAndContact() throws Exception {
        Object[][] cases = {
                {1, "Nama Group/Holding Company", "holding satu", "holding dua"},
                {2, "Nama Brand", "brand satu", "brand dua"},
                {10, "Address", "alamat satu", "alamat dua"},
                {11, "Office Phone", "6221123456", "6221654321"},
                {15, "Industry", "technology", "manufacturing"},
                {16, "Company Size (Revenue)", "100 miliar", "200 miliar"},
                {17, "Company Size (Employee)", "100", "200"},
                {18, "Company Hardware", "server", "laptop"},
                {20, "City", "jakarta", "bandung"},
                {21, "Postal Code", "12920", "12940"},
                {22, "Company Website", "https://example.com", "https://other.example.com"}
        };
        for (Object[] example : cases) {
            int column = (Integer) example[0];
            String header = (String) example[1], common = (String) example[2], different = (String) example[3];
            String[] first = row("Ahmad", "081234567890", "office@example.com", "");
            String[] second = row("Ronald", "081234567891", "office@example.com", "");
            String[] outlier = row("Nurul", "081234567892", "office@example.com", "");
            outlier[6] = "Pratiwi";
            first[column] = second[column] = common;
            outlier[column] = different;
            var upload = file(first, second, outlier);
            var result = preview(upload);
            assertEquals(3, result.getConflictCount(), header);
            assertTrue(result.getRows().get(2).getMessage().startsWith(
                    "Periksa nilai berbeda: " + header + " baris ini '" + different + "'"), header);
            for (var same : result.getRows().subList(0, 2)) {
                assertTrue(same.getMessage().startsWith(
                        "Ikut tertahan karena perbedaan data perusahaan: " + header), header);
                assertTrue(same.getMessage().contains("'" + common + "' dipakai 2 baris"), header);
                assertTrue(same.getMessage().contains("'" + different + "' dipakai 1 baris: #4 Nurul Pratiwi"), header);
            }
            assertEquals(400, controller.importDatabases(upload, "test").getStatusCode().value(), header);
        }
        verify(databases, never()).save(any());
        verify(companies, never()).save(any());
        verify(groups, never()).save(any());
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
