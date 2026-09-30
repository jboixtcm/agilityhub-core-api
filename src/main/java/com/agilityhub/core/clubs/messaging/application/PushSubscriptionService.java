package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.domain.MessagingEvent;
import com.agilityhub.core.clubs.messaging.persistence.PushSubscription;
import com.agilityhub.core.clubs.messaging.persistence.PushSubscriptionRepository;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * S11 R-11-07, the web-push devices of the caller's account (the PUSH module guards the routes): `POST` upserts by endpoint
 * (its SHA-256; one active subscription per endpoint and club) — a new device, the same browser again after an expiry or a
 * logout, or another account on it, which ends the previous owner's subscription and gets its own (a subscription never changes
 * owner, E76) — and publishes `PushSubscribed{accountId, endpoint}` when something changed; `DELETE` is the device's logout
 * (`EXPIRED`, `PushUnsubscribed`, idempotent). An endpoint that is no `https` URL, or keys that are not the base64url of a
 * point on P-256 (65 bytes, uncompressed) and a 16-byte secret, is `422 PUSH_SUBSCRIPTION_INVALID` (CATALEG_ERRORS rule 0):
 * WebPush could never encrypt to them. `deviceLabel` defaults to one read from the User-Agent («iPhone · Safari»). The URL and
 * the keys never leave this class.
 */
@Service
public class PushSubscriptionService {
    private final PushSubscriptionRepository subscriptions; private final MessagingContractAccess access; private final EventPublisher events; private final Clock clock;
    private final MessagingTransactions transactions;

    public PushSubscriptionService(PushSubscriptionRepository subscriptions, MessagingContractAccess access, EventPublisher events, Clock clock,
            MessagingTransactions transactions) {
        this.subscriptions = subscriptions; this.access = access; this.events = events; this.clock = clock; this.transactions = transactions;
    }

    /** Upsert by endpoint; a concurrent first subscription of the same endpoint is retried and becomes the upsert of the stored one. */
    public String subscribe(String endpoint, String p256dh, String auth, String deviceLabel, String userAgent) {
        validate(endpoint, p256dh, auth);
        return transactions.write(() -> upsert(endpoint, p256dh, auth, deviceLabel, userAgent));
    }
    /**
     * A subscription never changes owner (E76): another account's active subscription of the same browser ends here (`EXPIRED`,
     * `PushUnsubscribed` for that account), and the caller gets its own row — its earlier one of this endpoint again, or a new
     * one. A push still queued for the previous owner references the previous row, which the dispatcher no longer sends to.
     */
    private String upsert(String endpoint, String p256dh, String auth, String deviceLabel, String userAgent) {
        String account = access.account();
        var keys = new PushSubscription.Keys(p256dh.strip(), auth.strip());
        String agent = userAgent == null ? null : userAgent.length() > 512 ? userAgent.substring(0, 512) : userAgent;
        String label = deviceLabel == null || deviceLabel.isBlank() ? label(agent) : deviceLabel.strip();
        var now = clock.instant();
        PushSubscription own = null;
        for (var subscription : subscriptions.byEndpointHash(PushSubscription.hash(endpoint))) {
            if (subscription.accountId().equals(account)) { own = subscription; }
            else if (subscription.status() == PushSubscription.Status.ACTIVE && subscriptions.expire(subscription.id(), now)) {
                published(MessagingEvent.Kind.PushUnsubscribed, subscription);
            }
        }
        if (own != null) { return resubscribe(own, keys, label, agent); }
        var created = new PushSubscription(UUID.randomUUID().toString(), TenantContext.require(), account, endpoint, null, keys, label, agent,
                PushSubscription.Status.ACTIVE, 0, null, null, 0L, now, account, now, account);
        subscriptions.insert(created);
        published(MessagingEvent.Kind.PushSubscribed, created);
        return created.id();
    }

    /** The device's logout: `ACTIVE` → `EXPIRED` + `PushUnsubscribed`; an expired one stays as it is. Another account's is 404. */
    public void unsubscribe(String id) {
        transactions.run(() -> {
            var subscription = access.ownSubscription(id);
            if (subscriptions.expire(subscription.id(), clock.instant())) { published(MessagingEvent.Kind.PushUnsubscribed, subscription); }
        });
    }

