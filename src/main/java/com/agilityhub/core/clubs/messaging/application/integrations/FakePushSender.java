package com.agilityhub.core.clubs.messaging.application.integrations;

import com.agilityhub.core.clubs.messaging.persistence.PushSubscription;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Profiles `test` and `local` without VAPID keys: every push is accepted and remembered in memory; {@link #answer} scripts
 * the next results (T-11-10).
 */
public final class FakePushSender implements PushSender {
    public record Sent(String subscriptionId, PushPayload payload) { }
    private final ConcurrentLinkedDeque<Sent> sent = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<PushResult> scripted = new ConcurrentLinkedDeque<>();
    private final String publicKey;

    public FakePushSender(String publicKey) { this.publicKey = publicKey == null || publicKey.isBlank() ? null : publicKey; }

    @Override public PushResult send(PushSubscription subscription, PushPayload payload) {
        var next = scripted.poll();
        if (next != null) { return next; }
        sent.add(new Sent(subscription.id(), payload));
        return PushResult.ok();
    }
    @Override public String publicKey() { return publicKey; }
    public void answer(PushResult result) { scripted.add(result); }
    public List<Sent> sent() { return new ArrayList<>(sent); }
    public void clear() { sent.clear(); scripted.clear(); }
}
