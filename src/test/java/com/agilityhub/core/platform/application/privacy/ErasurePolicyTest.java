package com.agilityhub.core.platform.application.privacy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** S14 R-14-14 `executeAt` and R-14-15's pseudonym (T-14-09, T-14-10). */
class ErasurePolicyTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static ErasureMember left(LocalDate leaveDate, Instant leftAt) {
        return new ErasureMember("member-a", "account-a", 87, "LEFT", leaveDate, leftAt, "LEAVE_REQUEST", null, List.of());
    }

    @Test void T_14_09_executeAtIsTheLeaveDatePlusTheMarginAtTheClubsMidnight() {
        // Montse: leave 31-08, request 05-09, margin 30 → 30-09 (S14 R-14-14's example).
        Instant now = Instant.parse("2026-09-05T08:00:00Z");
        assertThat(ErasurePolicy.executeAt(now, left(LocalDate.of(2026, 8, 31), null), MADRID, 30, false))
                .isEqualTo(LocalDate.of(2026, 9, 30).atStartOfDay(MADRID).toInstant());
        // LEFT ten days ago: leaveDate + 30.
        assertThat(ErasurePolicy.executeAt(now, left(LocalDate.of(2026, 8, 26), null), MADRID, 30, false))
                .isEqualTo(Instant.parse("2026-09-24T22:00:00Z"));
    }

    @Test void T_14_09_aMarginAlreadyOverAndTheRetentionSourceExecuteNow() {
        Instant now = Instant.parse("2026-09-05T08:00:00Z");
        assertThat(ErasurePolicy.executeAt(now, left(LocalDate.of(2026, 6, 1), null), MADRID, 30, false)).isEqualTo(now);
        assertThat(ErasurePolicy.executeAt(now, left(LocalDate.of(2026, 8, 31), null), MADRID, 30, true)).isEqualTo(now);
    }

    @Test void T_14_09_aRejectedSignupCountsFromLeftAtAndNoDateMeansNow() {
        Instant now = Instant.parse("2026-09-05T08:00:00Z");
        // leftAt 2026-08-31T23:30Z is 01-09 in Madrid: the margin counts from the club's date.
        assertThat(ErasurePolicy.executeAt(now, left(null, Instant.parse("2026-08-31T23:30:00Z")), MADRID, 30, false))
                .isEqualTo(LocalDate.of(2026, 10, 1).atStartOfDay(MADRID).toInstant());
        assertThat(ErasurePolicy.executeAt(now, left(null, null), MADRID, 30, false)).isEqualTo(now);
    }

    @Test void T_14_10_hmac16IsStableAndDoesNotCollideOverTenThousandFictionalAddresses() {
        byte[] key = new byte[32]; for (int i = 0; i < key.length; i++) { key[i] = (byte) (i * 7 + 3); }
        var values = new HashSet<String>();
        for (int i = 0; i < 10000; i++) {
            String email = "fictional-" + i + "@example.test";
            String value = ErasurePolicy.hmac16(email, key);
            assertThat(value).hasSize(16).matches("[0-9a-f]{16}").isEqualTo(ErasurePolicy.hmac16(" " + email.toUpperCase(Locale.ROOT) + " ", key));
            values.add(value);
        }
        assertThat(values).hasSize(10000);
        assertThat(ErasurePolicy.pseudonym("laura@example.test", key)).matches("erased\\+[0-9a-f]{16}@erased\\.invalid")
                .doesNotContain("laura").isEqualTo(ErasurePolicy.pseudonym("LAURA@example.test", key));
        assertThatThrownBy(() -> ErasurePolicy.hmac16("fictional@example.test", new byte[31])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void T_14_15_placeholdersNumberAMemberOrUseADash() {
        assertThat(ErasurePlaceholders.number(87)).isEqualTo("#87");
        assertThat(ErasurePlaceholders.number(null)).isEqualTo("#—");
    }
}
