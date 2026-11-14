package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.persistence.AttendanceRepository;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.SeatLockRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort.StoredNotification;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationSubject;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.clubs.training.application.TrainingBookingService;
import com.agilityhub.core.clubs.training.application.TrainingNotificationFacts;
import com.agilityhub.core.clubs.training.persistence.TrainingBooking;
import com.agilityhub.core.clubs.training.persistence.TrainingBookingRepository;
import com.agilityhub.core.shared.application.IcuMessageSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * COMM-10 (owner decision of 13 Nov 2026, S11 R-11-07): the push deadlines S08 and S09 give the dispatcher — a class reminder's
 * class start, a training reminder's training start, a FIFO seat offer's `confirmBy` — and none for an ALL_AT_ONCE offer, another
 * code, or a booking or entry that is gone.
 */
class PushDeadlineFactsTest {
    static final Instant STARTS = Instant.parse("2026-10-07T16:00:00Z"), CONFIRM_BY = Instant.parse("2026-10-07T12:00:00Z");
    final BookingRepository bookings = mock(BookingRepository.class);
    final WaitlistEntryRepository waitlist = mock(WaitlistEntryRepository.class);
    final TrainingBookingRepository trainings = mock(TrainingBookingRepository.class);
    final BookingNotificationFacts facts = new BookingNotificationFacts(bookings, waitlist, mock(AttendanceRepository.class), mock(BookingMemberAccess.class),
            mock(ClassSessionBookingAccess.class), mock(BookingContext.class), mock(BookingViews.class), mock(IcuMessageSource.class), mock(WaitlistTransitions.class),
            mock(BookingTransactions.class), mock(SeatLockRepository.class));
    final TrainingNotificationFacts training = new TrainingNotificationFacts(trainings, mock(TrainingBookingService.class), facts);

    static StoredNotification notice(String code, NotificationSubject subject) {
        return new StoredNotification("n-1", code, "Event", "MEMBER", "account-laura", "member-laura", subject, List.of(), false);
    }

    @Test void COMM_10_aClassReminderEndsAtItsClassStartAndAFifoOfferAtItsConfirmBy() {
        var booking = mock(Booking.class); when(booking.classStartsAt()).thenReturn(STARTS);
        when(bookings.findById("booking-a")).thenReturn(Optional.of(booking));
        var subject = NotificationSubject.booking("booking-a").with(NotificationSubject.classSession("class-a"));
        assertThat(facts.pushDeadline(notice("N-13", subject))).contains(STARTS);
        var fifo = mock(WaitlistEntry.class); when(fifo.confirmBy()).thenReturn(CONFIRM_BY);
        var allAtOnce = mock(WaitlistEntry.class); when(allAtOnce.confirmBy()).thenReturn(null);
        when(waitlist.findById("entry-fifo")).thenReturn(Optional.of(fifo));
        when(waitlist.findById("entry-all")).thenReturn(Optional.of(allAtOnce));
        assertThat(facts.pushDeadline(notice("N-15", NotificationSubject.waitlistEntry("entry-fifo")))).contains(CONFIRM_BY);
        assertThat(facts.pushDeadline(notice("N-15", NotificationSubject.waitlistEntry("entry-all")))).isEmpty();
        // Nothing for another code, a reminder without a class booking (a training one), or a booking that is gone.
        assertThat(facts.pushDeadline(notice("N-04", subject))).isEmpty();
        assertThat(facts.pushDeadline(notice("N-13", NotificationSubject.trainingBooking("training-a")))).isEmpty();
        when(bookings.findById("booking-gone")).thenReturn(Optional.empty());
        assertThat(facts.pushDeadline(notice("N-13", NotificationSubject.booking("booking-gone")))).isEmpty();
    }

    @Test void COMM_10_aTrainingReminderEndsAtTheTrainingStart() {
        var booking = mock(TrainingBooking.class); when(booking.startsAt()).thenReturn(STARTS);
        when(trainings.findById("training-a")).thenReturn(Optional.of(booking));
        assertThat(training.pushDeadline(notice("N-13", NotificationSubject.trainingBooking("training-a")))).contains(STARTS);
        assertThat(training.pushDeadline(notice("N-13", NotificationSubject.booking("booking-a")))).isEmpty();
        assertThat(training.pushDeadline(notice("N-06", NotificationSubject.trainingBooking("training-a")))).isEmpty();
        verify(bookings, never()).findById(any());
    }
}
