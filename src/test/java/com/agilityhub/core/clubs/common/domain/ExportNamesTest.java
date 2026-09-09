package com.agilityhub.core.clubs.common.domain;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExportNamesTest {
    @Test void T_14_10_localFileNamesAndStablePseudonyms() {
        assertThat(ExportNames.fileName("canic", "members", Instant.parse("2026-08-10T07:12:00Z"), ZoneId.of("Europe/Madrid"), "XLSX"))
                .isEqualTo("canic_members_20260810-0912.xlsx");
        assertThat(ExportNames.fileName("club", "dogs", Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("America/Argentina/Buenos_Aires"), "pdf"))
                .isEqualTo("club_dogs_20251231-2100.pdf");
        byte[] key = new java.security.SecureRandom().generateSeed(32);
        var values = new HashSet<String>();
        for (int i = 0; i < 10000; i++) {
            String email = "fictional-" + i + "@example.test";
            String value = ExportNames.hmac16(email, key);
            assertThat(value).hasSize(16).isEqualTo(ExportNames.hmac16(email.toUpperCase(Locale.ROOT) + " ", key)); values.add(value);
        }
        assertThat(values).hasSize(10000);
        assertThatThrownBy(() -> ExportNames.hmac16("fictional@example.test", new byte[1])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExportNames.fileName("../bad", "dogs", Instant.EPOCH, ZoneOffset.UTC, "xlsx")).isInstanceOf(IllegalArgumentException.class);
    }
}
