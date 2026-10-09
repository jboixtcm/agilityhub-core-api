package com.agilityhub.core.identity.persistence;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT survivors of the {@link OidcState} documents' `toString`: logged flows, browser sessions and codes are
 * redacted, never their capability hashes (S01 R-01-11).
 */
class OidcStateSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final OidcState.Request REQUEST = new OidcState.Request("id-web", "https://id.example.test/callback", Set.of("openid"),
            "state", "c".repeat(43), null, "", null, null, null, null);

    @Test void E11_T06_aFlowPrintsRedacted() {
        assertThat(new OidcState.Flow("flow-hash", "browser-hash", REQUEST, NOW, NOW.plusSeconds(300)).toString()).isEqualTo("OidcFlow[redacted]");
    }

    @Test void E11_T06_aBrowserSessionPrintsRedacted() {
        assertThat(new OidcState.Browser("browser-hash", "acc-1", "family-1", NOW, NOW.plusSeconds(86_400)).toString()).isEqualTo("OidcBrowser[redacted]");
    }

    @Test void E11_T06_aCodePrintsRedacted() {
        assertThat(new OidcState.Code("code-hash", REQUEST, "acc-1", "browser-hash", "family-1", NOW, NOW.plusSeconds(60)).toString())
                .isEqualTo("OidcCode[redacted]");
    }
}
