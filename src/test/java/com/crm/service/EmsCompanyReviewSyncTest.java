package com.crm.service;

import com.crm.domain.*;
import com.crm.domain.Database;
import com.crm.repository.*;
import com.crm.util.ContactPhone;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmsCompanyReviewSyncTest {
    private final EmsService service = spy(new EmsService());
    private final DatabaseRepository databases = mock(DatabaseRepository.class);
    private final DatabaseEmailRepository emails = mock(DatabaseEmailRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final EventParticipantRepository participants = mock(EventParticipantRepository.class);
    private final EventParticipantActivityRepository activities = mock(EventParticipantActivityRepository.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final List<Database> contacts = new ArrayList<>();
    private final List<EventParticipant> links = new ArrayList<>();
    private final List<EventParticipantActivity> history = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);
    private final LocalDateTime originalTime = LocalDateTime.of(2026, 11, 1, 12, 0);
    private final Event event = Event.builder().id(1L).name("Anonymous Company Event").emsSource(EmsSource.V2).emsEventId(700L).syncDestination(DatabaseDestination.COMPANY).build();
    private Company company;
    private Database contact;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "databaseRepository", databases);
        ReflectionTestUtils.setField(service, "databaseEmailRepository", emails);
        ReflectionTestUtils.setField(service, "companyRepository", companies);
        ReflectionTestUtils.setField(service, "eventParticipantRepository", participants);
        ReflectionTestUtils.setField(service, "eventParticipantActivityRepository", activities);
        company = Company.builder().id(9L).name("Original CRM Company").industry("Original Industry").address("Original office").officePhone("0215550101").build();
        contact = Database.builder().id(5L).databaseDestination(DatabaseDestination.COMPANY).firstName("Member").lastName("Example")
                .company(company).mobilePhone("08123456789").normalizedPhone("628123456789").jobTitle("Original Manager")
                .positionLevel(PositionLevel.MANAGERIAL_HEAD).specialityDivision("Original Division").salutation("Ms")
                .createdAt(originalTime).updatedAt(originalTime).createdByUserId(7L).entryMethod("manual").isActive(true).build();
        contact.setEmails(new ArrayList<>(List.of(DatabaseEmail.builder().id(11L).database(contact).email("member@company.example")
                .emailType("company").isCorporate(true).isPrimary(true).isVerified(true).domain("company.example").createdAt(originalTime).build())));
        contacts.add(contact);
        when(databases.findAll()).thenReturn(contacts);
        when(databases.save(any())).thenAnswer(call -> { Database value=call.getArgument(0);if(value.getId()==null){value.setId(ids.incrementAndGet());contacts.add(value);}return value; });
        when(participants.findByEventId(anyLong())).thenAnswer(call -> links.stream().filter(link -> link.getEvent().getId().equals(call.getArgument(0))).toList());
        when(participants.findByEventIdAndDatabaseId(anyLong(), anyLong())).thenAnswer(call -> links.stream().filter(link -> link.getEvent().getId().equals(call.getArgument(0)) && link.getDatabase().getId().equals(call.getArgument(1))).findFirst());
        when(participants.save(any())).thenAnswer(call -> { EventParticipant value=call.getArgument(0);if(value.getId()==null){value.setId(ids.incrementAndGet());links.add(value);}return value; });
        when(emails.existsByDatabaseIdAndEmailIgnoreCase(anyLong(), anyString())).thenAnswer(call -> contacts.stream().filter(value -> value.getId().equals(call.getArgument(0)))
                .flatMap(value -> (value.getEmails()==null?List.<DatabaseEmail>of():value.getEmails()).stream()).anyMatch(value -> value.getEmail().equalsIgnoreCase(call.getArgument(1))));
        when(emails.save(any())).thenAnswer(call -> call.getArgument(0));
        when(activities.findByEventParticipantIdOrderByCreatedAtDesc(anyLong())).thenAnswer(call -> history.stream()
                .filter(value -> value.getEventParticipant().getId().equals(call.getArgument(0))).toList());
        when(activities.save(any())).thenAnswer(call -> { EventParticipantActivity value=call.getArgument(0);history.add(value);return value; });
    }

    @Test void reviewAcceptsTheSameIdentityAndPreservesEveryCrmOwnedFieldAcrossClientAndSourceDecisions() {
        EventParticipant link = existingLink();
        JsonNode originalContact = json.valueToTree(contact), originalCompany = json.valueToTree(company);
        for (String request : List.of("approve", "decline", "pending")) for (String client : List.of("approve", "decline", "pending")) {
            link.setConfirmationStatus(request);link.setPreEventApprovalStatus(client);
            link.setNotes("[Origin: Request] [PIC: Local Agent] [PreEventApproval: " + client + "] Keep exact notes  spacing.");
            for (String source : List.of("PENDING", "ACCEPTED", "DECLINED")) for (boolean checkedIn : List.of(false, true)) {
                ObjectNode before = crmOwnedLink(link);
                source(row("member@company.example", "08123456789", "Different EMS Company", source, checkedIn));
                Map<String,Object> preview=service.previewParticipantsForEvent(event);
                assertEquals(1, preview.get("existingCount"));assertEquals(0, preview.get("rejectedCount"));assertEquals(1, preview.get("companyReviewCount"));
                Map<?,?> review=(Map<?,?>)((List<?>)preview.get("rows")).get(0);
                assertEquals("Original CRM Company",review.get("crmCompanyName"));assertEquals("Different EMS Company",review.get("emsCompanyName"));assertEquals(true,review.get("companyReviewRequired"));
                assertEquals(1, apply(preview).get("count"));
                assertEquals(approval(source),link.getEmsApprovalStatus());assertEquals(approval(source),link.getPreEventApprovalStatus());
                assertEquals("Different EMS Company",link.getEmsCompanyName());assertEquals(true,link.getEmsCompanyReviewRequired());
                assertEquals(source.equals("DECLINED") ? AttendanceStatus.cancelled : checkedIn ? AttendanceStatus.attended : AttendanceStatus.registered,link.getAttendanceStatus());
                assertEquals(before,crmOwnedLink(link),"Only EMS metadata and factual attendance may change");
                assertTrue(link.getNotes().contains("Keep exact notes  spacing."));assertTrue(link.getNotes().contains("[PIC: Local Agent]"));
                assertEquals(originalContact,json.valueToTree(contact));assertEquals(originalCompany,json.valueToTree(company));
                assertEquals(1,apply(service.previewParticipantsForEvent(event)).get("count"));
                assertEquals(before,crmOwnedLink(link));assertEquals(1,contacts.size());assertEquals(1,links.size());assertEquals(1,history.size());
            }
        }
        verify(databases,never()).save(any());verifyNoInteractions(companies,emails);verify(activities,never()).save(any());
    }

    @Test void phoneMatchedCompanyIdentityNeverAppendsNewSourceEmailOrPhoneOrHydratesTheCompany() {
        existingLink();JsonNode before=json.valueToTree(contact), master=json.valueToTree(company);
        source(row("new-source@employer.example", "08123456789, 08139999999", "Different EMS Company", "ACCEPTED", false));
        Map<String,Object> preview=service.previewParticipantsForEvent(event);
        assertEquals(1,preview.get("existingCount"));assertEquals(1,preview.get("companyReviewCount"));apply(preview);
        assertEquals(before,json.valueToTree(contact));assertEquals(master,json.valueToTree(company));
        verify(databases,never()).save(any());verifyNoInteractions(companies,emails);verify(activities,never()).save(any());
    }

    @Test void independentValidSourcePhonesDoNotNeedToFitTheUnchangedStoredPhoneList() {
        existingLink();contact.setMobilePhone("+441234567890123, +331234567890123");
        String incoming="+811234567890123, +121234567890123";
        assertThrows(IllegalArgumentException.class,()->ContactPhone.merge(contact.getMobilePhone(),incoming));
        JsonNode before=json.valueToTree(contact);
        source(row("member@company.example",incoming,"Different EMS Company","ACCEPTED",false));
        Map<String,Object> preview=service.previewParticipantsForEvent(event);assertEquals(0,preview.get("rejectedCount"));assertEquals(1,preview.get("existingCount"));apply(preview);
        assertEquals(before,json.valueToTree(contact));verify(databases,never()).save(any());verifyNoInteractions(companies,emails);verify(activities,never()).save(any());
    }

    @Test void newLinksDefaultToClientPendingAndSourceDeclineOrCheckinCannotApproveThem() {
        for (String source : List.of("PENDING", "ACCEPTED", "DECLINED")) for (boolean checkedIn : List.of(false, true)) {
            links.clear();history.clear();source(row("member@company.example", "08123456789", "Different EMS Company", source, checkedIn));
            apply(service.previewParticipantsForEvent(event));EventParticipant link=links.get(0);Long id=link.getId();
            assertEquals("pending",link.getConfirmationStatus());assertEquals(approval(source),link.getPreEventApprovalStatus());
            assertTrue(link.getNotes().contains("[Origin: EMS Sync]"));assertTrue(link.getNotes().contains("[PreEventApproval: " + approval(source) + "]"));
            assertEquals(approval(source),link.getEmsApprovalStatus());
            assertEquals(source.equals("DECLINED")?ParticipantStatus.unable_to_attend:checkedIn?ParticipantStatus.confirm:ParticipantStatus.registered,link.getParticipantStatus());
            assertEquals(source.equals("DECLINED")?AttendanceStatus.cancelled:checkedIn?AttendanceStatus.attended:AttendanceStatus.registered,link.getAttendanceStatus());
            apply(service.previewParticipantsForEvent(event));assertEquals(1,links.size());assertEquals(id,links.get(0).getId());assertEquals(1,history.size());
        }
        verify(databases,never()).save(any());verifyNoInteractions(companies,emails);
    }

    @Test void existingNullClientDecisionIsPreservedWhileSourceApprovalAndMissingSystemHistoryAreRecorded() {
        EventParticipant link=existingLink();link.setConfirmationStatus(null);link.setPreEventApprovalStatus(null);link.setNotes(null);history.clear();
        source(row("member@company.example", "08123456789", "Different EMS Company", "ACCEPTED", true));
        apply(service.previewParticipantsForEvent(event));assertNull(link.getConfirmationStatus());assertEquals("approve",link.getPreEventApprovalStatus());
        assertEquals("approve",link.getEmsApprovalStatus());assertEquals(1,history.size());assertEquals("SYSTEM",history.get(0).getActivityType());
    }

    @Test void inactiveDuplicateAndConflictingIndividualIdentityRowsRemainHeld() {
        Map<String,Object> registration=row("member@company.example", "08123456789", "Different EMS Company", "ACCEPTED", false);
        source(registration);contact.setIsActive(false);assertHeld();contact.setIsActive(true);
        Database duplicate=Database.builder().id(6L).databaseDestination(DatabaseDestination.COMPANY).firstName("Member").lastName("Example").mobilePhone("+628123456789").isActive(true).build();
        duplicate.setEmails(new ArrayList<>(List.of(DatabaseEmail.builder().email("member@company.example").build())));
        contacts.add(duplicate);assertHeld();contacts.remove(duplicate);
        Map<String,Object> wrongPhoneOwner=row("member@company.example","08123456789","Different EMS Company","ACCEPTED",false);
        ((Map<String,Object>)wrongPhoneOwner.get("user_profile")).put("registered_name","Different Name");source(wrongPhoneOwner);assertHeld();
        contact.getEmails().get(0).setEmail("member@gmail.com");
        registration=row("member@gmail.com", "08123456789", "Different EMS Company", "ACCEPTED", false);
        ((Map<String,Object>)registration.get("user_profile")).put("registered_name","Different Name");source(registration);assertHeld();
        verify(databases,never()).save(any());verify(participants,never()).save(any());verifyNoInteractions(companies,emails,activities);
    }

    @Test void aSharedCorporateEmailAloneCanStillRepresentAnotherPersonWithoutCrossMerging() {
        JsonNode before=json.valueToTree(contact);when(companies.findByNameIgnoreCase(company.getName())).thenReturn(Optional.of(company));
        Map<String,Object> registration=row("member@company.example","",company.getName(),"PENDING",false);
        ((Map<String,Object>)registration.get("user_profile")).put("registered_name","Another Person");source(registration);
        Map<String,Object> preview=service.previewParticipantsForEvent(event);assertEquals(0,preview.get("rejectedCount"));assertEquals(1,preview.get("newCount"));apply(preview);
        assertEquals(2,contacts.size());assertNotEquals(contact.getId(),links.get(0).getDatabase().getId());assertEquals(before,json.valueToTree(contact));
        verify(companies,never()).save(any());
    }

    @Test void participantIdentityIsNeverCrossMergedIntoTheCompanyDestination() {
        contact.setDatabaseDestination(DatabaseDestination.PARTICIPANT);contact.setCompany(null);contact.setInstitutionName("Retained Campus");JsonNode before=json.valueToTree(contact);
        source(row("member@company.example", "08123456789", "", "ACCEPTED", false));
        Map<String,Object> preview=service.previewParticipantsForEvent(event);assertEquals(1,preview.get("newCount"));assertEquals(0,preview.get("existingCount"));apply(preview);
        assertEquals(2,contacts.size());assertNotEquals(contact.getId(),links.get(0).getDatabase().getId());assertEquals(DatabaseDestination.COMPANY,links.get(0).getDatabase().getDatabaseDestination());
        assertEquals(before,json.valueToTree(contact));apply(service.previewParticipantsForEvent(event));assertEquals(2,contacts.size());assertEquals(1,links.size());verifyNoInteractions(companies);
    }

    @Test void companyRenameAfterPreviewInvalidatesTheReviewBeforeAnyWrite() {
        existingLink();source(row("member@company.example", "08123456789", "Different EMS Company", "PENDING", false));
        Map<String,Object> preview=service.previewParticipantsForEvent(event);company.setName("Staff Renamed CRM Company");
        assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(preview)).getStatusCode().value());
        verify(participants,never()).save(any());verify(databases,never()).save(any());verifyNoInteractions(companies,emails,activities);
    }

    @Test void companyReviewClearsWhenRegistrationMatchesAndClientDeclineStillSurvivesEverySync() {
        EventParticipant link=existingLink();link.setConfirmationStatus("decline");
        for (String sourceCompany : List.of("Different Company",company.getName(),"")) {
            link.setPreEventApprovalStatus("pending");
            source(row("member@company.example","08123456789",sourceCompany,"ACCEPTED",false));
            Map<String,Object> preview=service.previewParticipantsForEvent(event);
            assertEquals(sourceCompany.equals("Different Company")?1:0,preview.get("companyReviewCount"));
            apply(preview);
            assertEquals("approve",link.getPreEventApprovalStatus());assertEquals("decline",link.getConfirmationStatus());
            assertEquals(sourceCompany,link.getEmsCompanyName());assertEquals(sourceCompany.equals("Different Company"),link.getEmsCompanyReviewRequired());
        }
        verify(databases,never()).save(any());verifyNoInteractions(companies,emails);
    }

    @Test void conflictingSourceApprovalsStayRejectedAndAreExcludedFromReviewCounts() {
        EventParticipant link=existingLink();JsonNode before=json.valueToTree(link);
        doReturn(List.of(row("member@company.example","08123456789","Different Company","ACCEPTED",false),
                row("member@company.example","08123456789","Different Company","PENDING",false)))
                .when(service).getEventParticipants(event.getEmsEventId(),event.getEmsSource());
        Map<String,Object> preview=service.previewParticipantsForEvent(event);
        assertEquals(2,preview.get("rejectedCount"));assertEquals(0,preview.get("companyReviewCount"));
        assertEquals(0,preview.get("existingCount"));assertEquals(0,apply(preview).get("count"));assertEquals(before,json.valueToTree(link));
        verify(participants,never()).save(any());verify(databases,never()).save(any());verifyNoInteractions(companies,emails,activities);
    }

    @Test void aHistoricalClientDeclinedAliasSyncsTheExactCurrentProfileWithAClientReviewVeto() {
        EventParticipant current=existingLink();current.setConfirmationStatus("pending");
        Database alias=Database.builder().id(6L).databaseDestination(DatabaseDestination.COMPANY).firstName("Historical").lastName("Alias")
                .mobilePhone("+628123456789").company(company).isActive(true).build();contacts.add(alias);
        EventParticipant declined=EventParticipant.builder().id(21L).event(event).database(alias).confirmationStatus("decline").build();links.add(declined);
        JsonNode mastersBefore=json.valueToTree(contacts),declinedBefore=json.valueToTree(declined);
        source(row("member@company.example","08123456789","Different Company","ACCEPTED",false));
        for(int repeat=0;repeat<2;repeat++) {
            Collections.reverse(contacts);
            Map<String,Object> preview=service.previewParticipantsForEvent(event);
            assertEquals(1,preview.get("existingCount"));assertEquals(0,preview.get("newCount"));assertEquals(0,preview.get("rejectedCount"));
            assertEquals(1,apply(preview).get("count"));
            assertEquals("approve",current.getEmsApprovalStatus());assertEquals("approve",current.getPreEventApprovalStatus());
            assertEquals("pending",current.getConfirmationStatus());assertEquals(true,current.getEmsClientDeclineReviewRequired());
            assertNotNull(current.getEmsIdentityReviewMessage());assertFalse(current.getEmsIdentityReviewMessage().isBlank());
            assertEquals(declinedBefore,json.valueToTree(declined));assertEquals("decline",declined.getConfirmationStatus());
        }
        contacts.sort(Comparator.comparing(Database::getId));
        assertEquals(mastersBefore,json.valueToTree(contacts));assertEquals(2,links.size());assertEquals(1,history.size());
        verify(databases,never()).save(any());verifyNoInteractions(companies,emails);
    }

    @Test void matchedContactsWithoutACompanyStayDetachedAndRequireReviewInsteadOfCreatingAMaster() {
        existingLink();contact.setCompany(null);JsonNode before=json.valueToTree(contact);
        source(row("member@company.example","08123456789","Different Company","ACCEPTED",false));
        Map<String,Object> preview=service.previewParticipantsForEvent(event);assertEquals(1,preview.get("companyReviewCount"));
        apply(preview);assertEquals(before,json.valueToTree(contact));assertEquals(true,links.get(0).getEmsCompanyReviewRequired());
        verify(databases,never()).save(any());verifyNoInteractions(companies,emails);
    }

    @Test void explicitClientApprovalResolvesOnlyTheSelectedProfilesAliasReviewAcrossRepeatedSyncs() {
        EventParticipant current=existingLink();
        Database alias=Database.builder().id(6L).databaseDestination(DatabaseDestination.COMPANY).firstName("Historical").lastName("Alias")
                .mobilePhone("+628123456789").company(company).isActive(true).build();contacts.add(alias);
        EventParticipant historical=EventParticipant.builder().id(21L).event(event).database(alias).confirmationStatus("decline").build();links.add(historical);
        JsonNode mastersBefore=json.valueToTree(contacts),historicalBefore=json.valueToTree(historical);
        source(row("member@company.example","08123456789","Different Company","ACCEPTED",false));
        for(String clientDecision:List.of("approve","confirmed")) {
            current.setConfirmationStatus(clientDecision);
            for(int repeat=0;repeat<2;repeat++) {
                Map<String,Object> preview=service.previewParticipantsForEvent(event);
                assertEquals(0,preview.get("rejectedCount"));
                assertEquals(false,((Map<?,?>)((List<?>)preview.get("rows")).get(0)).get("clientDeclineReviewRequired"));
                assertEquals(1,apply(preview).get("count"));
                assertEquals(clientDecision,current.getConfirmationStatus());assertEquals(false,current.getEmsClientDeclineReviewRequired());
                assertEquals("approve",current.getPreEventApprovalStatus());assertEquals("approve",current.getEmsApprovalStatus());
                assertEquals(historicalBefore,json.valueToTree(historical));assertEquals(mastersBefore,json.valueToTree(contacts));
            }
            current.setConfirmationStatus("pending");
            assertEquals(true,current.getEmsClientDeclineReviewRequired(),"Revoking the selected approval must reactivate the retained alias review without another sync");
        }
        current.setConfirmationStatus("approve");
        source(row("member@company.example","08123456789","Different Company","DECLINED",false));
        assertEquals(1,apply(service.previewParticipantsForEvent(event)).get("count"));
        assertEquals("approve",current.getConfirmationStatus());assertEquals("decline",current.getPreEventApprovalStatus());
        assertEquals("decline",current.getEmsApprovalStatus());assertEquals(ParticipantStatus.unable_to_attend,current.getParticipantStatus());
        assertEquals(historicalBefore,json.valueToTree(historical));assertEquals(mastersBefore,json.valueToTree(contacts));
        assertEquals(2,links.size());assertEquals(1,history.size());
        verify(databases,never()).save(any());verifyNoInteractions(companies,emails);
    }

    @Test void historicalClientReviewRequiresFreshPreviewAndOnlyClearsAfterAnEligibleSyncSeesTheChangedDecision() {
        EventParticipant current=existingLink();current.setConfirmationStatus("pending");
        Database alias=Database.builder().id(6L).databaseDestination(DatabaseDestination.COMPANY).firstName("Historical").lastName("Alias")
                .mobilePhone("08123456789").company(company).isActive(true).build();contacts.add(alias);
        EventParticipant historical=EventParticipant.builder().id(21L).event(event).database(alias).confirmationStatus("decline").build();links.add(historical);
        source(row("member@company.example","08123456789","Different Company","ACCEPTED",false));
        apply(service.previewParticipantsForEvent(event));
        assertEquals(true,current.getEmsClientDeclineReviewRequired());

        source(row("member@company.example","08137777777","Different Company","ACCEPTED",false));
        Map<String,Object> changedPhonePreview=service.previewParticipantsForEvent(event);
        assertEquals(1,changedPhonePreview.get("existingCount"));assertEquals(1,apply(changedPhonePreview).get("count"));
        assertEquals(true,current.getEmsClientDeclineReviewRequired());
        assertEquals("08123456789",contact.getMobilePhone());

        JsonNode protectedCurrent=json.valueToTree(current);
        Map<String,Object> invalid=row("changed@example.invalid","08123456789","Different Company","ACCEPTED",false);
        ((Map<String,Object>)invalid.get("user_profile")).put("registered_name","Unrecognized");
        source(invalid);
        assertHeld();
        assertEquals(protectedCurrent,json.valueToTree(current));assertEquals(true,current.getEmsClientDeclineReviewRequired());

        source(row("member@company.example","08123456789","Different Company","ACCEPTED",false));
        Map<String,Object> stalePreview=service.previewParticipantsForEvent(event);
        historical.setConfirmationStatus("approve");
        assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(stalePreview)).getStatusCode().value());
        assertEquals(protectedCurrent,json.valueToTree(current));
        Map<String,Object> freshPreview=service.previewParticipantsForEvent(event);
        assertEquals(1,apply(freshPreview).get("count"));
        assertEquals(false,current.getEmsClientDeclineReviewRequired());assertEquals("pending",current.getConfirmationStatus());
        assertEquals("approve",historical.getConfirmationStatus());assertEquals("approve",current.getPreEventApprovalStatus());
        verify(participants,times(3)).save(current);verify(participants,never()).save(historical);
        verify(databases,never()).save(any());verifyNoInteractions(companies,emails);
    }

    @Test void changingReviewMetadataAfterPreviewRequiresAFreshConfirmation() {
        EventParticipant link=existingLink();source(row("member@company.example","08123456789","Different Company","ACCEPTED",false));
        Map<String,Object> preview=service.previewParticipantsForEvent(event);link.setEmsCompanyName("Changed since preview");
        assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(preview)).getStatusCode().value());
        verify(participants,never()).save(any());verify(databases,never()).save(any());verifyNoInteractions(companies,emails,activities);
    }

    private void assertHeld(){Map<String,Object> preview=service.previewParticipantsForEvent(event);assertEquals(1,preview.get("rejectedCount"));assertEquals(0,apply(preview).get("count"));}
    private void source(Map<String,Object> row){doReturn(List.of(row)).when(service).getEventParticipants(event.getEmsEventId(),event.getEmsSource());}
    private Map<String,Object> apply(Map<String,Object> preview){return service.syncParticipantsForEvent(event,(String)preview.get("previewToken"));}
    private static String approval(String source){return source.equals("ACCEPTED")?"approve":source.equals("DECLINED")?"decline":"pending";}
    private ObjectNode crmOwnedLink(EventParticipant link){ObjectNode state=json.valueToTree(link);state.remove(List.of("participantStatus","attendanceStatus","emsCompanyName","emsCompanyReviewRequired","emsIdentityReviewMessage","emsClientDeclineReviewRequired","emsApprovalStatus","preEventApprovalStatus","notes","reminderHariH"));return state;}
    private EventParticipant existingLink(){
        EventParticipant link=EventParticipant.builder().id(20L).event(event).database(contact).participantStatus(ParticipantStatus.yellow).attendanceStatus(AttendanceStatus.invited)
                .confirmationStatus("approve").preEventApprovalStatus("pending").callStatus("called").emailStatus("sent").whatsappStatus("replied").meetingStatus("scheduled")
                .businessChallenges("Keep challenge").projectInfo("Keep project").timeline("Keep timeline").reminderH7("tentative").reminderH3("not_respond_yet").reminderH1("confirm").reminderHariH("on_the_way")
                .notes("[Origin: Request] [PIC: Local Agent] Keep notes").createdAt(originalTime).updatedAt(originalTime).requestedAt(originalTime).respondedAt(originalTime).build();
        links.add(link);history.add(EventParticipantActivity.builder().id(30L).eventParticipant(link).activityType("CALL").status("TENTATIVE").notes("Keep exact call history").createdBy("Local Agent").createdAt(originalTime).build());return link;
    }
    private static Map<String,Object> row(String email,String phone,String company,String status,boolean checkedIn){
        Map<String,Object> profile=new HashMap<>(Map.of("registered_name","Member","registered_last_name","Example","registered_email",email,"phone",phone,"company",company,"industry","Source Industry","job_title","Source Job"));
        Map<String,Object> row=new HashMap<>(Map.of("user_profile",profile,"status",status,"registration_code","ANONYMOUS"));if(checkedIn)row.put("checked_in_at","2026-11-09T02:00:00Z");return row;
    }
}
