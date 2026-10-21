package com.crm.tagtog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import jakarta.servlet.FilterChain;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TagtogAccessFilterTest {
    private TagtogProperties config;
    private TagtogService service;
    private TagtogAccessFilter filter;
    private FilterChain chain;
    @BeforeEach void setup() {
        config=new TagtogProperties();config.enabled=true;service=mock(TagtogService.class);
        filter=new TagtogAccessFilter(config,service,new ObjectMapper());chain=mock(FilterChain.class);
        when(service.authorize(isNull(),anyString(),anyString())).thenThrow(new TagtogException(401,"TAGTOG_LOGIN_REQUIRED","Sign in"));
    }
    @Test void privateApiIncludesPreviouslyUnprotectedDomainAndImportPreview() throws Exception {
        for(String route:new String[]{"/api/databases","/api/personal-email-domains","/api/databases/import/preview","/api/companies"}) {
            var response=request("POST",route,null);assertEquals(401,response.getStatus());assertTrue(response.getContentAsString().contains("TAGTOG_LOGIN_REQUIRED"));
        }
        verifyNoInteractions(chain);
    }
    @Test void credentialAndDevelopmentRoutesCannotBypassCentralLogin() throws Exception {
        for(String route:new String[]{"/api/auth/login","/api/auth/register","/api/users/17/password","/api/users/-1/password","/api/dev/seed"})
            assertEquals(403,request("POST",route,"Bearer "+"t".repeat(43)).getStatus());
        verifyNoInteractions(service,chain);
    }
    @Test void malformedApiPathsDoNotReachAnyController() throws Exception {
        for(String route:new String[]{"/api//tagtog/exchange","/api/auth;%2flogin","/api/../auth/login","/api%2ftagtog/exchange","/api\\tagtog/exchange"})
            assertEquals(400,request("POST",route,null).getStatus());
        verifyNoInteractions(service,chain);
    }
    @Test void onlyNarrowExemptionsReachTheirOwnChecks() throws Exception {
        assertEquals(200,request("POST","/api/tagtog/exchange",null).getStatus());
        assertEquals(200,request("POST","/api/auth/logout",null).getStatus());
        assertEquals(200,request("GET","/api/tagtog/admin/users",null).getStatus());
        assertEquals(200,request("GET","/api/event-participants/emails/track/17",null).getStatus());
        assertEquals(401,request("POST","/api/event-participants/emails/track/17",null).getStatus());
        assertEquals(401,request("GET","/api/tagtog/exchange",null).getStatus());
    }
    @Test void bearerBoundSessionStillPassesToExistingLocalRoleChecks() throws Exception {
        String token="t".repeat(43);var response=request("DELETE","/api/databases/5","Bearer "+token);
        assertEquals(200,response.getStatus());verify(service).authorize(token,"DELETE","/api/databases/5");verify(chain).doFilter(any(),any());
    }
    @Test void disabledModeAndCorsOptionsRemainCompatible() throws Exception {
        assertEquals(200,request("OPTIONS","/api/databases",null).getStatus());
        config.enabled=false;assertEquals(200,request("POST","/api/auth/login",null).getStatus());verifyNoInteractions(service);
    }
    private MockHttpServletResponse request(String method,String path,String auth) throws Exception {
        var request=new MockHttpServletRequest(method,path);if(auth!=null)request.addHeader("Authorization",auth);
        var response=new MockHttpServletResponse();filter.doFilter(request,response,chain);return response;
    }
}
