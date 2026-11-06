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

class EventImportIncompleteTest {
    private final EventController controller=new EventController();
    private final EventRepository events=mock(EventRepository.class);
    private final DatabaseRepository databases=mock(DatabaseRepository.class);
    private final DatabaseEmailRepository emails=mock(DatabaseEmailRepository.class);
    private final EventParticipantRepository participants=mock(EventParticipantRepository.class);
    private final CompanyRepository companies=mock(CompanyRepository.class);
    private final GroupRepository groups=mock(GroupRepository.class);
    private final List<Database> contacts=new ArrayList<>();
    private final List<DatabaseEmail> addresses=new ArrayList<>();
    private final List<EventParticipant> links=new ArrayList<>();
    private final AtomicLong ids=new AtomicLong(100);

    @BeforeEach void setup() {
        var security=mock(SecurityHelper.class); var user=AppUser.builder().id(7L).build();
        when(security.getAuthenticatedUser("test")).thenReturn(user);
        when(security.hasAnyRole(user,Role.ADMIN,Role.MANAGER)).thenReturn(true);
        when(security.hasRole(user,Role.ADMIN)).thenReturn(true);
        when(events.findById(anyLong())).thenAnswer(call->Optional.of(Event.builder().id(call.getArgument(0)).name("Anonymous event").build()));
        when(databases.findAll()).thenAnswer(call->new ArrayList<>(contacts));
        when(databases.findById(anyLong())).thenAnswer(call->contacts.stream().filter(row->row.getId().equals(call.getArgument(0))).findFirst());
        when(databases.save(any())).thenAnswer(call->{
            Database contact=call.getArgument(0); if(contact.getId()==null)contact.setId(ids.incrementAndGet());
            if(contacts.stream().noneMatch(row->row.getId().equals(contact.getId())))contacts.add(contact); return contact;
        });
        when(companies.save(any())).thenAnswer(call->{Company company=call.getArgument(0); if(company.getId()==null)company.setId(ids.incrementAndGet()); return company;});
        when(emails.findAllByEmailIgnoreCase(anyString())).thenAnswer(call->addresses.stream().filter(row->row.getEmail().equalsIgnoreCase(call.getArgument(0))).toList());
        when(emails.save(any())).thenAnswer(call->{DatabaseEmail email=call.getArgument(0); email.setId(ids.incrementAndGet()); addresses.add(email); return email;});
        when(participants.findByEventId(anyLong())).thenAnswer(call->links.stream().filter(row->row.getEvent().getId().equals(call.getArgument(0))).toList());
        when(participants.findByEventIdAndDatabaseId(anyLong(),anyLong())).thenAnswer(call->links.stream()
                .filter(row->row.getEvent().getId().equals(call.getArgument(0))&&row.getDatabase().getId().equals(call.getArgument(1))).findFirst());
        when(participants.save(any())).thenAnswer(call->{EventParticipant row=call.getArgument(0);row.setId(ids.incrementAndGet());links.add(row);return row;});
        ReflectionTestUtils.setField(controller,"securityHelper",security); ReflectionTestUtils.setField(controller,"eventRepository",events);
        ReflectionTestUtils.setField(controller,"databaseRepository",databases); ReflectionTestUtils.setField(controller,"databaseEmailRepository",emails);
        ReflectionTestUtils.setField(controller,"eventParticipantRepository",participants); ReflectionTestUtils.setField(controller,"companyRepository",companies);
        ReflectionTestUtils.setField(controller,"groupRepository",groups); ReflectionTestUtils.setField(controller,"auditLogService",mock(AuditLogService.class));
    }

