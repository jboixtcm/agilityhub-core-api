package com.agilityhub.core.clubs.census.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ConsentLedgersTest {
    @Test void T_04_25_INC91_legacyPrivacyAcceptanceSurvivesLedgerConversionWithoutInventingImageConsent() {
        var acceptance = Map.<String, Object>of("acceptedAt", Instant.EPOCH, "version", "v1");
        var rows = ConsentLedgers.entries(Map.of("privacyPolicy", acceptance, "imageRights", acceptance));
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst()).containsEntry("type", "PRIVACY_POLICY").containsEntry("granted", true);
        assertThat(rows.getLast()).containsEntry("type", "IMAGE_USE").containsEntry("granted", false);
        assertThat(ConsentLedgers.entries(Map.of("history", rows))).isEqualTo(rows);
        assertThat(ConsentLedgers.entries(Map.of("privacyPolicy", Map.of("version", "v1")))).isEmpty();
        assertThat(ConsentLedgers.entries(Map.of("imageRights", Map.of("at", Instant.EPOCH)))).isEmpty();
    }
}
