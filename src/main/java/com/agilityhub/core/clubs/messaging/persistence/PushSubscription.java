package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S11 §3 `PushSubscription` (`push_subscriptions`, R-11-07): one browser subscription of an account in the club, upserted by
 * endpoint. The endpoint is unique per club through its SHA-256 (`endpointHash`, the index key and the only form events carry);
 * the URL itself stays because WebPush (E7-T02) sends to it. `EXPIRED` on 404/410, three failures or logout; nothing is
 * deleted. {@link #toString()} never prints the endpoint or the keys.
 */
@Document("push_subscriptions")
public record PushSubscription(@Id String id, String clubId, String accountId, String endpoint, String endpointHash, Keys keys, String deviceLabel,
        String userAgent, Status status, int failureCount, Instant lastSuccessAt, Instant expiredAt, @Version Long version, Instant createdAt,
        String createdBy, Instant updatedAt, String updatedBy) implements TenantEntity {
    public enum Status { ACTIVE, EXPIRED }
    /** The browser's `p256dh` public key and `auth` secret (aes128gcm). */
    public record Keys(String p256dh, String auth) {
        @Override public String toString() { return "Keys[redacted]"; }
    }

    public PushSubscription {
        Objects.requireNonNull(accountId); Objects.requireNonNull(endpoint); Objects.requireNonNull(status);
        if (endpointHash == null) { endpointHash = hash(endpoint); }
        if (!endpointHash.equals(hash(endpoint))) { throw new IllegalArgumentException("endpointHash is the endpoint's SHA-256"); }
    }

    /** SHA-256 of the endpoint URL, lower-case hex. */
    public static String hash(String endpoint) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(endpoint.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @Override public String toString() { return "PushSubscription[id=" + id + ", accountId=" + accountId + ", status=" + status + "]"; }
}
