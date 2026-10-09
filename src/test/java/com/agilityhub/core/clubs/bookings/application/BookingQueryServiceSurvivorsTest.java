package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link BookingQueryService} (S08 §6 `GET /bookings`, T-08-47; S01 R-01-07, T-08-26): the list
 * projects every stored field of a row, and the member-reach check answers NOT_FOUND for an unknown booking.
 */
class BookingQueryServiceSurvivorsTest {
    final BookingRepository bookings = mock(BookingRepository.class);
    final BookingMemberAccess census = mock(BookingMemberAccess.class);
    final BookingQueryService service = new BookingQueryService(bookings, census, mock(BookingViews.class), mock(BookingContext.class), null, null, null);

    @Test void T_08_47_theListProjectsEveryStoredFieldOfARow() {
        var dataset = service.dataset("bookings");

        // MongoListRepository#project (page, rows) and #exportStream project exactly these stored keys of each booking.
        assertThat(dataset.collection()).isEqualTo("bookings");
        assertThat(dataset.projection()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of("id", "$_id", "state", 1, "origin", 1,
                "classSessionId", 1, "classStartsAt", 1, "bookingWeekKey", 1, "dogId", 1, "memberId", 1, "bookedAt", 1, "late", 1));
    }

    @Test void T_08_26_theMemberReachOfAnUnknownBookingIsNotFound() {
        var starts = Instant.parse("2026-10-07T16:00:00Z"); var booked = Instant.parse("2026-10-04T08:00:00Z");
        when(bookings.findById("booking-1")).thenReturn(Optional.of(new Booking("booking-1", "club-a", "class-a", "dog-duna", "member-laura",
                BookingState.ACTIVE, BookingOrigin.APP, booked, new Booking.Actor("account-laura", null, "Laura"), starts, starts.plusSeconds(3600),
                "2026-10-04", null, null, null, null, null, null, null, null, null, null, null, null, null, 1L, booked, "account-laura", booked,
                "account-laura")));
        when(census.reachableMembers("member-laura")).thenReturn(Set.of("member-laura"));

        assertThat(service.reachable("booking-1", "member-laura")).isTrue();
        assertThatThrownBy(() -> service.reachable("booking-unknown", "member-laura"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
