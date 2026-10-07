package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.application.ports.LapsedCheckoutsPort;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.jobs.JobContext;
import com.agilityhub.core.platform.application.jobs.JobEffect;
import com.agilityhub.core.platform.application.jobs.JobItem;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * S15 R-15-15 step h of P5 (`BILLING`; S04 R-04-26, ruling E80): every open signup checkout past its `expiresAt` expires and its
 * rows are payable again (`DUE`), whether its expiry never reached us (a lost provider callback) or the provider never had the
 * session (the process stopped after the checkout was prepared), and also with the provider switched off. Item
 * `EXPIRE_CHECKOUT {checkoutSessionId, minutesExpired}` (`WOULD_EXPIRE_CHECKOUT` in a dry run), counter `expiredCheckouts`.
 * Idempotent by state: an expired session is no longer `PENDING`, so a second run finds nothing.
 * <p>
 * The cut-off is the occurrence (`scheduledFor`), so the plan and the effects see the same sessions (as P9); a session that
 * lapses later waits for the next run, or for the next write that needs its rows (`CheckoutService.releaseLapsed`), and the
 * reads show its rows payable meanwhile. It reaches the checkouts through {@link LapsedCheckoutsPort} only.
 */
@Component
public class SignupCheckoutExpiryStep implements ExpirationStep {
    static final String CHECKOUT = "CheckoutSession";
    static final String EXPIRE = "EXPIRE_CHECKOUT";
    private final LapsedCheckoutsPort checkouts;
    public SignupCheckoutExpiryStep(LapsedCheckoutsPort checkouts) { this.checkouts = checkouts; }
    @Override public char letter() { return 'h'; }
    @Override public Module module() { return Module.BILLING; }
    @Override public java.util.Set<String> actions() { return java.util.Set.of(EXPIRE); }
    @Override public java.util.Set<String> counters() { return java.util.Set.of("expiredCheckouts"); }

    @Override public List<JobItem> plan(JobContext context) {
        var now = context.scheduledFor();
        return checkouts.lapsed(now).stream().map(lapsed -> new JobItem(CHECKOUT, lapsed.checkoutSessionId(), EXPIRE, Map.of(
                "checkoutSessionId", lapsed.checkoutSessionId(), "minutesExpired", Duration.between(lapsed.expiresAt(), now).toMinutes()))).toList();
    }

    @Override public JobEffect apply(JobContext context, JobItem item) {
        return checkouts.expire(item.entityId(), context.scheduledFor())
                ? new JobEffect(EXPIRE, item.detail(), Map.of("expiredCheckouts", 1L)) : ExpirationsJob.NOT_IN_SCOPE;
    }
}
