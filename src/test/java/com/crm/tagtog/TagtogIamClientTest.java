package com.crm.tagtog;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class TagtogIamClientTest {
    private HttpServer server;
    private TagtogProperties config;
    private TagtogIamClient client;
    private final AtomicReference<JsonNode> request=new AtomicReference<>();
    private String response="{\"data\":{\"active\":true,\"hasSystemAccess\":true,\"user\":{\"roles\":[\"SUPER_ADMIN\"]}}}";
    private int status=200;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            byte[] body=exchange.getRequestBody().readAllBytes();
            if(body.length>0)request.set(new ObjectMapper().readTree(body));
            byte[] result=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,result.length);exchange.getResponseBody().write(result);exchange.close();
        });server.start();
        config=new TagtogProperties();config.enabled=true;config.clientId="crm-local";config.clientSecret="local-fixture-secret-value-".repeat(2);
        config.issuer="http://127.0.0.1:8181";config.iamBaseUrl="http://127.0.0.1:"+server.getAddress().getPort();client=new TagtogIamClient(config);
    }
    @AfterEach void cleanup(){server.stop(0);}

    @Test void exchangeAndChecksSendOnlyCrmClientCredentialsToPinnedIam() {
        client.exchange("code","verifier","http://127.0.0.1:8188/api/tagtog/callback");
        assertEquals("crm-local",request.get().path("clientId").asText());assertEquals(config.clientSecret,request.get().path("clientSecret").asText());
        client.check("41","session");assertEquals("41",request.get().path("subject").asText());assertEquals("session",request.get().path("ssoSessionId").asText());
    }
    @Test void adminRequiresLiveIdentityCrmAccessAndCentralSuperAdmin() {
        client.requireSuperAdmin("Bearer central-fixture");assertEquals("central-fixture",request.get().path("token").asText());
        for(String data:new String[]{"{\"active\":false,\"hasSystemAccess\":true,\"user\":{\"roles\":[\"SUPER_ADMIN\"]}}",
                "{\"active\":true,\"hasSystemAccess\":false,\"user\":{\"roles\":[\"SUPER_ADMIN\"]}}",
                "{\"active\":true,\"hasSystemAccess\":true,\"user\":{\"roles\":[\"USER\"]}}"}) {
            response="{\"data\":"+data+"}";assertEquals(403,assertThrows(TagtogException.class,()->client.requireSuperAdmin("Bearer central-fixture")).status);
        }
    }
    @Test void upstreamFailureAndMalformedPayloadFailClosedWithoutLeakingBody() {
        status=500;response="private upstream detail";var unavailable=assertThrows(TagtogException.class,()->client.check("41","session"));
        assertEquals(503,unavailable.status);assertFalse(unavailable.getMessage().contains(response));
        status=200;response="{}";assertEquals(503,assertThrows(TagtogException.class,()->client.check("41","session")).status);
        status=401;assertEquals(401,assertThrows(TagtogException.class,()->client.check("41","session")).status);
    }
    @Test void insecureRemoteIamAndMissingClientSecretAreRejected() {
        config.iamBaseUrl="http://remote.example.test";assertEquals(503,assertThrows(TagtogException.class,()->client.check("41","session")).status);
        config.clientSecret="";assertEquals(503,assertThrows(TagtogException.class,()->client.check("41","session")).status);
    }
}
