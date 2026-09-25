package com.agilityhub.core.platform.application;

import java.util.Map;

/**
 * The two flags of a stored `CLUB.paymentProviders.{provider}` entry, one rule for every reader (E3-T14). `enabled` is the
 * switch: only `enabled: true` offers the provider's methods (S04 R-04-10) and lets S12 use it; a provider listed without it
 * is off. `configured` says the club entered its data (creditor, keys, instructions); it never gates the offer, S12 needs it
 * to collect (for example `SEPA_NOT_CONFIGURED` when generating a remittance).
 */
public final class PaymentProviderFlags {
    /** S04 R-04-10: each provider collects with one payment method. */
    private static final Map<String, String> METHODS = Map.of("SEPA_XML", "SEPA_DD", "STRIPE", "CARD", "MANUAL", "MANUAL");
    private PaymentProviderFlags() { }
    public static boolean enabled(Object stored) {
        return stored instanceof Map<?, ?> settings && Boolean.TRUE.equals(settings.get("enabled"));
    }
    public static boolean configured(Object stored) {
        return stored instanceof Map<?, ?> settings && settings.entrySet().stream().anyMatch(entry -> !"enabled".equals(entry.getKey()) && entry.getValue() != null);
    }
    /** The method a provider collects with (`SEPA_XML` → `SEPA_DD`, `STRIPE` → `CARD`, `MANUAL` → `MANUAL`); `null` for an unknown provider. */
    public static String method(String provider) { return provider == null ? null : METHODS.get(provider); }
    /** The provider of a payment method; `null` for an unknown method. */
    public static String provider(String method) {
        return METHODS.entrySet().stream().filter(entry -> entry.getValue().equals(method)).map(Map.Entry::getKey).findFirst().orElse(null);
    }
}
