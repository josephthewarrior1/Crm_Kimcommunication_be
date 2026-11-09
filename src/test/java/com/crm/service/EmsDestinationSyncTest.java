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

    private Map<String,Object> apply(Event event,Map<String,Object> preview){return service.syncParticipantsForEvent(event,(String)preview.get("previewToken"));}
    private static Event event(long id,DatabaseDestination destination){return Event.builder().id(id).name("Event "+id).emsEventId(id).emsSource(EmsSource.V2).syncDestination(destination).build();}
    private static Database contact(long id,String first,String last,String email,DatabaseDestination destination){return Database.builder().id(id).firstName(first).lastName(last).databaseDestination(destination).isActive(true).emails(new ArrayList<>(List.of(DatabaseEmail.builder().email(email).build()))).build();}
    private static Map<String,Object> row(String first,String last,String email,String company){Map<String,Object> profile=new HashMap<>();profile.put("registered_name",first);profile.put("registered_last_name",last);profile.put("registered_email",email);if(company!=null)profile.put("company",company);return Map.of("user_profile",profile,"registration_code","EXAMPLE");}
}
