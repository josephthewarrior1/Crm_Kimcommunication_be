package com.crm.controller;

import com.crm.domain.*;
import com.crm.domain.Database;
import com.crm.repository.*;
import com.crm.service.SecurityHelper;
import com.crm.service.SuspiciousIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StrictFlagDestinationTest {
    @Test void createAndUpdateCannotOverwriteOrReassignACompanyFlagUsingParticipantReferences() {
        var controller=new FlaggedIdentityController();var flags=mock(FlaggedIdentityRepository.class);var databases=mock(DatabaseRepository.class);
        var security=mock(SecurityHelper.class);var actor=AppUser.builder().id(7L).build();
        when(security.getAuthenticatedUser("test")).thenReturn(actor);when(security.hasAnyRole(actor,Role.ADMIN,Role.MANAGER)).thenReturn(true);
        ReflectionTestUtils.setField(controller,"flaggedIdentityRepository",flags);ReflectionTestUtils.setField(controller,"databaseRepository",databases);ReflectionTestUtils.setField(controller,"securityHelper",security);
        Database company=Database.builder().id(1L).databaseDestination(DatabaseDestination.COMPANY).isActive(false).build();
        Database participant=Database.builder().id(2L).databaseDestination(DatabaseDestination.PARTICIPANT).isActive(true).build();
        FlaggedIdentity original=FlaggedIdentity.builder().id(10L).database(company).status(FlagStatus.confirmed).evidenceNotes("Original Company restriction").build();
        when(flags.findById(10L)).thenReturn(Optional.of(original));when(databases.findById(2L)).thenReturn(Optional.of(participant));
        assertEquals(400,controller.createFlaggedIdentity(FlaggedIdentity.builder().id(10L).database(participant).status(FlagStatus.cleared).build(),"test").getStatusCode().value());
        assertEquals(409,controller.updateFlaggedIdentity(10L,FlaggedIdentity.builder().database(participant).status(FlagStatus.cleared).build(),"test").getStatusCode().value());
        assertSame(company,original.getDatabase());assertEquals(FlagStatus.confirmed,original.getStatus());assertEquals("Original Company restriction",original.getEvidenceNotes());assertFalse(company.getIsActive());
        verify(flags,never()).save(any());verify(databases,never()).save(any());
    }

    @Test void participantEventAutoLinkSkipsCompanyWithTheSameEmail() {
        var controller=new FlaggedIdentityController();var flags=mock(FlaggedIdentityRepository.class);var databases=mock(DatabaseRepository.class);var events=mock(EventRepository.class);
        var security=mock(SecurityHelper.class);var actor=AppUser.builder().id(7L).build();
        when(security.getAuthenticatedUser("test")).thenReturn(actor);when(security.hasAnyRole(actor,Role.ADMIN,Role.MANAGER)).thenReturn(true);
        ReflectionTestUtils.setField(controller,"flaggedIdentityRepository",flags);ReflectionTestUtils.setField(controller,"databaseRepository",databases);ReflectionTestUtils.setField(controller,"eventRepository",events);ReflectionTestUtils.setField(controller,"securityHelper",security);ReflectionTestUtils.setField(controller,"suspiciousIdentityService",new SuspiciousIdentityService());
        Database company=profile(1,DatabaseDestination.COMPANY),participant=profile(2,DatabaseDestination.PARTICIPANT);
        Event event=Event.builder().id(10L).syncDestination(DatabaseDestination.PARTICIPANT).build();
        when(events.findById(10L)).thenReturn(Optional.of(event));when(databases.findAll()).thenReturn(List.of(company,participant));when(databases.findById(2L)).thenReturn(Optional.of(participant));when(flags.save(any())).thenAnswer(call->call.getArgument(0));
        FlaggedIdentity request=FlaggedIdentity.builder().event(event).emailUsed("same@example.invalid").status(FlagStatus.confirmed).build();
        assertEquals(200,controller.createFlaggedIdentity(request,"test").getStatusCode().value());assertSame(participant,request.getDatabase());assertFalse(participant.getIsActive());assertTrue(company.getIsActive());
        verify(databases,never()).save(company);
    }
    private static Database profile(long id,DatabaseDestination kind){return Database.builder().id(id).databaseDestination(kind).isActive(true).emails(List.of(DatabaseEmail.builder().email("same@example.invalid").build())).build();}
}
