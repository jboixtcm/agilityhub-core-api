package com.agilityhub.core.identity.api;

import com.agilityhub.core.platform.persistence.SecurityEvent;
import com.agilityhub.core.shared.application.SecurityEvents;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** S14 R-14-17 / T-14-21 (E11-T01): security events carry the attempted address only as its keyed `emailHash`, and the user agent. */
@TestPropertySource(properties = "core.security.rate-limits.enabled=true")
class SecurityEventHashIT extends IdentityIntegrationSupport {
    @BeforeEach void clearEvents() { mongo.remove(new Query(), SecurityEvent.class); }

    @Test void T_14_21_R_14_17_sixFailedLoginsWriteSixHashedFailuresAndOneLock() throws Exception {
        for (int attempt = 0; attempt < 6; attempt++) {
            mvc.perform(post("/oauth2/token").header("Host", HOST).header("User-Agent", "Fictional Browser 1.0").contentType("application/x-www-form-urlencoded")
                    .param("grant_type", "password").param("username", "ADMIN@example.test").param("password", "a-wrong-password")
                    .with(request -> { request.setRemoteAddr("198.51.100.20"); return request; }));
        }
        var events = mongo.findAll(SecurityEvent.class);
        assertThat(events).filteredOn(event -> event.type() == SecurityEvents.Type.LOGIN_FAILED).hasSize(6);
        assertThat(events).filteredOn(event -> event.type() == SecurityEvents.Type.LOGIN_LOCKED).hasSize(1);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.accountId()).isEqualTo("account-a");
            assertThat(event.emailHash()).isNotBlank().isEqualTo(events.getFirst().emailHash());
            assertThat(event.userAgent()).isEqualTo("Fictional Browser 1.0");
            assertThat(event.ip()).isEqualTo("198.51.100.20");
            assertThat(event.route()).isEqualTo("/oauth2/token");
        });
        assertThat(mongo.getCollection("security_events").find().into(new java.util.ArrayList<>()).toString())
                .doesNotContain("example.test", "a-wrong-password");
        // An unknown address hashes the same way and differently from the known one.
        login(HOST, "absent@example.test", "a-wrong-password").andExpect(status().isUnauthorized());
        var unknown = mongo.findAll(SecurityEvent.class).stream().filter(event -> event.accountId() == null).findFirst().orElseThrow();
        assertThat(unknown.emailHash()).isNotBlank().isNotEqualTo(events.getFirst().emailHash());
        var ttl = mongo.indexOps(SecurityEvent.class).getIndexInfo().stream().filter(index -> index.getName().equals("security_event_retention")).findFirst().orElseThrow();
        assertThat(ttl.getExpireAfter()).contains(Duration.ofDays(90));
    }

    @Test void T_14_21_R_14_17_theRateLimitFilterRecordsTheLimitedSignupRoute() throws Exception {
        for (int attempt = 0; attempt < 11; attempt++) {
            mvc.perform(post("/api/v1/signup/identity-checks").header("Host", HOST).contentType("application/json").content("{}")
                    .with(request -> { request.setRemoteAddr("198.51.100.21"); return request; }));
        }
        assertThat(mongo.findAll(SecurityEvent.class)).filteredOn(event -> event.type() == SecurityEvents.Type.RATE_LIMITED).singleElement().satisfies(event -> {
            assertThat(event.details()).containsEntry("limit", "SIGNUP_IDENTITY");
            assertThat(event.emailHash()).isNull();
            assertThat(event.clubId()).isEqualTo("club-a");
        });
    }
}
