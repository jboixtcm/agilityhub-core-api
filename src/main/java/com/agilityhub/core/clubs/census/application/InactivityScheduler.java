package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.*;
import com.agilityhub.core.clubs.census.persistence.inactivity.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.*;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Plain S13 transition service; E8-T06 owns job wiring. */
@Service
public class InactivityScheduler {
    private final InactivityPeriodRepository periods; private final InactivityPeriodService service; private final CensusAccess census;
    private final LifecycleTransactions transactions; private final Clock clock;
    private final org.springframework.beans.factory.ObjectProvider<InactivityScheduler> self;
    public InactivityScheduler(InactivityPeriodRepository periods, InactivityPeriodService service, CensusAccess census,
            LifecycleTransactions transactions, Clock clock, org.springframework.beans.factory.ObjectProvider<InactivityScheduler> self) {
        this.periods = periods; this.service = service; this.census = census; this.transactions = transactions; this.clock = clock; this.self = self;
    }
    public int startDue(String clubId, LocalDate today) { return due(clubId, today, "APPROVED"); }
    public int finishDue(String clubId, LocalDate today) { return due(clubId, today, "ACTIVE"); }
    public int expireStale(String clubId, LocalDate today) { return due(clubId, today, "REQUESTED"); }
    private int due(String clubId, LocalDate today, String state) {
        try (var tenant = TenantContext.open(clubId)) {
            if (!census.enabled(Module.INACTIVITY)) { return 0; }
            int count = 0;
            for (var p : periods.inStates(state)) { if (transactions.run(() -> self.getObject().transition(p.id(), today, state))) { count++; } }
            return count;
        }
    }
    @Audited(action = AuditAction.INACTIVITY_RESOLVED, entityType = "'InactivityPeriod'", entity = "#id")
    public boolean transition(String id, LocalDate today, String state) {
        census.members.lock(); var p = service.get(id); var month = YearMonth.from(today); var e = new InactivityEdit(p);
        if (!p.state().name().equals(state)) { return false; }
        String event;
        if (p.state() == InactivityState.APPROVED && !YearMonth.parse(p.fromMonth()).isAfter(month)) {
            e.state = InactivityState.ACTIVE; e.startedAt = clock.instant(); event = "InactivityStarted";
        } else if (p.state() == InactivityState.ACTIVE && p.toMonth() != null && YearMonth.parse(p.toMonth()).isBefore(month)) {
            e.state = InactivityState.FINISHED; e.finishedAt = clock.instant(); e.finishReason = p.finishReason() == InactivityFinishReason.LEAVE ? InactivityFinishReason.LEAVE : InactivityFinishReason.SCHEDULED;
            event = "InactivityEnded";
        } else if (p.state() == InactivityState.REQUESTED && YearMonth.parse(p.fromMonth()).isBefore(month)) {
            service.cancelled(e, LifecycleCanceller.SYSTEM, InactivityCancelReason.EXPIRED); event = null;
        } else { return false; }
        var saved = service.save(e, p.version()); if (event != null) { service.emit(event, saved, Map.of()); } return true;
    }
}
