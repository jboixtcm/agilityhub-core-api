package com.agilityhub.core.platform.application.audit;

/** Trusted provenance of a durably authenticated webhook, including its deferred processing. */
public final class WebhookAuditContext {
    private static final ThreadLocal<String> EVENT = new ThreadLocal<>();
    private WebhookAuditContext() { }
    public static String eventId() { return EVENT.get(); }
    public static Scope open(String eventId) {
        if (eventId == null || eventId.isBlank()) { throw new IllegalArgumentException("Webhook event id is required"); }
        String previous = EVENT.get();
        EVENT.set(eventId);
        return () -> { if (previous == null) { EVENT.remove(); } else { EVENT.set(previous); } };
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
}
