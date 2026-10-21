package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.ImpersonationGrant;
import com.agilityhub.core.identity.persistence.ImpersonationGrantRepository;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * INC-37 (S01 R-01-09, §7): an impersonation that runs out ends with `ImpersonationEnded{reason: EXPIRED}`, as one ended by «Surt»
 * ends with `LOGOUT`. Every minute, the impersonations past their `expiresAt` that nothing ended yet are ended, each in its own
 * club and transaction. Each grant ends once whichever instance, or a logout, gets there first (a conditional write), so
 * instances may overlap. Not an S15 process: no club parameter or switch, like the outbox dispatcher.
 */
@Component
public class ImpersonationExpiry {
    /** The grants one page of a sweep reads. */
    public static final int BATCH = 200;
    private static final Logger LOG = LoggerFactory.getLogger(ImpersonationExpiry.class);
    private final ImpersonationGrantRepository grants;
    private final ImpersonationService impersonations;
    private final Clock clock;
    public ImpersonationExpiry(ImpersonationGrantRepository grants, ImpersonationService impersonations, Clock clock) {
        this.grants = grants; this.impersonations = impersonations; this.clock = clock;
    }
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void scheduled() { sweep(clock.instant()); }
    /**
     * The grants ended `EXPIRED` at {@code now}. E11-T09 round 2: page by page, each after the last grant of the one before, so
     * the grants that keep failing never hold back a newer one, however many they are.
     */
    public int sweep(Instant now) {
        int ended = 0;
        List<ImpersonationGrant> page;
        ImpersonationGrant last = null;
        do {
            page = grants.expiredOpen(now, last, BATCH);
            for (var grant : page) {
                try (var tenant = TenantContext.open(grant.clubId())) {
                    if (impersonations.expire(grant.id(), now)) { ended++; }
                } catch (RuntimeException failure) {
                    // One grant's failure never stops the others; the next sweep tries it again. One line a minute, not a trace.
                    LOG.warn("Impersonation expiry failed grantId={} error={}", grant.id(), failure.getClass().getName());
                    LOG.debug("Impersonation expiry failure grantId={}", grant.id(), failure);
                }
                last = grant;
            }
        } while (page.size() == BATCH);
        return ended;
    }
}
