package com.agilityhub.core.platform.domain;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collection;
import java.util.Locale;

/**
 * S17 §2 and R-17-03: the DNS proofs a club domain is verified with. (a) a CNAME from the host to the product host of its app,
 * or (b) the TXT record `_agilityhub-verify.{host}` = `agh-verify-{token}`. The token (model proposal E112, approved in E114) is
 * 32 random URL-safe characters; it is published in DNS, so it is not a secret.
 */
public final class DomainChallenge {
    public static final String RECORD_PREFIX = "_agilityhub-verify.";
    public static final String VALUE_PREFIX = "agh-verify-";
    private static final SecureRandom RANDOM = new SecureRandom();
    private DomainChallenge() { }

    public static String token() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String recordName(String host) { return RECORD_PREFIX + host; }
    public static String recordValue(String token) { return VALUE_PREFIX + token; }

    /** (a): one of the host's CNAME targets is the product host, compared as DNS names (case and a final dot do not count). */
    public static boolean cnameMatches(Collection<String> targets, String productHost) {
        String expected = HostNames.normalize(productHost);
        return !expected.isEmpty() && targets.stream().map(HostNames::normalize).anyMatch(expected::equals);
    }
    /** (b): one of the TXT strings is the challenge; DNS answers may quote a TXT string, and quotes do not count. */
    public static boolean txtMatches(Collection<String> values, String token) {
        if (token == null || token.isBlank()) { return false; }
        String expected = recordValue(token);
        return values.stream().map(value -> value.strip().replace("\"", "")).anyMatch(expected::equals);
    }
    /** R-17-02: the product hosts (`clubs.*`, `clubsadmin.*`, `id.*`, `core.*`) are never a club's. */
    public static boolean reserved(String host) {
        return host.toLowerCase(Locale.ROOT).matches("^(clubs|clubsadmin|id|core)\\..*");
    }
}