    @Test void incompleteEmailOnlyAndPhoneOnlyRowsImportWithSoftMetadataAndRemainGloballyDirty() throws Exception {
        var file=file(new String[]{"Alya","","alya@gmail.com","",""},new String[]{"Bima","","","081234567890",""},new String[]{"","","","",""});
        var preview=controller.validateEventParticipantsImport(1L,file,"request","test");
        assertEquals(200,preview.getStatusCode().value());
        Map<?,?> body=(Map<?,?>)preview.getBody(); Map<?,?> summary=(Map<?,?>)body.get("summary");
        assertEquals(2,summary.get("validCount"));assertEquals(0,summary.get("errorCount"));assertEquals(1,summary.get("blankCount"));
        List<?> rows=(List<?>)body.get("rows");
        assertEquals(List.of(),((Map<?,?>)rows.get(0)).get("issues"));
        assertTrue(((List<?>)((Map<?,?>)rows.get(0)).get("missingFields")).containsAll(List.of("Company Name","Mobile Phone","Last Name")));
        assertEquals(1,((List<?>)((Map<?,?>)rows.get(0)).get("warnings")).size());
        assertEquals(List.of(),((Map<?,?>)rows.get(1)).get("warnings"));
        verify(databases,never()).save(any());verify(participants,never()).save(any());
        assertEquals(200,controller.importEventParticipants(1L,file,"request","test").getStatusCode().value());
        assertEquals(2,contacts.size());assertEquals(1,addresses.size());assertEquals(2,links.size());
        for(Database contact:contacts){
            assertNull(contact.getCompany());assertNull(contact.getPositionLevel());assertNull(contact.getSalutation());
            assertTrue(contact.getLastName()==null||contact.getLastName().isEmpty());
            assertTrue((Boolean)ReflectionTestUtils.invokeMethod(new DatabaseController(),"isIncomplete",contact));
            assertEquals(7L,contact.getCreatedByUserId());assertEquals("excel_import",contact.getEntryMethod());
        }
        assertEquals("alya@gmail.com",addresses.get(0).getEmail()); // Old CRM email policy is preserved.
        for(EventParticipant link:links){assertEquals("[Origin: Request]",link.getNotes());assertEquals("pending",link.getPreEventApprovalStatus());}
        verify(companies,never()).save(any());verify(groups,never()).save(any());
    }

    @Test void phoneOnlyDuplicatesWithinFileAndAcrossEventsReuseTheExactNamedProfile() throws Exception {
        var batch=file(new String[]{"Alya","Demo","","081234567890",""},new String[]{"Alya","Demo","","+62 812-3456-7890",""});
        assertEquals(200,controller.importEventParticipants(1L,batch,"request","test").getStatusCode().value());
        var later=file(new String[]{" alya ","DEMO","","81234567890",""});
        assertEquals(200,controller.importEventParticipants(2L,later,"pre_event","test").getStatusCode().value());
        assertEquals(200,controller.importEventParticipants(1L,later,"request","test").getStatusCode().value());
        assertEquals(1,contacts.size());assertEquals(2,links.size());assertTrue(addresses.isEmpty());
        assertEquals("6281234567890",contacts.get(0).getNormalizedPhone());assertNull(links.get(1).getNotes());
        assertEquals("pending",links.get(1).getPreEventApprovalStatus());
    }

    @Test void campusEmailIdentityReusesProfileAndPreservesCompanyBranchEmailOwnershipAndCredit() throws Exception {
        Database old=oldContact("Alya","Demo","alya@campus.ac.id","081234567890");
        Company company=Company.builder().id(20L).name("Existing Employer").build();
        CompanyBranch branch=CompanyBranch.builder().id(21L).companyId(20L).name("Existing office").build();
        old.setCompany(company);old.setBranch(branch);old.setCreatedByUserId(99L);old.setEntryMethod("manual");
        DatabaseEmail original=old.getEmails().get(0);
        var file=file(new String[]{"ALYA","Demo","ALYA@CAMPUS.AC.ID","",""});
        for(long eventId:List.of(1L,2L,1L))assertEquals(200,controller.importEventParticipants(eventId,file,"request","test").getStatusCode().value());
        assertEquals(1,contacts.size());assertEquals(2,links.size());assertEquals(1,addresses.size());assertSame(original,addresses.get(0));
        assertSame(company,old.getCompany());assertSame(branch,old.getBranch());assertEquals(99L,old.getCreatedByUserId());assertEquals("manual",old.getEntryMethod());
        verify(companies,never()).save(any());
    }

