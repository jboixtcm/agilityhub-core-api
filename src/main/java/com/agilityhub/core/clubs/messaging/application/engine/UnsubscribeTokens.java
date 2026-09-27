package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * S11 R-11-08 «Deixar de rebre aquests comunicats»: a signed token valid {@value #DAYS} days naming the club and the member,
 * `{clubId}.{memberId}.{expiresEpoch}.{HMAC-SHA256}` in base64url, with `notifications.unsubscribe-key` (env
 * `EMAIL_UNSUBSCRIBE_KEY`, 32 bytes base64). Local/test use a random key; staging/prod refuse to start without one. A token
 * that is expired, tampered with or of another club is `UNSUBSCRIBE_TOKEN_INVALID` (422, CATALEG_ERRORS rule 0). The
 * token holds ids only, never an address.
 */
public final class UnsubscribeTokens {
    static final long DAYS = 30;
    private final byte[] key; private final Clock clock;

    public UnsubscribeTokens(byte[] key, Clock clock) {
        if (key == null || key.length != 32) { throw new IllegalStateException("notifications.unsubscribe-key must contain 32 bytes"); }
        this.key = key.clone(); this.clock = clock;
    }
    public static UnsubscribeTokens random(Clock clock) { return new UnsubscribeTokens(new SecureRandom().generateSeed(32), clock); }

    public record Claim(String clubId, String memberId) { }

    public String issue(String clubId, String memberId) {
        String expires = Long.toString(clock.instant().plus(Duration.ofDays(DAYS)).getEpochSecond());
        String payload = encode(clubId) + "." + encode(memberId) + "." + expires;
        return payload + "." + sign(payload);
    }
    /** The claim of a valid token of `clubId`; anything else is `UNSUBSCRIBE_TOKEN_INVALID`. */
    public Claim verify(String token, String clubId) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 4) { throw new IllegalArgumentException(); }
            String payload = parts[0] + "." + parts[1] + "." + parts[2];
            if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.US_ASCII), parts[3].getBytes(StandardCharsets.US_ASCII))) { throw new IllegalArgumentException(); }
            if (Long.parseLong(parts[2]) <= clock.instant().getEpochSecond()) { throw new IllegalArgumentException(); }
            var claim = new Claim(decode(parts[0]), decode(parts[1]));
            if (!claim.clubId().equals(clubId)) { throw new IllegalArgumentException(); }
            return claim;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ApiException(ErrorCode.UNSUBSCRIBE_TOKEN_INVALID);
        }
    }

    private String sign(String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
}
