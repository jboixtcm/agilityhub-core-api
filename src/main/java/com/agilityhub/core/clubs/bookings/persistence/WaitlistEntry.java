package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.domain.audit.AuditField;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/** S08 §3 waitlist entry; `confirmBy` only in FIFO mode (R-08-14); never deleted (BR-12). */
@Document("waitlist_entries")
public record WaitlistEntry(@Id String id, String clubId, String classSessionId, String dogId, String memberId, String accountId,
        Instant joinedAt, @AuditField WaitlistState state, int position, Instant notifiedAt, Instant confirmBy,
        String bookingId, @AuditField Instant cancelledAt, @AuditField WaitlistCancelReason cancelReason,
        Instant classStartsAt, String bookingWeekKey,
        @Version Long version, Instant createdAt, String createdByAccountId, Instant updatedAt, String updatedByAccountId) implements TenantEntity { }
