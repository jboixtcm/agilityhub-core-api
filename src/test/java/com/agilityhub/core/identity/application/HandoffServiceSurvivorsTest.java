package com.agilityhub.core.identity.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT survivor (NO_COVERAGE) of {@link HandoffService.Created#toString} (S01 R-01-13, T-01-12): the one-shot handoff code
 * and its URL never reach a log line through the record's string form. The values have the shape HandoffService.java:38-42
 * builds: a 43-character base64url code (32 random bytes, TokenService.java:238-241) on the club app's `/entrar`
 * (ClubAppUrls.java:26); the code is fictional, readable text in the base64url alphabet.
 */
class HandoffServiceSurvivorsTest {
    static final String CODE = "fictional-handoff-code-of-43-base64url-char";

    @Test void T_01_12_aCreatedHandoffPrintsRedacted() {
        var created = new HandoffService.Created(CODE, "https://club-a.example.test/entrar?handoff=" + CODE);

        assertThat(created.toString()).isEqualTo("Created[redacted]");
    }
}