    @Test void bothMissingOrInvalidFilledIdentityFieldsRejectBeforeAnyWrites() throws Exception {
        for(String[] row:List.of(new String[]{"Alya","","","",""},
                new String[]{"Alya","","broken@","081234567890",""},new String[]{"Alya","","alya@example.org","123",""},
                new String[]{"Alya","","alya@example.org","+",""},new String[]{"Alya","","alya@example.org","call 081234567890",""})){
            var file=file(row); assertEquals("error",previewRow(file).get("status"));
            assertEquals(400,controller.importEventParticipants(1L,file,"request","test").getStatusCode().value());
        }
        verify(databases,never()).save(any());verify(emails,never()).save(any());verify(participants,never()).save(any());verify(companies,never()).save(any());
    }

    @Test void explicitDifferentCompanyAndAmbiguousIdentityAreRejectedWithoutLosingTheExistingBranch() throws Exception {
        Database old=oldContact("Alya","Demo","alya@example.org","081234567890");
        Company company=Company.builder().id(20L).name("Employer A").build();
        CompanyBranch branch=CompanyBranch.builder().id(21L).companyId(20L).name("Existing office").build();old.setCompany(company);old.setBranch(branch);
        for(String[] row:List.of(new String[]{"Alya","Demo","","81234567890","Employer B"},new String[]{"Alya","Demo","alya@example.org","","Employer B"})){
            var file=file(row);assertTrue(previewRow(file).get("issues").toString().contains("Edit kontak"));
            assertEquals(400,controller.importEventParticipants(1L,file,"request","test").getStatusCode().value());
        }
        oldContact("Alya","Demo","other@example.org","+6281234567890");
        var ambiguous=file(new String[]{"Alya","Demo","","081234567890",""});
        assertTrue(previewRow(ambiguous).get("issues").toString().contains("beberapa profil"));
        assertEquals(400,controller.importEventParticipants(1L,ambiguous,"request","test").getStatusCode().value());
        assertSame(company,old.getCompany());assertSame(branch,old.getBranch());verify(databases,never()).save(any());verify(companies,never()).save(any());
    }

    @Test void sharedOfficeEmailRemainsAllowedButPersonalOwnershipConflictStillBlocks() throws Exception {
        oldContact("Alya","Demo","office@example.org","");
        var office=file(new String[]{"Bima","Demo","office@example.org","",""});
        assertEquals(200,controller.importEventParticipants(1L,office,"request","test").getStatusCode().value());
        assertEquals(2,contacts.size());assertEquals(2,addresses.size());
        var personal=workbook(new String[]{"First Name","Last Name","Personal Email Address"},new String[]{"Other","Demo","office@example.org"});
        assertEquals(400,controller.importEventParticipants(1L,personal,"request","test").getStatusCode().value());
        assertEquals(2,contacts.size());assertEquals(1,links.size());
    }

    @Test void aNameOrCompanyAloneNeverMergesDifferentPeople() throws Exception {
        oldContact("Alex","Demo","alex@example.org","081200000001");
        var file=file(new String[]{"Alex","Demo","other@example.org","081200000002",""});
        assertEquals(200,controller.importEventParticipants(1L,file,"request","test").getStatusCode().value());
        assertEquals(2,contacts.size());assertEquals(2,addresses.size());assertEquals(1,links.size());
    }


