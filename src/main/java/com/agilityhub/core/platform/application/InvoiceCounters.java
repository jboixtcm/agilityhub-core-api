package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.ParameterRepository;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * S12 R-12-08 / R-12-14 (E8-T02): the receipt counters of the open club (`CLUB.billing.counters`), the only writer of the
 * `clubs` document on behalf of `payments`, which never writes it directly. Every call joins the caller's Mongo transaction
 * (the billing run's, a manual invoice's or the rollback's), so a number is taken and given back atomically with the
 * invoices that use it; two writers of one counter meet as a write conflict that their transaction retries. The club
 * configuration cache is evicted after the commit, since the club's version moved.
 */
@Service
public class InvoiceCounters {
    private final ClubRepository clubs; private final ParameterRepository parameters; private final ClubConfigService configs;
    public InvoiceCounters(ClubRepository clubs, ParameterRepository parameters, ClubConfigService configs) {
        this.clubs = clubs; this.parameters = parameters; this.configs = configs;
    }

    /** The first of {@code count} consecutive numbers of the counter {@code key} (R-12-08). */
    public long reserve(String key, int count) {
        String club = TenantContext.require();
        long first = clubs.reserveInvoiceNumbers(club, key, count);
        evict(club);
        return first;
    }
    /** The number the next invoice of {@code key} would take, empty before the first one. */
    public Optional<Long> next(String key) { return clubs.invoiceCounter(TenantContext.require(), key); }
    /**
     * R-12-14: the counter goes back to {@code restoreTo} only while it is still {@code expectedNext}, i.e. while the block
     * being given back is the last one numbered; false when something was numbered after it (`MANUAL_INVOICE_AFTER`).
     */
    public boolean restore(String key, long expectedNext, long restoreTo) {
        String club = TenantContext.require();
        boolean restored = clubs.restoreInvoiceCounter(club, key, expectedNext, restoreTo);
        if (restored) { evict(club); }
        return restored;
    }
    private void evict(String club) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) { configs.invalidateAfterCommit(club); }
        else { configs.invalidate(club); }
    }
    /** R-12-07: when an override of a parameter whose key starts with {@code prefix} last changed (`billing.`), if ever. */
    public Optional<Instant> parametersChangedAt(String prefix) { return parameters.lastChange(prefix); }
}
