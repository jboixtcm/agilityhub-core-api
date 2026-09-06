package com.agilityhub.core.identity.api;

import com.agilityhub.core.platform.persistence.Club;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CorsIT extends IdentityIntegrationSupport {
    @Test void T_01_25_verifiedClubAndConfiguredPlatformOriginsWorkOnBothSecurityChainsWithoutCredentials() throws Exception {
        for (String origin : new String[]{"https://" + HOST, "https://id.agilitydoghub.com", "https://clubs.agilitydoghub.com",
                "https://clubsadmin.agilitydoghub.com"}) {
            for (String path : new String[]{"/api/v1/branding", "/oauth2/token"}) {
                mvc.perform(options(path).header("Origin", origin).header("Access-Control-Request-Method", "POST")
                                .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
                        .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", origin))
                        .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
            }
            mvc.perform(get("/api/v1/branding").header("Host", HOST).header("Origin", origin))
                    .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", origin))
                    .andExpect(header().string("Access-Control-Expose-Headers", "Retry-After, ETag, Content-Language"));
        }
    }

    @Test void T_01_25_unknownPendingMalformedAndInsecureOriginsHaveNoCorsHeaders() throws Exception {
        for (String origin : new String[]{"https://unknown.example.test", "https://pending." + HOST,
                "https://" + HOST + ".evil.example.test", "http://" + HOST, "https://" + HOST + ":8443", "null",
                "https://user@" + HOST, "https://" + HOST + "/", "https://" + HOST + "?secret=hidden",
                "https://" + HOST + "#fragment", "https://[broken"}) {
            for (String path : new String[]{"/oauth2/token", "/api/v1/branding"}) {
                mvc.perform(options(path).header("Origin", origin).header("Access-Control-Request-Method", "POST"))
                        .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                                .as("Preflight from %s to %s", origin, path).isEqualTo(403))
                        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
            }
            mvc.perform(get("/api/v1/branding").header("Host", "b.example.test").header("Origin", origin))
                    .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }

    @Test void T_01_25_domainRemovalInvalidatesCachedCorsPermissionAndNonCorsCallsStillWork() throws Exception {
        mvc.perform(get("/api/v1/branding").header("Host", HOST)).andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options("/oauth2/token").header("Origin", "https://" + HOST)
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isOk());
        mongo.updateFirst(Query.query(Criteria.where("_id").is("club-a")),
                new Update().set("domains.0.status", Club.DomainStatus.PENDING), Club.class);
        hosts.invalidate();
        mvc.perform(options("/oauth2/token").header("Origin", "https://" + HOST)
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(get("/api/v1/health").secure(true)).andExpect(header().doesNotExist("Strict-Transport-Security"));
    }
}
