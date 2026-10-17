package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.bookings.application.AttendanceCallers;
import com.agilityhub.core.clubs.bookings.application.AttendanceContractAccess;
import com.agilityhub.core.clubs.bookings.application.AttendanceListQuery;
import com.agilityhub.core.clubs.bookings.application.AttendanceSheetQuery;
import com.agilityhub.core.clubs.bookings.application.AttendanceSheetService;
import com.agilityhub.core.clubs.bookings.application.BookingContext;
import com.agilityhub.core.clubs.bookings.application.BookingTransactions;
import com.agilityhub.core.clubs.bookings.application.HistoryQuery;
import com.agilityhub.core.clubs.bookings.application.InstructorCardQuery;
import com.agilityhub.core.clubs.bookings.application.InstructorDayQuery;
import com.agilityhub.core.clubs.bookings.application.WeekAgendaPdf;
import com.agilityhub.core.clubs.bookings.application.WeekAgendaQuery;
import com.agilityhub.core.clubs.bookings.domain.AttendanceState;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.lists.ListEngine;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 session 10 PIT survivors of {@link InstructorController} (S10 §6, T-10-22): the day and week aggregates check
 * their instructor/ring filters, and the attendance save checks its class, before any query or transaction runs; another
 * club's instructor, ring or class is 404 NOT_FOUND.
 */
class InstructorControllerSurvivors3Test {
    final AttendanceContractAccess access = mock(AttendanceContractAccess.class);
    final AttendanceCallers callers = mock(AttendanceCallers.class);
    final AttendanceSheetService saves = mock(AttendanceSheetService.class);
    final InstructorDayQuery days = mock(InstructorDayQuery.class);
    final WeekAgendaQuery weeks = mock(WeekAgendaQuery.class);
    final BookingTransactions transactions = mock(BookingTransactions.class);
    final ObjectMapper mapper = mock(ObjectMapper.class);
    final InstructorController controller = new InstructorController(access, callers, mock(AttendanceSheetQuery.class), saves, days, weeks,
            mock(WeekAgendaPdf.class), mock(InstructorCardQuery.class), mock(HistoryQuery.class), mock(AttendanceListQuery.class), mock(ListEngine.class),
            transactions, mock(BookingContext.class), mapper);
    final Jwt jwt = Jwt.withTokenValue("test-only").header("alg", "none").subject("account-a").claim("memberId", "member-a")
            .claim("name", "Marta Puig").build();

    /** An instructor who is also a member of the club: CurrentUserFilter:26-27 gives the bearer the INSTRUCTOR origin. */
    @BeforeEach void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("account-a", null, "ROLE_INSTRUCTOR", "ROLE_MEMBER"));
    }
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test void T_10_22_anotherClubsInstructorFilterIsNotFoundBeforeTheDayIsRead() {
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(access).filters("instructor-b", null);

        try (var user = signedIn()) {
            assertNotFound(() -> controller.instructorDay(null, "instructor-b", jwt));
        }
        verifyNoInteractions(days, callers, mapper);
    }

    @Test void T_10_22_anotherClubsRingFilterIsNotFoundBeforeTheWeekIsRead() {
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(access).filters(null, "ring-b");

        try (var user = signedIn()) {
            assertNotFound(() -> controller.instructorWeek(null, null, "ring-b", jwt));
        }
        verifyNoInteractions(weeks, callers, mapper);
    }

    @Test void T_10_22_anotherClubsClassAttendanceIsNotSaved() {
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(access).classSession("class-b");
        var request = new InstructorContracts.AttendanceSaveRequest(3L, List.of(new InstructorContracts.AttendanceSaveItem("booking-1", AttendanceState.PRESENT)));

        try (var user = signedIn()) {
            assertNotFound(() -> controller.saveAttendance("class-b", request, UUID.randomUUID(), jwt));
        }
        verifyNoInteractions(callers, transactions, saves, mapper);
    }

    /** What CurrentUserFilter:39 opens for {@link #jwt} (no `imp`): its subject and name, no impersonation, the INSTRUCTOR origin. */
    CurrentUser.Scope signedIn() {
        return CurrentUser.open(new CurrentUser(jwt.getSubject(), jwt.getClaimAsString("name"), null, DomainEvent.Origin.INSTRUCTOR));
    }

    static void assertNotFound(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
