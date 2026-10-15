package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.application.ports.LapsedRingSetupsPort;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.jobs.JobContext;
import com.agilityhub.core.platform.application.jobs.JobEffect;
import com.agilityhub.core.platform.application.jobs.JobItem;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * S15 R-15-15 step f of P5 (`COURSES`; S16 R-16-08): every `ACTIVE` ring setup with `expiresAt ≤ now` becomes `EXPIRED`, the
 * ring's `activeSetupId` is cleared when it named it, and `RingSetupChanged{EXPIRED}` is published (N-31 does not apply). Item
 * `EXPIRE_SETUP {setupId, ringId, expiresAt}` (`WOULD_EXPIRE_SETUP` in a dry run), counter `expiredSetups`. Idempotent by state:
 * an expired setup is no longer `ACTIVE`, so a second run finds nothing. The cut-off is the occurrence (`scheduledFor`), as step h.
 * It reaches the setups through {@link LapsedRingSetupsPort} only.
 */
@Component
public class RingSetupExpiryStep implements ExpirationStep {
    static final String SETUP = "RingSetup";
    static final String EXPIRE = "EXPIRE_SETUP";
    private final LapsedRingSetupsPort setups;
    public RingSetupExpiryStep(LapsedRingSetupsPort setups) { this.setups = setups; }
    @Override public char letter() { return 'f'; }
    @Override public Module module() { return Module.COURSES; }
    @Override public java.util.Set<String> actions() { return java.util.Set.of(EXPIRE); }
    @Override public java.util.Set<String> counters() { return java.util.Set.of("expiredSetups"); }

    @Override public List<JobItem> plan(JobContext context) {
        return setups.lapsed(context.scheduledFor()).stream().map(lapsed -> new JobItem(SETUP, lapsed.setupId(), EXPIRE, Map.of(
                "setupId", lapsed.setupId(), "ringId", lapsed.ringId(), "expiresAt", lapsed.expiresAt().toString()))).toList();
    }

    @Override public JobEffect apply(JobContext context, JobItem item) {
        return setups.expire(item.entityId(), context.scheduledFor())
                ? new JobEffect(EXPIRE, item.detail(), Map.of("expiredSetups", 1L)) : ExpirationsJob.NOT_IN_SCOPE;
    }
}
