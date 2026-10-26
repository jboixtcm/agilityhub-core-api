package com.agilityhub.core.shared.domain;

import com.agilityhub.core.clubs.census.persistence.ConsentLedgerConverter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ConsentGrantTest {
    @Test void T_14_18_INC91_legacyPrivacyAcceptanceKeepsItsDocumentedEvidence() {
        var acceptance = new Document("acceptedAt", Instant.parse("2026-09-01T00:00:00Z")).append("version", "v1");
        assertThat(ConsentGrant.legacyPrivacy(acceptance)).isTrue();
        assertThat(ConsentGrant.granted(acceptance)).as("the same metadata cannot grant image rights").isFalse();
        assertThat(ConsentGrant.legacyPrivacy(null)).isFalse();
        assertThat(ConsentGrant.legacyPrivacy(Map.of())).isFalse();
        assertThat(ConsentGrant.legacyPrivacy(Map.of("version", "v1"))).isFalse();
        assertThat(ConsentGrant.legacyPrivacy(Map.of("acceptedAt", Instant.EPOCH))).isFalse();
        assertThat(ConsentGrant.legacyPrivacy(Map.of("acceptedAt", Instant.EPOCH, "version", " "))).isFalse();
        assertThat(ConsentGrant.legacyPrivacy(new Document(acceptance).append("granted", false))).isFalse();
        assertThat(ConsentGrant.legacyPrivacy(new Document(acceptance).append("accepted", false))).isFalse();
        assertThat(ConsentGrant.legacyPrivacy(Map.of("accepted", true))).isTrue();
    }
    @Test void T_14_18_INC91_onlyExplicitBooleanEvidenceGrantsConsent() {
        assertThat(ConsentGrant.granted(null)).isFalse();
        assertThat(ConsentGrant.granted(Map.of())).isFalse();
        for (Object value : List.of(false, "true", 1)) {
            assertThat(ConsentGrant.granted(Map.of("accepted", value))).isFalse();
            assertThat(ConsentGrant.granted(Map.of("granted", value, "accepted", true))).isFalse();
        }
        assertThat(ConsentGrant.granted(new Document("granted", null).append("accepted", true))).isFalse();
        assertThat(ConsentGrant.granted(Map.of("accepted", true))).isTrue();
        assertThat(ConsentGrant.granted(Map.of("granted", true, "accepted", false))).isTrue();
    }

    @Test void T_14_18_INC91_converterNormalizesProjectionWithoutRewritingLedgerEvidence() {
        var at = Instant.parse("2026-09-01T00:00:00Z");
        var converter = new ConsentLedgerConverter();
        var first = Map.<String, Object>of("type", "IMAGE_USE", "accepted", true, "acceptedAt", at);
        var last = Map.<String, Object>of("type", "IMAGE_USE", "granted", false, "acceptedAt", at.plusSeconds(1));
        var ledger = List.of(first, last);
        var result = converter.read(ledger, null);
        assertThat(result.get("imageRights")).isEqualTo(ConsentGrant.image(last));
        assertThat(converter.write(result, null)).isEqualTo(ledger);
        var legacy = Map.<String, Object>of("imageRights", first);
        assertThat(converter.read(legacy, null).get("imageRights")).isEqualTo(ConsentGrant.image(first));
        assertThat(first).doesNotContainKey("granted").doesNotContainKey("at");
        assertThat(ConsentGrant.image(Map.of("granted", true, "at", at))).containsEntry("at", at);
        assertThat(converter.read(Map.of(), null)).isEmpty();
        assertThat(converter.read(null, null)).isEmpty();
    }
}
