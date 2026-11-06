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

    @Test void existingCompanyProfileAddsParticipantMembershipWithoutChangingCompanyMaster() {
        Company company=Company.builder().id(77L).name("Original Company").industry("Technology").build();
        Database contact=contact(5,"Member","Example","member@company.example",DatabaseDestination.COMPANY);contact.setCompany(company);contacts.add(contact);
        doReturn(List.of(row("Member","Example","member@company.example","Different Institute"))).when(service).getEventParticipants(1L,EmsSource.V2);
        Event event=event(1,DatabaseDestination.PARTICIPANT);apply(event,service.previewParticipantsForEvent(event));
        assertEquals(1,contacts.size());assertEquals(DatabaseDestination.BOTH,contact.getDatabaseDestination());assertSame(company,contact.getCompany());
        assertEquals("Original Company",company.getName());verifyNoInteractions(companies);
    }

    @Test void companyDestinationRejectsGmailAndParticipantOnlyProfilesWithoutAutomaticPromotion() {
        contacts.add(contact(5,"Student","Example","student@campus.ac.id",DatabaseDestination.PARTICIPANT));
        doReturn(List.of(row("Gmail","Person","member@gmail.com","School"),row("Student","Example","student@campus.ac.id","School")))
                .when(service).getEventParticipants(1L,EmsSource.V2);
        Event event=event(1,DatabaseDestination.COMPANY);Map<String,Object> preview=service.previewParticipantsForEvent(event);
        assertEquals(2,preview.get("rejectedCount"));assertEquals(0,preview.get("newCount"));assertEquals(0,apply(event,preview).get("count"));
        assertEquals(DatabaseDestination.PARTICIPANT,contacts.get(0).getDatabaseDestination());verify(databases,never()).save(any());verifyNoInteractions(companies);
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
        assertEquals("0812-3456-789",contacts.get(0).getMobilePhone());
        assertEquals("628123456789",contacts.get(0).getNormalizedPhone());
        assertEquals(Set.of("student@campus.ac.id","student@gmail.com","alumni@campus.ac.id"),contacts.get(0).getEmails().stream().map(DatabaseEmail::getEmail).collect(java.util.stream.Collectors.toSet()));
        Map<String,Object> nextEvent=row("Student","Example","latest@new-campus.ac.id",null);
        ((Map<String,Object>)nextEvent.get("user_profile")).put("phone","8123456789");
        doReturn(List.of(nextEvent)).when(service).getEventParticipants(2L,EmsSource.V2);
        Event second=event(2,DatabaseDestination.PARTICIPANT);Map<String,Object> secondPreview=service.previewParticipantsForEvent(second);
        assertEquals(0,secondPreview.get("newCount"));assertEquals(1,secondPreview.get("existingCount"));apply(second,secondPreview);
        assertEquals(1,contacts.size());assertEquals(2,links.size());assertSame(links.get(0).getDatabase(),links.get(1).getDatabase());
        assertEquals("0812-3456-789",contacts.get(0).getMobilePhone());
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
        assertEquals("0812 3456 789",existing.getMobilePhone());
    }

    private Map<String,Object> apply(Event event,Map<String,Object> preview){return service.syncParticipantsForEvent(event,(String)preview.get("previewToken"));}
    private static Event event(long id,DatabaseDestination destination){return Event.builder().id(id).name("Event "+id).emsEventId(id).emsSource(EmsSource.V2).syncDestination(destination).build();}
    private static Database contact(long id,String first,String last,String email,DatabaseDestination destination){return Database.builder().id(id).firstName(first).lastName(last).databaseDestination(destination).isActive(true).emails(new ArrayList<>(List.of(DatabaseEmail.builder().email(email).build()))).build();}
    private static Map<String,Object> row(String first,String last,String email,String company){Map<String,Object> profile=new HashMap<>();profile.put("registered_name",first);profile.put("registered_last_name",last);profile.put("registered_email",email);if(company!=null)profile.put("company",company);return Map.of("user_profile",profile,"registration_code","EXAMPLE");}
}
