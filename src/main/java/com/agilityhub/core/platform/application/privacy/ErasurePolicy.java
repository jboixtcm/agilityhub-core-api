package com.agilityhub.core.platform.application.privacy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** The pure rules of S14 R-14-14 (`executeAt`) and R-14-15 (the account pseudonym). */
public final class ErasurePolicy {
    public static final String PSEUDONYM_DOMAIN = "@erased.invalid";
    private ErasurePolicy() { }

    /**
     * R-14-14: `executeAt = max(now, leaveDate + rgpd.erasureMinDaysAfterLeave)`, the start of that day in the club's zone (a
     * reconsideration and readmission margin). A member left without `leaveDate` (a rejected signup) counts from `leftAt`'s
     * local date; without either, now. `source = RETENTION` (R-14-16) skips the margin: now (T-14-09).
     */
    public static Instant executeAt(Instant now, ErasureMember member, ZoneId zone, int minDaysAfterLeave, boolean retention) {
        if (retention) { return now; }
        LocalDate left = member.leaveDate() != null ? member.leaveDate() : member.leftAt() == null ? null : member.leftAt().atZone(zone).toLocalDate();
        if (left == null) { return now; }
        Instant margin = left.plusDays(minDaysAfterLeave).atStartOfDay(zone).toInstant();
        return margin.isAfter(now) ? margin : now;
    }

    /**
     * R-14-15: `erased+{hmac16(email)}@erased.invalid` — unique per address, not reversible and not enumerable without the
     * server key. The address is trimmed and lower-cased first, so one account always gets the same pseudonym.
     */
    public static String pseudonym(String email, byte[] key) { return "erased+" + hmac16(email, key) + PSEUDONYM_DOMAIN; }

    /** T-14-10: the first 16 hex characters of HMAC-SHA256 over the normalised address. */
    public static String hmac16(String email, byte[] key) { return hmac(email.strip().toLowerCase(Locale.ROOT), key).substring(0, 16); }

    public static String hmac(String value, byte[] key) {
        if (key.length < 32) { throw new IllegalArgumentException("HMAC requires at least 32 key bytes"); }
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("HMAC unavailable", ex); }
    }
}
