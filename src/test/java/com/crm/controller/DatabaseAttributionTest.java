package com.crm.controller;

import com.crm.domain.*;
import com.crm.repository.DatabaseRepository;
import com.crm.service.SecurityHelper;
import com.crm.service.SuspiciousIdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseAttributionTest {
    @Test void requestJsonCannotChooseCreatorOrInputMethod() throws Exception {
        Database contact=new ObjectMapper().readValue("{\"firstName\":\"Budi\",\"createdByUserId\":99,\"entryMethod\":\"excel_import\"}",Database.class);
        assertNull(contact.getCreatedByUserId());assertNull(contact.getEntryMethod());
    }

    @Test void addRecordsTheAuthenticatedCreatorAndUpdateKeepsOriginalAttribution() {
        DatabaseController controller=new DatabaseController();
        DatabaseRepository databases=mock(DatabaseRepository.class);
        SecurityHelper security=mock(SecurityHelper.class);
        AppUser user=AppUser.builder().id(7L).build();
        when(security.getAuthenticatedUser("test")).thenReturn(user);
        when(security.hasAnyRole(user,Role.ADMIN,Role.MANAGER)).thenReturn(true);
        ReflectionTestUtils.setField(controller,"databaseRepository",databases);
        ReflectionTestUtils.setField(controller,"securityHelper",security);
        ReflectionTestUtils.setField(controller,"suspiciousIdentityService",mock(SuspiciousIdentityService.class));
        when(databases.save(any())).thenAnswer(i -> i.getArgument(0));
        Database contact=Database.builder().firstName("Budi").createdByUserId(99L).entryMethod("excel_import").build();
        assertEquals(200,controller.createDatabase(contact,null,"test").getStatusCode().value());
        assertEquals(7L,contact.getCreatedByUserId());assertEquals("manual",contact.getEntryMethod());
        contact.setId(10L);
        assertEquals(400,controller.createDatabase(contact,null,"test").getStatusCode().value());
        when(databases.findById(10L)).thenReturn(Optional.of(contact));
        Database changes=Database.builder().firstName("Budi updated").createdByUserId(9L).entryMethod("excel_import").build();
        assertEquals(200,controller.updateDatabase(10L,changes,null,"test").getStatusCode().value());
        assertEquals(7L,contact.getCreatedByUserId());assertEquals("manual",contact.getEntryMethod());
        verify(databases,times(2)).save(any());
    }
}
