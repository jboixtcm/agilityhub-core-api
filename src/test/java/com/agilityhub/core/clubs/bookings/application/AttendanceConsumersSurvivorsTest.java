package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.AttendanceState;
import com.agilityhub.core.clubs.bookings.domain.BookingEvent;
import com.agilityhub.core.clubs.bookings.domain.ForeignEvent;
import com.agilityhub.core.clubs.bookings.persistence.Attendance;
import com.agilityhub.core.clubs.bookings.persistence.AttendanceRepository;
import com.agilityhub.core.clubs.scheduling.application.InstructorScheduleAccess;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link AttendanceConsumers} (S10 §7): both outbox handlers delegate to the consumer, and a
 * `BookingCancelled` of a booking whose stored mark is not PRESENT/NO_SHOW leaves the trail alone. The PRESENT/NO_SHOW
 * branch itself is not driven here: S08 refuses to cancel a marked booking (`BookingCancellationService#cancel`,
 * `attendance.marked`) and a cancelled booking cannot be marked (`AttendanceTransitions#decide`), so no real event reaches it.
 * A stored PENDING mark does reach line 49: an instructor or an admin may put a mark back to PENDING (R-10-03,
 * `AttendanceWindow#canMarkPresence`), and `marked` is then false, so the member can still cancel.
 */
class AttendanceConsumersSurvivorsTest {
    static final Instant UPDATED_AT = Instant.parse("2026-10-05T08:10:00Z");
    static final Instant CANCELLED_AT = Instant.parse("2026-10-05T09:30:00.123Z");
    /** Monday 5 October, 18:00–19:00 Madrid. */
    static final Instant CLASS_STARTS = Instant.parse("2026-10-05T16:00:00Z");

    @Test void E11_T06_theClassSessionUpdatedHandlerDelegatesToTheConsumer() throws Exception {
        var target = mock(AttendanceConsumers.class);
        // ClassSessionService#update publishes {classId, diff, bookedCount} on the ClassSession aggregate.
        var event = new ForeignEvent("ClassSessionUpdated", null, "club-a", "ClassSession", "class-1", UPDATED_AT,
                Map.of("classId", "class-1", "diff", Map.of(), "bookedCount", 0), "account-admin", null, DomainEvent.Origin.BACKOFFICE);

        new AttendanceConsumers.Handlers().classUpdated(target).handle("event-1", event);

        verify(target).classUpdated(event);
    }

    @Test void E11_T06_theBookingCancelledHandlerDelegatesToTheConsumer() throws Exception {
        var target = mock(AttendanceConsumers.class);
        // BookingCancellationService#cancel payload as read back from the outbox (enums as their names).
        var event = new BookingEvent(BookingEvent.Kind.BookingCancelled, "club-a", "booking-1", CANCELLED_AT, Map.of("bookingId", "booking-1",
                "by", "MEMBER", "late", false, "minutesBefore", 300, "origin", "APP", "reason", "MEMBER"), "account-laura", null, DomainEvent.Origin.APP);

        new AttendanceConsumers.Handlers().bookingCancelled(target).handle("event-2", event);

        verify(target).bookingCancelled(event);
    }

    @Test void E11_T06_aMemberCancellationAfterTheMarkWasPutBackToPendingLeavesTheTrailAlone() {
        var attendances = mock(AttendanceRepository.class);
        var transactions = mock(BookingTransactions.class);
        doAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get()).when(transactions).write(any(), any());
        var consumers = new AttendanceConsumers(attendances, mock(InstructorScheduleAccess.class), transactions);
        // On the class day (the window opens at 00:00, AttendanceWindow#of) Neus marked Duna PRESENT by mistake and put her back
        // to PENDING a minute later (AttendanceTransitions MARK): the stored row stays, with state PENDING, so
        // AttendanceStatePort#marked is false and Laura cancels in time; S08 publishes BookingCancelled{by MEMBER, origin APP}.
        var marked = Instant.parse("2026-10-05T07:00:00Z"); var unmarked = marked.plusSeconds(60);
        when(attendances.findByBooking("booking-1")).thenReturn(Optional.of(new Attendance("attendance-1", "club-a", "booking-1", "class-1",
                LocalDate.parse("2026-10-05"), CLASS_STARTS, CLASS_STARTS.plusSeconds(3600), "dog-duna", "member-laura", AttendanceState.PENDING,
                unmarked, new Attendance.Marker("account-neus", ActorRole.INSTRUCTOR, "Neus"), null, null,
                List.of(new Attendance.Change(AttendanceState.PRESENT, marked, "account-neus"), new Attendance.Change(AttendanceState.PENDING, unmarked, "account-neus")),
                1L, marked, unmarked)));
        var event = new BookingEvent(BookingEvent.Kind.BookingCancelled, "club-a", "booking-1", CANCELLED_AT, Map.of("bookingId", "booking-1",
                "by", "MEMBER", "late", false, "minutesBefore", 389, "origin", "APP", "reason", "MEMBER"), "account-laura", null, DomainEvent.Origin.APP);

        consumers.bookingCancelled(event);

        // S10 §7: only a PRESENT/NO_SHOW mark contradicts the cancellation; a PENDING one gets no trail entry and no write.
        verify(attendances).findByBooking("booking-1");
        verify(attendances, never()).appendHistory(any(), any());
        verifyNoInteractions(transactions);
    }
}
