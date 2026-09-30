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
 * (unique per club through its SHA-256) — a new device, or the same browser again after an expiry, a logout or another account
 * on it — and publishes `PushSubscribed{accountId, endpoint}` when something changed; `DELETE` is the device's logout (`EXPIRED`,
 * `PushUnsubscribed`, idempotent). An endpoint that is no `https` URL, or keys that are not the base64url of a P-256 public key
 * (65 bytes) and a 16-byte secret, is `422 PUSH_SUBSCRIPTION_INVALID` (CATALEG_ERRORS rule 0): WebPush could never encrypt to
 * them. `deviceLabel` defaults to one read from the User-Agent («iPhone · Safari»). The URL and the keys never leave this class.
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
    private String upsert(String endpoint, String p256dh, String auth, String deviceLabel, String userAgent) {
        String account = access.account();
        var keys = new PushSubscription.Keys(p256dh.strip(), auth.strip());
        String agent = userAgent == null ? null : userAgent.length() > 512 ? userAgent.substring(0, 512) : userAgent;
        String label = deviceLabel == null || deviceLabel.isBlank() ? label(agent) : deviceLabel.strip();
        var now = clock.instant();
        var existing = subscriptions.findByEndpointHash(PushSubscription.hash(endpoint));
        if (existing.isPresent()) { return resubscribe(existing.get(), account, keys, label, agent); }
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

    private String resubscribe(PushSubscription current, String account, PushSubscription.Keys keys, String label, String agent) {
        boolean same = current.status() == PushSubscription.Status.ACTIVE && current.accountId().equals(account) && Objects.equals(current.keys(), keys)
                && Objects.equals(current.deviceLabel(), label);
        if (same) { return current.id(); }
        if (!subscriptions.resubscribe(current, account, keys, label, agent, clock.instant(), account)) { throw new ApiException(ErrorCode.STALE_VERSION); }
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
        if (key.length != 65 || key[0] != 0x04) { throw invalid("keys.p256dh"); }
        if (secret.length != 16) { throw invalid("keys.auth"); }
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
