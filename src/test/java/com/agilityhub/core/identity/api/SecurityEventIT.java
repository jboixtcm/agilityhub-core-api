package com.agilityhub.core.identity.api;

import com.agilityhub.core.platform.persistence.SecurityEvent;
import com.agilityhub.core.shared.application.SecurityEvents;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SecurityEventIT extends IdentityIntegrationSupport {
    @BeforeEach void clearEvents() { mongo.remove(new Query(), SecurityEvent.class); }

    @Test void E0_T12_existingRetentionIndexCanBeAlignedAndOverriddenWithoutRecreation() {
        var events = new com.agilityhub.core.platform.persistence.SecurityEventRepository(mongo);
        events.ensureIndexes(365);
        events.ensureIndexes(90);
        events.ensureIndexes(90);
        assertThat(mongo.indexOps(SecurityEvent.class).getIndexInfo()).filteredOn("name", "security_event_retention")
                .singleElement().satisfies(index -> assertThat(index.getExpireAfter()).contains(Duration.ofDays(90)));
    }

    @Test void T_01_15_failedPasswordWritesGlobalEventForKnownAndUnknownAccountWithoutSecrets() throws Exception {
        login(HOST, "admin@example.test", "do-not-store-password").andExpect(status().isUnauthorized());
        login(HOST, "absent@example.test", "do-not-store-password").andExpect(status().isUnauthorized());
        var events = mongo.findAll(SecurityEvent.class);
        assertThat(events).hasSize(2).allSatisfy(event -> {
            assertThat(event.type()).isEqualTo(SecurityEvents.Type.LOGIN_FAILED);
            assertThat(event.at()).isEqualTo(clock.instant());
            assertThat(event.ip()).isEqualTo("127.0.0.1");
            assertThat(event.clubId()).isEqualTo("club-a");
            assertThat(event.route()).isEqualTo("/oauth2/token");
        });
        assertThat(events.get(0).accountId()).isEqualTo("account-a");
        assertThat(events.get(1).accountId()).isNull();
        assertThat(mongo.getCollection("security_events").find().into(new java.util.ArrayList<>()).toString())
                .doesNotContain("do-not-store-password", "@example.test", "passwordHash", "Authorization", "refresh_token");
        login();
        assertThat(mongo.count(new Query(), SecurityEvent.class)).isEqualTo(2);
        var ttl = mongo.indexOps(SecurityEvent.class).getIndexInfo().stream()
                .filter(index -> index.getName().equals("security_event_retention")).findFirst().orElseThrow();
        assertThat(ttl.getExpireAfter()).contains(Duration.ofDays(90));
    }

    @Test void T_01_17_tenantRejectionIsRecordedWithoutExposingGlobalEventsToClubRoles() throws Exception {
        mvc.perform(get("/api/v1/me").header("Host", "b.example.test").with(jwt().jwt(token ->
                token.subject("account-a").claim("clubId", "club-a"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_MISMATCH"));
        assertThat(mongo.findAll(SecurityEvent.class)).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(SecurityEvents.Type.TENANT_MISMATCH);
            assertThat(event.accountId()).isEqualTo("account-a");
            assertThat(event.clubId()).isEqualTo("club-a");
        });
        mvc.perform(get("/api/v1/platform/security-events").with(jwt().jwt(token -> token.subject("account-a"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test void T_01_15_refreshReuseEventSurvivesCommittedFamilyRevocationAndTestProfileDisablesLimits() throws Exception {
        String first = refreshValue(login());
        refresh(first, HOST, "clubs-app").andExpect(status().isOk());
        refresh(first, HOST, "clubs-app").andExpect(status().isBadRequest());
        assertThat(mongo.findAll(SecurityEvent.class)).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(SecurityEvents.Type.REFRESH_TOKEN_REUSED);
            assertThat(event.accountId()).isEqualTo("account-a");
        });
        for (int index = 0; index < 31; index++) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/oauth2/token").header("Host", HOST))
                    .andExpect(status().isBadRequest());
        }
    }
}
