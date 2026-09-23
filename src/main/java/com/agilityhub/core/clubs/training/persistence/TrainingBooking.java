package com.agilityhub.core.clubs.training.persistence;

import com.agilityhub.core.clubs.training.domain.*;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.domain.audit.AuditField;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S09 §3 free-training booking. `seatIndex` (0..capacity-1) is the concurrency guard of R-09-06 and is never
 * exposed; `weekStart` is the training week of the slot (R-09-05); `reminderSentAt` is the S15 P4 mark.
 */
@Document("training_bookings")
public record TrainingBooking(@Id String id, String clubId, String memberId, String dogId, String ringId,
        Instant startsAt, Instant endsAt, String slotId, int seatIndex, Instant weekStart,
        @AuditField TrainingBookingState state, TrainingOrigin origin,
        String createdByAccountId, String impersonatedByAccountId,
        @AuditField Instant cancelledAt, @AuditField TrainingCancelledBy cancelledBy, @AuditField TrainingCancelReason cancelReason, String cancelNote,
        String idempotencyKey, Instant reminderSentAt,
        @Version Long version, Instant createdAt, Instant updatedAt, String updatedByAccountId) implements TenantEntity { }
