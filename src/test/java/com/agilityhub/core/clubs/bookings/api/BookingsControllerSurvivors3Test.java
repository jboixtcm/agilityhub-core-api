package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.bookings.application.BookableClassesQuery;
import com.agilityhub.core.clubs.bookings.application.BookingActors;
import com.agilityhub.core.clubs.bookings.application.BookingCancellationService;
import com.agilityhub.core.clubs.bookings.application.BookingConfirmationService;
import com.agilityhub.core.clubs.bookings.application.BookingContractAccess;
import com.agilityhub.core.clubs.bookings.application.BookingQueryService;
import com.agilityhub.core.clubs.bookings.application.BookingTransactions;
import com.agilityhub.core.clubs.bookings.application.BookingViews;
import com.agilityhub.core.clubs.bookings.application.MemberHomeQuery;
import com.agilityhub.core.clubs.bookings.application.SeatHoldService;
import com.agilityhub.core.clubs.bookings.application.WaitlistService;
import com.agilityhub.core.shared.application.lists.ListEngine;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 session 10 PIT survivors of {@link BookingsController} (S08 §6, T-08-26): the staff reads of a class's bookings
 * and waiting list check the class (another club's or an unknown one → 404 NOT_FOUND, `SchedulingContractAccess#classSession`)
 * before any booking or entry is read.
 */
class BookingsControllerSurvivors3Test {
    final BookingContractAccess access = mock(BookingContractAccess.class);
    final BookingQueryService queries = mock(BookingQueryService.class);
    final BookingViews views = mock(BookingViews.class);
    final WaitlistService waitlist = mock(WaitlistService.class);
    final ObjectMapper mapper = mock(ObjectMapper.class);
    final BookingsController controller = new BookingsController(access, mock(BookingActors.class), mock(SeatHoldService.class),
            mock(BookingConfirmationService.class), mock(BookingCancellationService.class), queries, views, mock(BookingTransactions.class),
            mock(ListEngine.class), mapper, waitlist, mock(MemberHomeQuery.class), mock(BookableClassesQuery.class));

    @Test void T_08_26_anotherClubsClassHasNoBookingList() {
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(access).classSession("class-b");

        assertThatThrownBy(() -> controller.classBookings("class-b"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(queries, mapper);
    }

    @Test void T_08_26_anotherClubsClassHasNoWaitingList() {
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(access).classSession("class-b");

        assertThatThrownBy(() -> controller.classWaitlist("class-b"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(waitlist, views, mapper);
    }
}
