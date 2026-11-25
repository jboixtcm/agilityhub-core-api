package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.DogTrainingOverlapPort;
import com.agilityhub.core.clubs.bookings.application.ports.InactivityPort;
import com.agilityhub.core.clubs.bookings.application.ports.PackBalancePort;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * BOOK-02 (owner decision, 13-11-2026), R-08-08: a dog never holds a class booking over another of its live class bookings
 * (ACTIVE or PAYMENT_PENDING) or over its ACTIVE free-training booking; 422 DOG_ALREADY_BOOKED. The booking the swap cancels
 * does not count.
 */
class BookingChecksOverlapTest {
    static final Instant STARTS = Instant.parse("2026-10-08T16:50:00Z"), ENDS = STARTS.plusSeconds(3600);
    final BookingRepository bookings = mock(BookingRepository.class);
    final DogTrainingOverlapPort training = mock(DogTrainingOverlapPort.class);
    final BookingChecks checks = new BookingChecks(mock(BookingContext.class), mock(ClassSessionBookingAccess.class), mock(BookingMemberAccess.class),
            bookings, mock(InactivityPort.class), mock(PackBalancePort.class), training);
    final BookingChecks.Subject subject = new BookingChecks.Subject(
            new ClassSessionBookingAccess.Session("class-b", "ACTIVE", LocalDate.of(2026, 10, 8), "18:50", "19:50", STARTS, ENDS,
                    "ring-a", List.of(), List.of(), 5, 0, 0, false, null, 0L),
            new BookingMemberAccess.Dog("dog-duna", "Duna", "FEMALE", "member-laura", "level-c", "ACTIVE"), null, null, null, null, Optional.empty(), null);

    void refused() {
        assertThatThrownBy(() -> checks.noOverlap(subject, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.DOG_ALREADY_BOOKED));
    }

    @Test void BOOK_02_aLiveBookingOfAnotherClassAtThatTimeRefusesTheBookingWhetherActiveOrPaymentPending() {
        for (var state : List.of(BookingState.ACTIVE, BookingState.PAYMENT_PENDING)) {
            when(bookings.liveOverlapping("dog-duna", STARTS, ENDS)).thenReturn(List.of(
                    BookingCancellationServiceSurvivorsTest.booking("booking-a", "class-a", "member-laura", "dog-duna", STARTS.minusSeconds(1800), state, 0, null)));
            refused();
        }
        verify(bookings, times(2)).liveOverlapping("dog-duna", STARTS, ENDS);
    }

    @Test void BOOK_02_anActiveTrainingBookingOverTheClassRefusesIt() {
        when(bookings.liveOverlapping("dog-duna", STARTS, ENDS)).thenReturn(List.of());
        when(training.dogInTraining("dog-duna", STARTS, ENDS)).thenReturn(true);
        refused();
    }

    @Test void BOOK_02_noOtherBookingAtThatTimeOrOnlyTheSwappedOneLetsItThrough() {
        when(bookings.liveOverlapping("dog-duna", STARTS, ENDS)).thenReturn(List.of());
        assertThatCode(() -> checks.noOverlap(subject, null)).doesNotThrowAnyException();
        verify(training).dogInTraining("dog-duna", STARTS, ENDS);
        var parallel = BookingCancellationServiceSurvivorsTest.booking("booking-a", "class-a", "member-laura", "dog-duna", STARTS, BookingState.ACTIVE, 0, null);
        when(bookings.liveOverlapping("dog-duna", STARTS, ENDS)).thenReturn(List.of(parallel));
        assertThatCode(() -> checks.noOverlap(subject, "booking-a")).doesNotThrowAnyException();
        assertThatThrownBy(() -> checks.noOverlap(subject, "booking-other")).isInstanceOf(ApiException.class);
    }
}
