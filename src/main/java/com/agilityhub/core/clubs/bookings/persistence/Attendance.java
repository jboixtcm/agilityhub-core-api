package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.AttendanceState;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.convert.ValueConverter;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S10 §3 `Attendance`: one per booking, created on the first save (no document = PENDING). Class fields are denormalised
 * from `Booking`/`ClassSession`. The model's flat `noticeSentAt` (Annex B, S15 R-15-13) is `noShowNotice.queuedAt`;
 * `markedByAccountId`/`late` are `markedBy.accountId`/`notice.late` (E6-T01 ruling: the S10 subdocuments are a superset).
 */
@Document("attendances")
public record Attendance(@Id String id, String clubId, String bookingId, String classSessionId,
        @ValueConverter(ClassDateConverter.class) LocalDate classDate, Instant classStartsAt, Instant classEndsAt,
        String dogId, String memberId, AttendanceState state, Instant markedAt, Marker markedBy, Notice notice,
        NoShowNotice noShowNotice, List<Change> history, @Version Long version, Instant createdAt, Instant updatedAt) implements TenantEntity {
    /** The last effective change: INSTRUCTOR or ADMIN (R-10-03). */
    public record Marker(String accountId, ActorRole role, String displayName) { }
    /** R-10-05 result of the S08 cancellation; `afterClassEnd` = record only, the booking stays ACTIVE. */
    public record Notice(Instant at, boolean late, Integer minutesBefore, boolean seatReleased, boolean waitlistNotified,
            boolean afterClassEnd, BookingState bookingState) { }
    /** R-10-06 idempotency of the N-19 batch: `queuedAt` is the claim (`noticeSentAt` of S15), `sentAt` the S11 delivery. */
    public record NoShowNotice(Instant queuedAt, String eventId, Instant sentAt) { }
    /** Append-only trail. */
    public record Change(AttendanceState state, Instant at, String byAccountId) { }
}
