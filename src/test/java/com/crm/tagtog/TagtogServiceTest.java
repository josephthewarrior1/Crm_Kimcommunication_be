package com.crm.tagtog;

import com.crm.domain.*;
import com.crm.repository.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TagtogServiceTest {
    private TagtogProperties config;
    private TagtogIamClient iam;
    private TagtogIdentityRepository identities;
    private SessionTokenRepository sessions;
    private UserRepository users;
    private TagtogService service;
    private AppUser user;
    private TagtogIdentityLink link;
    private static final String TOKEN = "t".repeat(43), SID = "s".repeat(43), CODE = "c".repeat(43), VERIFIER = "v".repeat(43);
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        config = new TagtogProperties(); config.enabled = true; config.issuer = "http://127.0.0.1:8181";
        config.iamBaseUrl = config.issuer; config.clientId = "crm-local"; config.clientSecret = "example-test-value-".repeat(3);
        config.redirectUri = "http://127.0.0.1:8188/api/tagtog/callback";
        iam = mock(TagtogIamClient.class); identities = mock(TagtogIdentityRepository.class);
        sessions = mock(SessionTokenRepository.class); users = mock(UserRepository.class);
        service = new TagtogService(config, iam, identities, sessions, users);
        user = AppUser.builder().id(17L).username("existing-crm-user").email("fixture@example.test").fullName("Local Fixture")
                .password("not-a-real-password-hash").roles(Set.of(Role.MANAGER)).allowedEventIds(Set.of(88L)).build();
        link = new TagtogIdentityLink(); link.setIssuer(config.issuer); link.setSubject("41"); link.setCrmUserId(17L); link.setUpdatedAt(Instant.now());
        link.setLinkVersion("00000000-0000-4000-8000-000000000041");
        when(identities.findByIssuerAndSubject(config.issuer, "41")).thenReturn(Optional.of(link));
        when(users.findById(17L)).thenReturn(Optional.of(user));
        when(iam.exchange(CODE, VERIFIER, config.redirectUri)).thenReturn(JSON.readTree("{\"user\":{\"id\":41},\"ssoSessionId\":\""+SID+"\",\"expiresIn\":900,\"accessToken\":\"never-persist-this\"}"));
        when(iam.check("41", SID)).thenReturn(access(true, true, "CRM", true, true, true, true));
    }

    @Test void exchangePreservesUserAndRolesAndStoresOnlyHashedOpaqueToken() {
        var result = service.exchange(CODE, VERIFIER, config.redirectUri);
        String token = (String) result.get("token");
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        assertEquals(17L, result.get("userId")); assertEquals(Set.of(Role.MANAGER), result.get("roles"));
        assertEquals(Set.of(88L), result.get("allowedEventIds")); assertFalse(result.containsKey("password"));
        assertTrue(((Instant)result.get("expiresAt")).isBefore(Instant.now().plusSeconds(901)));
        var saved = ArgumentCaptor.forClass(SessionToken.class); verify(sessions).save(saved.capture());
        assertEquals(TagtogService.digest(token), saved.getValue().getTokenHash());
        assertNotEquals(token, saved.getValue().getTokenHash()); assertEquals(SID, saved.getValue().getTagtogSessionId());
        assertEquals(config.issuer, saved.getValue().getTagtogIssuer()); assertSame(user, saved.getValue().getUser());
        verify(users, never()).save(any());
    }

    @Test void exchangeNeedsExactCallbackAndPkceBeforeContactingIam() {
        for (String callback : List.of("http://127.0.0.1:8188/other", "https://attacker.example/callback"))
            status(400, () -> service.exchange(CODE, VERIFIER, callback));
        status(400, () -> service.exchange(CODE, "short", config.redirectUri));
        status(400, () -> service.exchange("123", VERIFIER, config.redirectUri));
        verifyNoInteractions(iam, sessions, users);
    }

    @Test void noEmailAutolinkOrAccountCreationWhenIdentityIsMissing() {
        when(identities.findByIssuerAndSubject(config.issuer, "41")).thenReturn(Optional.empty());
        TagtogException error = assertThrows(TagtogException.class, () -> service.exchange(CODE, VERIFIER, config.redirectUri));
        assertEquals(409, error.status); assertEquals("CRM_IDENTITY_UNMAPPED", error.code);
        verifyNoInteractions(users, sessions);
    }

    @Test void rolelessExistingUserIsNotPromoted() {
        user.setRoles(Set.of()); status(403, () -> service.exchange(CODE, VERIFIER, config.redirectUri));
        verify(users, never()).save(any()); verifyNoInteractions(sessions);
    }

    @Test void invalidCentralSessionOrNoCrmGrantCannotIssueSession() throws Exception {
        when(iam.check("41", SID)).thenReturn(access(false, true, "CRM", true, true, true, true));
        status(401, () -> service.exchange(CODE, VERIFIER, config.redirectUri));
        when(iam.check("41", SID)).thenReturn(access(true, false, "CRM", true, true, true, true));
        status(403, () -> service.exchange(CODE, VERIFIER, config.redirectUri));
        verifyNoInteractions(sessions);
    }

    @Test void numericAndMalformedTokensAreRejectedBeforeDatabaseLookup() {
        for (String token : List.of("1", "123", "Bearer "+TOKEN, " "+TOKEN, TOKEN+"x"))
            status(401, () -> service.authorize(token, "GET", "/api/databases"));
        status(401, () -> service.authorize(null, "GET", "/api/databases"));
        verifyNoInteractions(sessions, iam);
    }

    @Test void boundSessionRechecksIamAndCurrentMapping() {
        bound(); assertSame(user, service.authorize(TOKEN, "GET", "/api/databases"));
        verify(iam).check("41", SID);
        link.setCrmUserId(99L); status(401, () -> service.authorize(TOKEN, "GET", "/api/databases"));
        verify(iam, times(1)).check("41", SID);
    }

    @Test void legacyUnboundWrongIssuerRevokedAndExpiredSessionsFailClosed() {
        var session=bound(); session.setTagtogSessionId(null);
        status(401, () -> service.authorize(TOKEN, "GET", "/api/databases"));
        session.setTagtogSessionId(SID); session.setTagtogIssuer("https://other-issuer.example");
        status(401, () -> service.authorize(TOKEN, "GET", "/api/databases"));
        session.setTagtogIssuer(config.issuer); session.setTagtogRevoked(true);
        status(401, () -> service.authorize(TOKEN, "GET", "/api/databases"));
        session.setTagtogRevoked(false); session.setTagtogExpiresAt(Instant.now().minusSeconds(1));
        status(401, () -> service.authorize(TOKEN, "GET", "/api/databases"));
        verifyNoInteractions(iam);
    }

    @Test void remappingToSameUserCannotReviveAnInFlightOldSession() {
        bound(); link.setLinkVersion("00000000-0000-4000-8000-000000000042");
        status(401,()->service.authorize(TOKEN,"GET","/api/databases"));verifyNoInteractions(iam);
    }

    @Test void centralDeactivationRevocationAndOutageNeverFallBackToLocalPermissions() throws Exception {
        bound(); when(iam.check("41", SID)).thenReturn(access(false, true, "CRM", true, true, true, true));
        status(401, () -> service.authorize(TOKEN, "GET", "/api/databases"));
        when(iam.check("41", SID)).thenThrow(new TagtogException(503,"IAM_UNAVAILABLE","Unavailable"));
        status(503, () -> service.authorize(TOKEN, "GET", "/api/databases"));
    }

    @Test void wrongSystemAndSubjectCannotAuthorizeCrm() throws Exception {
        var wrong=access(true,true,"PMS",true,true,true,true);
        status(403,()->TagtogService.requirePermission(wrong,"41","GET","/api/databases"));
        var other=access(true,true,"CRM",true,true,true,true);
        ((com.fasterxml.jackson.databind.node.ObjectNode)other.path("user")).put("id",42);
        status(401,()->TagtogService.requirePermission(other,"41","GET","/api/databases"));
    }

    @Test void workspaceCrudCapsLocalRoleAndPreviewUsesOnlyReadGrant() throws Exception {
        JsonNode readonly=access(true,true,"CRM",true,false,false,false);
        TagtogService.requirePermission(readonly,"41","GET","/api/databases");
        TagtogService.requirePermission(readonly,"41","POST","/api/databases/import/preview");
        for(String method:List.of("POST","PUT","PATCH","DELETE"))
            status(403,()->TagtogService.requirePermission(readonly,"41",method,"/api/databases"));
        var noView=access(true,true,"CRM",false,true,true,true);
        status(403,()->TagtogService.requirePermission(noView,"41","POST","/api/databases"));
        var createOnly=access(true,true,"CRM",true,true,false,false);
        status(403,()->TagtogService.requirePermission(createOnly,"41","POST","/api/unknown-action"));
    }

    @Test void destructiveCleanerAndContactMergeNeedDeleteAsWellAsEdit() throws Exception {
        var noDelete=access(true,true,"CRM",true,true,true,false);
        var editDelete=access(true,true,"CRM",true,false,true,true);
        var deleteOnly=access(true,true,"CRM",true,false,false,true);
        for(String path:List.of("/api/data-cleaner/apply","/api/data-cleaner/merge-groups","/api/databases/merge")) {
            status(403,()->TagtogService.requirePermission(noDelete,"41","POST",path));
            status(403,()->TagtogService.requirePermission(deleteOnly,"41","POST",path));
            TagtogService.requirePermission(editDelete,"41","POST",path);
        }
    }

    @Test void optOutAndConfirmedFlagSideEffectsRequireDeletePermission() throws Exception {
        var noDelete=access(true,true,"CRM",true,true,true,false);
        var full=access(true,true,"CRM",true,true,true,true);
        for(String path:List.of("/api/removal-requests","/api/flagged-identities")) {
            status(403,()->TagtogService.requirePermission(noDelete,"41","POST",path));
            TagtogService.requirePermission(full,"41","POST",path);
        }
        for(String path:List.of("/api/databases/17","/api/flagged-identities/17","/api/removal-requests/17/status")) {
            status(403,()->TagtogService.requirePermission(noDelete,"41","PUT",path));
            status(403,()->TagtogService.requirePermission(noDelete,"41","PATCH",path));
            TagtogService.requirePermission(full,"41","PUT",path);
        }
    }

    @Test void flagReadsAndRemovalRequireMutationGrantsForTheirLegacySideEffects() throws Exception {
        var readonly = access(true, true, "CRM", true, false, false, false);
        var editOnly = access(true, true, "CRM", true, false, true, false);
        var deleteOnly = access(true, true, "CRM", true, false, false, true);
        var editDelete = access(true, true, "CRM", true, false, true, true);
        for (String method : List.of("GET", "HEAD")) {
            for (String path : List.of("/api/flagged-identities", "/api/flagged-identities/list")) {
                status(403, () -> TagtogService.requirePermission(readonly, "41", method, path));
                status(403, () -> TagtogService.requirePermission(editOnly, "41", method, path));
                status(403, () -> TagtogService.requirePermission(deleteOnly, "41", method, path));
                TagtogService.requirePermission(editDelete, "41", method, path);
            }
        }
        TagtogService.requirePermission(readonly, "41", "GET", "/api/flagged-identities/filter-options");
        status(403, () -> TagtogService.requirePermission(deleteOnly, "41", "DELETE", "/api/flagged-identities/17"));
        status(403, () -> TagtogService.requirePermission(editOnly, "41", "DELETE", "/api/flagged-identities/17"));
        TagtogService.requirePermission(editDelete, "41", "DELETE", "/api/flagged-identities/17");
    }

    @Test void readOnlyPostPreviewsDoNotNeedMutationGrants() throws Exception {
        var readonly=access(true,true,"CRM",true,false,false,false);
        for(String path:List.of("/api/databases/import/preview","/api/databases/merge/preview","/api/events/17/participants/import/validate"))
            TagtogService.requirePermission(readonly,"41","POST",path);
    }

    @Test void knownCreateImportAndAssignmentActionsUseTheirAuditedGrantSets() throws Exception {
        var createOnly=access(true,true,"CRM",true,true,false,false);
        TagtogService.requirePermission(createOnly,"41","POST","/api/companies");
        TagtogService.requirePermission(createOnly,"41","POST","/api/databases");
        var createEdit=access(true,true,"CRM",true,true,true,false);
        for(String path:List.of("/api/databases/import","/api/events/17/participants/import","/api/events/17/sync-ems")) {
            status(403,()->TagtogService.requirePermission(createOnly,"41","POST",path));
            TagtogService.requirePermission(createEdit,"41","POST",path);
        }
        var editOnly=access(true,true,"CRM",true,false,true,false);
        TagtogService.requirePermission(editOnly,"41","POST","/api/events/17/assignments/auto-split");
        status(403,()->TagtogService.requirePermission(createEdit,"41","POST","/api/unreviewed-action"));
    }

    @Test void mappingConflictDoesNotOverwriteAnotherIdentity() {
        var other=new TagtogIdentityLink();other.setIssuer(config.issuer);other.setSubject("9");other.setCrmUserId(17L);
        when(identities.findByCrmUserId(17L)).thenReturn(Optional.of(other));
        status(409,()->service.link("41",17L,"Bearer central-fixture"));
        verify(identities,never()).saveAndFlush(any()); verifyNoInteractions(sessions);
    }

    @Test void changingAndDeletingMappingRevokesExistingBoundSessions() {
        var session=bound(); when(sessions.findByTagtogIssuerAndTagtogSubject(config.issuer,"41")).thenReturn(List.of(session));
        service.link("41",17L,"Bearer central-fixture");assertTrue(session.isTagtogRevoked());
        verify(iam).requireActiveIdentity("Bearer central-fixture","41");verify(identities).saveAndFlush(link);
        session.setTagtogRevoked(false);service.unlink("41");assertTrue(session.isTagtogRevoked());verify(identities).delete(link);
    }

    @Test void localSignoutWorksWithoutIamAndNoPasswordCanBeSerialized() throws Exception {
        var session=bound(); service.logout(TOKEN);verify(sessions).delete(session);verifyNoInteractions(iam);
        assertFalse(JSON.writeValueAsString(user).contains("not-a-real-password-hash"));
        assertFalse(TagtogService.userView(user).containsKey("password"));
        assertEquals("not-a-real-password-hash", user.getPassword());
        status(401,()->service.logout("17"));
    }

    @Test void bearerParserRejectsAmbiguityAndDisabledBridgeIsClosed() {
        assertEquals(TOKEN,TagtogService.bearer("Bearer "+TOKEN));
        for(String value:List.of("bearer "+TOKEN,"Bearer  "+TOKEN,"Bearer 1","Bearer "+TOKEN+" "))assertNull(TagtogService.bearer(value));
        config.enabled=false;status(404,()->service.exchange(CODE,VERIFIER,config.redirectUri));verifyNoInteractions(iam,sessions);
    }

    private SessionToken bound() {
        var expires=Instant.now().plusSeconds(600);
        var session=SessionToken.builder().id(100L).user(user).tokenHash(TagtogService.digest(TOKEN)).tagtogIssuer(config.issuer)
                .tagtogSubject("41").tagtogSessionId(SID).tagtogExpiresAt(expires).expiresAt(LocalDateTime.ofInstant(expires,ZoneOffset.UTC)).build();
        session.setTagtogMappingVersion(link.getLinkVersion());
        when(sessions.findByTokenHash(TagtogService.digest(TOKEN))).thenReturn(Optional.of(session));return session;
    }
    private static JsonNode access(boolean active, boolean system, String code, boolean view, boolean create, boolean edit, boolean delete) throws Exception {
        return JSON.readTree("{\"active\":"+active+",\"hasSystemAccess\":"+system+",\"user\":{\"id\":41},\"permissions\":[{\"systemCode\":\""+code+"\",\"featureCode\":\"workspace\",\"canView\":"+view+",\"canCreate\":"+create+",\"canEdit\":"+edit+",\"canDelete\":"+delete+"}]}");
    }
    private static void status(int expected, Runnable action) { assertEquals(expected,assertThrows(TagtogException.class,action::run).status); }
}
