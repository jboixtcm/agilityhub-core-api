package com.agilityhub.core.platform.application;

import com.agilityhub.core.shared.domain.audit.Sensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Map;

/**
 * The shape of `CLUB.paymentProviders.STRIPE` (S12 §3, ADR-009; E8-T01 declares it, E8-T04 and S17's console fill it): the
 * club's own Stripe account. `secretKeyEnc` and `webhookSecretEnc` are encrypted with a server key from the environment and
 * never leave the server: no projection publishes them (`GET /club` and `club:export` show the provider's `enabled` and
 * `configured` flags only, {@link PaymentProviderFlags}), they are hidden in any audit snapshot and in any JSON of this view.
 * `mode` is `test` or `live`. Nothing is stored in the repository: the fields stay empty until the club enters its keys.
 */
public record StripeProviderSettings(boolean enabled, String publishableKey, @JsonIgnore @Sensitive(Sensitive.Strategy.HIDE) String secretKeyEnc,
        @JsonIgnore @Sensitive(Sensitive.Strategy.HIDE) String webhookSecretEnc, String mode, String accountId) {
    public static final String PROVIDER = "STRIPE";
    /** The view of a stored `paymentProviders.STRIPE` entry; an absent provider is an empty, disabled one. */
    public static StripeProviderSettings of(Map<String, Object> paymentProviders) {
        if (paymentProviders == null || !(paymentProviders.get(PROVIDER) instanceof Map<?, ?> stored)) {
            return new StripeProviderSettings(false, null, null, null, null, null);
        }
        return new StripeProviderSettings(PaymentProviderFlags.enabled(stored), text(stored.get("publishableKey")), text(stored.get("secretKeyEnc")),
                text(stored.get("webhookSecretEnc")), text(stored.get("mode")), text(stored.get("accountId")));
    }
    /** Never the secrets: what a log line or an error may print. */
    @Override public String toString() {
        return "StripeProviderSettings[enabled=" + enabled + ", mode=" + mode + ", accountId=" + accountId + ", secrets=" + (secretKeyEnc != null) + "]";
    }
    private static String text(Object value) { return value == null ? null : value.toString(); }
}
