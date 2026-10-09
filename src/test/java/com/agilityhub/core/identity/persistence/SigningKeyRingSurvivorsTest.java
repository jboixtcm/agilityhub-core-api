package com.agilityhub.core.identity.persistence;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** E11-T06 PIT survivor of {@link SigningKeyRing#toString}: a logged key ring is redacted, never its encrypted keys (S01 T-01-25). */
class SigningKeyRingSurvivorsTest {
    @Test void E11_T06_aSigningKeyRingPrintsRedacted() {
        // SigningKeys stores a single ring under the id "active" (SigningKeys.java:41,57).
        var ring = new SigningKeyRing("active", "encrypted-key-material", 2, Instant.parse("2026-10-05T08:00:00Z"));

        assertThat(ring.toString()).isEqualTo("SigningKeyRing[redacted]");
    }
}
