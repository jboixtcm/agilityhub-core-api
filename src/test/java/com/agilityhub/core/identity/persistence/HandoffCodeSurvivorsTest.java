package com.agilityhub.core.identity.persistence;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** E11-T06 PIT survivor of {@link HandoffCode#toString}: a logged handoff code is redacted, never its code hash (S01 R-01-13). */
class HandoffCodeSurvivorsTest {
    @Test void E11_T06_aHandoffCodePrintsRedacted() {
        var code = new HandoffCode("hc-1", "club-a", "acc-1", "clubs-admin", "code-hash", "clubs-app", "family-1", 0,
                Instant.parse("2026-10-05T08:01:00Z"), null);

        assertThat(code.toString()).isEqualTo("HandoffCode[redacted]");
    }
}
