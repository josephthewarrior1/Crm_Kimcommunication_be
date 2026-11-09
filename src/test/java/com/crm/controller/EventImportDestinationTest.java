package com.crm.controller;

import com.crm.domain.*;
import com.crm.domain.Database;
import com.crm.repository.*;
import com.crm.service.AuditLogService;
import com.crm.service.SecurityHelper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventImportDestinationTest {
    private final EventController controller = new EventController();
    private final EventRepository events = mock(EventRepository.class);
    private final DatabaseRepository databases = mock(DatabaseRepository.class);
    private final DatabaseEmailRepository emails = mock(DatabaseEmailRepository.class);
    private final EventParticipantRepository participants = mock(EventParticipantRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final GroupRepository groups = mock(GroupRepository.class);
    private final Map<Long, Event> eventRecords = new HashMap<>();
    private final List<Database> contacts = new ArrayList<>();
    private final List<DatabaseEmail> addresses = new ArrayList<>();
    private final List<EventParticipant> attendance = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach void setup() {
        var security = mock(SecurityHelper.class);
        var user = AppUser.builder().id(7L).build();
        when(security.getAuthenticatedUser("test")).thenReturn(user);
        when(security.hasAnyRole(user, Role.ADMIN, Role.MANAGER)).thenReturn(true);
        when(security.hasRole(user, Role.ADMIN)).thenReturn(true);
        when(events.findById(anyLong())).thenAnswer(call -> Optional.of(eventRecords.computeIfAbsent(call.getArgument(0),
                id -> Event.builder().id(id).name("Example event " + id).syncDestination(DatabaseDestination.PARTICIPANT).build())));
        when(events.findForSyncById(anyLong())).thenAnswer(call -> events.findById(call.getArgument(0)));
        when(databases.findAll()).thenAnswer(call -> new ArrayList<>(contacts));
        when(databases.findById(anyLong())).thenAnswer(call -> contacts.stream().filter(contact -> contact.getId().equals(call.getArgument(0))).findFirst());
        when(companies.save(any())).thenAnswer(call -> {
            Company company = call.getArgument(0);
            if (company.getId() == null) company.setId(ids.incrementAndGet());
            return company;
        });
        when(databases.save(any())).thenAnswer(call -> {
            Database contact = call.getArgument(0);
            if (contact.getId() == null) contact.setId(ids.incrementAndGet());
            if (contacts.stream().noneMatch(saved -> saved.getId().equals(contact.getId()))) contacts.add(contact);
            return contact;
        });
        when(emails.findAllByEmailIgnoreCase(anyString())).thenAnswer(call -> addresses.stream()
                .filter(email -> email.getEmail().equalsIgnoreCase(call.getArgument(0))).toList());
        when(emails.save(any())).thenAnswer(call -> {
            DatabaseEmail email = call.getArgument(0);
            if (email.getId() == null) { email.setId(ids.incrementAndGet()); addresses.add(email); }
            return email;
        });
        when(participants.findByEventId(anyLong())).thenAnswer(call -> attendance.stream()
                .filter(row -> row.getEvent().getId().equals(call.getArgument(0))).toList());
        when(participants.findByEventIdAndDatabaseId(anyLong(), anyLong())).thenAnswer(call -> attendance.stream()
                .filter(row -> row.getEvent().getId().equals(call.getArgument(0)) && row.getDatabase().getId().equals(call.getArgument(1))).findFirst());
        when(participants.save(any())).thenAnswer(call -> {
            EventParticipant row = call.getArgument(0);
            if (row.getId() == null) { row.setId(ids.incrementAndGet()); attendance.add(row); }
            return row;
        });
        ReflectionTestUtils.setField(controller, "securityHelper", security);
        ReflectionTestUtils.setField(controller, "eventRepository", events);
        ReflectionTestUtils.setField(controller, "databaseRepository", databases);
        ReflectionTestUtils.setField(controller, "databaseEmailRepository", emails);
        ReflectionTestUtils.setField(controller, "eventParticipantRepository", participants);
        ReflectionTestUtils.setField(controller, "companyRepository", companies);
        ReflectionTestUtils.setField(controller, "groupRepository", groups);
        ReflectionTestUtils.setField(controller, "auditLogService", mock(AuditLogService.class));
    }

    @Test void eventTypeIsRequiredAndPreviewDerivesItsDestinationWithoutCreatingAnything() throws Exception {
        var file = participantFile(new String[]{"Alex", "Example", "alex@campus.ac.id", "", "Example Campus", "mahasiswa"});
        typeEvent(1L, null);
        assertEquals(409, controller.validateEventParticipantsImport(1L, file, "request", null, "test").getStatusCode().value());
        assertEquals(409, controller.importEventParticipants(1L, file, "request", null, "test").getStatusCode().value());
        typeEvent(1L, DatabaseDestination.PARTICIPANT);
        var response = controller.validateEventParticipantsImport(1L, file, "request", null, "test");
        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("participant", body.get("destination"));
        assertEquals(0, ((Map<?, ?>) body.get("summary")).get("errorCount"));
        verify(databases, never()).save(any()); verify(emails, never()).save(any());
        verify(participants, never()).save(any()); verify(companies, never()).save(any()); verify(groups, never()).save(any());
    }

    @Test void participantCampusOrWorkEmailCreatesNoCompanyProspectsAndKeepsEventHistory() throws Exception {
        var file = participantFile(new String[]{"Alex", "Campus", "alex@campus.ac.id", "", "Example Campus", "mahasiswa"},
                new String[]{"Bima", "Work", "bima@employer.example", "", "Example Employer", "umum"});
        var response = controller.importEventParticipants(1L, file, "request", "participant", "test");
        assertEquals(200, response.getStatusCode().value());
        assertEquals("participant", ((Map<?, ?>) response.getBody()).get("destination"));
        assertEquals(2, contacts.size()); assertEquals(2, attendance.size()); assertEquals(2, addresses.size());
        for (Database contact : contacts) {
            assertEquals(DatabaseDestination.PARTICIPANT, contact.getDatabaseDestination());
            assertNull(contact.getCompany()); assertNull(contact.getBranch());
            assertEquals(7L, contact.getCreatedByUserId()); assertEquals("excel_import", contact.getEntryMethod());
            assertEquals(DatabaseSource.excel_import, contact.getSource());
            assertNotNull(contact.getInstitutionName());
        }
        for (DatabaseEmail email : addresses) { assertEquals("personal", email.getEmailType()); assertFalse(email.getIsCorporate()); }
        assertEquals(ParticipantStatus.white, attendance.get(0).getParticipantStatus());
        assertEquals("[Origin: Request]", attendance.get(0).getNotes());
        verify(companies, never()).save(any()); verify(groups, never()).save(any());
    }

    @Test void participantImportsCreateSeparateProfileFromCompanyAndReuseItAcrossEvents() throws Exception {
        Database old = oldContact("Alex", "Example", "alex@campus.ac.id", DatabaseDestination.COMPANY);
        Company company = Company.builder().id(20L).name("Existing employer").build();
        CompanyBranch branch = CompanyBranch.builder().id(21L).companyId(20L).name("Existing office").build();
        old.setCompany(company); old.setBranch(branch); old.setCreatedByUserId(99L); old.setEntryMethod("manual");
        old.setMobilePhone("081234567890, 081300001111");
        DatabaseEmail original = old.getEmails().get(0); original.setIsCorporate(true); original.setEmailType("company");
        var file = participantFile(new String[]{"Alex", "Example", "alex@campus.ac.id", "081234567890, 081300001111", "Example Campus", "mahasiswa"});
        for (long eventId : List.of(1L, 2L, 1L)) {
            assertEquals(200, controller.importEventParticipants(eventId, file, "request", "participant", "test").getStatusCode().value());
        }
        assertEquals(2, contacts.size()); assertEquals(2, attendance.size());
        Database participant = contacts.get(1);
        assertNotEquals(old.getId(), participant.getId());
        assertSame(participant, attendance.get(0).getDatabase()); assertSame(participant, attendance.get(1).getDatabase());
        assertEquals(DatabaseDestination.COMPANY, old.getDatabaseDestination());
        assertEquals(DatabaseDestination.PARTICIPANT, participant.getDatabaseDestination());
        assertNull(participant.getCompany()); assertNull(participant.getBranch());
        assertEquals("081234567890, 081300001111", participant.getMobilePhone());
        assertSame(company, old.getCompany()); assertSame(branch, old.getBranch());
        assertEquals(99L, old.getCreatedByUserId()); assertEquals("manual", old.getEntryMethod());
        assertEquals(2, addresses.size()); assertSame(original, addresses.get(0));
        assertEquals("company", original.getEmailType()); assertTrue(original.getIsCorporate());
        assertEquals("personal", participant.getEmails().get(0).getEmailType());
        verify(companies, never()).save(any()); verify(groups, never()).save(any());
    }

    @Test void companyEventKeepsIncompleteRequestsAndCreatesSeparateCompanyProfile() throws Exception {
        typeEvent(1L, DatabaseDestination.COMPANY);
        var gmail = companyFile("alex@gmail.com");
        assertEquals(200, controller.importEventParticipants(1L, gmail, "request", "company", "test").getStatusCode().value());
        assertEquals(DatabaseDestination.COMPANY, contacts.get(0).getDatabaseDestination());
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(new DatabaseController(), "isIncomplete", contacts.get(0)));
        Database old = oldContact("Alex", "Example", "alex@campus.ac.id", DatabaseDestination.PARTICIPANT);
        var campus = simpleFile(new String[]{"Alex", "Example", "alex@campus.ac.id", "", "Example Employer"});
        assertEquals(200, controller.importEventParticipants(1L, campus, "request", "company", "test").getStatusCode().value());
        assertEquals(DatabaseDestination.PARTICIPANT, old.getDatabaseDestination());
        assertNull(old.getCompany()); assertNull(old.getBranch());
        assertEquals(3, contacts.size()); assertEquals(2, attendance.size());
        assertEquals(DatabaseDestination.COMPANY, attendance.get(1).getDatabase().getDatabaseDestination());
        assertNotEquals(old.getId(), attendance.get(1).getDatabase().getId());
    }

    @Test void oppositeDestinationNamesAndAmbiguousContactOwnersDoNotBlockSelectedDestination() throws Exception {
        for (DatabaseDestination destination : List.of(DatabaseDestination.COMPANY, DatabaseDestination.PARTICIPANT)) {
            contacts.clear(); addresses.clear(); attendance.clear();
            DatabaseDestination opposite = destination == DatabaseDestination.COMPANY ? DatabaseDestination.PARTICIPANT : DatabaseDestination.COMPANY;
            for (String name : List.of("Other", "Another")) {
                Database old = oldContact(name, "Person", "shared@gmail.com", opposite);
                old.setMobilePhone("081234567890, 081300001111");
            }
            typeEvent(1L, destination);
            var upload = simpleFile(new String[]{"", "", "shared@gmail.com", "081234567890, 081300001111", ""});
            assertEquals("valid", previewRow(upload, destination.value()).get("status"));
            assertEquals(200, controller.importEventParticipants(1L, upload, "request", destination.value(), "test").getStatusCode().value());
            assertEquals(3, contacts.size()); assertEquals(1, attendance.size());
            assertEquals(destination, attendance.get(0).getDatabase().getDatabaseDestination());
            assertEquals(3, addresses.size());
            assertEquals(200, controller.importEventParticipants(1L, upload, "request", destination.value(), "test").getStatusCode().value());
            assertEquals(3, contacts.size()); assertEquals(1, attendance.size());
        }
    }

    @Test void nameConflictAndInactiveParticipantAreRejectedBeforeWrites() throws Exception {
        Database old = oldContact("Other", "Person", "alex@campus.ac.id", DatabaseDestination.PARTICIPANT);
        var file = participantFile(new String[]{"Alex", "Example", "alex@campus.ac.id", "", "Example Campus", "mahasiswa"});
        assertEquals(400, controller.importEventParticipants(1L, file, "request", "participant", "test").getStatusCode().value());
        old.setFirstName("Alex"); old.setLastName("Example"); old.setIsActive(false);
        assertEquals(400, controller.importEventParticipants(1L, file, "request", "participant", "test").getStatusCode().value());
        verify(databases, never()).save(any()); verify(participants, never()).save(any());
    }

    @Test void phoneOnlyEventRequestsImportDirtyAndReuseNormalizedIdentityAcrossFilesAndEvents() throws Exception {
        for (String destination : List.of("company", "participant")) {
            long firstEventId = destination.equals("company") ? 1L : 3L;
            typeEvent(firstEventId, DatabaseDestination.fromValue(destination));
            typeEvent(firstEventId + 1, DatabaseDestination.fromValue(destination));
            String first = destination.equals("company") ? "Alex" : "Bima";
            var batch = simpleFile(new String[]{first, "", "", "081234567890", ""},
                    new String[]{first, "", "", "+62 812-3456-7890", ""});
            Map<?, ?> preview = previewRow(firstEventId, batch, destination);
            assertEquals("valid", preview.get("status"));
            assertEquals(List.of(), preview.get("issues"));
            assertEquals(destination.equals("company"), ((List<?>) preview.get("missingFields")).contains("Company Email"));
            assertEquals(List.of(), preview.get("warnings"));
            assertEquals(200, controller.importEventParticipants(firstEventId, batch, "request", destination, "test").getStatusCode().value());
            Database contact = contacts.stream().filter(row -> first.equals(row.getFirstName())).findFirst().orElseThrow();
            assertEquals("6281234567890", contact.getNormalizedPhone());
            assertNull(contact.getCompany()); assertNull(contact.getPositionLevel());
            assertTrue(contact.getLastName() == null || contact.getLastName().isEmpty());
            assertTrue(contact.getEmails() == null || contact.getEmails().isEmpty());
            assertEquals(destination.equals("company"), (Boolean) ReflectionTestUtils.invokeMethod(new DatabaseController(), "isIncomplete", contact));
            var later = simpleFile(new String[]{first, "", "", "81234567890", ""});
            for (long eventId : List.of(firstEventId + 1, firstEventId)) assertEquals(200,
                    controller.importEventParticipants(eventId, later, "request", destination, "test").getStatusCode().value());
            assertEquals(2, attendance.stream().filter(row -> row.getDatabase() == contact).count());
            assertEquals(destination.equals("company") ? DatabaseDestination.COMPANY : DatabaseDestination.PARTICIPANT, contact.getDatabaseDestination());
        }
        assertEquals(2, contacts.size()); assertEquals(4, attendance.size()); assertTrue(addresses.isEmpty());
        verify(companies, never()).save(any()); verify(groups, never()).save(any());
    }

    @Test void emailOnlyRequestsAllowCampusEmailAndWarnAboutMissingPhoneWithoutBlocking() throws Exception {
        for (String destination : List.of("company", "participant")) {
            long firstEventId = destination.equals("company") ? 1L : 3L;
            typeEvent(firstEventId, DatabaseDestination.fromValue(destination));
            typeEvent(firstEventId + 1, DatabaseDestination.fromValue(destination));
            String first = destination.equals("company") ? "Alex" : "Bima";
            var file = simpleFile(new String[]{first, "", first.toLowerCase() + "@campus.ac.id", "", ""});
            Map<?, ?> preview = previewRow(firstEventId, file, destination);
            assertEquals("valid", preview.get("status")); assertEquals(List.of(), preview.get("issues"));
            assertEquals(List.of("Nomor HP/WhatsApp belum diisi; peserta belum bisa ditelepon."), preview.get("warnings"));
            assertEquals(200, controller.importEventParticipants(firstEventId, file, "request", destination, "test").getStatusCode().value());
            Database contact = contacts.stream().filter(row -> first.equals(row.getFirstName())).findFirst().orElseThrow();
            assertNull(contact.getMobilePhone()); assertNull(contact.getCompany());
            assertEquals(destination.equals("company"), (Boolean) ReflectionTestUtils.invokeMethod(new DatabaseController(), "isIncomplete", contact));
            assertEquals(200, controller.importEventParticipants(firstEventId + 1, file, "request", destination, "test").getStatusCode().value());
            assertEquals(2, attendance.stream().filter(row -> row.getDatabase() == contact).count());
        }
        assertEquals(2, contacts.size()); assertEquals(2, addresses.size()); assertEquals(4, attendance.size());
        verify(companies, never()).save(any()); verify(groups, never()).save(any());
    }

    @Test void companyDetailsAreSoftCompletenessOnlyForEventImport() throws Exception {
        typeEvent(1L, DatabaseDestination.COMPANY);
        var file = simpleFile(new String[]{"Alex", "", "alex@employer.example", "", "Example Employer"});
        Map<?, ?> preview = previewRow(file, "company");
        assertEquals("valid", preview.get("status")); assertEquals(List.of(), preview.get("issues"));
        assertTrue(((List<?>) preview.get("missingFields")).containsAll(List.of("Nama Group Holding", "Last Name", "Address", "Industry")));
        assertEquals(200, controller.importEventParticipants(1L, file, "request", "company", "test").getStatusCode().value());
        Database contact = contacts.get(0);
        assertEquals("Example Employer", contact.getCompany().getName());
        assertNull(contact.getCompany().getGroup()); assertNull(contact.getCompany().getAddress()); assertNull(contact.getPositionLevel());
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(new DatabaseController(), "isIncomplete", contact));
        assertEquals(DatabaseDestination.COMPANY, contact.getDatabaseDestination());
        assertEquals("[Origin: Request]", attendance.get(0).getNotes());
    }

    @Test void bothMissingInvalidFilledContactAndAmbiguousIdentityStayBlockedBeforeWrites() throws Exception {
        for (String destination : List.of("company", "participant")) {
            typeEvent(1L, DatabaseDestination.fromValue(destination));
            for (String phone : List.of("", "123", "+", "call 081234567890", "000000")) {
                String email = phone.isEmpty() ? "" : "alex@campus.ac.id";
                var file = simpleFile(new String[]{"Alex", "", email, phone, ""});
                assertEquals("error", previewRow(file, destination).get("status"));
                assertEquals(400, controller.importEventParticipants(1L, file, "request", destination, "test").getStatusCode().value());
            }
            var invalidEmail = simpleFile(new String[]{"Alex", "", "broken@", "081234567890", ""});
            assertEquals(400, controller.importEventParticipants(1L, invalidEmail, "request", destination, "test").getStatusCode().value());
        }
        Database first = Database.builder().id(1L).firstName("Alex").mobilePhone("081234567890").isActive(true).build();
        Database other = Database.builder().id(2L).firstName("Alex").normalizedPhone("+6281234567890").isActive(true).build();
        contacts.add(first); contacts.add(other);
        var phoneOnly = simpleFile(new String[]{"Alex", "", "", "81234567890", ""});
        for (String destination : List.of("company", "participant")) {
            first.setDatabaseDestination(DatabaseDestination.fromValue(destination));
            other.setDatabaseDestination(DatabaseDestination.fromValue(destination));
            typeEvent(1L, DatabaseDestination.fromValue(destination));
            assertTrue(previewRow(phoneOnly, destination).get("issues").toString().contains("beberapa profil"));
            assertEquals(400, controller.importEventParticipants(1L, phoneOnly, "request", destination, "test").getStatusCode().value());
        }
        verify(databases, never()).save(any()); verify(emails, never()).save(any()); verify(participants, never()).save(any());
        verify(companies, never()).save(any()); verify(groups, never()).save(any());
    }

    @Test void companyImportCannotMoveAnExistingContactOrClearItsBranchThroughPhoneOrEmailMatch() throws Exception {
        typeEvent(1L, DatabaseDestination.COMPANY);
        Database contact = oldContact("Alex", "", "alex@employer.example", DatabaseDestination.COMPANY);
        contact.setMobilePhone("081234567890");
        Company company = Company.builder().id(20L).name("Example Employer A").build();
        CompanyBranch branch = CompanyBranch.builder().id(21L).companyId(20L).name("Existing office").build();
        contact.setCompany(company); contact.setBranch(branch);
        for (String[] incoming : List.of(new String[]{"Alex", "", "", "+6281234567890", "Example Employer B"},
                new String[]{"Alex", "", "alex@employer.example", "", "Example Employer B"})) {
            var conflicting = simpleFile(incoming);
            assertEquals("error", previewRow(conflicting, "company").get("status"));
            assertTrue(previewRow(conflicting, "company").get("issues").toString().contains("Edit kontak"));
            assertEquals(400, controller.importEventParticipants(1L, conflicting, "request", "company", "test").getStatusCode().value());
            assertSame(company, contact.getCompany()); assertSame(branch, contact.getBranch());
        }
        verify(databases, never()).save(any()); verify(emails, never()).save(any()); verify(participants, never()).save(any());
        verify(companies, never()).save(any()); verify(groups, never()).save(any());
        var noCompanyChange = simpleFile(new String[]{"Alex", "", "", "81234567890", ""});
        assertEquals("valid", previewRow(noCompanyChange, "company").get("status"));
        assertEquals(200, controller.importEventParticipants(1L, noCompanyChange, "request", "company", "test").getStatusCode().value());
        assertEquals(1, contacts.size()); assertEquals(1, attendance.size());
        assertSame(contact, attendance.get(0).getDatabase());
        assertSame(company, contact.getCompany()); assertSame(branch, contact.getBranch());
    }

    @Test void eventTypeCannotBeOverriddenByTheImportDestinationParameter() throws Exception {
        typeEvent(1L, DatabaseDestination.COMPANY);
        var file = simpleFile(new String[]{"Alex", "", "alex@campus.ac.id", "", ""});
        assertEquals(400, controller.validateEventParticipantsImport(1L, file, "request", "participant", "test").getStatusCode().value());
        assertEquals(400, controller.importEventParticipants(1L, file, "request", "participant", "test").getStatusCode().value());
        verify(databases, never()).save(any()); verify(participants, never()).save(any());
        var preview = controller.validateEventParticipantsImport(1L, file, "request", null, "test");
        assertEquals("company", ((Map<?, ?>) preview.getBody()).get("destination"));
        assertEquals(200, controller.importEventParticipants(1L, file, "request", null, "test").getStatusCode().value());
        assertEquals(DatabaseDestination.COMPANY, contacts.get(0).getDatabaseDestination());
        typeEvent(2L, null);
        assertEquals(409, controller.validateEventParticipantsImport(2L, file, "request", "company", "test").getStatusCode().value());
        assertEquals(409, controller.importEventParticipants(2L, file, "request", "company", "test").getStatusCode().value());
        assertEquals(1, contacts.size()); assertEquals(1, attendance.size());
    }

    private void typeEvent(long id, DatabaseDestination destination) {
        eventRecords.computeIfAbsent(id, key -> Event.builder().id(key).name("Example event " + key).build()).setSyncDestination(destination);
    }

    private Map<?, ?> previewRow(MockMultipartFile file, String destination) {
        return previewRow(1L, file, destination);
    }

    private Map<?, ?> previewRow(long eventId, MockMultipartFile file, String destination) {
        var response = controller.validateEventParticipantsImport(eventId, file, "request", destination, "test");
        assertEquals(200, response.getStatusCode().value());
        return (Map<?, ?>) ((List<?>) ((Map<?, ?>) response.getBody()).get("rows")).get(0);
    }

    private static MockMultipartFile simpleFile(String[]... rows) throws Exception {
        return file(new String[]{"First Name", "Last Name", "Email", "Mobile Phone", "Company Name"}, rows);
    }

    private Database oldContact(String first, String last, String address, DatabaseDestination destination) {
        Database contact = Database.builder().id(ids.incrementAndGet()).firstName(first).lastName(last)
                .databaseDestination(destination).isActive(true).build();
        DatabaseEmail email = DatabaseEmail.builder().id(ids.incrementAndGet()).database(contact).email(address)
                .emailType("personal").isCorporate(false).build();
        contact.setEmails(new ArrayList<>(List.of(email))); contacts.add(contact); addresses.add(email);
        return contact;
    }

    private static MockMultipartFile participantFile(String[]... rows) throws Exception {
        return file(new String[]{"First Name", "Last Name", "Email", "Mobile Phone", "Sekolah/Kampus/Instansi", "Kategori Peserta"}, rows);
    }

    private static MockMultipartFile companyFile(String email) throws Exception {
        return file(new String[]{"Nama Group", "Nama Brand", "Company Name", "Salutation", "First Name", "Last Name", "Position", "Jobtitle", "Address", "Office Phone", "Mobile Phone", "Company Email Address", "Industry", "City", "Company Website"},
                new String[]{"Example Group", "Example", "Example Company", "Mr", "Alex", "Example", "Unknown", "Officer", "Example Address", "021123", "081234", email, "Technology", "Jakarta", "https://example.test"});
    }

    private static MockMultipartFile file(String[] headers, String[]... rows) throws Exception {
        try (var workbook = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Import");
            var header = sheet.createRow(0);
            for (int column = 0; column < headers.length; column++) header.createCell(column).setCellValue(headers[column]);
            for (int index = 0; index < rows.length; index++) {
                var row = sheet.createRow(index + 1);
                for (int column = 0; column < rows[index].length; column++) row.createCell(column).setCellValue(rows[index][column]);
            }
            workbook.write(bytes);
            return new MockMultipartFile("file", "anonymous-event.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.toByteArray());
        }
    }
}
