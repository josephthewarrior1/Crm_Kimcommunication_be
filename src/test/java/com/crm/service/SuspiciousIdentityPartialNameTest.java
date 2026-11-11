package com.crm.service;

import com.crm.domain.Database;
import com.crm.domain.DatabaseEmail;
import com.crm.domain.DatabaseDestination;
import com.crm.domain.Event;
import com.crm.domain.FlaggedIdentity;
import com.crm.domain.FlagStatus;
import com.crm.repository.DatabaseEmailRepository;
import com.crm.repository.DatabaseRepository;
import com.crm.repository.FlaggedIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SuspiciousIdentityPartialNameTest {
    private final SuspiciousIdentityService service = new SuspiciousIdentityService();
    private final DatabaseRepository databases = mock(DatabaseRepository.class);
    private final DatabaseEmailRepository emails = mock(DatabaseEmailRepository.class);
    private final FlaggedIdentityRepository flags = mock(FlaggedIdentityRepository.class);

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "databaseRepository", databases);
        ReflectionTestUtils.setField(service, "databaseEmailRepository", emails);
        ReflectionTestUtils.setField(service, "flaggedIdentityRepository", flags);
        when(flags.findByDatabaseIsNull()).thenReturn(List.of());
    }

    @Test void phoneScanAcceptsPartialNamesAndStillFlagsDifferentNames() {
        Database current = Database.builder().id(1L).firstName("Ayu").mobilePhone("08123456789").build();
        Database other = Database.builder().id(2L).firstName(null).lastName("Putri").build();
        when(databases.findByNormalizedPhone("+628123456789")).thenReturn(List.of(other));

        assertDoesNotThrow(() -> service.checkAndFlagDatabase(current));
        ArgumentCaptor<FlaggedIdentity> saved = ArgumentCaptor.forClass(FlaggedIdentity.class);
        verify(flags, times(2)).save(saved.capture());
        assertEquals(List.of(1L, 2L), saved.getAllValues().stream().map(flag -> flag.getDatabase().getId()).toList());
    }

    @Test void personalEmailScanAcceptsPartialNamesAndStillFlagsDifferentNames() {
        Database current = Database.builder().id(1L).firstName("Ayu").build();
        current.setEmails(List.of(DatabaseEmail.builder().email("person@example.test").emailType("personal").build()));
        Database other = Database.builder().id(2L).firstName(null).lastName("Putri").build();
        when(emails.findAllByEmailIgnoreCase("person@example.test"))
                .thenReturn(List.of(DatabaseEmail.builder().database(other).email("person@example.test").build()));

        assertDoesNotThrow(() -> service.checkAndFlagDatabase(current));
        verify(flags, times(2)).save(any(FlaggedIdentity.class));
    }

    @Test void equalNamesRemainCaseInsensitiveAndMissingPartsAreSafe() {
        Database current = Database.builder().id(1L).firstName("AYU").lastName("").mobilePhone("08123456789").build();
        Database other = Database.builder().id(2L).firstName("ayu").lastName(null).build();
        when(databases.findByNormalizedPhone("+628123456789")).thenReturn(List.of(other));

        assertDoesNotThrow(() -> service.checkAndFlagDatabase(current));
        verify(flags, never()).save(any());
    }

    @Test void unlinkedManualFlagsRetainNullStatusClearedAndDestinationRules() {
        Database current = Database.builder().id(1L).databaseDestination(DatabaseDestination.PARTICIPANT)
                .firstName("Ayu").mobilePhone("08123456789")
                .emails(List.of(DatabaseEmail.builder().email("person@example.test").emailType("personal").build())).build();
        Event participantEvent = Event.builder().id(4L).syncDestination(DatabaseDestination.PARTICIPANT).build();
        FlaggedIdentity nullStatus = FlaggedIdentity.builder().id(11L).event(participantEvent).phoneUsed("+62 812-3456-789").build();
        FlaggedIdentity emailMatch = FlaggedIdentity.builder().id(12L).event(participantEvent)
                .emailUsed(" PERSON@example.test ").status(FlagStatus.confirmed).build();
        FlaggedIdentity cleared = FlaggedIdentity.builder().id(13L).event(participantEvent)
                .phoneUsed("08123456789").status(FlagStatus.cleared).build();
        FlaggedIdentity companyFlag = FlaggedIdentity.builder().id(14L).emailUsed("person@example.test")
                .status(FlagStatus.suspected).build();
        FlaggedIdentity unrelated = FlaggedIdentity.builder().id(15L).event(participantEvent)
                .phoneUsed("08139999999").status(FlagStatus.suspected).build();
        when(flags.findByDatabaseIsNull()).thenReturn(List.of(nullStatus, emailMatch, cleared, companyFlag, unrelated));

        service.checkAndFlagDatabase(current);

        assertSame(current, nullStatus.getDatabase()); assertNull(nullStatus.getStatus());
        assertSame(current, emailMatch.getDatabase()); assertEquals(FlagStatus.confirmed, emailMatch.getStatus());
        assertNull(cleared.getDatabase()); assertNull(companyFlag.getDatabase()); assertNull(unrelated.getDatabase());
        verify(flags).save(nullStatus); verify(flags).save(emailMatch);
        verify(flags, times(2)).save(any()); verify(flags, never()).findAll();
    }

    @Test void manualLinkScanDoesNotLoadAlreadyLinkedCompanyProfiles() {
        Database linkedCompany = mock(Database.class);
        FlaggedIdentity linkedFlag = FlaggedIdentity.builder().id(11L).database(linkedCompany)
                .status(FlagStatus.confirmed).build();
        when(flags.findAll()).thenReturn(List.of(linkedFlag));
        Database current = Database.builder().id(1L).databaseDestination(DatabaseDestination.PARTICIPANT).firstName("Ayu").build();

        service.checkAndFlagDatabase(current);

        verify(flags).findByDatabaseIsNull(); verify(flags, never()).findAll();
        verifyNoInteractions(linkedCompany); verify(flags, never()).save(any());
    }
}
