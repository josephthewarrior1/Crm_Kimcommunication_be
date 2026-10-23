package com.crm.controller;

import com.crm.domain.AppUser;
import com.crm.domain.EmsSource;
import com.crm.domain.Event;
import com.crm.domain.Role;
import com.crm.repository.EventRepository;
import com.crm.service.EmsService;
import com.crm.service.SecurityHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class EventEmsRoutingTest {
    private final EventController controller = new EventController();
    private final EventRepository events = mock(EventRepository.class);
    private final EmsService ems = mock(EmsService.class);
    private final SecurityHelper security = spy(new SecurityHelper());
    private MockMvc mvc;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(controller, "eventRepository", events);
        ReflectionTestUtils.setField(controller, "emsService", ems);
        ReflectionTestUtils.setField(controller, "securityHelper", security);
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        mvc = standaloneSetup(controller).build();
    }

    @Test void defaultSourceAndExistingReadVersusSyncPermissionsArePreserved() throws Exception {
        mvc.perform(get("/api/events/ems-upcoming")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/events/ems-participants/42")).andExpect(status().isUnauthorized());
        assertEquals(401, controller.syncEmsParticipants(1L, null).getStatusCode().value());
        verifyNoInteractions(ems, events);

        authenticate(Role.USER);
        mvc.perform(get("/api/events/ems-upcoming").header("Authorization", "Bearer test"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/events/ems-participants/42").header("Authorization", "Bearer test"))
                .andExpect(status().isOk());
        assertEquals(403, controller.syncEmsParticipants(1L, "Bearer test").getStatusCode().value());
        assertEquals(403, controller.updateEvent(1L, Event.builder().name("Fixture").build(), "Bearer test").getStatusCode().value());
        verify(ems).getUpcomingEvents(EmsSource.LEGACY);
        verify(ems).getEventParticipants(42L, EmsSource.LEGACY);
        verifyNoMoreInteractions(ems);
        verifyNoInteractions(events);
    }

    @Test void sameExternalIdIsDispatchedToTheExplicitSource() throws Exception {
        authenticate(Role.ADMIN);
        for (EmsSource source : EmsSource.values()) {
            var result = List.of(Map.of("id", 42, "source", source.name()));
            when(ems.getUpcomingEvents(source)).thenReturn(result);
            when(ems.getEventParticipants(42L, source)).thenReturn(result);
            var upcoming = mvc.perform(get("/api/events/ems-upcoming").param("source", source.name())
                            .header("Authorization", "Bearer test"))
                    .andExpect(status().isOk());
            var participants = mvc.perform(get("/api/events/ems-participants/42").param("source", source.name())
                            .header("Authorization", "Bearer test"))
                    .andExpect(status().isOk());
            if (source == EmsSource.V2) {
                upcoming.andExpect(jsonPath("$.source").value("V2")).andExpect(jsonPath("$.data[0].id").value(42));
                participants.andExpect(jsonPath("$.source").value("V2")).andExpect(jsonPath("$.data[0].id").value(42));
            } else {
                upcoming.andExpect(jsonPath("$[0].source").value("LEGACY"));
                participants.andExpect(jsonPath("$[0].source").value("LEGACY"));
            }
            verify(ems).getUpcomingEvents(source);
            verify(ems).getEventParticipants(42L, source);
        }
        verifyNoInteractions(events);
    }

    @Test void missingCrmIdDoesNotFallBackToAnExternalIdOrTheFirstLinkedEvent() {
        authenticate(Role.ADMIN);
        assertEquals(404, controller.syncEmsParticipants(9000L, "Bearer test").getStatusCode().value());
        assertEquals(404, controller.updateEvent(9000L, Event.builder().name("Fixture").build(), "Bearer test").getStatusCode().value());
        verify(events, times(2)).findById(9000L);
        verifyNoMoreInteractions(events);
        verifyNoInteractions(ems);
    }

    @Test void adminAndManagerSyncTheRequestedCrmRecordEvenWhenExternalIdsCollide() {
        var legacy = Event.builder().id(1L).emsSource(EmsSource.LEGACY).emsEventId(42L).build();
        var v2 = Event.builder().id(2L).emsSource(EmsSource.V2).emsEventId(42L).build();
        when(events.findById(1L)).thenReturn(Optional.of(legacy));
        when(events.findById(2L)).thenReturn(Optional.of(v2));
        authenticate(Role.ADMIN);
        assertEquals(200, controller.syncEmsParticipants(1L, "Bearer test").getStatusCode().value());
        authenticate(Role.MANAGER);
        assertEquals(200, controller.syncEmsParticipants(2L, "Bearer test").getStatusCode().value());
        verify(ems).syncParticipantsForEvent(legacy);
        verify(ems).syncParticipantsForEvent(v2);
        verify(events).findById(1L);
        verify(events).findById(2L);
        verifyNoMoreInteractions(events, ems);
    }

    @Test void omittedSourceStaysNullInRequestsAndPreservesTheStoredSourceWhileSwitchesUseOnlyTheRequestedId() throws Exception {
        assertNull(new Event().getEmsSource());
        assertNull(Event.builder().name("Fixture").build().getEmsSource());
        Event omitted = new ObjectMapper().readValue("{\"name\":\"Renamed\",\"emsEventId\":42}", Event.class);
        assertNull(omitted.getEmsSource());
        var existing = Event.builder().id(1L).name("Fixture").emsSource(EmsSource.V2).emsEventId(42L).build();
        when(events.findById(1L)).thenReturn(Optional.of(existing));
        authenticate(Role.MANAGER);

        assertEquals(200, controller.updateEvent(1L, omitted, "Bearer test").getStatusCode().value());
        assertEquals(EmsSource.V2, existing.getEmsSource());
        assertEquals(42L, existing.getEmsEventId());
        controller.updateEvent(1L, Event.builder().name("Renamed").emsSource(EmsSource.LEGACY).emsEventId(7L).build(), "Bearer test");
        assertEquals(EmsSource.LEGACY, existing.getEmsSource());
        assertEquals(7L, existing.getEmsEventId());
        controller.updateEvent(1L, Event.builder().name("Renamed").emsSource(EmsSource.V2).build(), "Bearer test");
        assertEquals(EmsSource.V2, existing.getEmsSource());
        assertNull(existing.getEmsEventId());
        verify(events, times(3)).save(existing);
        verifyNoInteractions(ems);
    }

    private void authenticate(Role role) {
        doReturn(AppUser.builder().id(7L).roles(Set.of(role)).build())
                .when(security).getAuthenticatedUser("Bearer test");
    }
}
