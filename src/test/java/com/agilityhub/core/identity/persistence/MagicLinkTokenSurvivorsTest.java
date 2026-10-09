package com.agilityhub.core.identity.persistence;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** E11-T06 PIT survivor of {@link MagicLinkToken#toString}: a logged link is redacted, never its token hash (S01 T-01-17). */
class MagicLinkTokenSurvivorsTest {
    @Test void E11_T06_aMagicLinkTokenPrintsRedacted() {
        var now = Instant.parse("2026-10-05T08:00:00Z");
        var link = new MagicLinkToken("ml-1", "hash-1", "acc-1", "club-a", "clubs-app", MagicLinkToken.Purpose.RESET, null,
                now, now.plusSeconds(900), null, "ip-hash", "Firefox / macOS");

        assertThat(link.toString()).isEqualTo("MagicLinkToken[redacted]");
    }
}