    @Test void multiplePhoneNumbersRemainSeparateAndEveryNumberCanMatchTheSameProfile() throws Exception {
        long eventId=1;
        for(String separator:List.of(", ","; "," / "," | ","\n","\r\n")){
            var batch=file(new String[]{"Alya","Demo","","0812-8777-2967"+separator+"0811-909-939",""});
            var row=(Map<?,?>)((List<?>)((Map<?,?>)controller.validateEventParticipantsImport(eventId,batch,"request","test").getBody()).get("rows")).get(0);assertEquals("valid",row.get("status"));
            assertEquals("081287772967, 0811909939",row.get("mobilePhone"));
            assertEquals(200,controller.importEventParticipants(eventId++,batch,"request","test").getStatusCode().value());
        }
        var secondNumber=file(new String[]{"ALYA","Demo","","+62 811-909-939",""});
        assertEquals(contacts.get(0).getId(),previewRow(secondNumber).get("existingDatabaseId"));
        assertEquals(200,controller.importEventParticipants(eventId++,secondNumber,"request","test").getStatusCode().value());
        var extraNumber=file(new String[]{"Alya","Demo","","62811909939; 0821-1234-5678",""});
        assertEquals(200,controller.importEventParticipants(eventId++,extraNumber,"request","test").getStatusCode().value());
        var reordered=file(new String[]{"Alya","Demo","","6282112345678 | 6281287772967 | 62811909939",""});
        assertEquals(200,controller.importEventParticipants(eventId++,reordered,"request","test").getStatusCode().value());
        assertEquals(1,contacts.size());assertEquals(eventId-1,links.size());
        assertEquals("081287772967, 0811909939, 082112345678",contacts.get(0).getMobilePhone());
        assertEquals("6281287772967",contacts.get(0).getNormalizedPhone());
        assertTrue(addresses.isEmpty());
    }

    @Test void invalidMembersAndOverlongMobileOrOfficePhoneListsBlockTheWholeBatch() throws Exception {
        String oversized="111111111111111, 222222222222222, 333333333333333, 444444444444444";
        for(String phone:List.of("081287772967, call 0811909939","081287772967 / 123","081287772967, +","081287772967,",oversized)){
            var batch=file(new String[]{"Alya","Demo","alya@example.org",phone,""});
            assertEquals("error",previewRow(batch).get("status"));
            assertEquals(400,controller.importEventParticipants(1L,batch,"request","test").getStatusCode().value());
        }
        for(String office:List.of("0211234567; ext 12",oversized)){
            var batch=workbook(new String[]{"First Name","Company Email Address","Office Phone"},new String[]{"Alya","alya@example.org",office});
            assertEquals("error",previewRow(batch).get("status"));
            assertEquals(400,controller.importEventParticipants(1L,batch,"request","test").getStatusCode().value());
        }
        oldContact("Alya","Demo","alya@example.org","111111111111111,222222222222222,333333333333333");
        var mergedTooLong=file(new String[]{"Alya","Demo","alya@example.org","444444444444444",""});
        assertTrue(previewRow(mergedTooLong).get("issues").toString().contains("50 karakter"));
        assertEquals(400,controller.importEventParticipants(1L,mergedTooLong,"request","test").getStatusCode().value());
        verify(databases,never()).save(any());verify(participants,never()).save(any());verify(emails,never()).save(any());verify(companies,never()).save(any());
    }

    @Test void officePhoneListsRemainSeparateAndMalformedStoredMobileMustBeReviewedBeforeMerging() throws Exception {
        var office=workbook(new String[]{"First Name","Company Email Address","Company Name","Office Phone"},
                new String[]{"Bima","bima@example.org","Employer A","021-1234567 / 021-7654321"});
        assertEquals("valid",previewRow(office).get("status"));
        assertEquals(200,controller.importEventParticipants(1L,office,"request","test").getStatusCode().value());
        verify(companies).save(argThat(company->"0211234567, 0217654321".equals(company.getOfficePhone())));
        Database old=oldContact("Alya","Demo","alya@example.org","0812877729670811909939");
        var incoming=file(new String[]{"Alya","Demo","alya@example.org","081287772967",""});
        assertTrue(previewRow(incoming).get("issues").toString().contains("HP tersimpan tidak valid"));
        assertEquals(400,controller.importEventParticipants(2L,incoming,"request","test").getStatusCode().value());
        assertEquals("0812877729670811909939",old.getMobilePhone());
        var emailOnly=file(new String[]{"Alya","Demo","alya@example.org","",""});
        assertEquals("valid",previewRow(emailOnly).get("status"));
        assertEquals(200,controller.importEventParticipants(2L,emailOnly,"request","test").getStatusCode().value());
        assertEquals("0812877729670811909939",old.getMobilePhone());
    }

