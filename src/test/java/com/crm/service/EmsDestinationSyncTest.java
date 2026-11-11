package com.crm.service;

import com.crm.domain.*;
import com.crm.domain.Database;
import com.crm.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmsDestinationSyncTest {
    private final EmsService service = spy(new EmsService());
    private final DatabaseRepository databases = mock(DatabaseRepository.class);
    private final DatabaseEmailRepository emails = mock(DatabaseEmailRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final EventParticipantRepository participants = mock(EventParticipantRepository.class);
    private final EventParticipantActivityRepository activities = mock(EventParticipantActivityRepository.class);
    private final List<Database> contacts = new ArrayList<>();
    private final List<EventParticipant> links = new ArrayList<>();
    private final List<EventParticipantActivity> history = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service,"databaseRepository",databases);
        ReflectionTestUtils.setField(service,"databaseEmailRepository",emails);
        ReflectionTestUtils.setField(service,"companyRepository",companies);
        ReflectionTestUtils.setField(service,"eventParticipantRepository",participants);
        ReflectionTestUtils.setField(service,"eventParticipantActivityRepository",activities);
        when(databases.findAll()).thenReturn(contacts);
        when(databases.save(any())).thenAnswer(call -> {
            Database value=call.getArgument(0);
            if(value.getId()==null){value.setId(ids.incrementAndGet());contacts.add(value);} return value;
        });
        when(participants.findByEventId(anyLong())).thenAnswer(call -> links.stream().filter(link -> link.getEvent().getId().equals(call.getArgument(0))).toList());
        when(participants.findByEventIdAndDatabaseId(anyLong(),anyLong())).thenAnswer(call -> links.stream()
                .filter(link -> link.getEvent().getId().equals(call.getArgument(0)) && link.getDatabase().getId().equals(call.getArgument(1))).findFirst());
        when(participants.save(any())).thenAnswer(call -> {EventParticipant link=call.getArgument(0);if(link.getId()==null){link.setId(ids.incrementAndGet());links.add(link);}return link;});
        when(emails.existsByDatabaseIdAndEmailIgnoreCase(anyLong(),anyString())).thenAnswer(call -> contacts.stream()
                .filter(contact -> contact.getId().equals(call.getArgument(0))).flatMap(contact -> (contact.getEmails()==null?List.<DatabaseEmail>of():contact.getEmails()).stream())
                .anyMatch(email -> email.getEmail().equalsIgnoreCase(call.getArgument(1))));
        when(emails.save(any())).thenAnswer(call -> call.getArgument(0));
        when(activities.findByEventParticipantIdOrderByCreatedAtDesc(anyLong())).thenAnswer(call -> history.stream().filter(activity -> activity.getEventParticipant().getId().equals(call.getArgument(0))).toList());
        when(activities.save(any())).thenAnswer(call -> {EventParticipantActivity activity=call.getArgument(0);history.add(activity);return activity;});
        doReturn(List.of()).when(service).getEventParticipants(anyLong(),any());
    }

    @Test void previewRequiresExplicitDestinationAndDoesNotWrite() {
        Event event=event(1,DatabaseDestination.PARTICIPANT);event.setSyncDestination(null);
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.previewParticipantsForEvent(event)).getStatusCode().value());
        event.setSyncDestination(DatabaseDestination.BOTH);
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.previewParticipantsForEvent(event)).getStatusCode().value());
        verifyNoInteractions(databases,emails,companies,participants,activities);
    }

    @Test void campusAndWorkEmailFollowParticipantDestinationWithoutCreatingCompanyProspectsAndHistorySpansEvents() {
        Map<String,Object> registration=row("Student","Example","student@campus.ac.id","Campus School");
        doReturn(List.of(registration,registration)).when(service).getEventParticipants(1L,EmsSource.V2);
        Event first=event(1,DatabaseDestination.PARTICIPANT);
        Map<String,Object> preview=service.previewParticipantsForEvent(first);
        assertEquals(1,preview.get("newCount"));assertEquals(1,preview.get("existingCount"));assertEquals(0,preview.get("rejectedCount"));
        assertTrue(contacts.isEmpty());verify(databases,never()).save(any());verify(companies,never()).save(any());
        assertEquals(2,apply(first,preview).get("count"));
        assertEquals(1,contacts.size());assertEquals(1,links.size());assertEquals(1,history.size());
        assertEquals(DatabaseDestination.PARTICIPANT,contacts.get(0).getDatabaseDestination());
        assertEquals("Campus School",contacts.get(0).getInstitutionName());assertNull(contacts.get(0).getCompany());
        doReturn(List.of(registration)).when(service).getEventParticipants(2L,EmsSource.V2);
        Event second=event(2,DatabaseDestination.PARTICIPANT);
        assertEquals(1,service.previewParticipantsForEvent(second).get("existingCount"));
        apply(second,service.previewParticipantsForEvent(second));
        assertEquals(1,contacts.size());assertEquals(2,links.size());assertSame(links.get(0).getDatabase(),links.get(1).getDatabase());
        verifyNoInteractions(companies);
    }

    @Test void participantSyncCreatesIndependentProfileForTheSameCompanyEmailAndPhone() {
        Company company=Company.builder().id(77L).name("Original Company").industry("Technology").build();
        Database contact=contact(5,"Member","Example","member@company.example",DatabaseDestination.COMPANY);
        contact.setCompany(company);contact.setMobilePhone("08123456789");contact.setJobTitle("Manager");contacts.add(contact);
        Map<String,Object> registration=row("Member","Example","member@company.example","Different Institute");
        ((Map<String,Object>)registration.get("user_profile")).put("phone","+62 8123456789, 08139999999");
        doReturn(List.of(registration)).when(service).getEventParticipants(anyLong(),eq(EmsSource.V2));
        Event event=event(1,DatabaseDestination.PARTICIPANT);
        Map<String,Object> preview=service.previewParticipantsForEvent(event);
        assertEquals(1,preview.get("newCount"));assertEquals(0,preview.get("existingCount"));assertEquals(0,preview.get("rejectedCount"));
        assertEquals(1,apply(event,preview).get("count"));assertEquals(2,contacts.size());
        Database participant=links.get(0).getDatabase();assertNotSame(contact,participant);assertNotEquals(contact.getId(),participant.getId());
        assertEquals(DatabaseDestination.PARTICIPANT,participant.getDatabaseDestination());assertNull(participant.getCompany());assertNull(participant.getJobTitle());
        assertEquals("Different Institute",participant.getInstitutionName());assertEquals("+628123456789, 08139999999",participant.getMobilePhone());
        assertEquals(DatabaseDestination.COMPANY,contact.getDatabaseDestination());assertSame(company,contact.getCompany());
        assertEquals("08123456789",contact.getMobilePhone());assertEquals("Manager",contact.getJobTitle());assertEquals(1,contact.getEmails().size());
        preview=service.previewParticipantsForEvent(event);assertEquals(0,preview.get("newCount"));assertEquals(1,preview.get("existingCount"));apply(event,preview);
        Event second=event(2,DatabaseDestination.PARTICIPANT);apply(second,service.previewParticipantsForEvent(second));
        assertEquals(2,contacts.size());assertEquals(2,links.size());assertTrue(links.stream().allMatch(link -> link.getDatabase()==participant));
        assertEquals("Original Company",company.getName());assertEquals("Technology",company.getIndustry());verifyNoInteractions(companies);
    }

    @Test void companySyncCreatesIndependentProfilesRegardlessOfEmailDomainAndExistingParticipantIdentity() {
        Database existing = contact(5,"Student","Example","student@campus.ac.id",DatabaseDestination.PARTICIPANT);
        existing.setMobilePhone("08123456789");existing.setInstitutionName("Original Campus");contacts.add(existing);
        Company company=Company.builder().id(77L).name("School").industry("Education").build();
        when(companies.findByNameIgnoreCase("School")).thenReturn(Optional.of(company));
        Map<String,Object> registration=row("Student","Example","student@campus.ac.id","School");
        ((Map<String,Object>)registration.get("user_profile")).put("phone","+62 8123456789");
        doReturn(List.of(row("Gmail","Person","member@gmail.com","School"),registration))
                .when(service).getEventParticipants(1L,EmsSource.V2);
        Event event=event(1,DatabaseDestination.COMPANY);Map<String,Object> preview=service.previewParticipantsForEvent(event);
        assertEquals(0,preview.get("rejectedCount"));assertEquals(2,preview.get("newCount"));assertEquals(0,preview.get("existingCount"));assertEquals(2,apply(event,preview).get("count"));
        assertEquals(3,contacts.size());assertEquals(DatabaseDestination.PARTICIPANT,existing.getDatabaseDestination());
        assertNull(existing.getCompany());assertEquals("Original Campus",existing.getInstitutionName());assertEquals("08123456789",existing.getMobilePhone());
        assertTrue(links.stream().allMatch(link -> link.getDatabase()!=existing && link.getDatabase().getDatabaseDestination()==DatabaseDestination.COMPANY && link.getDatabase().getCompany()==company));
        preview=service.previewParticipantsForEvent(event);assertEquals(0,preview.get("newCount"));assertEquals(2,preview.get("existingCount"));apply(event,preview);
        assertEquals(3,contacts.size());assertEquals(2,links.size());
    }

    @Test void otherDestinationOwnersIncludingInactiveDuplicateAndLegacyBothProfilesDoNotBlockParticipantSync() {
        for (DatabaseDestination destination : List.of(DatabaseDestination.COMPANY,DatabaseDestination.COMPANY,DatabaseDestination.BOTH)) {
            Database other=contact(ids.incrementAndGet(),"Other","Name","shared@example.invalid",destination);
            other.setMobilePhone("08123456789");other.setIsActive(false);contacts.add(other);
        }
        Map<String,Object> registration=row("Student","Example","shared@example.invalid","Campus");
        ((Map<String,Object>)registration.get("user_profile")).put("phone","08123456789");
        doReturn(List.of(registration)).when(service).getEventParticipants(1L,EmsSource.V2);
        Event event=event(1,DatabaseDestination.PARTICIPANT);Map<String,Object> preview=service.previewParticipantsForEvent(event);
        assertEquals(1,preview.get("newCount"));assertEquals(0,preview.get("existingCount"));assertEquals(0,preview.get("rejectedCount"));
        assertEquals(1,apply(event,preview).get("count"));assertEquals(4,contacts.size());
        assertEquals(DatabaseDestination.PARTICIPANT,links.get(0).getDatabase().getDatabaseDestination());verifyNoInteractions(companies);
    }

    @Test void previewFingerprintIgnoresOtherDestinationProfilesAndEventLinksInBothDirections() {
        for (DatabaseDestination destination : List.of(DatabaseDestination.COMPANY,DatabaseDestination.PARTICIPANT)) {
            contacts.clear();links.clear();history.clear();
            DatabaseDestination opposite=destination==DatabaseDestination.COMPANY?DatabaseDestination.PARTICIPANT:DatabaseDestination.COMPANY;
            Database other=contact(5,"Member","Example","member@example.invalid",opposite);other.setMobilePhone("08123456789");contacts.add(other);
            Map<String,Object> registration=row("Member","Example","member@example.invalid",null);
            ((Map<String,Object>)registration.get("user_profile")).put("phone","08123456789");
            doReturn(List.of(registration)).when(service).getEventParticipants(1L,EmsSource.V2);
            Event event=event(1,destination);Map<String,Object> preview=service.previewParticipantsForEvent(event);
            other.setFirstName("Changed");other.setMobilePhone("08999999999");other.setIsActive(false);
            contacts.add(contact(6,"Another","Owner","member@example.invalid",opposite));
            links.add(EventParticipant.builder().id(7L).event(event).database(other).build());
            assertEquals(preview.get("previewToken"),service.previewParticipantsForEvent(event).get("previewToken"));
            assertEquals(1,apply(event,preview).get("count"));assertEquals(3,contacts.size());
            assertEquals(destination,links.get(1).getDatabase().getDatabaseDestination());
        }
        verifyNoInteractions(companies);
    }

    @Test void legacyNullIsCompanyOnlyAndBothIsNeverAnOperationalIdentityMatch() {
        Database legacy=contact(5,"Member","Example","member@example.invalid",null);contacts.add(legacy);
        Database both=contact(6,"Member","Example","member@example.invalid",DatabaseDestination.BOTH);contacts.add(both);
        doReturn(List.of(row("Member","Example","member@example.invalid",null))).when(service).getEventParticipants(anyLong(),eq(EmsSource.V2));
        Event company=event(1,DatabaseDestination.COMPANY);Map<String,Object> preview=service.previewParticipantsForEvent(company);
        assertEquals(0,preview.get("newCount"));assertEquals(1,preview.get("existingCount"));assertEquals(0,preview.get("rejectedCount"));apply(company,preview);
        assertSame(legacy,links.get(0).getDatabase());assertNull(legacy.getDatabaseDestination());
        Event participant=event(2,DatabaseDestination.PARTICIPANT);preview=service.previewParticipantsForEvent(participant);
        assertEquals(1,preview.get("newCount"));assertEquals(0,preview.get("existingCount"));apply(participant,preview);
        assertEquals(3,contacts.size());assertNotSame(legacy,links.get(1).getDatabase());assertNotSame(both,links.get(1).getDatabase());
        assertEquals(DatabaseDestination.BOTH,both.getDatabaseDestination());verifyNoInteractions(companies);
    }

    @Test void staleDestinationSourceAndCrmStateRejectBeforeAnyWrites() {
        doReturn(List.of(row("Student","Example","student@gmail.com",null))).when(service).getEventParticipants(1L,EmsSource.V2);
        Event event=event(1,DatabaseDestination.PARTICIPANT);Map<String,Object> preview=service.previewParticipantsForEvent(event);
        event.setSyncDestination(DatabaseDestination.COMPANY);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(event,preview)).getStatusCode().value());
        event.setSyncDestination(DatabaseDestination.PARTICIPANT);
        doReturn(List.of(row("Changed","Source","different@gmail.com",null))).when(service).getEventParticipants(1L,EmsSource.V2);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(event,preview)).getStatusCode().value());
        doReturn(List.of(row("Student","Example","student@gmail.com",null))).when(service).getEventParticipants(1L,EmsSource.V2);
        contacts.add(contact(9,"Other","Contact","other@gmail.com",DatabaseDestination.PARTICIPANT));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(event,preview)).getStatusCode().value());
        verify(databases,never()).save(any());verify(emails,never()).save(any());verify(participants,never()).save(any());verifyNoInteractions(companies,activities);
    }

    @Test void invalidDifferentNameAndAmbiguousIdentityRowsAreReportedInsteadOfMerged() {
        contacts.add(contact(4,"Existing","Name","shared@school.ac.id",DatabaseDestination.PARTICIPANT));
        contacts.add(contact(5,"Duplicate","Name","dupe@school.ac.id",DatabaseDestination.PARTICIPANT));
        contacts.add(contact(6,"Duplicate","Name","dupe@school.ac.id",DatabaseDestination.PARTICIPANT));
        doReturn(List.of(row("Wrong","Name","shared@school.ac.id",null),row("Duplicate","Name","dupe@school.ac.id",null),row("Invalid","Email","broken",null)))
                .when(service).getEventParticipants(1L,EmsSource.V2);
        Event event=event(1,DatabaseDestination.PARTICIPANT);Map<String,Object> preview=service.previewParticipantsForEvent(event);
        assertEquals(3,preview.get("rejectedCount"));assertEquals(0,apply(event,preview).get("count"));assertEquals(3,contacts.size());
        verify(databases,never()).save(any());
    }

    @Test void conflictingNamesForOneParticipantEmailRejectEveryRowInsteadOfChoosingTheFirst() {
        doReturn(List.of(row("First","Person","shared@campus.ac.id",null),row("Second","Person","SHARED@campus.ac.id",null)))
                .when(service).getEventParticipants(1L,EmsSource.V2);
        Event event=event(1,DatabaseDestination.PARTICIPANT);Map<String,Object> preview=service.previewParticipantsForEvent(event);
        assertEquals(2,preview.get("rejectedCount"));assertEquals(0,preview.get("newCount"));
        assertEquals(0,apply(event,preview).get("count"));assertTrue(contacts.isEmpty());
        verify(databases,never()).save(any());verify(emails,never()).save(any());verify(participants,never()).save(any());
    }

    @Test void sameDestinationPhoneAmbiguityAndInactiveOwnersRemainBlocked() {
        Database first=contact(4,"Duplicate","Name","first@example.invalid",DatabaseDestination.PARTICIPANT);
        Database second=contact(5,"Duplicate","Name","second@example.invalid",DatabaseDestination.PARTICIPANT);
        Database inactive=contact(6,"Inactive","Owner","old@example.invalid",DatabaseDestination.PARTICIPANT);
        first.setMobilePhone("08123456789");second.setMobilePhone("+628123456789");inactive.setMobilePhone("08139999999");inactive.setIsActive(false);
        contacts.addAll(List.of(first,second,inactive));
        Map<String,Object> duplicateRow=row("Duplicate","Name","new@example.invalid",null);
        ((Map<String,Object>)duplicateRow.get("user_profile")).put("phone","8123456789");
        Map<String,Object> inactiveRow=row("Inactive","Owner","another@example.invalid",null);
        ((Map<String,Object>)inactiveRow.get("user_profile")).put("phone","+628139999999");
        doReturn(List.of(duplicateRow,inactiveRow)).when(service).getEventParticipants(1L,EmsSource.V2);
        Event event=event(1,DatabaseDestination.PARTICIPANT);Map<String,Object> preview=service.previewParticipantsForEvent(event);
        assertEquals(2,preview.get("rejectedCount"));assertEquals(0,apply(event,preview).get("count"));assertEquals(3,contacts.size());
        verify(databases,never()).save(any());verify(emails,never()).save(any());verify(participants,never()).save(any());verifyNoInteractions(companies);
    }

    @Test void sameNewParticipantNameAndPhoneWithDifferentEmailsReusesOnePendingProfileAndEventLink() {
        Map<String,Object> campus=row("Student","Example","student@campus.ac.id","Campus School");
        Map<String,Object> gmail=row("STUDENT","EXAMPLE","student@gmail.com","Campus School");
        ((Map<String,Object>)campus.get("user_profile")).put("phone","0812-3456-789");
        ((Map<String,Object>)gmail.get("user_profile")).put("phone","+62812 3456789");
        Map<String,Object> alumni=row("Student","Example","alumni@campus.ac.id","Campus School");
        ((Map<String,Object>)alumni.get("user_profile")).put("phone","8123456789");
        doReturn(List.of(campus,gmail,alumni)).when(service).getEventParticipants(1L,EmsSource.V2);
        Event first=event(1,DatabaseDestination.PARTICIPANT);Map<String,Object> preview=service.previewParticipantsForEvent(first);
        assertEquals(1,preview.get("newCount"));assertEquals(2,preview.get("existingCount"));assertEquals(0,preview.get("rejectedCount"));
        assertEquals(3,apply(first,preview).get("count"));
        assertEquals(1,contacts.size());assertEquals(1,links.size());assertEquals(1,history.size());
        assertEquals("08123456789",contacts.get(0).getMobilePhone());
        assertEquals("628123456789",contacts.get(0).getNormalizedPhone());
        assertEquals(Set.of("student@campus.ac.id","student@gmail.com","alumni@campus.ac.id"),contacts.get(0).getEmails().stream().map(DatabaseEmail::getEmail).collect(java.util.stream.Collectors.toSet()));
        Map<String,Object> nextEvent=row("Student","Example","latest@new-campus.ac.id",null);
        ((Map<String,Object>)nextEvent.get("user_profile")).put("phone","8123456789");
        doReturn(List.of(nextEvent)).when(service).getEventParticipants(2L,EmsSource.V2);
        Event second=event(2,DatabaseDestination.PARTICIPANT);Map<String,Object> secondPreview=service.previewParticipantsForEvent(second);
        assertEquals(0,secondPreview.get("newCount"));assertEquals(1,secondPreview.get("existingCount"));apply(second,secondPreview);
        assertEquals(1,contacts.size());assertEquals(2,links.size());assertSame(links.get(0).getDatabase(),links.get(1).getDatabase());
        assertEquals("08123456789",contacts.get(0).getMobilePhone());
        verifyNoInteractions(companies);
    }

    @Test void changedPhoneIdentityInvalidatesPreviewBeforeCreatingAnotherProfile() {
        Database existing=contact(9,"Student","Example","previous@campus.ac.id",DatabaseDestination.PARTICIPANT);
        existing.setMobilePhone("08123456789");contacts.add(existing);
        Map<String,Object> incoming=row("Student","Example","new@gmail.com",null);
        ((Map<String,Object>)incoming.get("user_profile")).put("phone","0812-3456-789");
        doReturn(List.of(incoming)).when(service).getEventParticipants(1L,EmsSource.V2);
        Event event=event(1,DatabaseDestination.PARTICIPANT);Map<String,Object> preview=service.previewParticipantsForEvent(event);
        assertEquals(1,preview.get("existingCount"));
        existing.setMobilePhone("08999999999");
        assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(event,preview)).getStatusCode().value());
        verify(databases,never()).save(any());assertEquals(1,contacts.size());
    }

    @Test void phoneKeysNormalizeIndonesianFormsAndCanMatchOnlyTheStoredNormalizedPhone() {
        for (String phone : List.of("0812-3456-789", "+62 8123456789", "628123456789", "8123456789", "00628123456789")) {
            assertEquals("628123456789",ReflectionTestUtils.invokeMethod(service,"normalizePhone",phone));
        }
        assertEquals("819012345678",ReflectionTestUtils.invokeMethod(service,"normalizePhone","+81 (90) 1234-5678"));
        assertEquals("44123456789",ReflectionTestUtils.invokeMethod(service,"normalizePhone","+44 123456789"));
        for (String phone : new String[]{null,"","0","000","62","+62"}) {
            assertEquals("",ReflectionTestUtils.invokeMethod(service,"normalizePhone",phone));
        }
        Database existing=contact(9,"Student","Example","old@campus.ac.id",DatabaseDestination.PARTICIPANT);
        existing.setNormalizedPhone("+628123456789");contacts.add(existing);
        Map<String,Object> incoming=row("Student","Example","new@gmail.com",null);
        ((Map<String,Object>)incoming.get("user_profile")).put("phone","0812 3456 789");
        doReturn(List.of(incoming)).when(service).getEventParticipants(1L,EmsSource.V2);
        Event event=event(1,DatabaseDestination.PARTICIPANT);Map<String,Object> preview=service.previewParticipantsForEvent(event);
        assertEquals(0,preview.get("newCount"));assertEquals(1,preview.get("existingCount"));apply(event,preview);
        assertEquals(1,contacts.size());assertEquals(1,links.size());assertSame(existing,links.get(0).getDatabase());
        assertEquals("08123456789",existing.getMobilePhone());
    }

    @Test void unnamedPhoneOnlyAndUnnamedEmailFollowExplicitDestinationWithoutFakeValues() {
        Map<String,Object> phone = row("", "", "", null);
        ((Map<String,Object>)phone.get("user_profile")).put("phone", "081400003333, 081500004444");
        Map<String,Object> email = row("", "", "unnamed@example.invalid", null);
        doReturn(List.of(phone, email)).when(service).getEventParticipants(1L,EmsSource.V2);
        Event event = event(1, DatabaseDestination.PARTICIPANT);
        Map<String,Object> preview = service.previewParticipantsForEvent(event);
        assertEquals(2, preview.get("newCount")); assertEquals(0, preview.get("rejectedCount"));
        assertEquals(2, apply(event, preview).get("count")); assertEquals(2, contacts.size());
        assertTrue(contacts.stream().allMatch(contact -> contact.getFirstName().isBlank()));
        assertEquals("081400003333, 081500004444", contacts.get(0).getMobilePhone());
        assertTrue(contacts.get(0).getEmails() == null || contacts.get(0).getEmails().isEmpty());
        apply(event, service.previewParticipantsForEvent(event)); assertEquals(2, contacts.size()); assertEquals(2, links.size());
        verifyNoInteractions(companies);
    }

    @Test void studentSyncPreservesAvailableOrganizationFieldsWithoutMastersAndKeepsStaffValuesOnRepeat() {
        Map<String,Object> incoming = row("Alex", "Student", "alex@campus.ac.id", "Campus Institute");
        Map<String,Object> profile = (Map<String,Object>) incoming.get("user_profile");
        profile.putAll(Map.of("organization_group_name", "Campus Holding", "organization_brand_name", "Campus Brand",
                "organization_address", "Campus Address", "organization_office_phone", "021-111 ext 7", "organization_industry", "Education",
                "organization_size_revenue", "Revenue band", "organization_size_employee", "Employee band", "organization_branch_name", "Central Campus",
                "organization_postal_code", "12345", "organization_website", "https://campus.invalid"));
        profile.put("job_title", "Coordinator"); profile.put("job_level", "Staff"); profile.put("division", "Student Services");
        profile.put("registered_salutation", "Ms"); profile.put("phone", "081200001111, 081300002222");
        doReturn(List.of(incoming)).when(service).getEventParticipants(1L, EmsSource.V2);
        Event event = event(1, DatabaseDestination.PARTICIPANT);
        apply(event, service.previewParticipantsForEvent(event));
        Database contact = contacts.get(0);
        assertEquals("Campus Institute", contact.getInstitutionName()); assertEquals("Ms", contact.getSalutation());
        assertEquals(PositionLevel.STAFF, contact.getPositionLevel()); assertEquals("Coordinator", contact.getJobTitle());
        assertEquals("Student Services", contact.getSpecialityDivision()); assertEquals("Campus Holding", contact.getOrganizationGroupName());
        assertEquals("Campus Brand", contact.getOrganizationBrandName()); assertEquals("Campus Address", contact.getOrganizationAddress());
        assertEquals("021-111 ext 7", contact.getOrganizationOfficePhone()); assertEquals("Education", contact.getOrganizationIndustry());
        assertEquals("Revenue band", contact.getOrganizationSizeRevenue()); assertEquals("Employee band", contact.getOrganizationSizeEmployee());
        assertEquals("Central Campus", contact.getOrganizationBranchName()); assertEquals("12345", contact.getOrganizationPostalCode());
        assertEquals("https://campus.invalid", contact.getOrganizationWebsite()); assertNull(contact.getCompany()); assertNull(contact.getBranch());
        assertEquals("081200001111, 081300002222", contact.getMobilePhone());
        contact.setOrganizationAddress("Staff revised address"); contact.setJobTitle("Staff revised role");
        Map<String,Object> preview = service.previewParticipantsForEvent(event);
        contact.setOrganizationBrandName("Staff revised brand");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> apply(event, preview)).getStatusCode().value());
        apply(event, service.previewParticipantsForEvent(event));
        assertEquals("Staff revised address", contact.getOrganizationAddress()); assertEquals("Staff revised role", contact.getJobTitle());
        assertEquals("Staff revised brand", contact.getOrganizationBrandName()); assertEquals(1, contacts.size()); assertEquals(1, links.size());
        verifyNoInteractions(companies);
    }

    @Test void oversizedStudentOrganizationIsHeldDuringPreviewBeforeAnyWrites() {
        Map<String,Object> incoming = row("Alex", "Student", "alex@campus.ac.id", "Campus Institute");
        ((Map<String,Object>)incoming.get("user_profile")).put("office_phone", "x".repeat(51));
        doReturn(List.of(incoming)).when(service).getEventParticipants(1L, EmsSource.V2);
        Event event = event(1, DatabaseDestination.PARTICIPANT);
        Map<String,Object> preview = service.previewParticipantsForEvent(event);
        assertEquals(1, preview.get("rejectedCount")); assertEquals(0, apply(event, preview).get("count"));
        verify(databases, never()).save(any()); verifyNoInteractions(companies);
    }

    @Test void clientDecisionsSurviveWhilePreEventFollowsEveryEmsStateAndRepeatSync() {
        for (DatabaseDestination destination : List.of(DatabaseDestination.COMPANY, DatabaseDestination.PARTICIPANT)) {
            for (String client : List.of("pending", "approve", "decline")) {
                for (String preEvent : List.of("pending", "approve", "decline")) {
                    for (String state : List.of("pending", "accepted", "checked_in", "declined")) {
                        contacts.clear(); links.clear(); history.clear();
                        Event event = event(1, destination);
                        Database contact = contact(9, "Approval", "Example", "approval@company.example", destination);
                        contacts.add(contact);
                        EventParticipant link = EventParticipant.builder().id(10L).event(event).database(contact)
                                .confirmationStatus(client).preEventApprovalStatus(preEvent)
                                .notes("[Origin: Request] [PIC: Test Agent] [PreEventApproval: " + preEvent + "] Staff note").build();
                        links.add(link);
                        doReturn(List.of(approvalRow(state))).when(service).getEventParticipants(1L, EmsSource.V2);
                        for (int repeat = 0; repeat < 2; repeat++) {
                            assertEquals(1, apply(event, service.previewParticipantsForEvent(event)).get("count"));
                            String context = destination + "/" + client + "/" + preEvent + "/" + state;
                            assertEquals(client, link.getConfirmationStatus(), context);
                            assertEquals(expectedApproval(state), link.getPreEventApprovalStatus(), context);
                            assertEquals(expectedApproval(state), link.getEmsApprovalStatus(), context);
                            assertTrue(link.getNotes().contains("[PreEventApproval: " + expectedApproval(state) + "]"), context);
                            assertTrue(link.getNotes().contains("[Origin: Request]"), context);
                            assertTrue(link.getNotes().contains("[PIC: Test Agent]"), context);
                            assertTrue(link.getNotes().contains("Staff note"), context);
                            assertEquals(state.equals("declined"), link.getNotes().contains("[EMS Declined]"), context);
                            assertEquals(state.equals("declined") ? AttendanceStatus.cancelled
                                    : state.equals("checked_in") ? AttendanceStatus.attended : AttendanceStatus.registered,
                                    link.getAttendanceStatus(), context);
                            assertEquals(1, contacts.size()); assertEquals(1, links.size());
                        }
                    }
                }
            }
        }
        verifyNoInteractions(companies);
    }

    @Test void newEmsParticipantsKeepClientPendingWhileTheirPreEventApprovalFollowsEms() {
        for (DatabaseDestination destination : List.of(DatabaseDestination.COMPANY, DatabaseDestination.PARTICIPANT)) {
            for (String state : List.of("pending", "accepted", "checked_in", "declined")) {
                contacts.clear(); links.clear(); history.clear();
                Event event = event(1, destination);
                doReturn(List.of(approvalRow(state))).when(service).getEventParticipants(1L, EmsSource.V2);
                apply(event, service.previewParticipantsForEvent(event));
                EventParticipant link = links.get(0);
                assertEquals("pending", link.getConfirmationStatus());
                assertEquals(expectedApproval(state), link.getPreEventApprovalStatus());
                assertEquals(expectedApproval(state), link.getEmsApprovalStatus());
                assertTrue(link.getNotes().contains("[PreEventApproval: " + expectedApproval(state) + "]"));
                assertEquals(state.equals("declined"), link.getNotes().contains("[EMS Declined]"));
                apply(event, service.previewParticipantsForEvent(event));
                assertEquals(1, contacts.size()); assertEquals(1, links.size());
                assertEquals("pending", link.getConfirmationStatus());
                assertEquals(expectedApproval(state), link.getPreEventApprovalStatus());
            }
        }
    }

    @Test void confirmedSyncOverwritesLegacyAndCurrentManualPreEventValues() {
        for (String legacy : List.of("approve", "decline", "declined", "pending")) {
            contacts.clear(); links.clear(); history.clear();
            Event event = event(1, DatabaseDestination.COMPANY);
            Database contact = contact(9, "Approval", "Example", "approval@company.example", DatabaseDestination.COMPANY);
            contacts.add(contact);
            EventParticipant link = EventParticipant.builder().id(10L).event(event).database(contact)
                    .confirmationStatus("decline").preEventApprovalStatus(null)
                    .notes("[preEventApproval: " + legacy.toUpperCase(Locale.ROOT) + "] Staff note").build();
            links.add(link);
            assertNull(link.getEmsApprovalStatus());
            assertNull(link.getPreEventApprovalStatus(), "An absent stored decision stays unset until manual input or sync");
            assertTrue(link.getNotes().contains(legacy.toUpperCase(Locale.ROOT)), "Legacy manual notes are retained before sync");
            doReturn(List.of(approvalRow("accepted"))).when(service).getEventParticipants(1L, EmsSource.V2);
            apply(event, service.previewParticipantsForEvent(event));
            assertEquals("decline", link.getConfirmationStatus());
            assertEquals("approve", link.getPreEventApprovalStatus());
            link.setPreEventApprovalStatus("pending");
            link.setNotes("[PreEventApproval: decline] Staff note");
            doReturn(List.of(approvalRow("pending"))).when(service).getEventParticipants(1L, EmsSource.V2);
            apply(event, service.previewParticipantsForEvent(event));
            assertEquals("pending", link.getPreEventApprovalStatus());
            assertTrue(link.getNotes().contains("[PreEventApproval: pending]"));
            link.setPreEventApprovalStatus(" DECLINED ");
            doReturn(List.of(approvalRow("declined"))).when(service).getEventParticipants(1L, EmsSource.V2);
            apply(event, service.previewParticipantsForEvent(event));
            assertEquals("decline", link.getConfirmationStatus());
            assertEquals("decline", link.getPreEventApprovalStatus());
            assertTrue(link.getNotes().contains("[PreEventApproval: decline]"));
        }
    }

    @Test void emsDeclineMetadataClearsOnRestorationWithoutRestoringClientApproval() {
        Event event = event(1, DatabaseDestination.COMPANY);
        Map<String,Object> declinedWithCheckIn = approvalRow("declined");
        declinedWithCheckIn.put("checked_in_at", "2026-11-10T01:00:00Z");
        doReturn(List.of(declinedWithCheckIn)).when(service).getEventParticipants(1L, EmsSource.V2);
        apply(event, service.previewParticipantsForEvent(event));
        EventParticipant link = links.get(0);
        assertEquals(AttendanceStatus.cancelled, link.getAttendanceStatus());
        assertNull(link.getReminderHariH());
        link.setConfirmationStatus("decline"); link.setPreEventApprovalStatus("approve");
        doReturn(List.of(approvalRow("accepted"))).when(service).getEventParticipants(1L, EmsSource.V2);
        apply(event, service.previewParticipantsForEvent(event));
        assertEquals(AttendanceStatus.registered, link.getAttendanceStatus());
        assertFalse(link.getNotes().contains("[EMS Declined]"));
        assertEquals("decline", link.getConfirmationStatus());
        assertEquals("approve", link.getPreEventApprovalStatus());
    }

    @Test void approvalMovesBothDirectionsOnTheSameProfileAndNeverRestoresClientApproval() {
        Event event = event(1, DatabaseDestination.COMPANY);
        doReturn(List.of(approvalRow("checked_in"))).when(service).getEventParticipants(1L, EmsSource.V2);
        apply(event, service.previewParticipantsForEvent(event));
        EventParticipant link = links.get(0);
        Long originalId = link.getId();
        link.setConfirmationStatus("decline");
        link.setReminderH7("confirm"); link.setReminderH3("tentative"); link.setReminderH1("on_the_way");
        assertEquals("on_location", link.getReminderHariH());
        for (String state : List.of("pending", "declined", "accepted", "pending", "accepted")) {
            Map<String,Object> incoming = approvalRow(state);
            if (state.equals("pending")) {
                incoming.put("verified_at", "2026-11-10T01:00:00Z");
                incoming.put("checked_in_at", "2026-11-10T01:00:00Z");
            }
            doReturn(List.of(incoming)).when(service).getEventParticipants(1L, EmsSource.V2);
            apply(event, service.previewParticipantsForEvent(event));
            assertEquals(originalId, link.getId());
            assertEquals(expectedApproval(state), link.getEmsApprovalStatus());
            assertEquals(expectedApproval(state), link.getPreEventApprovalStatus());
            assertEquals("decline", link.getConfirmationStatus());
            assertEquals("confirm", link.getReminderH7()); assertEquals("tentative", link.getReminderH3()); assertEquals("on_the_way", link.getReminderH1());
            assertEquals(1, contacts.size()); assertEquals(1, links.size());
        }
        assertNull(link.getReminderHariH(), "A removed EMS check-in must not leave stale On Location after acceptance");
        link.setReminderHariH("on_the_way");
        apply(event, service.previewParticipantsForEvent(event));
        assertEquals("on_the_way", link.getReminderHariH(), "Other CRM follow-up choices remain unchanged");
        verifyNoInteractions(companies);
    }

    @Test void everySyncOverwritesManualApprovalEvenWhenTheEmsStateHasNotChanged() {
        for (DatabaseDestination destination : List.of(DatabaseDestination.COMPANY, DatabaseDestination.PARTICIPANT)) {
            for (String client : List.of("pending", "decline")) {
                contacts.clear(); links.clear(); history.clear();
                Event event = event(1, destination);
                doReturn(List.of(approvalRow("accepted"))).when(service).getEventParticipants(1L, EmsSource.V2);
                apply(event, service.previewParticipantsForEvent(event));
                EventParticipant link = links.get(0);
                Long originalId = link.getId();
                link.setConfirmationStatus(client);
                link.setReminderH7("confirm");

                for (String sourceState : List.of("accepted", "pending", "declined")) {
                    doReturn(List.of(approvalRow(sourceState))).when(service).getEventParticipants(1L, EmsSource.V2);
                    apply(event, service.previewParticipantsForEvent(event));
                    String sourceApproval = expectedApproval(sourceState);
                    String manualApproval = sourceApproval.equals("approve") ? "decline" : "approve";
                    link.setPreEventApprovalStatus(manualApproval);
                    link.setNotes("[Origin: EMS Sync] [PIC: Operator] [PreEventApproval: " + manualApproval + "] Staff correction");
                    assertEquals(manualApproval, link.getPreEventApprovalStatus(), "Manual choice takes effect between syncs");
                    assertEquals(sourceApproval, link.getEmsApprovalStatus(), "Manual choice does not forge the last source snapshot");

                    apply(event, service.previewParticipantsForEvent(event));
                    assertEquals(sourceApproval, link.getPreEventApprovalStatus(), "Fresh sync overwrites the manual choice, even with unchanged source data");
                    assertEquals(sourceApproval, link.getEmsApprovalStatus());
                    assertTrue(link.getNotes().contains("[PreEventApproval: " + sourceApproval + "]"));
                    assertTrue(link.getNotes().contains("[PIC: Operator]"));
                    assertTrue(link.getNotes().contains("Staff correction"));
                    assertEquals(client, link.getConfirmationStatus(), "Source sync must not overwrite Client Approval");
                    assertEquals("confirm", link.getReminderH7());
                    assertEquals(originalId, link.getId());
                    assertEquals(1, contacts.size()); assertEquals(1, links.size()); assertEquals(1, history.size());
                }
            }
        }
        verifyNoInteractions(companies);
    }

    @Test void v2RequiresAnExplicitAcceptedStatusWhileLegacyMayUseAValidVerificationTimestamp() {
        for (EmsSource source : EmsSource.values()) {
            for (Object verification : List.of("2026-11-10T01:00:00Z", "not-a-date", false)) {
                contacts.clear(); links.clear(); history.clear();
                Event event = event(1, DatabaseDestination.PARTICIPANT); event.setEmsSource(source);
                Map<String,Object> incoming = new HashMap<>(row("Approval", "Example", "approval@company.example", null));
                incoming.put("verified_at", verification);
                incoming.put("checked_in_at", "2026-11-10T01:00:00Z");
                doReturn(List.of(incoming)).when(service).getEventParticipants(1L, source);
                apply(event, service.previewParticipantsForEvent(event));
                String expected = source == EmsSource.LEGACY && verification.equals("2026-11-10T01:00:00Z") ? "approve" : "pending";
                assertEquals(expected, links.get(0).getEmsApprovalStatus());
                incoming.put("status", "PENDING");
                apply(event, service.previewParticipantsForEvent(event));
                assertEquals("pending", links.get(0).getPreEventApprovalStatus(), "Explicit pending overrides retained dates");
                incoming.put("status", "unexpected");
                apply(event, service.previewParticipantsForEvent(event));
                assertEquals("pending", links.get(0).getPreEventApprovalStatus(), "Unknown state fails closed");
            }
        }
    }

    @Test void conflictingApprovalsForOnePlannedProfileHoldAllRowsRegardlessOfOrder() {
        for (DatabaseDestination destination : List.of(DatabaseDestination.COMPANY, DatabaseDestination.PARTICIPANT)) {
            for (boolean stored : List.of(false, true)) for (boolean reverse : List.of(false, true)) {
                contacts.clear(); links.clear(); history.clear();
                Event event = event(1, destination);
                EventParticipant previous = null;
                if (stored) {
                    Database contact = contact(9, "Approval", "Example", "approval@company.example", destination);
                    contact.getEmails().add(DatabaseEmail.builder().email("alias@company.example").build());
                    contacts.add(contact);
                    previous = EventParticipant.builder().id(10L).event(event).database(contact)
                            .emsApprovalStatus("approve").preEventApprovalStatus("approve").confirmationStatus("decline")
                            .notes("Staff note").build();
                    links.add(previous);
                }
                Map<String,Object> accepted = approvalRow("accepted");
                Map<String,Object> pending = new HashMap<>(row("Approval", "Example", "alias@company.example", null));
                pending.put("status", "PENDING");
                if (!stored) {
                    ((Map<String,Object>) accepted.get("user_profile")).put("phone", "08123456789");
                    ((Map<String,Object>) pending.get("user_profile")).put("phone", "+628123456789");
                }
                doReturn(reverse ? List.of(pending, accepted) : List.of(accepted, pending))
                        .when(service).getEventParticipants(1L, EmsSource.V2);
                Map<String,Object> preview = service.previewParticipantsForEvent(event);
                assertEquals(2, preview.get("rejectedCount"));
                assertEquals(0, preview.get("newCount")); assertEquals(0, preview.get("existingCount"));
                assertTrue(((List<Map<String,Object>>) preview.get("rows")).stream()
                        .allMatch(row -> row.get("message").toString().contains("approval EMS berbeda")));
                assertEquals(0, apply(event, preview).get("count"));
                assertEquals(stored ? 1 : 0, contacts.size()); assertEquals(stored ? 1 : 0, links.size());
                if (previous != null) {
                    assertEquals("approve", previous.getEmsApprovalStatus());
                    assertEquals("decline", previous.getConfirmationStatus());
                    assertEquals("Staff note", previous.getNotes());
                }
            }
        }
        verify(databases, never()).save(any()); verify(emails, never()).save(any()); verify(participants, never()).save(any());
        verifyNoInteractions(companies, activities);
    }

    @Test void anyApprovalBearingSourceChangeInvalidatesPreviewBeforeWrites() {
        for (String field : List.of("status", "declined_at", "verified_at", "checked_in_at")) {
            contacts.clear(); links.clear(); history.clear();
            Event event = event(1, DatabaseDestination.PARTICIPANT);
            Map<String,Object> incoming = approvalRow("pending");
            doReturn(List.of(incoming)).when(service).getEventParticipants(1L, EmsSource.V2);
            Map<String,Object> preview = service.previewParticipantsForEvent(event);
            incoming.put(field, field.equals("status") ? "ACCEPTED" : "2026-11-10T01:00:00Z");
            assertEquals(409, assertThrows(ResponseStatusException.class, () -> apply(event, preview)).getStatusCode().value(), field);
            assertTrue(contacts.isEmpty()); assertTrue(links.isEmpty());
        }
        verify(databases, never()).save(any()); verify(emails, never()).save(any()); verify(participants, never()).save(any());
        verifyNoInteractions(companies, activities);
    }

    @Test void changedSnapshotManualApprovalClientDecisionOrEmsBindingAlsoInvalidatesPreviewBeforeWrites() {
        for (String change : List.of("snapshot", "preEvent", "client", "emsId", "emsSource")) {
            contacts.clear(); links.clear(); history.clear();
            Event event = event(1, DatabaseDestination.PARTICIPANT);
            Database contact = contact(9, "Approval", "Example", "approval@company.example", DatabaseDestination.PARTICIPANT);
            contacts.add(contact);
            EventParticipant link = EventParticipant.builder().id(10L).event(event).database(contact)
                    .emsApprovalStatus("pending").confirmationStatus("pending").build();
            links.add(link);
            doReturn(List.of(approvalRow("accepted"))).when(service).getEventParticipants(1L, EmsSource.V2);
            Map<String,Object> preview = service.previewParticipantsForEvent(event);
            switch (change) {
                case "snapshot" -> link.setEmsApprovalStatus("approve");
                case "preEvent" -> link.setPreEventApprovalStatus("approve");
                case "client" -> link.setConfirmationStatus("decline");
                case "emsId" -> event.setEmsEventId(2L);
                case "emsSource" -> event.setEmsSource(EmsSource.LEGACY);
            }
            assertEquals(409, assertThrows(ResponseStatusException.class, () -> apply(event, preview)).getStatusCode().value(), change);
            assertEquals(1, contacts.size()); assertEquals(1, links.size());
        }
        verify(databases, never()).save(any()); verify(emails, never()).save(any()); verify(participants, never()).save(any());
        verifyNoInteractions(companies, activities);
    }

    @Test void sameCorporateMobileUnderDifferentNamesIsHeldAgainstStoredProfilesAndWithinTheBatch() {
        Database known = contact(9, "Known", "Name", "shared@company.example", DatabaseDestination.COMPANY);
        known.setMobilePhone("08123456789"); contacts.add(known);
        Map<String,Object> alias = row("Other", "Name", "shared@company.example", null);
        ((Map<String,Object>)alias.get("user_profile")).put("phone", "+62 8123456789");
        doReturn(List.of(alias)).when(service).getEventParticipants(1L, EmsSource.V2);
        Event event = event(1, DatabaseDestination.COMPANY);
        Map<String,Object> preview = service.previewParticipantsForEvent(event);
        assertEquals(1, preview.get("rejectedCount"));
        assertEquals(0, apply(event, preview).get("count"));
        contacts.clear();
        Map<String,Object> first = row("First", "Person", "shared@company.example", null);
        ((Map<String,Object>)first.get("user_profile")).put("phone", "08123456789");
        doReturn(List.of(first, alias)).when(service).getEventParticipants(1L, EmsSource.V2);
        preview = service.previewParticipantsForEvent(event);
        assertEquals(2, preview.get("rejectedCount"));
        assertEquals(0, apply(event, preview).get("count"));
        assertTrue(contacts.isEmpty()); assertTrue(links.isEmpty());
        verify(databases, never()).save(any()); verify(participants, never()).save(any());
        verifyNoInteractions(companies);
    }

    @Test void corporateSharedEmailAloneDoesNotMergeDifferentPeople() {
        Database known = contact(9, "Known", "Name", "shared@company.example", DatabaseDestination.COMPANY);
        contacts.add(known);
        doReturn(List.of(row("Other", "Name", "shared@company.example", null)))
                .when(service).getEventParticipants(1L, EmsSource.V2);
        Event event = event(1, DatabaseDestination.COMPANY);
        Map<String,Object> preview = service.previewParticipantsForEvent(event);
        assertEquals(0, preview.get("rejectedCount")); assertEquals(1, preview.get("newCount"));
        assertEquals(1, apply(event, preview).get("count"));
        assertEquals(2, contacts.size()); assertNotSame(known, links.get(0).getDatabase());
    }

    @Test void sharedCompanyGmailKeepsDifferentNamedPhonesSeparateAcrossNewAndRepeatedSyncs() {
        Event event = event(1, DatabaseDestination.COMPANY);
        Map<String,Object> first = namedPhoneRow("First", "Person", "team@gmail.com", "08123456789", "ACCEPTED");
        Map<String,Object> second = namedPhoneRow("Second", "Person", "TEAM@gmail.com", "08139999999", "PENDING");
        Map<String,Object> alternate = namedPhoneRow("First", "Person", "first@gmail.com", "+628123456789", "ACCEPTED");
        doReturn(List.of(first, second, alternate)).when(service).getEventParticipants(1L, EmsSource.V2);
        Map<String,Object> preview = service.previewParticipantsForEvent(event);
        assertEquals(2, preview.get("newCount")); assertEquals(1, preview.get("existingCount")); assertEquals(0, preview.get("rejectedCount"));
        assertTrue(contacts.isEmpty()); verify(databases, never()).save(any()); verify(participants, never()).save(any());
        assertEquals(3, apply(event, preview).get("count")); assertEquals(2, contacts.size()); assertEquals(2, links.size());
        EventParticipant firstLink = links.stream().filter(link -> link.getDatabase().getFirstName().equals("First")).findFirst().orElseThrow();
        EventParticipant secondLink = links.stream().filter(link -> link.getDatabase().getFirstName().equals("Second")).findFirst().orElseThrow();
        assertEquals("approve", firstLink.getPreEventApprovalStatus()); assertEquals("pending", secondLink.getPreEventApprovalStatus());
        assertNotEquals(firstLink.getDatabase().getId(), secondLink.getDatabase().getId());
        assertEquals("08123456789", firstLink.getDatabase().getMobilePhone()); assertEquals("08139999999", secondLink.getDatabase().getMobilePhone());
        Long firstId = firstLink.getId(), secondId = secondLink.getId();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var before = json.valueToTree(contacts);
        firstLink.setConfirmationStatus("decline");
        for (int i = 0; i < 2; i++) {
            Collections.reverse(contacts);
            doReturn(i == 0 ? List.of(second, alternate, first) : List.of(first, second, alternate)).when(service).getEventParticipants(1L, EmsSource.V2);
            preview = service.previewParticipantsForEvent(event);
            assertEquals(3, preview.get("existingCount")); assertEquals(0, preview.get("newCount")); assertEquals(0, preview.get("rejectedCount"));
            assertEquals(3, apply(event, preview).get("count")); assertEquals(2, contacts.size()); assertEquals(2, links.size()); assertEquals(2, history.size());
            assertEquals(firstId, firstLink.getId()); assertEquals(secondId, secondLink.getId());
            assertEquals("decline", firstLink.getConfirmationStatus()); assertEquals("approve", firstLink.getPreEventApprovalStatus());
            assertEquals("pending", secondLink.getPreEventApprovalStatus());
        }
        assertEquals(before, json.valueToTree(contacts));
        verifyNoInteractions(companies);
    }

    @Test void aPublicCompanyMailboxMatchesKnownPeopleByTheirOwnPhonesRegardlessOfOwnerOrder() {
        Database first = contact(5, "First", "Person", "team@gmail.com", DatabaseDestination.COMPANY);
        Database second = contact(6, "Second", "Person", "team@gmail.com", DatabaseDestination.COMPANY);
        first.setMobilePhone("08123456789"); second.setMobilePhone("08139999999"); contacts.addAll(List.of(first, second));
        Map<String,Object> a = namedPhoneRow("First", "Person", "team@gmail.com", "+628123456789", "ACCEPTED");
        Map<String,Object> b = namedPhoneRow("Second", "Person", "team@gmail.com", "08139999999", "DECLINED");
        Event event = event(1, DatabaseDestination.COMPANY);
        for (int i = 0; i < 2; i++) {
            Collections.reverse(contacts);
            doReturn(i == 0 ? List.of(a, b) : List.of(b, a)).when(service).getEventParticipants(1L, EmsSource.V2);
            Map<String,Object> preview = service.previewParticipantsForEvent(event);
            assertEquals(2, preview.get("existingCount")); assertEquals(0, preview.get("rejectedCount")); apply(event, preview);
            assertEquals(2, links.size());
            assertEquals("approve", links.stream().filter(link -> link.getDatabase() == first).findFirst().orElseThrow().getPreEventApprovalStatus());
            assertEquals("decline", links.stream().filter(link -> link.getDatabase() == second).findFirst().orElseThrow().getPreEventApprovalStatus());
        }
        verify(databases, never()).save(any()); verifyNoInteractions(emails, companies);
    }

    @Test void aSharedPublicCompanyMailboxDoesNotPermitMissingOrChangedPersonIdentity() {
        Event event = event(1, DatabaseDestination.COMPANY);
        Database first = contact(5, "First", "Person", "team@gmail.com", DatabaseDestination.COMPANY);
        Database second = contact(6, "Second", "Person", "team@gmail.com", DatabaseDestination.COMPANY);
        first.setMobilePhone("08123456789"); second.setMobilePhone("08139999999"); contacts.addAll(List.of(first, second));
        Map<String,Object> incoming = namedPhoneRow("First", "Person", "team@gmail.com", "08127777777", "ACCEPTED");
        doReturn(List.of(incoming)).when(service).getEventParticipants(1L, EmsSource.V2);
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.previewParticipantsForEvent(event)).getStatusCode().value());
        for (String missing : List.of("source-phone", "source-name", "stored-phone", "stored-name")) {
            first.setFirstName("First"); first.setLastName("Person"); first.setMobilePhone("08123456789"); second.setMobilePhone("08139999999");
            incoming = namedPhoneRow(missing.equals("source-name") ? "" : "First", missing.equals("source-name") ? "" : "Person", "team@gmail.com", missing.equals("source-phone") ? "" : "08123456789", "ACCEPTED");
            if (missing.equals("stored-phone")) second.setMobilePhone(null);
            if (missing.equals("stored-name")) { first.setFirstName(null); first.setLastName(null); }
            doReturn(List.of(incoming)).when(service).getEventParticipants(1L, EmsSource.V2);
            Map<String,Object> preview = service.previewParticipantsForEvent(event);
            assertEquals(1, preview.get("rejectedCount"), missing); assertEquals(0, apply(event, preview).get("count"));
        }
        assertTrue(links.isEmpty()); verify(databases, never()).save(any()); verify(participants, never()).save(any()); verifyNoInteractions(companies, emails, activities);
    }

    @Test void aFreshSharedPublicMailboxRequiresDistinctKnownPhonesForEveryNamedPerson() {
        Event event = event(1, DatabaseDestination.COMPANY);
        for (String missing : List.of("name", "phone")) {
            Map<String,Object> first = namedPhoneRow("First", "Person", "team@gmail.com", "08123456789", "ACCEPTED");
            Map<String,Object> second = namedPhoneRow(missing.equals("name") ? "" : "Second", missing.equals("name") ? "" : "Person", "team@gmail.com", missing.equals("phone") ? "" : "08139999999", "PENDING");
            doReturn(List.of(first, second)).when(service).getEventParticipants(1L, EmsSource.V2);
            Map<String,Object> preview = service.previewParticipantsForEvent(event);
            assertEquals(2, preview.get("rejectedCount"), missing); assertEquals(0, apply(event, preview).get("count"));
        }
        assertTrue(contacts.isEmpty()); verify(databases, never()).save(any()); verify(participants, never()).save(any()); verifyNoInteractions(companies, emails, activities);
    }

    @Test void sharedPublicEmailNeverBypassesDifferentNamesUsingTheSamePhoneOrParticipantScope() {
        Map<String,Object> first = namedPhoneRow("First", "Person", "team@gmail.com", "08123456789", "ACCEPTED");
        Map<String,Object> second = namedPhoneRow("Second", "Person", "team@gmail.com", "+628123456789", "ACCEPTED");
        for (DatabaseDestination destination : List.of(DatabaseDestination.COMPANY, DatabaseDestination.PARTICIPANT)) {
            doReturn(List.of(first, second)).when(service).getEventParticipants(1L, EmsSource.V2);
            Event event = event(1, destination); Map<String,Object> preview = service.previewParticipantsForEvent(event);
            assertEquals(2, preview.get("rejectedCount")); assertEquals(0, apply(event, preview).get("count"));
        }
        ((Map<String,Object>)second.get("user_profile")).put("phone", "08139999999");
        doReturn(List.of(first, second)).when(service).getEventParticipants(1L, EmsSource.V2);
        Event participant = event(1, DatabaseDestination.PARTICIPANT);
        assertEquals(2, service.previewParticipantsForEvent(participant).get("rejectedCount"));
        assertTrue(contacts.isEmpty()); verify(databases, never()).save(any()); verify(participants, never()).save(any()); verifyNoInteractions(companies, emails, activities);
    }

    @Test void sharedPublicMailboxDoesNotAliasANewSameNameOntoAnotherDisjointPhone() {
        Map<String,Object> first = namedPhoneRow("First", "Person", "team@gmail.com", "08123456789", "ACCEPTED");
        Map<String,Object> second = namedPhoneRow("Second", "Person", "team@gmail.com", "08139999999", "ACCEPTED");
        Map<String,Object> changed = namedPhoneRow("First", "Person", "team@gmail.com", "08127777777", "ACCEPTED");
        doReturn(List.of(first, second, changed)).when(service).getEventParticipants(1L, EmsSource.V2);
        Event event = event(1, DatabaseDestination.COMPANY);
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.previewParticipantsForEvent(event)).getStatusCode().value());
        verify(databases, never()).save(any()); verify(participants, never()).save(any()); verifyNoInteractions(companies, emails, activities);
    }

    @Test void aKnownSharedMailboxProfileMayUseEitherOfItsTwoAlreadyStoredNumbers() {
        Database first = contact(5, "First", "Person", "team@gmail.com", DatabaseDestination.COMPANY);
        Database second = contact(6, "Second", "Person", "team@gmail.com", DatabaseDestination.COMPANY);
        first.setMobilePhone("08123456789, 08127777777"); second.setMobilePhone("08139999999"); contacts.addAll(List.of(first, second));
        Map<String,Object> a = namedPhoneRow("First", "Person", "team@gmail.com", "08123456789", "ACCEPTED");
        Map<String,Object> b = namedPhoneRow("Second", "Person", "team@gmail.com", "08139999999", "PENDING");
        Map<String,Object> another = namedPhoneRow("First", "Person", "team@gmail.com", "08127777777", "ACCEPTED");
        doReturn(List.of(a, b, another)).when(service).getEventParticipants(1L, EmsSource.V2);
        Event event = event(1, DatabaseDestination.COMPANY); Map<String,Object> preview = service.previewParticipantsForEvent(event);
        assertEquals(3, preview.get("existingCount")); assertEquals(0, preview.get("rejectedCount")); apply(event, preview);
        assertEquals(2, links.size()); assertEquals("08123456789, 08127777777", first.getMobilePhone());
        verify(databases, never()).save(any()); verifyNoInteractions(companies, emails);
    }

    @Test void aSharedMailboxAliasCannotAddADisjointPhoneToAPhoneMatchedStoredProfile() {
        Database first = contact(5, "First", "Person", "other@gmail.com", DatabaseDestination.COMPANY);
        first.setMobilePhone("08123456789"); contacts.add(first);
        Map<String,Object> a = namedPhoneRow("First", "Person", "team@gmail.com", "08123456789", "ACCEPTED");
        Map<String,Object> b = namedPhoneRow("Second", "Person", "team@gmail.com", "08139999999", "PENDING");
        Map<String,Object> changed = namedPhoneRow("First", "Person", "team@gmail.com", "08127777777", "ACCEPTED");
        Event event = event(1, DatabaseDestination.COMPANY);
        for (List<Map<String,Object>> incoming : List.of(List.of(a, b, changed), List.of(changed, b, a))) {
            doReturn(incoming).when(service).getEventParticipants(1L, EmsSource.V2);
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.previewParticipantsForEvent(event)).getStatusCode().value());
        }
        Map<String,Object> alternate = namedPhoneRow("First", "Person", "other@gmail.com", "08123456789, 08127777777", "ACCEPTED");
        doReturn(List.of(changed, b, alternate)).when(service).getEventParticipants(1L, EmsSource.V2);
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.previewParticipantsForEvent(event)).getStatusCode().value());
        assertEquals("08123456789", first.getMobilePhone()); assertTrue(links.isEmpty());
        verify(databases, never()).save(any()); verify(participants, never()).save(any()); verifyNoInteractions(companies, emails, activities);
    }

    @Test void changingAnotherSharedMailboxOwnersPhoneInvalidatesThePreviewWithoutSyncWrites() {
        Database first = contact(5, "First", "Person", "team@gmail.com", DatabaseDestination.COMPANY);
        Database second = contact(6, "Second", "Person", "team@gmail.com", DatabaseDestination.COMPANY);
        first.setMobilePhone("08123456789"); second.setMobilePhone("08139999999"); contacts.addAll(List.of(first, second));
        doReturn(List.of(namedPhoneRow("First", "Person", "team@gmail.com", "08123456789", "ACCEPTED"))).when(service).getEventParticipants(1L, EmsSource.V2);
        Event event = event(1, DatabaseDestination.COMPANY); Map<String,Object> preview = service.previewParticipantsForEvent(event);
        second.setMobilePhone("08128888888");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> apply(event, preview)).getStatusCode().value());
        assertTrue(links.isEmpty()); verify(databases, never()).save(any()); verify(participants, never()).save(any()); verifyNoInteractions(companies, emails, activities);
    }

    private static Map<String,Object> namedPhoneRow(String first, String last, String email, String phone, String status) {
        Map<String,Object> incoming = new HashMap<>(row(first, last, email, null));
        ((Map<String,Object>)incoming.get("user_profile")).put("phone", phone); incoming.put("status", status); return incoming;
    }

    private Map<String,Object> approvalRow(String state) {
        Map<String,Object> incoming = new HashMap<>(row("Approval", "Example", "approval@company.example", null));
        incoming.put("status", state.equals("checked_in") ? "ACCEPTED" : state.toUpperCase(Locale.ROOT));
        if (state.equals("accepted")) incoming.put("verified_at", "2026-11-10T01:00:00Z");
        if (state.equals("checked_in")) incoming.put("checked_in_at", "2026-11-10T01:00:00Z");
        if (state.equals("declined")) incoming.put("declined_at", "2026-11-10T01:00:00Z");
        return incoming;
    }

    private static String expectedApproval(String state) {
        return state.equals("declined") ? "decline" : state.equals("accepted") || state.equals("checked_in") ? "approve" : "pending";
    }

    private Map<String,Object> apply(Event event,Map<String,Object> preview){return service.syncParticipantsForEvent(event,(String)preview.get("previewToken"));}
    private static Event event(long id,DatabaseDestination destination){return Event.builder().id(id).name("Event "+id).emsEventId(id).emsSource(EmsSource.V2).syncDestination(destination).build();}
    private static Database contact(long id,String first,String last,String email,DatabaseDestination destination){return Database.builder().id(id).firstName(first).lastName(last).databaseDestination(destination).isActive(true).emails(new ArrayList<>(List.of(DatabaseEmail.builder().email(email).build()))).build();}
    private static Map<String,Object> row(String first,String last,String email,String company){Map<String,Object> profile=new HashMap<>();profile.put("registered_name",first);profile.put("registered_last_name",last);profile.put("registered_email",email);if(company!=null)profile.put("company",company);return Map.of("user_profile",profile,"registration_code","EXAMPLE");}
}
