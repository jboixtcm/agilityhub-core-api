package com.agilityhub.core.payments.application;

import com.agilityhub.core.platform.application.ClubPaymentProviders;
import com.agilityhub.core.platform.application.StripeProviderSettings;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * S12 R-12-21 (E8-T01 round 2): `POST /webhooks/stripe/{clubId}` authenticates the raw body before anything else. Stripe signs
 * `"{t}.{body}"` with HMAC-SHA256 under the club's webhook secret and sends `Stripe-Signature: t={unix seconds},v1={hex}`
 * (more `v1` while a secret rolls over, `v0` for test events, which never count). A delivery is authentic when one `v1` is the
 * HMAC of this very body and `t` is within Stripe's 5-minute tolerance of the clock, so an old delivery cannot be replayed. The
 * secret is the club's `paymentProviders.STRIPE.webhookSecretEnc`, decrypted with `BILLING_SECRETS_KEY`
 * ({@link ProviderSecretVault}). A missing, malformed, wrong or stale signature, a tampered body, an unknown club, a club
 * without a secret and one whose secret does not decrypt all answer `401 WEBHOOK_SIGNATURE_INVALID` with a
 * `SecurityEvent` (S14 R-14-17) and store nothing else; the answer never says which.
 */
@Service
public class StripeWebhookSignatures {
    static final Duration TOLERANCE = Duration.ofMinutes(5);
    static final String FIELD = "webhookSecretEnc";
    private static final Logger LOG = LoggerFactory.getLogger(StripeWebhookSignatures.class);
    private final ClubPaymentProviders providers;
    private final ProviderSecretVault vault;
    private final SecurityEvents securityEvents;
    private final Clock clock;
    public StripeWebhookSignatures(ClubPaymentProviders providers, ProviderSecretVault vault, SecurityEvents securityEvents, Clock clock) {
        this.providers = providers; this.vault = vault; this.securityEvents = securityEvents; this.clock = clock;
    }

    /** Returns when {@code body} is signed by the club's webhook secret; otherwise the SecurityEvent and the 401. */
    public void authenticate(String clubId, String header, byte[] body) {
        var stripe = providers.stripe(clubId);
        String secret = stripe.map(settings -> secret(clubId, settings)).orElse(null);
        if (secret != null && valid(header, body, secret, clock.instant())) { return; }
        // An unknown club is recorded without the path's text.
        securityEvents.record(SecurityEvents.Type.WEBHOOK_SIGNATURE_INVALID, null, stripe.isPresent() ? clubId : null);
        throw new ApiException(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
    }

    private String secret(String clubId, StripeProviderSettings settings) {
        String stored = settings.webhookSecretEnc();
        if (stored == null || stored.isBlank()) { return null; }
        try {
            String secret = vault.decrypt(stored, clubId, StripeProviderSettings.PROVIDER, FIELD);
            return secret.isEmpty() ? null : secret;
        } catch (IllegalStateException misconfigured) {
            // A server misconfiguration (no key, another key, a value copied from elsewhere): never the value in the log.
            LOG.warn("Stripe webhook secret unusable: clubId={} error={}", clubId, misconfigured.getMessage());
            return null;
        }
    }

    /** Stripe's scheme: one `t`, at least one `v1` equal to HMAC-SHA256(secret, "{t}.{body}") in hex, `t` within the tolerance. */
    public static boolean valid(String header, byte[] body, String secret, Instant now) {
        if (header == null) { return false; }
        Long timestamp = null;
        List<byte[]> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            int separator = part.indexOf('=');
            if (separator < 0) { continue; }
            String name = part.substring(0, separator).strip(), value = part.substring(separator + 1).strip();
            if (name.equals("t")) {
                if (timestamp != null || !value.matches("[0-9]{1,12}")) { return false; }
                timestamp = Long.parseLong(value);
            } else if (name.equals("v1")) {
                try { signatures.add(HexFormat.of().parseHex(value)); }
                catch (IllegalArgumentException notHex) { /* never matches */ }
            }
        }
        if (timestamp == null || signatures.isEmpty() || Math.abs(now.getEpochSecond() - timestamp) > TOLERANCE.toSeconds()) { return false; }
        byte[] expected = hmac(secret, timestamp, body);
        boolean match = false;
        for (byte[] signature : signatures) { match |= MessageDigest.isEqual(expected, signature); }
        return match;
    }

    static byte[] hmac(String secret, long timestamp, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (GeneralSecurityException unavailable) { throw new IllegalStateException("HmacSHA256 unavailable"); }
    }
}
