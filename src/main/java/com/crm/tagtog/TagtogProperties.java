package com.crm.tagtog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TagtogProperties {
    @Value("${tagtog.sso.enabled:${TAGTOG_SSO_ENABLED:false}}") public boolean enabled;
    @Value("${tagtog.sso.iam-base-url:${TAGTOG_IAM_BASE_URL:http://127.0.0.1:8181}}") public String iamBaseUrl;
    @Value("${tagtog.sso.issuer:${TAGTOG_IAM_ISSUER:http://127.0.0.1:8181}}") public String issuer;
    @Value("${tagtog.sso.client-id:${TAGTOG_SSO_CLIENT_ID:crm-local}}") public String clientId;
    @Value("${tagtog.sso.client-secret:${TAGTOG_SSO_CLIENT_SECRET:}}") public String clientSecret;
    @Value("${tagtog.sso.redirect-uri:${TAGTOG_SSO_REDIRECT_URI:http://127.0.0.1:8188/api/tagtog/callback}}") public String redirectUri;

    void requireEnabled() {
        if (!enabled) throw new TagtogException(404, "TAGTOG_DISABLED", "TAGTOG login is disabled");
        if (clientSecret == null || clientSecret.length() < 32 || issuer == null || issuer.isBlank()
                || issuer.length() > 255 || clientId == null || clientId.isBlank())
            throw new TagtogException(503, "IAM_UNAVAILABLE", "Central login is not configured");
    }
}
