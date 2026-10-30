package com.agilityhub.core.shared.application;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;

/** Explicit hosted demo restrictions. This does not activate any local/test provider or helper. */
public record HostedDemoPolicy(boolean enabled, Set<String> emailAllowlist) {
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._%+\\-]*@[A-Za-z0-9](?:[A-Za-z0-9.\\-]*[A-Za-z0-9])?\\.[A-Za-z]{2,}");
    public HostedDemoPolicy { emailAllowlist = Set.copyOf(emailAllowlist); }
    public static HostedDemoPolicy from(Environment environment) {
        String value = environment.getProperty("core.hosted-demo.enabled", "false");
        if (!Set.of("true", "false").contains(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("HOSTED_DEMO_ENABLED must be true or false");
        }
        boolean enabled = Boolean.parseBoolean(value);
        boolean emailEnabled = emailDeliveryEnabled(environment);
        if (!enabled) {
            if (!emailEnabled) { throw new IllegalStateException("EMAIL_DELIVERY_ENABLED=false requires HOSTED_DEMO_ENABLED=true"); }
            return new HostedDemoPolicy(false, Set.of());
        }
        if (!environment.matchesProfiles("prod", "staging") || environment.matchesProfiles("local", "test")) {
            throw new IllegalStateException("HOSTED_DEMO_ENABLED requires only a deployed profile, without local/test");
        }
        if (!emailEnabled) { return new HostedDemoPolicy(true, Set.of()); }
        var allowed = new HashSet<String>();
        for (String item : environment.getProperty("core.hosted-demo.email-allowlist", "").split(",", -1)) {
            String email = item.strip();
            if (!EMAIL.matcher(email).matches()) {
                throw new IllegalStateException("HOSTED_DEMO_EMAIL_ALLOWLIST requires nonempty exact email addresses, without wildcards");
            }
            allowed.add(email.toLowerCase(Locale.ROOT));
        }
        return new HostedDemoPolicy(true, allowed);
    }
    public static boolean emailDeliveryEnabled(Environment environment) {
        String value = environment.getProperty("email.delivery-enabled", "true");
        if (!Set.of("true", "false").contains(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("EMAIL_DELIVERY_ENABLED must be true or false");
        }
        return Boolean.parseBoolean(value);
    }
    public static boolean emailRecoveryEnabled(Environment environment) {
        String value = environment.getProperty("email.recovery-enabled", "false");
        if (!Set.of("true", "false").contains(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("EMAIL_RECOVERY_ENABLED must be true or false");
        }
        return Boolean.parseBoolean(value);
    }
    public static boolean emailTransportEnabled(Environment environment) {
        boolean recovery = emailRecoveryEnabled(environment);
        return emailDeliveryEnabled(environment) || recovery;
    }
    public boolean permitsEmail(String recipient) {
        return recipient != null && emailAllowlist.contains(recipient.strip().toLowerCase(Locale.ROOT));
    }
}
