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
        var key = new RSAKeyGenerator(2048).generate().toPrivateKey();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{10})
                .encodeToString(key.getEncoded()) + "\n-----END PRIVATE KEY-----";
        registry.add("identity.jwk-pem", () -> pem);
        registry.add("spring.data.mongodb.host", () -> "localhost");
        registry.add("spring.data.mongodb.database", () -> "agilityhub_test");
        registry.add("spring.data.mongodb.username", () -> "fixture");
        registry.add("spring.data.mongodb.password", () -> "fixture");
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