    @Test void sharedSecondaryPhoneStillRejectsAmbiguousProfiles() throws Exception {
        oldContact("Alya","Demo","alya@example.org","081287772967, 0811909939");
        oldContact("Alya","Demo","other@example.org","+62 811-909-939");
        var batch=file(new String[]{"Alya","Demo","","62811909939",""});
        assertTrue(previewRow(batch).get("issues").toString().contains("beberapa profil"));
        assertEquals(400,controller.importEventParticipants(1L,batch,"request","test").getStatusCode().value());
        verify(databases,never()).save(any());verify(participants,never()).save(any());
    }


    @Test void unnamedEmailOnlyAndPhoneOnlyRowsImportAndRepeatWithoutInventingNames() throws Exception {
        var batch=file(new String[]{"","","unnamed@example.invalid","",""},new String[]{"","","","081400003333",""});
        var preview=controller.validateEventParticipantsImport(1L,batch,"request","test");
        Map<?,?> summary=(Map<?,?>)((Map<?,?>)preview.getBody()).get("summary");
        assertEquals(2,summary.get("validCount"));assertEquals(0,summary.get("errorCount"));
        assertTrue(((List<?>)previewRow(batch).get("missingFields")).contains("First Name"));
        for(long eventId:List.of(1L,1L,2L)){
            assertEquals(200,controller.importEventParticipants(eventId,batch,"request","test").getStatusCode().value());
        }
        assertEquals(2,contacts.size());assertEquals(4,links.size());assertEquals(1,addresses.size());
        for(Database contact:contacts){
            assertTrue(contact.getFirstName()==null||contact.getFirstName().isBlank());
            assertTrue(contact.getLastName()==null||contact.getLastName().isBlank());
            assertTrue((Boolean)ReflectionTestUtils.invokeMethod(new DatabaseController(),"isIncomplete",contact));
        }
        assertEquals("duplicate",previewRow(batch).get("status"));
    }

    @Test void unnamedInputPreservesKnownNamesAndLaterNamedInputCanFillAUniqueUnnamedProfile() throws Exception {
        Database known=oldContact("Alya","Demo","alya@example.invalid","081200001111, 081300002222");
        var personal=workbook(new String[]{"First Name","Last Name","Personal Email Address"},new String[]{"","","alya@example.invalid"});
        assertEquals(known.getId(),previewRow(personal).get("existingDatabaseId"));
        assertEquals(200,controller.importEventParticipants(1L,personal,"request","test").getStatusCode().value());
        var phone=file(new String[]{"","","","+6281300002222",""});
        assertEquals(known.getId(),previewRow(phone).get("existingDatabaseId"));
        assertEquals(200,controller.importEventParticipants(2L,phone,"request","test").getStatusCode().value());
        assertEquals("Alya",known.getFirstName());assertEquals("Demo",known.getLastName());
        var unnamed=file(new String[]{"","","new-person@example.invalid","081400003333",""});
        assertEquals(200,controller.importEventParticipants(1L,unnamed,"request","test").getStatusCode().value());
        Database toEnrich=contacts.get(1);
        var named=file(new String[]{"Bima","Actual","new-person@example.invalid","+6281400003333",""});
        assertEquals(toEnrich.getId(),previewRow(named).get("existingDatabaseId"));
        assertEquals(200,controller.importEventParticipants(2L,named,"request","test").getStatusCode().value());
        assertEquals(2,contacts.size());assertEquals(4,links.size());
        assertEquals("Bima",toEnrich.getFirstName());assertEquals("Actual",toEnrich.getLastName());
        assertEquals("duplicate",previewRow(named).get("status"));
    }

