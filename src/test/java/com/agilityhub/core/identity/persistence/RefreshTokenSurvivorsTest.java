package com.agilityhub.core.identity.persistence;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** E11-T06 PIT survivor of {@link RefreshToken#toString}: a logged session is redacted, never its token hashes (S01 T-01-17). */
class RefreshTokenSurvivorsTest {
    @Test void E11_T06_aRefreshTokenPrintsRedacted() {
        var now = Instant.parse("2026-10-05T08:00:00Z");
        var token = new RefreshToken("rt-1", "hash-1", "acc-1", "club-a", "clubs-app", "family-1", 0, now, now.plusSeconds(86_400), now, null, "hash-2");

        assertThat(token.toString()).isEqualTo("RefreshToken[redacted]");
    }
}
