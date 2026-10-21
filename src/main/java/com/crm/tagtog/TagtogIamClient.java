package com.crm.tagtog;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/** Server-to-server IAM calls; no IAM token is persisted in CRM. */
@Component
public class TagtogIamClient {
    private final TagtogProperties config;
    private final RestClient http;
    public TagtogIamClient(TagtogProperties config) {
        this.config = config;
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public JsonNode exchange(String code, String verifier, String redirectUri) {
        return post("/api/sso/token", Map.of("clientId", config.clientId, "clientSecret", config.clientSecret,
                "code", code, "codeVerifier", verifier, "redirectUri", redirectUri));
    }

    public JsonNode check(String subject, String sessionId) {
        return post("/api/sso/check", Map.of("clientId", config.clientId, "clientSecret", config.clientSecret,
                "subject", subject, "ssoSessionId", sessionId));
    }

    public void requireSuperAdmin(String authorization) {
        config.requireEnabled();
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 8192)
            throw new TagtogException(401, "CENTRAL_LOGIN_REQUIRED", "Central administrator login required");
        try {
            JsonNode access = post("/api/sso/introspect", Map.of("clientId", config.clientId,
                    "clientSecret", config.clientSecret, "token", authorization.substring(7)));
            JsonNode user = access.path("user");
            boolean admin = false;
            for (JsonNode role : user.path("roles"))
                if ("SUPER_ADMIN".equals(role.isTextual() ? role.asText() : role.path("code").asText())) admin = true;
            if (!admin || !access.path("active").asBoolean(false) || !access.path("hasSystemAccess").asBoolean(false))
                throw new TagtogException(403, "CENTRAL_ADMIN_REQUIRED", "Central SUPER_ADMIN role required");
        } catch (TagtogException ex) { throw ex;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError())
                throw new TagtogException(401, "CENTRAL_LOGIN_REQUIRED", "Central administrator login required");
            throw unavailable();
        } catch (RuntimeException ex) { throw unavailable(); }
    }

    public void requireActiveIdentity(String authorization, String subject) {
        config.requireEnabled();
        try {
            JsonNode user = unwrap(http.get().uri(endpoint("/api/users/" + subject))
                    .header(HttpHeaders.AUTHORIZATION, authorization).retrieve().body(JsonNode.class));
            if (!subject.equals(user.path("id").asText()) || !"ACTIVE".equals(user.path("status").asText()))
                throw new TagtogException(403, "IAM_USER_INACTIVE", "Select an active existing central user");
        } catch (TagtogException ex) { throw ex;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404)
                throw new TagtogException(404, "IAM_USER_MISSING", "Central user not found");
            if (ex.getStatusCode().is4xxClientError())
                throw new TagtogException(401, "CENTRAL_LOGIN_REQUIRED", "Central administrator login required");
            throw unavailable();
        } catch (RuntimeException ex) { throw unavailable(); }
    }

    private JsonNode post(String path, Map<String, String> body) {
        config.requireEnabled();
        try {
            return unwrap(http.post().uri(endpoint(path)).body(body).retrieve().body(JsonNode.class));
        } catch (TagtogException ex) { throw ex;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError())
                throw new TagtogException(401, "SSO_INVALID", "Central session is invalid or expired");
            throw unavailable();
        } catch (RuntimeException ex) { throw unavailable(); }
    }

    private URI endpoint(String path) {
        URI uri = URI.create(config.iamBaseUrl);
        boolean local = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()) || "::1".equals(uri.getHost());
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && local))) throw unavailable();
        return URI.create(config.iamBaseUrl.replaceAll("/+$", "") + path);
    }

    private JsonNode unwrap(JsonNode response) {
        if (response == null || !response.hasNonNull("data")) throw unavailable();
        return response.get("data");
    }
    private TagtogException unavailable() {
        return new TagtogException(503, "IAM_UNAVAILABLE", "Central access verification is unavailable");
    }
}
