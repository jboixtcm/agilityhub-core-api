package com.agilityhub.core.clubs.census.persistence.inactivity;

import com.agilityhub.core.clubs.census.domain.*;
import com.agilityhub.core.clubs.census.persistence.LifecycleParts.CancelledBooking;
import com.agilityhub.core.clubs.census.persistence.LifecycleParts.Requester;
import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.domain.audit.AuditField;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S13 §3 inactivity period (`PERIODE_INACTIVITAT`), by whole months: `fromMonth`/`toMonth` are `YYYY-MM` (`toMonth` null =
 * «encara no ho sé», open). `feeSnapshot` is frozen at approval with `BILLING` (R-13-08); `history[]` is append-only, one row
 * per change of months (R-13-04). Nothing is deleted: a withdrawn or expired request is `CANCELLED`.
 */
@Document("inactivity_periods")
public record InactivityPeriod(@Id String id, String clubId, String memberId, @AuditField String fromMonth, @AuditField String toMonth,
        String comments, @AuditField InactivityState state, LifecycleOrigin origin, Instant requestedAt, Requester requestedBy,
        @AuditField Decision decision, FeeSnapshot feeSnapshot, Instant startedAt, Instant finishedAt, InactivityFinishReason finishReason,
        Instant cancelledAt, LifecycleCanceller cancelledBy, InactivityCancelReason cancelReason, List<CancelledBooking> cancelledBookings,
        List<HistoryEntry> history, @Version Long version, Instant createdAt, Instant updatedAt) implements TenantEntity {
    /** R-13-05: `deadlineOverridden` only when the admin skipped R-13-03's day-25 rule (audited). */
    public record Decision(Instant at, String byAccountId, LifecycleDecision decision, String note, boolean deadlineOverridden) { }
    public record FeeSnapshot(Money firstMonth, Money followingMonths) { }
    public record HistoryEntry(Instant at, String byAccountId, String fromMonth, String toMonth, ChangeSource source) { }
}
