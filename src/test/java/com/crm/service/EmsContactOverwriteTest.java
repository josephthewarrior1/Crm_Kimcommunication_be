package com.crm.service;

import com.crm.domain.*;
import com.crm.domain.Database;
import com.crm.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmsContactOverwriteTest {
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
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Company company = Company.builder().id(9L).name("CRM Organization").industry("Original Industry").address("Original Address").build();
    private final Event event = Event.builder().id(1L).name("Contact overwrite QA").emsEventId(9001314L)
            .emsSource(EmsSource.V2).syncDestination(DatabaseDestination.COMPANY).build();

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "databaseRepository", databases);
        ReflectionTestUtils.setField(service, "databaseEmailRepository", emails);
        ReflectionTestUtils.setField(service, "companyRepository", companies);
        ReflectionTestUtils.setField(service, "eventParticipantRepository", participants);
        ReflectionTestUtils.setField(service, "eventParticipantActivityRepository", activities);
        when(databases.findAll()).thenReturn(contacts);
        when(databases.save(any())).thenAnswer(call -> { Database value = call.getArgument(0); if (value.getId() == null) { value.setId(ids.incrementAndGet()); contacts.add(value); } return value; });
        when(participants.findByEventId(anyLong())).thenAnswer(call -> links.stream().filter(link -> link.getEvent().getId().equals(call.getArgument(0))).toList());
        when(participants.findByDatabaseId(anyLong())).thenAnswer(call -> links.stream().filter(link -> link.getDatabase().getId().equals(call.getArgument(0))).toList());
        when(participants.findByEventIdAndDatabaseId(anyLong(), anyLong())).thenAnswer(call -> links.stream().filter(link -> link.getEvent().getId().equals(call.getArgument(0)) && link.getDatabase().getId().equals(call.getArgument(1))).findFirst());
        when(participants.save(any())).thenAnswer(call -> { EventParticipant value = call.getArgument(0); if (value.getId() == null) { value.setId(ids.incrementAndGet()); links.add(value); } return value; });
        when(emails.existsByDatabaseIdAndEmailIgnoreCase(anyLong(), anyString())).thenAnswer(call -> contacts.stream().filter(contact -> contact.getId().equals(call.getArgument(0))).flatMap(contact -> contact.getEmails().stream()).anyMatch(email -> email.getEmail().equalsIgnoreCase(call.getArgument(1))));
        when(emails.save(any())).thenAnswer(call -> { DatabaseEmail value = call.getArgument(0); if (value.getId() == null) value.setId(ids.incrementAndGet()); return value; });
        when(activities.findByEventParticipantIdOrderByCreatedAtDesc(anyLong())).thenAnswer(call -> history.stream().filter(activity -> activity.getEventParticipant().getId().equals(call.getArgument(0))).toList());
        when(activities.save(any())).thenAnswer(call -> { EventParticipantActivity value = call.getArgument(0); history.add(value); return value; });
        doReturn(List.of()).when(service).getEventParticipants(anyLong(), any());
    }

    @Test void ciwankV2FullNameUpdatesTheExistingPhoneOwnerInPlaceAndRetainsClientDecisionAndMasters() {
        Database owner = contact(5, "Ciwankcyril", "Ciwankcyril", "+6281399738453", DatabaseDestination.COMPANY);
        Database separate = contact(6, "Separate", "Student", "+6281399738453", DatabaseDestination.PARTICIPANT);
        contacts.addAll(List.of(owner, separate));
        EventParticipant link = linked(owner, event, "decline");
        Event otherEvent = Event.builder().id(2L).syncDestination(DatabaseDestination.COMPANY).build();
        EventParticipant otherLink = linked(owner, otherEvent, "approve");
        EventParticipantActivity call = EventParticipantActivity.builder().id(30L).eventParticipant(link).activityType("CALL").notes("Keep existing conversation").build(); history.add(call);
        JsonNode masterBefore = json.valueToTree(company), separateBefore = json.valueToTree(separate);
        source(full("Ciwank Cyril", "siswanto@example.invalid", "081399738453", "2026-11-13T03:00:00Z", Map.of("job_title", "Content Creator", "registered_salutation", "Mr", "company", "Incoming stage name")));
        Map<String,Object> preview = service.previewParticipantsForEvent(event);
        assertEquals(0, preview.get("rejectedCount")); assertEquals(1, preview.get("existingCount")); assertEquals(1, preview.get("contactUpdateCount"));
        Map<?,?> row = row(preview, 0);
        assertEquals(5L, row.get("existingDatabaseId")); assertFalse(((List<?>) row.get("contactUpdates")).isEmpty());
        assertEquals("Ciwankcyril", owner.getFirstName(), "Preview must not write contact data");
        verify(databases, never()).save(any());
        apply(preview);
        assertEquals("Ciwank Cyril", owner.getFirstName()); assertEquals("", Objects.toString(owner.getLastName(), ""));
        assertEquals("Content Creator", owner.getJobTitle()); assertEquals("Mr", owner.getSalutation());
        assertEquals("081399738453", owner.getMobilePhone()); assertEquals("6281399738453", owner.getNormalizedPhone());
        assertEquals(5L, owner.getId()); assertEquals(2, contacts.size()); assertSame(owner, link.getDatabase()); assertSame(owner, otherLink.getDatabase());
        assertEquals("decline", link.getConfirmationStatus()); assertEquals("approve", otherLink.getConfirmationStatus());
        assertEquals("approve", link.getPreEventApprovalStatus()); assertEquals("pending", otherLink.getPreEventApprovalStatus());
        assertEquals(masterBefore, json.valueToTree(company)); assertEquals(separateBefore, json.valueToTree(separate));
        assertEquals(1, history.size()); assertSame(call, history.get(0)); assertEquals("Keep existing conversation", call.getNotes());
        assertSame(company, owner.getCompany()); assertEquals(1, owner.getEmails().size());
        assertEquals("siswanto@example.invalid", owner.getEmails().get(0).getEmail());
        apply(service.previewParticipantsForEvent(event));
        assertEquals(2, contacts.size()); assertEquals(2, links.size()); assertEquals(1, history.size()); assertEquals(1, owner.getEmails().size());
        assertEquals(0, service.previewParticipantsForEvent(event).get("contactUpdateCount")); verifyNoInteractions(companies);
    }

    @Test void explicitFirstLastPairAndTwoNumbersOverwriteOnlyNonblankContactFieldsAndKeepExistingEmails() {
        Database owner = contact(5, "Old", "Name", "08123456789", DatabaseDestination.COMPANY);
        owner.setSalutation("Dr"); owner.setJobTitle("Retained job"); owner.setSpecialityDivision("Original division"); owner.setPositionLevel(PositionLevel.STAFF);
        owner.getEmails().add(address(owner, "old@company.example", "company")); contacts.add(owner); linked(owner, event, "pending");
        Map<String,Object> incoming = pair("New", "Name", "new@gmail.com", "08123456789, +6581234567", "2026-11-13T04:00:00Z", Map.of("registered_salutation", " ", "job_title", "", "division", "New division", "job_level", "Manajerial/Head"));
        source(incoming); apply(service.previewParticipantsForEvent(event));
        assertEquals("New", owner.getFirstName()); assertEquals("Name", owner.getLastName()); assertEquals("Dr", owner.getSalutation()); assertEquals("Retained job", owner.getJobTitle());
        assertEquals("New division", owner.getSpecialityDivision()); assertEquals(PositionLevel.MANAGERIAL_HEAD, owner.getPositionLevel());
        assertEquals("08123456789, +6581234567", owner.getMobilePhone()); assertEquals("628123456789", owner.getNormalizedPhone());
        assertEquals(2, owner.getEmails().size()); assertTrue(owner.getEmails().stream().anyMatch(email -> email.getEmail().equals("old@company.example")));
        DatabaseEmail added = owner.getEmails().stream().filter(email -> email.getEmail().equals("new@gmail.com")).findFirst().orElseThrow();
        assertEquals("personal", added.getEmailType()); assertFalse(added.getIsCorporate());
        // A blank incoming identity cannot identify a contact; retain it through its known mailbox instead.
        ((Map<String,Object>) incoming.get("user_profile")).put("phone", "");
        ((Map<String,Object>) incoming.get("user_profile")).put("registered_name", "");
        ((Map<String,Object>) incoming.get("user_profile")).put("registered_last_name", "");
        source(incoming); apply(service.previewParticipantsForEvent(event));
        assertEquals("New", owner.getFirstName()); assertEquals("Name", owner.getLastName()); assertEquals("08123456789, +6581234567", owner.getMobilePhone());
        assertEquals(2, owner.getEmails().size()); verifyNoInteractions(companies);
    }

    @Test void unchangedV2FullNamePreservesTheExistingFirstAndLastNamePartition() {
        Database owner = contact(5, "Ciwank", "Cyril", "081399738453", DatabaseDestination.COMPANY); contacts.add(owner);
        source(full("Ciwank Cyril", "same@gmail.com", "081399738453", "2026-11-13T05:00:00Z", Map.of("job_title", "Updated role")));
        apply(service.previewParticipantsForEvent(event));
        assertEquals("Ciwank", owner.getFirstName()); assertEquals("Cyril", owner.getLastName()); assertEquals("Updated role", owner.getJobTitle());
        assertEquals(1, contacts.size()); verifyNoInteractions(companies);
    }

    @Test void newestContactSnapshotWinsRegardlessOfRowOrderButBlankNewValuesRetainCrmData() {
        for (boolean reverse : List.of(false, true)) {
            contacts.clear(); links.clear(); history.clear(); clearInvocations(databases, participants, companies, emails, activities);
            Database owner = contact(5, "Same", "Person", "08123456789", DatabaseDestination.COMPANY);
            owner.setJobTitle("CRM retained job"); owner.getEmails().add(address(owner, "known@gmail.com", "personal")); contacts.add(owner); linked(owner, event, "decline");
            Map<String,Object> old = pair("Same", "Person", "known@gmail.com", "08123456789", "2026-11-12T01:00:00Z", Map.of("job_title", "Older EMS job"));
            Map<String,Object> latest = pair("Same", "Person", "extra@gmail.com", "08123456789, 08139999999", "2026-11-13T01:00:00Z", Map.of("job_title", "", "registered_salutation", "Mr"));
            source(reverse ? new Map[]{latest, old} : new Map[]{old, latest});
            Map<String,Object> preview = service.previewParticipantsForEvent(event);
            assertEquals(0, preview.get("rejectedCount")); assertEquals(1, preview.get("contactUpdateCount")); apply(preview);
            assertEquals("08123456789, 08139999999", owner.getMobilePhone()); assertEquals("CRM retained job", owner.getJobTitle()); assertEquals("Mr", owner.getSalutation());
            assertEquals(Set.of("known@gmail.com", "extra@gmail.com"), owner.getEmails().stream().map(DatabaseEmail::getEmail).collect(java.util.stream.Collectors.toSet()));
            assertEquals("decline", links.get(0).getConfirmationStatus());
        }
    }

    @Test void conflictingContactVersionsWithoutAnOrderingTimestampStayHeldButEmailOnlyDifferencesDoNot() {
        Database owner = contact(5, "Same", "Person", "08123456789", DatabaseDestination.COMPANY); contacts.add(owner);
        for (String timestamp : new String[]{null, "2026-11-13T01:00:00Z"}) {
            source(pair("Same", "Person", "one@gmail.com", "08123456789", timestamp, Map.of("job_title", "One")),
                    pair("Same", "Person", "two@gmail.com", "08123456789", timestamp, Map.of("job_title", "Two")));
            Map<String,Object> preview = service.previewParticipantsForEvent(event); assertEquals(2, preview.get("rejectedCount")); assertEquals(0, apply(preview).get("count"));
            assertEquals("Original job", owner.getJobTitle()); assertTrue(links.isEmpty());
        }
        source(pair("Same", "Person", "one@gmail.com", "08123456789", null, Map.of()), pair("Same", "Person", "two@gmail.com", "08123456789", null, Map.of()));
        Map<String,Object> preview = service.previewParticipantsForEvent(event); assertEquals(0, preview.get("rejectedCount")); apply(preview);
        assertEquals(2, owner.getEmails().size()); assertEquals(1, links.size());
    }

    @Test void sharedPhonesAndDisjointEmailAndPhoneOwnersCannotBeOverwrittenByANewName() {
        Database first = contact(5, "First", "Owner", "08123456789", DatabaseDestination.COMPANY);
        Database second = contact(6, "Second", "Owner", "08123456789", DatabaseDestination.COMPANY); contacts.addAll(List.of(first, second));
        source(full("Unrelated Person", "new@gmail.com", "08123456789", null, Map.of()));
        Map<String,Object> preview = service.previewParticipantsForEvent(event); assertEquals(1, preview.get("rejectedCount")); assertEquals(0, apply(preview).get("count"));
        second.setMobilePhone("08139999999"); second.setNormalizedPhone("628139999999"); second.getEmails().add(address(second, "second@gmail.com", "personal"));
        source(full("Unrelated Person", "second@gmail.com", "08123456789", null, Map.of()));
        preview = service.previewParticipantsForEvent(event); assertEquals(1, preview.get("rejectedCount")); assertEquals(0, apply(preview).get("count"));
        assertEquals("First", first.getFirstName()); assertEquals("Second", second.getFirstName()); verify(databases, never()).save(any()); verify(participants, never()).save(any());
    }

    @Test void personalEmailCanResolveAUniqueOwnerButCorporateMailboxAloneNeverRenamesAnotherPerson() {
        Database owner = contact(5, "Old", "Name", "", DatabaseDestination.COMPANY); owner.getEmails().add(address(owner, "known@gmail.com", "personal")); contacts.add(owner);
        source(full("Updated Personal Name", "known@gmail.com", "", null, Map.of())); apply(service.previewParticipantsForEvent(event)); assertEquals("Updated Personal Name", owner.getFirstName());
        owner.getEmails().add(address(owner, "shared@company.example", "company"));
        source(full("Another Person", "shared@company.example", "", null, Map.of()));
        Map<String,Object> preview = service.previewParticipantsForEvent(event);
        assertEquals(1, preview.get("rejectedCount")); assertEquals(0, apply(preview).get("count"));
        assertEquals("Updated Personal Name", owner.getFirstName());
    }

    @Test void replacingAnOldSharedPhoneCannotEraseAClientDeclineReviewUntilTheChosenProfileIsApproved() {
        Database owner = contact(5, "Current", "Person", "08123456789", DatabaseDestination.COMPANY);
        owner.getEmails().add(address(owner, "current@company.example", "company"));
        Database alias = contact(6, "Historical", "Alias", "+628123456789", DatabaseDestination.COMPANY); contacts.addAll(List.of(owner, alias));
        EventParticipant chosen = linked(owner, event, "pending"), declined = linked(alias, event, "decline");
        history.add(EventParticipantActivity.builder().id(30L).eventParticipant(chosen).activityType("CALL").notes("Keep call history").build());
        JsonNode aliasBefore = json.valueToTree(alias);
        source(pair("Current", "Person", "current@company.example", "08139999999", "2026-11-13T05:00:00Z", Map.of()));
        Map<String,Object> preview = service.previewParticipantsForEvent(event);
        assertEquals(0, preview.get("rejectedCount")); assertEquals(true, row(preview, 0).get("clientDeclineReviewRequired"));
        apply(preview);
        assertEquals("08139999999", owner.getMobilePhone()); assertEquals("pending", chosen.getConfirmationStatus());
        assertTrue(chosen.getEmsClientDeclineReviewRequired()); assertEquals("decline", declined.getConfirmationStatus());
        assertEquals(aliasBefore, json.valueToTree(alias)); assertEquals(1, history.size());
        for (int repeat = 0; repeat < 2; repeat++) {
            Map<String,Object> again = service.previewParticipantsForEvent(event);
            assertEquals(true, row(again, 0).get("clientDeclineReviewRequired")); apply(again);
            assertTrue(chosen.getEmsClientDeclineReviewRequired()); assertEquals("pending", chosen.getConfirmationStatus());
            assertEquals(aliasBefore, json.valueToTree(alias)); assertEquals(1, history.size());
        }
        chosen.setConfirmationStatus("approve"); apply(service.previewParticipantsForEvent(event));
        assertFalse(chosen.getEmsClientDeclineReviewRequired()); assertEquals("approve", chosen.getConfirmationStatus()); assertEquals("decline", declined.getConfirmationStatus());
    }

    @Test void provenSharedCompanyGmailStillBelongsToSeparatePeopleAndDoesNotBecomeCorporateCleanEmail() {
        Database first = contact(5, "First", "Person", "08123456789", DatabaseDestination.COMPANY);
        Database second = contact(6, "Second", "Person", "08139999999", DatabaseDestination.COMPANY); contacts.addAll(List.of(first, second));
        source(pair("First", "Person", "team@gmail.com", "08123456789", null, Map.of()),
                pair("Second", "Person", "team@gmail.com", "08139999999", null, Map.of()));
        Map<String,Object> preview = service.previewParticipantsForEvent(event); assertEquals(0, preview.get("rejectedCount")); apply(preview);
        assertEquals(2, contacts.size()); assertEquals(2, links.size());
        for (Database owner : List.of(first, second)) {
            assertEquals(1, owner.getEmails().size()); DatabaseEmail mailbox = owner.getEmails().get(0);
            assertEquals("team@gmail.com", mailbox.getEmail()); assertEquals("company", mailbox.getEmailType()); assertFalse(mailbox.getIsCorporate());
        }
        apply(service.previewParticipantsForEvent(event)); assertEquals(2, contacts.size()); assertEquals(2, links.size());
        assertEquals("First", first.getFirstName()); assertEquals("Second", second.getFirstName());
    }

    @Test void replacingAnotherProfilesPhoneCannotReleaseAnUnselectedParticipantsHistoricClientDeclineHold() {
        Database held = contact(5, "Held", "Person", "08123456789", DatabaseDestination.COMPANY);
        Database changed = contact(6, "Changed", "Person", "08123456789", DatabaseDestination.COMPANY);
        changed.getEmails().add(address(changed, "changed@gmail.com", "personal")); contacts.addAll(List.of(held, changed));
        EventParticipant unselected = linked(held, event, "pending"), selected = linked(changed, event, "decline");
        unselected.setEmsApprovalStatus("approve"); unselected.setPreEventApprovalStatus("approve");
        unselected.setEmsClientDeclineReviewRequired(true);
        unselected.setEmsIdentityReviewMessage("HP on another profile has a historical Client Decline; review required.");
        history.add(EventParticipantActivity.builder().id(30L).eventParticipant(unselected).activityType("CALL").notes("Keep unselected call history").build());
        JsonNode heldBefore = json.valueToTree(held), unselectedBefore = json.valueToTree(unselected), historyBefore = json.valueToTree(history.get(0));
        source(pair("Changed", "Person", "changed@gmail.com", "08139999999", "2026-11-13T05:00:00Z", Map.of()));
        for (int repeat = 0; repeat < 3; repeat++) {
            Map<String,Object> preview = service.previewParticipantsForEvent(event);
            assertEquals(0, preview.get("rejectedCount")); assertEquals(1, apply(preview).get("count"));
            assertEquals("08139999999", changed.getMobilePhone()); assertEquals("decline", selected.getConfirmationStatus());
            assertTrue(unselected.getRawEmsClientDeclineReviewRequired()); assertTrue(unselected.getEmsClientDeclineReviewRequired());
            assertEquals(heldBefore, json.valueToTree(held)); assertEquals(unselectedBefore, json.valueToTree(unselected));
            assertEquals(historyBefore, json.valueToTree(history.get(0))); assertEquals(2, history.size());
        }
        unselected.setConfirmationStatus("approve"); EmsIdentityReview.refresh(participants, event.getId());
        assertFalse(unselected.getEmsClientDeclineReviewRequired());
        apply(service.previewParticipantsForEvent(event));
        assertFalse(unselected.getEmsClientDeclineReviewRequired()); assertEquals("approve", unselected.getConfirmationStatus());
        assertEquals("decline", selected.getConfirmationStatus()); assertEquals(2, contacts.size()); assertEquals(2, links.size());
        assertEquals(heldBefore, json.valueToTree(held)); assertEquals(2, history.size()); verifyNoInteractions(companies);
    }

    @Test void acceptedDuplicateWinsOverPendingInEitherOrderWithoutChangingSourceClientDecisionOrHistory() {
        for (boolean reverse : List.of(false, true)) {
            contacts.clear(); links.clear(); history.clear();
            Database owner = contact(5, "Duplicate", "Participant", "0895332251043", DatabaseDestination.COMPANY);
            Database separate = contact(6, "Protected", "Student", "0895332251043", DatabaseDestination.PARTICIPANT);
            contacts.addAll(List.of(owner, separate));
            EventParticipant link = linked(owner, event, "decline");
            EventParticipantActivity call = EventParticipantActivity.builder().id(40L).eventParticipant(link).activityType("CALL").notes("Keep duplicate participant history").build();
            history.add(call);
            JsonNode companyBefore = json.valueToTree(company), separateBefore = json.valueToTree(separate);
            Map<String,Object> accepted = duplicate("accepted@example.invalid", "ACCEPTED", "2026-11-11T01:00:00Z", "Mr");
            Map<String,Object> pending = duplicate("pending@example.invalid", "PENDING", "2026-11-13T01:00:00Z", "Dr");
            JsonNode sourceBefore = json.valueToTree(List.of(accepted, pending));
            source(reverse ? new Map[]{pending, accepted} : new Map[]{accepted, pending});
            for (int repeat = 0; repeat < 3; repeat++) {
                Map<String,Object> preview = service.previewParticipantsForEvent(event);
                assertEquals(0, preview.get("rejectedCount")); assertEquals(2, preview.get("existingCount")); assertEquals(1, preview.get("uniqueContactCount"));
                for (int index = 0; index < 2; index++) assertEquals("approve", row(preview, index).get("preEventApprovalAfterSync"));
                assertEquals(Set.of("ACCEPTED", "PENDING"), ((List<Map<String,Object>>) preview.get("rows")).stream().map(row -> (String) row.get("emsStatus")).collect(java.util.stream.Collectors.toSet()));
                assertEquals(Set.of("approve", "pending"), ((List<Map<String,Object>>) preview.get("rows")).stream().map(row -> (String) row.get("emsApprovalStatus")).collect(java.util.stream.Collectors.toSet()));
                assertEquals(2, apply(preview).get("count"));
                assertEquals("approve", link.getPreEventApprovalStatus()); assertEquals("approve", link.getEmsApprovalStatus());
                assertEquals("decline", link.getConfirmationStatus()); assertEquals("Mr", owner.getSalutation(), "Newer Pending duplicate must not replace Accepted contact values");
                assertEquals("Content Creator", owner.getJobTitle()); assertEquals(1, links.size()); assertEquals(2, contacts.size());
                assertEquals(2, owner.getEmails().size()); assertSame(company, owner.getCompany());
                assertEquals(companyBefore, json.valueToTree(company)); assertEquals(separateBefore, json.valueToTree(separate));
                assertSame(call, history.get(0)); assertEquals("Keep duplicate participant history", call.getNotes());
                assertEquals("CALL", call.getActivityType()); assertEquals(1, history.size());
                assertEquals(sourceBefore, json.valueToTree(List.of(accepted, pending)), "Sync must not mutate EMS source registrations");
            }
        }
        verifyNoInteractions(companies);
    }

    @Test void acceptedPreferenceDoesNotResolveDeclinesOrIncompleteOrDifferentIdentityEvidence() {
        for (String difference : List.of("declined", "company", "job", "name", "phone", "missingPhone", "missingCompany", "missingJob")) {
            contacts.clear(); links.clear(); history.clear();
            Database owner = contact(5, "Duplicate", "Participant", "0895332251043", DatabaseDestination.COMPANY);
            owner.getEmails().add(address(owner, "pending@example.invalid", "personal")); contacts.add(owner);
            Map<String,Object> accepted = duplicate("accepted@example.invalid", "ACCEPTED", null, "Mr");
            Map<String,Object> other = duplicate("pending@example.invalid", "PENDING", null, "Mr");
            Map<String,Object> profile = (Map<String,Object>) other.get("user_profile");
            switch (difference) {
                case "declined" -> other.put("status", "DECLINED");
                case "company" -> profile.put("company", "Different Organization");
                case "job" -> profile.put("job_title", "Different Role");
                case "name" -> profile.put("registered_name", "Different Person");
                case "phone" -> profile.put("phone", "08139999999");
                case "missingPhone" -> profile.put("phone", "");
                case "missingCompany" -> profile.put("company", "");
                case "missingJob" -> profile.put("job_title", "");
            }
            source(accepted, other);
            Map<String,Object> preview = service.previewParticipantsForEvent(event);
            assertEquals(2, preview.get("rejectedCount"), difference); assertEquals(0, apply(preview).get("count"), difference);
            assertEquals("Original job", owner.getJobTitle(), difference); assertEquals(0, links.size(), difference);
        }
        verifyNoInteractions(companies);
    }

    @Test void conflictingAcceptedSnapshotsStayHeldEvenWithAMatchingPendingDuplicate() {
        Database owner = contact(5, "Duplicate", "Participant", "0895332251043", DatabaseDestination.COMPANY); contacts.add(owner);
        for (String secondSalutation : List.of("Dr", "Mr")) {
            source(duplicate("accepted-one@example.invalid", "ACCEPTED", null, "Mr"),
                    duplicate("accepted-two@example.invalid", "ACCEPTED", null, secondSalutation),
                    duplicate("pending@example.invalid", "PENDING", null, "Mr"));
            Map<String,Object> preview = service.previewParticipantsForEvent(event);
            assertEquals(3, preview.get("rejectedCount")); assertEquals(0, apply(preview).get("count"));
        }
        assertEquals("Original job", owner.getJobTitle()); assertEquals(1, contacts.size()); assertTrue(links.isEmpty());
    }

    @Test void sharedPublicCompanyMailboxKeepsAnotherPersonSeparateFromTheAcceptedDuplicate() {
        Database owner = contact(5, "Duplicate", "Participant", "0895332251043", DatabaseDestination.COMPANY);
        Database another = contact(6, "Another", "Creator", "08139999999", DatabaseDestination.COMPANY);
        DatabaseEmail ownerShared = address(owner, "shared@gmail.com", "company"); ownerShared.setIsCorporate(false); owner.getEmails().add(ownerShared);
        DatabaseEmail shared = address(another, "shared@gmail.com", "company"); shared.setIsCorporate(false); another.getEmails().add(shared);
        contacts.addAll(List.of(owner, another));
        Map<String,Object> first = duplicate("accepted@example.invalid", "ACCEPTED", null, "Mr");
        Map<String,Object> pending = duplicate("shared@gmail.com", "PENDING", null, "Mr");
        Map<String,Object> other = full("Another Creator", "shared@gmail.com", "08139999999", null, Map.of("company", "Source Organization", "job_title", "Content Creator"));
        source(first, pending, other);
        Map<String,Object> preview = service.previewParticipantsForEvent(event);
        assertEquals(0, preview.get("rejectedCount"), preview.get("rows").toString()); assertEquals(2, preview.get("uniqueContactCount")); assertEquals(3, apply(preview).get("count"));
        assertEquals(2, contacts.size()); assertEquals(2, links.size());
        assertEquals(Set.of(owner.getId(), another.getId()), links.stream().map(link -> link.getDatabase().getId()).collect(java.util.stream.Collectors.toSet()));
        assertEquals("Another", another.getFirstName()); assertEquals("Creator", another.getLastName());
        assertEquals("08139999999", another.getMobilePhone()); assertEquals(1, another.getEmails().size()); assertSame(shared, another.getEmails().get(0));
        assertEquals(Set.of("accepted@example.invalid", "shared@gmail.com"), owner.getEmails().stream().map(DatabaseEmail::getEmail).collect(java.util.stream.Collectors.toSet()));
        assertEquals("company", owner.getEmails().stream().filter(email -> email.getEmail().equals("shared@gmail.com")).findFirst().orElseThrow().getEmailType());
    }

    private Map<String,Object> duplicate(String email, String status, String updated, String salutation) {
        Map<String,Object> value = full("Duplicate Participant", email, "0895332251043", updated,
                Map.of("company", "Source Organization", "job_title", "Content Creator", "registered_salutation", salutation));
        value.put("status", status); return value;
    }

    private Database contact(long id, String first, String last, String phone, DatabaseDestination destination) {
        return Database.builder().id(id).firstName(first).lastName(last).mobilePhone(phone).jobTitle("Original job")
                .company(destination == DatabaseDestination.COMPANY ? company : null).institutionName(destination == DatabaseDestination.PARTICIPANT ? "Protected campus" : null)
                .databaseDestination(destination).isActive(true).emails(new ArrayList<>()).build();
    }
    private DatabaseEmail address(Database owner, String email, String type) { return DatabaseEmail.builder().id(ids.incrementAndGet()).database(owner).email(email).emailType(type).isCorporate("company".equals(type)).isPrimary(owner.getEmails().isEmpty()).build(); }
    private EventParticipant linked(Database owner, Event target, String decision) { EventParticipant link = EventParticipant.builder().id(ids.incrementAndGet()).event(target).database(owner).confirmationStatus(decision).preEventApprovalStatus("pending").notes("[PIC: Admin] Preserve human note").build(); links.add(link); return link; }
    private void source(Map<String,Object>... source) { doReturn(Arrays.asList(source)).when(service).getEventParticipants(event.getEmsEventId(), event.getEmsSource()); }
    private Map<String,Object> apply(Map<String,Object> preview) { return service.syncParticipantsForEvent(event, (String) preview.get("previewToken")); }
    private Map<?,?> row(Map<String,Object> preview, int index) { return (Map<?,?>) ((List<?>) preview.get("rows")).get(index); }
    private static Map<String,Object> full(String name, String email, String phone, String updated, Map<String,Object> extra) { return pair(name, null, email, phone, updated, extra); }
    private static Map<String,Object> pair(String first, String last, String email, String phone, String updated, Map<String,Object> extra) {
        Map<String,Object> profile = new LinkedHashMap<>(); profile.put("registered_name", first); if (last != null) profile.put("registered_last_name", last);
        profile.put("registered_email", email); profile.put("phone", phone); profile.putAll(extra);
        Map<String,Object> result = new LinkedHashMap<>(); result.put("user_profile", profile); result.put("status", "ACCEPTED"); result.put("registration_code", "ANONYMOUS");
        if (updated != null) result.put("updated_at", updated); return result;
    }
}
