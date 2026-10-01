package com.agilityhub.core.clubs.census.persistence.leave;

import com.agilityhub.core.clubs.census.domain.*;
import com.agilityhub.core.clubs.census.persistence.LifecycleParts.CancelledBooking;
import com.agilityhub.core.clubs.census.persistence.LifecycleParts.Requester;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.domain.audit.AuditField;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S13 §3 leave request (`SOL_LICITUD_BAIXA`). `requestedDate` and `decision.effectiveDate` are club-local `YYYY-MM-DD`;
 * the effective date becomes `Member.leaveDate` (R-13-10). `reasonKey` is a key of `leave.reasons` (`PACK_EXPIRED` for the
 * automatic one, R-13-14); `nps` only with `leave.npsEnabled`. Nothing is deleted.
 */
@Document("leave_requests")
public record LeaveRequest(@Id String id, String clubId, String memberId, LeaveSource source, LifecycleOrigin origin, Instant requestedAt,
        Requester requestedBy, String requestedDate, String reasonKey, Integer nps, String comment, @AuditField LeaveRequestState state,
        @AuditField Decision decision, Instant executedAt, Instant cancelledAt, LifecycleCanceller cancelledBy, LeaveCancelReason cancelReason,
        List<CancelledBooking> cancelledBookings, String packBalanceId, @Version Long version, Instant createdAt, Instant updatedAt) implements TenantEntity {
    /** `effectiveDate` is null on a `DENIED` decision. */
    public record Decision(Instant at, String byAccountId, LifecycleDecision decision, String effectiveDate, String note) { }
}
