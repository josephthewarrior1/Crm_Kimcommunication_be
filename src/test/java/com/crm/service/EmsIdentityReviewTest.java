package com.crm.service;

import com.crm.domain.Database;
import com.crm.domain.DatabaseDestination;
import com.crm.domain.Event;
import com.crm.domain.EventParticipant;
import com.crm.repository.EventParticipantRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmsIdentityReviewTest {
    private final EventParticipantRepository repository = mock(EventParticipantRepository.class);
    private final Event event = Event.builder().id(1L).emsEventId(14L).syncDestination(DatabaseDestination.COMPANY).build();

    @Test void ownExplicitApproveResolvesTheActiveReviewWithoutChangingTheRawAliasDecline() {
        EventParticipant selected = reviewed(1, "Harry", "Mahardika", "approve", true);
        EventParticipant alias = row(2, "Harry", "Maharsmilo", "decline");
        when(repository.findByEventId(1L)).thenReturn(List.of(selected, alias));

        EmsIdentityReview.refresh(repository, 1L);

        assertTrue(selected.getRawEmsClientDeclineReviewRequired());
        assertFalse(selected.getEmsClientDeclineReviewRequired());
        assertEquals("approve", selected.getConfirmationStatus());
        assertEquals("decline", alias.getConfirmationStatus());
        assertEquals("approve", selected.getPreEventApprovalStatus());
        assertEquals("Registration Company", selected.getEmsCompanyName());
        assertTrue(selected.getEmsCompanyReviewRequired());
        assertFalse(selected.getRawEmsIdentityReviewMessage().contains("tertahan"));
        assertTrue(selected.getRawEmsIdentityReviewMessage().contains("Harry Maharsmilo"));
    }

    @Test void approvingTheLastDeclinedAliasRefreshesStoredReviewImmediatelyAndIsIdempotent() {
        EventParticipant selected = reviewed(1, "Harry", "Mahardika", "pending", true);
        EventParticipant alias = row(2, "Harry", "Maharsmilo", "approve");
        when(repository.findByEventId(1L)).thenReturn(List.of(selected, alias));

        EmsIdentityReview.refresh(repository, 1L);
        assertFalse(selected.getRawEmsClientDeclineReviewRequired());
        assertFalse(selected.getEmsClientDeclineReviewRequired());
        assertFalse(selected.getEmsIdentityReviewMessage().contains("tertahan"));
        assertEquals("pending", selected.getConfirmationStatus());
        assertEquals("approve", alias.getConfirmationStatus());
        EmsIdentityReview.refresh(repository, 1L);
        verify(repository, times(1)).saveAll(any());
    }

    @Test void aNewDeclineOnAnAliasReactivatesReviewWithoutOverwritingEitherDecision() {
        EventParticipant selected = reviewed(1, "Harry", "Mahardika", "pending", false);
        EventParticipant alias = row(2, "Harry", "Maharsmilo", "declined");
        when(repository.findByEventId(1L)).thenReturn(List.of(selected, alias));

        EmsIdentityReview.refresh(repository, 1L);

        assertTrue(selected.getRawEmsClientDeclineReviewRequired());
        assertTrue(selected.getEmsClientDeclineReviewRequired());
        assertEquals("pending", selected.getConfirmationStatus());
        assertEquals("declined", alias.getConfirmationStatus());
        assertEquals("approve", selected.getPreEventApprovalStatus());
    }

    @Test void removedAliasesClearTheirStaleMessageWhileRepeatedNamesAndOtherScopesStayIntact() {
        EventParticipant selected = reviewed(1, "Harry", "Mahardika", "pending", true);
        EventParticipant repeated = reviewed(3, "Williem", "Williem", "approve", false);
        repeated.getDatabase().setMobilePhone("081808232200");
        repeated.setEmsIdentityReviewMessage("Nama berulang pada profil CRM; nama dan HP cocok. Profil asli dipertahankan.");
        EventParticipant personal = reviewed(4, "Other", "Person", "pending", true);
        personal.getDatabase().setDatabaseDestination(DatabaseDestination.PARTICIPANT);
        EventParticipant unmatched = reviewed(5, "Legacy", "Profile", "pending", true);
        unmatched.setEmsApprovalStatus(null);
        when(repository.findByEventId(1L)).thenReturn(List.of(selected, repeated, personal, unmatched));
        unmatched.getDatabase().setMobilePhone("081122334455");

        EmsIdentityReview.refresh(repository, 1L);

        assertFalse(selected.getRawEmsClientDeclineReviewRequired());
        assertNull(selected.getRawEmsIdentityReviewMessage());
        assertTrue(repeated.getRawEmsIdentityReviewMessage().startsWith("Nama berulang"));
        assertTrue(personal.getRawEmsClientDeclineReviewRequired());
        assertTrue(unmatched.getRawEmsClientDeclineReviewRequired());
    }

    private EventParticipant row(long id, String first, String last, String client) {
        return EventParticipant.builder().id(id).event(event)
                .database(Database.builder().id(id).firstName(first).lastName(last).mobilePhone("+6281312345678")
                        .databaseDestination(DatabaseDestination.COMPANY).build())
                .confirmationStatus(client).preEventApprovalStatus("approve").build();
    }

    private EventParticipant reviewed(long id, String first, String last, String client, boolean rawDecline) {
        EventParticipant result = row(id, first, last, client);
        result.setEmsApprovalStatus("approve");
        result.setEmsClientDeclineReviewRequired(rawDecline);
        result.setEmsIdentityReviewMessage("Previous alias details. Ada keputusan Client Decline pada profil terkait; tertahan dari Pre-Event dan Reminder sampai ditinjau.");
        result.setEmsCompanyName("Registration Company");
        result.setEmsCompanyReviewRequired(true);
        return result;
    }
}
