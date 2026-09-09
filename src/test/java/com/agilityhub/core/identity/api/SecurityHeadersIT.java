package com.agilityhub.core.identity.api;

import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles({"test", "prod"})
class SecurityHeadersIT extends IdentityIntegrationSupport {
    @DynamicPropertySource static void productionProperties(DynamicPropertyRegistry registry) throws Exception {
        registry.add("OIDC_LEARN_CLIENT_SECRET", () -> java.util.UUID.randomUUID().toString());
        var emailKeys = java.security.KeyPairGenerator.getInstance("EC");
        emailKeys.initialize(256);
        String webhookKey = Base64.getEncoder().encodeToString(emailKeys.generateKeyPair().getPublic().getEncoded());
        String emailKey = java.util.UUID.randomUUID().toString();
        registry.add("email.sendgrid.webhook-public-key", () -> webhookKey);
        registry.add("email.sendgrid.api-key", () -> emailKey);
        registry.add("email.platform-from", () -> "sender@example.test");
        registry.add("exports.s3.region", () -> "eu-west-1");
        registry.add("exports.s3.bucket", () -> "fictional-security-test");
        registry.add("exports.s3.access-key", () -> java.util.UUID.randomUUID().toString());
        registry.add("exports.s3.secret-key", () -> java.util.UUID.randomUUID().toString());
        registry.add("spring.data.mongodb.host", () -> "localhost");
        registry.add("spring.data.mongodb.database", () -> "agilityhub_test");
        registry.add("spring.data.mongodb.username", () -> "fixture");
        registry.add("spring.data.mongodb.password", () -> "fixture");
    }

    @Test void E0_T12_productionDocumentationIsDeniedEvenWithAnAuthenticatedPlatformRole() throws Exception {
        for (String path : new String[]{"/api/v1/openapi.json", "/v3/api-docs"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                            .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN"))))
                    .andExpect(status().isForbidden());
        }
    }

    @Test void T_01_25_productionHttpsHeadersCoverPublicOAuthProtectedAndRejectedRequests() throws Exception {
        for (String path : new String[]{"/api/v1/health", "/api/v1/me", "/oauth2/jwks", "/oauth2/token"}) {
            mvc.perform((path.equals("/oauth2/token") ? post(path) : get(path)).secure(true).header("Host", HOST))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                    .andExpect(header().string("Content-Security-Policy",
                            "default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"))
                    .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"))
                    .andExpect(header().doesNotExist("Server"));
        }
        mvc.perform(get("/api/v1/health")).andExpect(header().doesNotExist("Strict-Transport-Security"));
    }
}
