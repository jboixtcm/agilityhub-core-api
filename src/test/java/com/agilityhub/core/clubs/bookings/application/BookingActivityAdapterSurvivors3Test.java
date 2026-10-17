package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 session 10 PIT survivors of {@link BookingActivityAdapter} (the S14/S06 {@code BookingActivity} port; T-08-26: club B
 * never reads club A): a club id other than the open tenant is TENANT_MISMATCH before any booking is read.
 */
class BookingActivityAdapterSurvivors3Test {
    static final Instant FROM = Instant.parse("2026-09-07T00:00:00Z");
    static final Instant UNTIL = Instant.parse("2026-10-05T00:00:00Z");

    final BookingRepository bookings = mock(BookingRepository.class);
    final BookingMemberAccess census = mock(BookingMemberAccess.class);
    final BookingActivityAdapter adapter = new BookingActivityAdapter(bookings, census);
    TenantContext.Scope tenant;

    @BeforeEach void setUp() { tenant = TenantContext.open("club-a"); }
    @AfterEach void tearDown() { tenant.close(); }

    @Test void T_08_26_anotherClubNeverReadsTheDogsWithABooking() {
        assertThatThrownBy(() -> adapter.dogsWithBooking("club-b", FROM, UNTIL))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.TENANT_MISMATCH));
        verifyNoInteractions(bookings);
    }

    @Test void T_08_26_anotherClubNeverReadsTheActiveBookingsByLevel() {
        assertThatThrownBy(() -> adapter.activeBookingsByLevel("club-b", FROM, UNTIL))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.TENANT_MISMATCH));
        verifyNoInteractions(bookings, census);
    }
}
