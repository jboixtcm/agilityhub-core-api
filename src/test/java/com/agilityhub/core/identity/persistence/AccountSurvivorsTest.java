package com.agilityhub.core.identity.persistence;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT survivor of {@link Account#toString}: a logged account names only its id, never its email, name or password
 * hash (S01 T-01-17: no credential leaves the service; personal data stays out of logs).
 */
class AccountSurvivorsTest {
    @Test void E11_T06_anAccountPrintsOnlyItsId() {
        var account = new Account("acc-1", "laura@example.test", "Laura Example", "ca", "$argon2id$v=19$fictional", Set.of(),
                Account.Status.ACTIVE, new Account.Security(0, null, Instant.parse("2026-10-05T08:00:00Z"), 0), Map.of(), false,
                Instant.parse("2026-10-05T08:00:00Z"));

        assertThat(account.toString()).isEqualTo("Account[id=acc-1]");
    }
}
