package com.agilityhub.core.platform.application;

import java.util.Map;

/**
 * The two flags of a stored `CLUB.paymentProviders.{provider}` entry, one rule for every reader (E3-T14). `enabled` is the
 * switch: only `enabled: true` offers the provider's methods (S04 R-04-10) and lets S12 use it; a provider listed without it
 * is off. `configured` says the club entered its data (creditor, keys, instructions); it never gates the offer, S12 needs it
 * to collect (for example `SEPA_NOT_CONFIGURED` when generating a remittance).
 */
public final class PaymentProviderFlags {
    private PaymentProviderFlags() { }
    public static boolean enabled(Object stored) {
        return stored instanceof Map<?, ?> settings && Boolean.TRUE.equals(settings.get("enabled"));
    }
    public static boolean configured(Object stored) {
        return stored instanceof Map<?, ?> settings && settings.entrySet().stream().anyMatch(entry -> !"enabled".equals(entry.getKey()) && entry.getValue() != null);
    }
}
