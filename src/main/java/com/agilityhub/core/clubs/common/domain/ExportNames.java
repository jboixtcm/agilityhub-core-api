package com.agilityhub.core.clubs.common.domain;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class ExportNames {
    private ExportNames() { }
    public static String fileName(String slug, String resource, Instant at, ZoneId zone, String format) {
        return safe(slug) + "_" + safe(resource) + "_" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(zone).format(at)
                + "." + safe(format.toLowerCase(Locale.ROOT));
    }
    private static String safe(String value) {
        if (!value.matches("[a-zA-Z0-9_-]+")) { throw new IllegalArgumentException("Invalid export filename component"); }
        return value;
    }
    public static String hmac16(String email, byte[] key) { return hmac(email.strip().toLowerCase(Locale.ROOT), key).substring(0, 16); }
    public static String hmac(String value, byte[] key) {
        if (key.length < 32) { throw new IllegalArgumentException("HMAC requires at least 32 key bytes"); }
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("HMAC unavailable", ex); }
    }
}
