package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.LeaveSource;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

/** S13 R-13-13: strictly after leaveDate; a delayed or repeated run has the same result. */
@Service
public class LeaveScheduler {
    private final CensusAccess census; private final LeaveRequestService leaves; private final MemberStatusService statuses;
    private final LifecycleTransactions transactions; private final Clock clock;
    public LeaveScheduler(CensusAccess census, LeaveRequestService leaves, MemberStatusService statuses, LifecycleTransactions transactions, Clock clock) {
        this.census = census; this.leaves = leaves; this.statuses = statuses; this.transactions = transactions; this.clock = clock;
    }
    public int executeDue(String clubId, LocalDate today) {
        try (var tenant = TenantContext.open(clubId)) {
            int count = 0;
            for (var candidate : census.members.matching(Criteria.where("status").is("ACTIVE"))) {
                if (candidate.leaveDate == null || !candidate.leaveDate.isBefore(today)) { continue; }
                boolean changed = transactions.run(() -> execute(candidate.id, today));
                if (changed) { count++; }
            }
            return count;
        }
    }
    /** One P5 item, inside the runner's transaction together with its cancellation sweep and outbox. */
    public boolean execute(String memberId, LocalDate today) {
        census.members.lock(); var member = census.members.require(memberId);
        if (!"ACTIVE".equals(member.status) || member.leaveDate == null || !member.leaveDate.isBefore(today)) { return false; }
        var request = member.leaveRequestId == null ? null : leaves.get(member.leaveRequestId);
        leaves.sweep(member.id, member.leaveDate); leaves.closePeriods(member.id, member.leaveDate, true);
        member.leftAt = clock.instant(); member.leftReason = request == null ? "MIGRATED" : request.source() == LeaveSource.MEMBER ? "LEAVE_REQUEST" : request.source().name();
        census.members.save(member); statuses.transition(member.id, "LEFT", member.leaveDate, member.leftReason);
        if (request != null) { leaves.markExecuted(request.id()); }
        return true;
    }
}
