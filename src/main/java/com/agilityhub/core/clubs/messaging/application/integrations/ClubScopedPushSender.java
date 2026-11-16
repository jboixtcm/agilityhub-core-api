package com.agilityhub.core.clubs.messaging.application.integrations;

import com.agilityhub.core.clubs.messaging.persistence.PushSubscription;
import com.agilityhub.core.shared.application.TenantContext;
import java.util.HashSet;
import java.util.Set;

/** Deployment opt-in: publish the key and send only within an explicitly enabled club's scope. */
public final class ClubScopedPushSender implements PushSender {
    private final PushSender delegate;
    private final Set<String> clubs;

    public ClubScopedPushSender(PushSender delegate, Set<String> clubs) {
        this.delegate = delegate;
        this.clubs = Set.copyOf(clubs);
    }

    public static Set<String> parseClubIds(String value) {
        if (value == null || value.isBlank()) { return Set.of(); }
        var clubs = new HashSet<String>();
        for (String part : value.split(",", -1)) {
            String id = part.strip();
            if (!id.matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}") || !clubs.add(id)) {
                throw new IllegalStateException("PUSH_ENABLED_CLUB_IDS requires distinct exact club UUIDs");
            }
        }
        return Set.copyOf(clubs);
    }

    @Override public String publicKey() { return enabled() ? delegate.publicKey() : null; }

    @Override public PushResult send(PushSubscription subscription, PushPayload payload) {
        if (!enabled() || subscription == null || !subscription.clubId().equals(TenantContext.current())) {
            return PushResult.of(PushResult.Status.FAILED, "Push disabled for this club");
        }
        return delegate.send(subscription, payload);
    }

    private boolean enabled() {
        String club = TenantContext.current();
        return club != null && clubs.contains(club);
    }
}
