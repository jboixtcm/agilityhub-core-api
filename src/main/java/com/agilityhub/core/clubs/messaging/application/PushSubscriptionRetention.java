package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.persistence.PushSubscriptionRepository;
import com.agilityhub.core.platform.application.jobs.JobContext;
import com.agilityhub.core.platform.application.privacy.RetentionTask;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * COMM-10 (owner decision of 13 Nov 2026, S14 R-14-16 (h)): the monthly `retention-sweep` deletes the club's push subscriptions
 * that have been `EXPIRED` (a 404/410 of the push service, three failures in a row, a logout or another account on the browser,
 * R-11-07) for {@value #DAYS} days: their endpoint, keys and device label are of no use any more. One item per subscription;
 * a subscription the browser renewed meanwhile is `ACTIVE` again and stays. An `ACTIVE` one is never touched here (an erased
 * account's go with R-14-15).
 */
@Component
public class PushSubscriptionRetention implements RetentionTask {
    /** How long an `EXPIRED` subscription is kept after `expiredAt` (a product constant of the owner's decision). */
    static final long DAYS = 90;
    private final PushSubscriptionRepository subscriptions;
    public PushSubscriptionRetention(PushSubscriptionRepository subscriptions) { this.subscriptions = subscriptions; }

    @Override public String entityType() { return "PushSubscription"; }
    @Override public String action() { return "PURGE_EXPIRED_PUSH_SUBSCRIPTION"; }
    @Override public String counter() { return "expiredPushSubscriptionsPurged"; }
    @Override public List<String> due(JobContext context) { return subscriptions.expiredBefore(cutoff(context)); }
    @Override public boolean apply(JobContext context, String subscriptionId) { return subscriptions.deleteExpired(subscriptionId, cutoff(context)); }

    static Instant cutoff(JobContext context) { return context.scheduledFor().minus(Duration.ofDays(DAYS)); }
}
