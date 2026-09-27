package com.agilityhub.core.clubs.messaging.application.integrations;

/**
 * S11 R-11-07 web-push payload `{notificationId, title, body, icon, url, tag: code}`: the service worker shows it and opens
 * `url` (`/notificacions` or the action's route) on click.
 */
public record PushPayload(String notificationId, String title, String body, String icon, String url, String tag) {
    @Override public String toString() { return "PushPayload[notificationId=" + notificationId + ", tag=" + tag + "]"; }
}
