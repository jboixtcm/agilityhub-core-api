package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.AttendanceEvent;
import com.agilityhub.core.clubs.bookings.domain.AttendanceState;
import com.agilityhub.core.clubs.bookings.domain.AttendanceWindow;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.persistence.Attendance;
import com.agilityhub.core.clubs.bookings.persistence.AttendanceRepository;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.SeatLockRepository;
import com.agilityhub.core.clubs.scheduling.application.InstructorScheduleAccess;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link AttendanceSheetService} (S10 R-10-03, R-10-04; T-10-02, T-10-12): the save takes the
 * class's seat lock, an unknown class is 404, a class that is not markable refuses even an empty save, a re-mark bumps
 * the stored attendance's version and an instructor's mark is announced with origin INSTRUCTOR.
 */
class AttendanceSheetServiceSurvivorsTest {
    /** Monday 2026-10-05 10:00 Madrid; the class ran at 09:00-10:00 local, inside its marking window. */
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final LocalDate DATE = LocalDate.parse("2026-10-05");
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    final BookingContext context = mock(BookingContext.class);
    final BookingTransactions transactions = mock(BookingTransactions.class);
    final SeatLockRepository locks = mock(SeatLockRepository.class);
    final InstructorScheduleAccess schedule = mock(InstructorScheduleAccess.class);
    final BookingRepository bookings = mock(BookingRepository.class);
    final AttendanceRepository attendances = mock(AttendanceRepository.class);
    final AttendanceStatusCalculator calculator = mock(AttendanceStatusCalculator.class);
    final BookingCancellationService cancellations = mock(BookingCancellationService.class);
    final AttendanceSheetQuery sheets = mock(AttendanceSheetQuery.class);
    final EventPublisher publisher = mock(EventPublisher.class);
    final AuditWriter audit = mock(AuditWriter.class);
    final AttendanceSheetService service = new AttendanceSheetService(context, transactions, locks, schedule, bookings, attendances, calculator,
            cancellations, sheets, publisher, audit);
    final AttendanceCaller instructor = new AttendanceCaller("account-neus", "Neus", false, "instructor-neus");
    TenantContext.Scope tenant;

    @BeforeEach void setUp() {
        tenant = TenantContext.open("club-a");
        when(context.now()).thenReturn(NOW);
        doAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get()).when(transactions).write(any(), any());
        // AttendanceStatusCalculator#window: AttendanceWindow.of(date, zone, attendance.editDays = 1, the catalog default).
        when(calculator.window(DATE)).thenReturn(AttendanceWindow.of(DATE, MADRID, 1));
        when(sheets.build(any(), any())).thenAnswer(inv -> new HashMap<String, Object>());
    }

    @AfterEach void tearDown() { tenant.close(); }

    @Test void T_10_02_aReMarkLocksTheClassBumpsTheStoredVersionAndIsAnnouncedAsTheInstructors() {
        // The first save (summary version 0 -> 1) inserted the NO_SHOW mark, which Mongo stored with version 0.
        when(schedule.find("class-1")).thenReturn(Optional.of(cls("ACTIVE", new InstructorScheduleAccess.Summary(1, 1, 0, 0, 1, 0, NOW.minusSeconds(600), "Neus"))));
        when(bookings.byIds(List.of("booking-1"))).thenReturn(List.of(booking()));
        when(attendances.byBookings(List.of("booking-1"))).thenReturn(Map.of("booking-1", stored(AttendanceState.NO_SHOW, 0L)));

        var result = service.save("class-1", 1L, List.of(new AttendanceSheetService.Item("booking-1", AttendanceState.PRESENT)), instructor);

        assertThat(result).containsEntry("applied", List.of("booking-1"));
        verify(locks).lock("class-1");
        var next = ArgumentCaptor.forClass(Attendance.class);
        verify(attendances).upsert(next.capture(), eq(0L));
        assertThat(next.getValue().version()).isEqualTo(1L);
        assertThat(next.getValue().state()).isEqualTo(AttendanceState.PRESENT);
        var event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(publisher).publish(event.capture());
        var marked = (AttendanceEvent) event.getValue();
        assertThat(marked.kind()).isEqualTo(AttendanceEvent.Kind.AttendanceMarked);
        assertThat(marked.origin()).isEqualTo(DomainEvent.Origin.INSTRUCTOR);
    }

    @Test void T_10_12_aSaveOfAClassThatDoesNotExistIsNotFound() {
        when(schedule.find("class-x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save("class-x", 0L, List.of(), instructor)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test void T_10_02_aCancelledClassRefusesEvenASaveWithoutItems() {
        // A sheet left open on a class the club has since cancelled, resent with its current (never saved) version 0.
        when(schedule.find("class-1")).thenReturn(Optional.of(cls("CANCELLED", InstructorScheduleAccess.Summary.EMPTY)));

        assertThatThrownBy(() -> service.save("class-1", 0L, List.of(), instructor)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE));
        verify(sheets, never()).build(any(), any());
    }

    private static InstructorScheduleAccess.ClassView cls(String state, InstructorScheduleAccess.Summary summary) {
        return new InstructorScheduleAccess.ClassView("class-1", state, DATE, "09:00", "10:00", Instant.parse("2026-10-05T07:00:00Z"),
                Instant.parse("2026-10-05T08:00:00Z"), null, List.of("instructor-neus"), "Neus", "Agility · Iniciació", List.of(), 6, 1, 0, summary);
    }

    private static Booking booking() {
        var booked = NOW.minusSeconds(86_400);
        return new Booking("booking-1", "club-a", "class-1", "dog-duna", "member-laura", BookingState.ACTIVE, BookingOrigin.APP, booked,
                new Booking.Actor("account-laura", null, "Laura"), Instant.parse("2026-10-05T07:00:00Z"), Instant.parse("2026-10-05T08:00:00Z"),
                "2026-10-04", null, null, null, null, null, null,
                null, null, null, null, null,
                null, null,
                1L, booked, "account-laura", booked, "account-laura");
    }

    private static Attendance stored(AttendanceState state, long version) {
        var marked = NOW.minusSeconds(600);
        return new Attendance("attendance-1", "club-a", "booking-1", "class-1", DATE, Instant.parse("2026-10-05T07:00:00Z"),
                Instant.parse("2026-10-05T08:00:00Z"), "dog-duna", "member-laura", state, marked,
                new Attendance.Marker("account-neus", com.agilityhub.core.clubs.bookings.domain.ActorRole.INSTRUCTOR, "Neus"), null, null,
                List.of(new Attendance.Change(state, marked, "account-neus")), version, marked, marked);
    }
}
