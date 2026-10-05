package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.InactivityState;
import com.agilityhub.core.clubs.census.inactivity.domain.InactivityCalendar;
import com.agilityhub.core.clubs.census.persistence.inactivity.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.InactivityFeePort;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.Money;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;

/** R-13-08: reads the frozen approval fee, never the current parameter values. */
@Service
public class InactivityFeeService implements InactivityFeePort {
    private final InactivityPeriodRepository periods;
    private final CensusAccess census;
    public InactivityFeeService(InactivityPeriodRepository periods, CensusAccess census) { this.periods = periods; this.census = census; }
    public InactivityPeriod.FeeSnapshot snapshot() {
        if (!census.enabled(Module.BILLING)) { return null; }
        return new InactivityPeriod.FeeSnapshot(census.config().get("billing.inactivityFeeFirstMonth", Money.class),
                census.config().get("billing.inactivityFeeFollowingMonths", Money.class));
    }
    @Override public Optional<Money> feeFor(String memberId, YearMonth month) {
        if (!census.enabled(Module.BILLING) || !census.enabled(Module.INACTIVITY)) { return Optional.empty(); }
        var member = census.members.require(memberId);
        if (!"MONTHLY".equals(census.references.plan(member.planId).get("type"))) { return Optional.empty(); }
        return periods.ofMember(memberId).stream().map(p -> fee(p, month)).flatMap(Optional::stream).findFirst();
    }
    public static Optional<Money> fee(InactivityPeriod p, YearMonth month) {
        if (!Set.of(InactivityState.APPROVED, InactivityState.ACTIVE, InactivityState.FINISHED).contains(p.state()) || p.feeSnapshot() == null
                || !InactivityCalendar.covers(YearMonth.parse(p.fromMonth()), p.toMonth() == null ? null : YearMonth.parse(p.toMonth()), month)) { return Optional.empty(); }
        return Optional.of(month.toString().equals(p.fromMonth()) ? p.feeSnapshot().firstMonth() : p.feeSnapshot().followingMonths());
    }
    @Override public List<MemberFee> feesForMonth(YearMonth month) {
        if (!census.enabled(Module.BILLING) || !census.enabled(Module.INACTIVITY)) { return List.of(); }
        return periods.inStates("APPROVED", "ACTIVE", "FINISHED").stream()
                .filter(p -> "MONTHLY".equals(census.references.plan(census.members.require(p.memberId()).planId).get("type")))
                .flatMap(p -> fee(p, month).stream().map(amount -> new MemberFee(p.memberId(), amount, p.fromMonth().equals(month.toString())))).toList();
    }
    public List<MemberFee> feesForMonth(String clubId, YearMonth month) {
        try (var scope = TenantContext.open(clubId)) { return feesForMonth(month); }
    }
}
