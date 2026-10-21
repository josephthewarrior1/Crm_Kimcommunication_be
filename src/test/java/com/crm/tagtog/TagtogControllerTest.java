package com.crm.tagtog;

import com.crm.controller.AuthController;
import com.crm.controller.UserController;
import com.crm.domain.AppUser;
import com.crm.domain.Role;
import com.crm.repository.*;
import com.crm.service.SecurityHelper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TagtogControllerTest {
    @Test void mappingEndpointsEnforceCentralAdminEvenThoughExemptFromCrmSessionFilter() throws Exception {
        var service=mock(TagtogService.class);var mvc=standaloneSetup(new TagtogController(service)).build();
        doThrow(new TagtogException(401,"CENTRAL_LOGIN_REQUIRED","Sign in")).when(service).requireAdmin(null);
        mvc.perform(get("/api/tagtog/admin/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tagtog/admin/links")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/tagtog/admin/links/41").contentType("application/json").content("{\"crmUserId\":17}")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/tagtog/admin/links/41")).andExpect(status().isUnauthorized());
        verify(service,never()).users();verify(service,never()).links();verify(service,never()).link(anyString(),anyLong(),any());verify(service,never()).unlink(anyString());
    }

    @Test void successfulExplicitLinkUsesCrmUserIdNotAnEmail() throws Exception {
        var service=mock(TagtogService.class);var mvc=standaloneSetup(new TagtogController(service)).build();
        mvc.perform(put("/api/tagtog/admin/links/41").header("Authorization","Bearer central-fixture")
                .contentType("application/json").content("{\"crmUserId\":17}")).andExpect(status().isNoContent());
        verify(service).requireAdmin("Bearer central-fixture");verify(service).link("41",17L,"Bearer central-fixture");
    }

    @Test void directCredentialControllerCallsCannotBypassSsoMode() {
        var config=new TagtogProperties();config.enabled=true;
        var users=mock(UserRepository.class);var sessions=mock(SessionTokenRepository.class);
        var auth=new AuthController();ReflectionTestUtils.setField(auth,"tagtogConfig",config);
        ReflectionTestUtils.setField(auth,"userRepository",users);ReflectionTestUtils.setField(auth,"sessionTokenRepository",sessions);
        assertEquals(403,auth.login(new AuthController.LoginRequest()).getStatusCode().value());
        assertEquals(403,auth.register(new AuthController.RegisterRequest(),null).getStatusCode().value());
        var controller=new UserController();ReflectionTestUtils.setField(controller,"tagtogConfig",config);
        assertEquals(403,controller.updateUserPassword(17L,new UserController.UpdatePasswordRequest(),null).getStatusCode().value());
        verifyNoInteractions(users,sessions);
    }

    @Test void meUsesSafeDtoAndLogoutIgnoresQueryTokenInSsoMode() {
        var config=new TagtogProperties();config.enabled=true;var security=mock(SecurityHelper.class);var service=mock(TagtogService.class);
        var auth=new AuthController();ReflectionTestUtils.setField(auth,"tagtogConfig",config);
        ReflectionTestUtils.setField(auth,"tagtogService",service);ReflectionTestUtils.setField(auth,"securityHelper",security);
        String token="t".repeat(43);String header="Bearer "+token;
        var user=AppUser.builder().id(17L).username("existing-user").email("local@example.test").password("not-a-real-hash").roles(Set.of(Role.USER)).allowedEventIds(Set.of(88L)).build();
        when(security.getAuthenticatedUser(header)).thenReturn(user);
        Map<?,?> view=(Map<?,?>)auth.getCurrentUser(header).getBody();assertEquals(17L,view.get("id"));assertFalse(view.containsKey("password"));
        assertEquals("not-a-real-hash",user.getPassword());
        assertEquals(200,auth.logout(header,"123").getStatusCode().value());verify(service).logout(token);
        auth.logout(null,"123");verify(service).logout(null);
    }
}