    @Test void unnamedContactAmbiguityAndMissingBothContactMethodsAreHeldBeforeWrites() throws Exception {
        oldContact("Alya","Demo","shared@example.invalid","081200001111");
        oldContact("Bima","Demo","shared@example.invalid","081300002222");
        var shared=file(new String[]{"","","shared@example.invalid","",""});
        assertTrue(previewRow(shared).get("issues").toString().contains("beberapa profil"));
        assertEquals(400,controller.importEventParticipants(1L,shared,"request","test").getStatusCode().value());
        oldContact("","","shared@example.invalid","081200001111");
        for(String[] row:List.of(new String[]{"","","","+6281200001111",""},new String[]{"Real","Name","shared@example.invalid","",""})){
            var batch=file(row);assertTrue(previewRow(batch).get("issues").toString().contains("beberapa profil"));
            assertEquals(400,controller.importEventParticipants(1L,batch,"request","test").getStatusCode().value());
        }
        var noContact=file(new String[]{"","","","","Employer A"});
        assertTrue(previewRow(noContact).get("issues").toString().contains("Isi minimal satu kontak"));
        assertEquals(400,controller.importEventParticipants(1L,noContact,"request","test").getStatusCode().value());
        verify(databases,never()).save(any());verify(participants,never()).save(any());verify(emails,never()).save(any());verify(companies,never()).save(any());
    }


    @Test void unnamedRowsWithSeveralNamedOwnersInTheSameFileAreHeldRegardlessOfOrder() throws Exception {
        String[] alya={"Alya","Demo","shared@example.invalid","",""};
        String[] bima={"Bima","Demo","shared@example.invalid","",""};
        String[] unnamed={"","","shared@example.invalid","",""};
        for(boolean unnamedFirst:List.of(false,true)){
            var batch=unnamedFirst?file(unnamed,alya,bima):file(alya,bima,unnamed);
            Map<?,?> body=(Map<?,?>)controller.validateEventParticipantsImport(1L,batch,"request","test").getBody();
            assertEquals(1,((Map<?,?>)body.get("summary")).get("errorCount"));
            var row=(Map<?,?>)((List<?>)body.get("rows")).get(unnamedFirst?0:2);
            assertTrue(row.get("issues").toString().contains("beberapa profil di file ini"));
            assertEquals(400,controller.importEventParticipants(1L,batch,"request","test").getStatusCode().value());
        }
        for(var batch:List.of(file(alya,alya,unnamed),file(alya,bima))){
            Map<?,?> body=(Map<?,?>)controller.validateEventParticipantsImport(1L,batch,"request","test").getBody();
            assertEquals(0,((Map<?,?>)body.get("summary")).get("errorCount"));
        }
        oldContact("Alya","Demo","shared@example.invalid","081200001111");
        var existingAndNew=file(bima,unnamed);
        assertEquals(400,controller.importEventParticipants(1L,existingAndNew,"request","test").getStatusCode().value());
        verify(databases,never()).save(any());verify(participants,never()).save(any());verify(emails,never()).save(any());verify(companies,never()).save(any());
    }

    private Map<?,?> previewRow(MockMultipartFile file){
        var response=controller.validateEventParticipantsImport(1L,file,"request","test");assertEquals(200,response.getStatusCode().value());
        return (Map<?,?>)((List<?>)((Map<?,?>)response.getBody()).get("rows")).get(0);
    }
    private Database oldContact(String first,String last,String address,String phone){
        Database contact=Database.builder().id(ids.incrementAndGet()).firstName(first).lastName(last).mobilePhone(phone).isActive(true).build();
        DatabaseEmail email=DatabaseEmail.builder().id(ids.incrementAndGet()).database(contact).email(address).emailType("company").isCorporate(true).build();
        contact.setEmails(new ArrayList<>(List.of(email)));contacts.add(contact);addresses.add(email);return contact;
    }
    private static MockMultipartFile file(String[]...rows)throws Exception{return workbook(new String[]{"First Name","Last Name","Company Email Address","Mobile Phone","Company Name"},rows);}
    private static MockMultipartFile workbook(String[] headers,String[]...rows)throws Exception{
        try(var workbook=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()){
            var sheet=workbook.createSheet("Anonymous import");var header=sheet.createRow(0);
            for(int column=0;column<headers.length;column++)header.createCell(column).setCellValue(headers[column]);
            for(int index=0;index<rows.length;index++){var row=sheet.createRow(index+1);for(int column=0;column<rows[index].length;column++)row.createCell(column).setCellValue(rows[index][column]);}
            workbook.write(bytes);return new MockMultipartFile("file","anonymous-event.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes.toByteArray());
        }
    }
}
