package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.domain.audit.AuditField;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S08 §3 class booking. `memberId` is the dog owner (the «member + dog» unit); who booked is `bookedBy`.
 * `classStartsAt`, `classEndsAt` and `bookingWeekKey` (club-local `YYYY-MM-DD` of the opening instant, R-08-01)
 * are denormalised for the weekly counters. `reminderSentAt` is the S15 P4 mark. Never deleted (BR-12).
 */
@Document("bookings")
public record Booking(@Id String id, String clubId, String classSessionId, String dogId, String memberId,
        @AuditField BookingState state, BookingOrigin origin, Instant bookedAt, Actor bookedBy,
        Instant classStartsAt, Instant classEndsAt, String bookingWeekKey,
        @AuditField Instant cancelledAt, Canceller cancelledBy, @AuditField BookingCancelReason cancelReason, String cancelMessage,
        @AuditField Boolean late, Integer minutesBefore,
        String swapFromBookingId, String swapToBookingId, String waitlistEntryId, String packMovementId, String packRefundMovementId,
        Charge charge, Instant reminderSentAt,
        @Version Long version, Instant createdAt, String createdByAccountId, Instant updatedAt, String updatedByAccountId) implements TenantEntity {
    public record Actor(String accountId, String impersonatedMemberId, String displayName) { }
    public record Canceller(String accountId, ActorRole role, String displayName, String impersonatedMemberId) { }
    public record Charge(ChargeMode mode, Money price, String chargeInvoiceLineRef, String checkoutSessionId, String paymentIntentId, Instant paidAt) { }
}
