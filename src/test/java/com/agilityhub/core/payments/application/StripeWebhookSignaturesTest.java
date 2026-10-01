package com.agilityhub.core.payments.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * S12 R-12-21 (E8-T01 round 2): Stripe's signature scheme as the webhook checks it — one `t`, a `v1` that is the hex
 * HMAC-SHA256 of `"{t}.{raw body}"` under the club's secret, `t` within 5 minutes of now; `v0` never counts. The expected
 * values are computed here with the JDK, independently of {@link StripeWebhookSignatures#hmac}. Fixture secrets only.
 */
class StripeWebhookSignaturesTest {
    static final String SECRET = "whsec_fake_fake_fake";
    static final Instant NOW = Instant.parse("2026-09-24T08:00:00Z");
    static final byte[] BODY = "{\"id\":\"evt_e8_fixture\",\"type\":\"payment_intent.succeeded\"}".getBytes(StandardCharsets.UTF_8);

    static String v1(String secret, long t, byte[] body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test void R_12_21_aValidSignatureOfThisBodyWithinTheToleranceIsAuthentic() throws Exception {
        long t = NOW.getEpochSecond();
        assertThat(StripeWebhookSignatures.valid("t=" + t + ",v1=" + v1(SECRET, t, BODY), BODY, SECRET, NOW)).isTrue();
        // A secret rolling over sends two v1 (and a test v0): one match is enough, in any order, spaces tolerated.
        assertThat(StripeWebhookSignatures.valid("t=" + t + ", v1=" + "0".repeat(64) + ", v0=" + "f".repeat(64) + ", v1=" + v1(SECRET, t, BODY), BODY, SECRET, NOW)).isTrue();
        assertThat(StripeWebhookSignatures.valid("v1=" + v1(SECRET, t, BODY) + ",t=" + t, BODY, SECRET, NOW)).isTrue();
        // Exactly 5 minutes away on either side is still inside.
        for (long at : new long[] {t - 300, t + 300}) {
            assertThat(StripeWebhookSignatures.valid("t=" + at + ",v1=" + v1(SECRET, at, BODY), BODY, SECRET, NOW)).as("t=" + at).isTrue();
        }
    }

    @Test void R_12_21_aMissingMalformedWrongStaleOrAnotherBodysSignatureIsRefused() throws Exception {
        long t = NOW.getEpochSecond();
        String good = v1(SECRET, t, BODY);
        byte[] tampered = "{\"id\":\"evt_e8_fixture\",\"type\":\"charge.refunded\"}".getBytes(StandardCharsets.UTF_8);
        for (String header : new String[] {null, "", "garbage", "t=" + t, "v1=" + good, "t=abc,v1=" + good, "t=-1,v1=" + good,
                "t=" + t + ",t=" + t + ",v1=" + good, "t=" + t + ",v0=" + good, "t=" + t + ",v1=not-hex", "t=" + t + ",v1=" + good.substring(2),
                "t=" + t + ",v1=" + v1("whsec_fake_other_fake", t, BODY), "t=1234567890123,v1=" + good,
                "t=" + (t - 301) + ",v1=" + v1(SECRET, t - 301, BODY), "t=" + (t + 301) + ",v1=" + v1(SECRET, t + 301, BODY),
                "t=" + (t + 1) + ",v1=" + good}) {
            assertThat(StripeWebhookSignatures.valid(header, BODY, SECRET, NOW)).as(String.valueOf(header)).isFalse();
        }
        assertThat(StripeWebhookSignatures.valid("t=" + t + ",v1=" + good, tampered, SECRET, NOW)).as("tampered body").isFalse();
    }

    /** ADR-009: the provider secrets are bound to their club and field; another key, club or field never decrypts them. */
    @Test void ADR_009_aProviderSecretDecryptsOnlyWithItsKeyClubAndField() {
        String key = Base64.getEncoder().encodeToString("e8-t01-fictional-secrets-key-32b".getBytes(StandardCharsets.US_ASCII));
        String other = Base64.getEncoder().encodeToString("e8-t01-another-secrets-key-32-by".getBytes(StandardCharsets.US_ASCII));
        var vault = new ProviderSecretVault(key);
        String stored = vault.encrypt(SECRET, "club-a", "STRIPE", "webhookSecretEnc");
        assertThat(stored).doesNotContain("whsec");
        assertThat(vault.decrypt(stored, "club-a", "STRIPE", "webhookSecretEnc")).isEqualTo(SECRET);
        for (var failure : java.util.List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                () -> vault.decrypt(stored, "club-b", "STRIPE", "webhookSecretEnc"),
                () -> vault.decrypt(stored, "club-a", "STRIPE", "secretKeyEnc"),
                () -> new ProviderSecretVault(other).decrypt(stored, "club-a", "STRIPE", "webhookSecretEnc"))) {
            assertThatThrownBy(failure).isInstanceOf(IllegalStateException.class).hasMessage("Provider secret decryption failed");
        }
        assertThatThrownBy(() -> new ProviderSecretVault("").decrypt(stored, "club-a", "STRIPE", "webhookSecretEnc"))
                .isInstanceOf(IllegalStateException.class).hasMessage("BILLING_SECRETS_KEY must be 32 bytes");
    }
}