    private String resubscribe(PushSubscription current, PushSubscription.Keys keys, String label, String agent) {
        boolean same = current.status() == PushSubscription.Status.ACTIVE && Objects.equals(current.keys(), keys) && Objects.equals(current.deviceLabel(), label);
        if (same) { return current.id(); }
        String account = current.accountId();
        if (!subscriptions.resubscribe(current, keys, label, agent, clock.instant(), account)) { throw new ApiException(ErrorCode.STALE_VERSION); }
        published(MessagingEvent.Kind.PushSubscribed, new PushSubscription(current.id(), current.clubId(), account, current.endpoint(), current.endpointHash(), keys,
                label, agent, PushSubscription.Status.ACTIVE, 0, current.lastSuccessAt(), null, current.version(), current.createdAt(), current.createdBy(),
                clock.instant(), account));
        return current.id();
    }

    private void published(MessagingEvent.Kind kind, PushSubscription subscription) {
        var user = CurrentUser.current();
        events.publish(new MessagingEvent(kind, subscription.clubId(), subscription.id(), clock.instant(),
                Map.of("accountId", subscription.accountId(), "endpoint", subscription.endpointHash()), user == null ? null : user.accountId(), null,
                user == null ? DomainEvent.Origin.SYSTEM : Objects.requireNonNullElse(user.origin(), DomainEvent.Origin.APP)));
    }

    static void validate(String endpoint, String p256dh, String auth) {
        try {
            var uri = URI.create(endpoint.strip());
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) { throw invalid("endpoint"); }
        } catch (IllegalArgumentException malformed) { throw invalid("endpoint"); }
        byte[] key = decode(p256dh, "keys.p256dh"), secret = decode(auth, "keys.auth");
        if (key.length != 65 || key[0] != 0x04 || !onP256(key)) { throw invalid("keys.p256dh"); }
        if (secret.length != 16) { throw invalid("keys.auth"); }
    }
    /**
     * An uncompressed point `04 ‖ x ‖ y` is a P-256 public key when both coordinates are field elements and y² = x³ + ax + b
     * (mod p). The curve's cofactor is 1, so every point on it is in the group; `(0, 0)` and any other point off the curve is
     * refused (the WebPush encryption would fail on it at every send).
     */
    static boolean onP256(byte[] key) {
        var curve = P256.getCurve();
        var p = ((java.security.spec.ECFieldFp) curve.getField()).getP();
        var x = new java.math.BigInteger(1, java.util.Arrays.copyOfRange(key, 1, 33)); var y = new java.math.BigInteger(1, java.util.Arrays.copyOfRange(key, 33, 65));
        if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0) { return false; }
        var right = x.pow(3).add(curve.getA().multiply(x)).add(curve.getB()).mod(p);
        return y.pow(2).mod(p).equals(right);
    }
    private static final java.security.spec.ECParameterSpec P256 = p256();
    private static java.security.spec.ECParameterSpec p256() {
        try {
            var parameters = java.security.AlgorithmParameters.getInstance("EC");
            parameters.init(new java.security.spec.ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(java.security.spec.ECParameterSpec.class);
        } catch (java.security.GeneralSecurityException missing) { throw new IllegalStateException("The JDK has no secp256r1", missing); }
    }
    private static byte[] decode(String value, String field) {
        try { return Base64.getUrlDecoder().decode(value.strip()); }
        catch (IllegalArgumentException notBase64Url) { throw invalid(field); }
    }
    private static ApiException invalid(String field) { return new ApiException(ErrorCode.PUSH_SUBSCRIPTION_INVALID, Map.of("field", field)); }

    /** «iPhone · Safari» from a User-Agent: the device family and the browser, the parts it can tell. */
    static String label(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) { return null; }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        String device = ua.contains("iphone") ? "iPhone" : ua.contains("ipad") ? "iPad" : ua.contains("android") ? "Android"
                : ua.contains("macintosh") || ua.contains("mac os") ? "Mac" : ua.contains("windows") ? "Windows" : ua.contains("linux") ? "Linux" : null;
        String browser = ua.contains("edg/") ? "Edge" : ua.contains("opr/") || ua.contains("opera") ? "Opera" : ua.contains("firefox/") || ua.contains("fxios/") ? "Firefox"
                : ua.contains("samsungbrowser/") ? "Samsung Internet" : ua.contains("chrome/") || ua.contains("crios/") ? "Chrome" : ua.contains("safari/") ? "Safari" : null;
        if (device == null && browser == null) { return null; }
        return device == null ? browser : browser == null ? device : device + " · " + browser;
    }
}
