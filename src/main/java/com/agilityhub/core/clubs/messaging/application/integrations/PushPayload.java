package com.agilityhub.core.clubs.messaging.application.integrations;

/**
 * S11 R-11-07 web-push payload `{notificationId, title, body, icon, url, tag: code}`: the service worker shows it and opens
 * `url` (`/notificacions` or the action's route) on click. `ttlSeconds` is not part of the payload: it is the request's `TTL`
 * header (COMM-10: per code, {@link com.agilityhub.core.clubs.messaging.domain.PushTtl}); null = the sender's club default.
 */
public record PushPayload(String notificationId, String title, String body, String icon, String url, String tag, Long ttlSeconds) {
    /** Without a TTL of its own: the sender's `messaging.push.ttlMinutes`. */
    public PushPayload(String notificationId, String title, String body, String icon, String url, String tag) {
        this(notificationId, title, body, icon, url, tag, null);
    }
    @Override public String toString() { return "PushPayload[notificationId=" + notificationId + ", tag=" + tag + ", ttlSeconds=" + ttlSeconds + "]"; }
}
