package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.InactivityPort;
import com.agilityhub.core.clubs.bookings.application.ports.PackBalancePort;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 session 10 PIT survivors of {@link BookingChecks} (S08 R-08-07, T-08-14): `POST /seat-holds` names its class in
 * the body, so a class the tenant does not have (unknown or another club's) is 404 NOT_FOUND before the dog is read.
 */
class BookingChecksSurvivors3Test {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    final ClassSessionBookingAccess classes = mock(ClassSessionBookingAccess.class);
    final BookingMemberAccess census = mock(BookingMemberAccess.class);
    final BookingChecks checks = new BookingChecks(mock(BookingContext.class), classes, census, mock(BookingRepository.class),
            mock(InactivityPort.class), mock(PackBalancePort.class));

    @Test void T_08_14_aHoldForAClassTheClubDoesNotHaveIsNotFound() {
        when(classes.find("class-unknown")).thenReturn(Optional.empty());
        var laura = BookingActor.member("account-laura", "member-laura", "Laura");

        assertThatThrownBy(() -> checks.subject(laura, "class-unknown", "dog-duna", NOW))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(census);
    }
}
