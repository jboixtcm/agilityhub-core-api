package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * S08 R-08-08 `.ics` capability: `HMAC-SHA256(clubId|bookingId|expiresEpoch)` with `bookings.calendar-key`
 * (env `BOOKING_CALENDAR_KEY`, 32 bytes base64), valid until `classEndsAt`. Local/test generate a random key;
 * staging/prod refuse to start without one. Any mismatch answers 404, never revealing whether the booking exists.
 */
@Component
public class BookingCalendarTokens {
    private final byte[] key; private final Clock clock;
    public BookingCalendarTokens(Environment env, Clock clock) {
        this.clock = clock;
        String value = env.getProperty("bookings.calendar-key", "");
        if (value.isBlank()) {
            if (env.acceptsProfiles(Profiles.of("staging", "prod"))) { throw new IllegalStateException("Missing bookings.calendar-key"); }
            key = new SecureRandom().generateSeed(32);
        } else {
            try { key = Base64.getDecoder().decode(value); }
            catch (IllegalArgumentException invalid) { throw new IllegalStateException("Invalid bookings.calendar-key"); }
            if (key.length != 32) { throw new IllegalStateException("bookings.calendar-key must contain 32 bytes"); }
        }
    }
    public String issue(String bookingId, Instant validUntil) {
        String expires = Long.toString(validUntil.getEpochSecond());
        return expires + "." + sign(TenantContext.require() + "|" + bookingId + "|" + expires);
    }
    public void require(String bookingId, String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(TenantContext.require() + "|" + bookingId + "|" + parts[0]).getBytes(StandardCharsets.US_ASCII),
                    parts[1].getBytes(StandardCharsets.US_ASCII)) || Long.parseLong(parts[0]) <= clock.instant().getEpochSecond()) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | NullPointerException invalid) { throw new ApiException(ErrorCode.NOT_FOUND); }
    }
    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
}
