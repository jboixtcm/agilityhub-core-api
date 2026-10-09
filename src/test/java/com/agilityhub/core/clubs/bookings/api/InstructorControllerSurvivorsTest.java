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
import com.agilityhub.core.shared.application.IdempotentOperation;
import com.agilityhub.core.shared.application.lists.ListEngine;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link InstructorController} (S10 §6, T-10-12, T-10-19, T-10-21, T-10-22): every handler checks the
 * tenant, then its resource or filter guard, before any query runs; the save locks its idempotency key inside the
 * transaction; `/me/history` acts as the impersonated member.
 * Every route here is authenticated and runs behind `CurrentUserFilter` (SecurityConfiguration:79,111), which always opens a
 * {@link CurrentUser} for the bearer (CurrentUserFilter:23,39): each test opens the one the filter opens for its token.
 */
class InstructorControllerSurvivorsTest {
    final AttendanceContractAccess access = mock(AttendanceContractAccess.class);
    final AttendanceCallers callers = mock(AttendanceCallers.class);
    final AttendanceSheetQuery sheets = mock(AttendanceSheetQuery.class);
    final AttendanceSheetService saves = mock(AttendanceSheetService.class);
    final InstructorDayQuery days = mock(InstructorDayQuery.class);
    final WeekAgendaQuery weeks = mock(WeekAgendaQuery.class);
    final WeekAgendaPdf pdf = mock(WeekAgendaPdf.class);
    final InstructorCardQuery cards = mock(InstructorCardQuery.class);
    final HistoryQuery history = mock(HistoryQuery.class);
    final AttendanceListQuery attendances = mock(AttendanceListQuery.class);
    final ListEngine lists = mock(ListEngine.class);
    final BookingTransactions transactions = mock(BookingTransactions.class);
    final BookingContext context = mock(BookingContext.class);
    final ObjectMapper mapper = mock(ObjectMapper.class);
    final InstructorController controller = new InstructorController(access, callers, sheets, saves, days, weeks, pdf, cards, history, attendances, lists,
            transactions, context, mapper);
    final Jwt jwt = Jwt.withTokenValue("test-only").header("alg", "none").subject("account-a").claim("memberId", "member-a")
            .claim("name", "Marta Puig").build();
    /** An impersonation token as ImpersonationService:68-72 issues it: the impersonated account and member, MEMBER only. */
    final Jwt impersonation = Jwt.withTokenValue("test-only").header("alg", "none").subject("account-b").claim("memberId", "member-b")
            .claim("imp", true).claim("actorAccountId", "account-admin").claim("impersonatedMemberId", "member-b").claim("name", "Pau Vidal").build();

    /** An instructor who is also a member of the club: CurrentUserFilter:26-27 gives the bearer the INSTRUCTOR origin. */
    @BeforeEach void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("account-a", null, "ROLE_INSTRUCTOR", "ROLE_MEMBER"));
    }
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test void T_10_22_everyHandlerChecksTheTenantBeforeAnythingElse() {
        doThrow(new ApiException(ErrorCode.NO_MEMBERSHIP)).when(access).tenant();

        try (var user = signedIn()) {
            assertCode(() -> controller.instructorDay(null, null, jwt), ErrorCode.NO_MEMBERSHIP);
            assertCode(() -> controller.instructorWeek(null, null, null, jwt), ErrorCode.NO_MEMBERSHIP);
            assertCode(() -> controller.exportInstructorWeek("pdf", null, null, null, jwt), ErrorCode.NO_MEMBERSHIP);
            assertCode(() -> controller.attendanceSheet("class-1", jwt), ErrorCode.NO_MEMBERSHIP);
            assertCode(() -> controller.saveAttendance("class-1", saveRequest(), UUID.randomUUID(), jwt), ErrorCode.NO_MEMBERSHIP);
            assertCode(() -> controller.attendances(new LinkedMultiValueMap<>()), ErrorCode.NO_MEMBERSHIP);
            assertCode(() -> controller.instructorCard("dog-1"), ErrorCode.NO_MEMBERSHIP);
            assertCode(() -> controller.memberHistory(null, null, jwt), ErrorCode.NO_MEMBERSHIP);
        }

        verify(access, never()).filters(any(), any());
        verify(access, never()).classSession(any());
        verify(access, never()).attendances(any());
        verify(access, never()).dog(any());
        verify(access, never()).accessibleDog(any(), any());
        verifyNoInteractions(callers, sheets, saves, days, weeks, pdf, cards, history, attendances, lists, transactions, context, mapper);
    }

    @Test void T_10_22_anotherClubsClassHasNoAttendanceSheet() {
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(access).classSession("class-b");

        try (var user = signedIn()) {
            assertCode(() -> controller.attendanceSheet("class-b", jwt), ErrorCode.NOT_FOUND);
        }
        verifyNoInteractions(callers, sheets);
    }

    @Test void T_10_21_anUndeclaredAttendanceFilterIsRefusedBeforeTheListRuns() {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("filter", "markedByName:eq:Marta");
        doThrow(new ApiException(ErrorCode.INVALID_FILTER)).when(access).attendances(params);

        try (var user = signedIn()) {
            assertCode(() -> controller.attendances(params), ErrorCode.INVALID_FILTER);
        }
        verifyNoInteractions(attendances, lists);
    }

    @Test void T_10_22_anotherClubsInstructorOrRingFilterIsNotFoundBeforeTheWeekIsExported() {
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(access).filters("instructor-b", null);

        try (var user = signedIn()) {
            assertCode(() -> controller.exportInstructorWeek("pdf", null, "instructor-b", null, jwt), ErrorCode.NOT_FOUND);
        }
        verifyNoInteractions(weeks, pdf, context, callers);
    }

    @Test void T_10_22_anotherClubsDogHasNoInstructorCard() {
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(access).dog("dog-b");

        try (var user = signedIn()) {
            assertCode(() -> controller.instructorCard("dog-b"), ErrorCode.NOT_FOUND);
        }
        verifyNoInteractions(cards);
    }

    @Test void T_10_19_aDogThatIsNotAccessibleHasNoHistory() {
        doThrow(new ApiException(ErrorCode.DOG_NOT_ACCESSIBLE)).when(access).accessibleDog("member-a", "dog-b");

        try (var user = signedIn()) {
            assertCode(() -> controller.memberHistory("dog-b", null, jwt), ErrorCode.DOG_NOT_ACCESSIBLE);
        }
        verifyNoInteractions(history);
    }

    @Test void T_10_19_aSignedInMemberReadsTheHistoryOfTheirTokensMember() {
        try (var user = signedIn()) {
            controller.memberHistory(null, null, jwt);
        }

        verify(access).accessibleDog("member-a", null);
        verify(history).history("member-a", null, null);
    }

    @Test void T_10_19_anImpersonationReadsTheImpersonatedMembersHistory() {
        // CurrentUserFilter:37 leaves an impersonation token ROLE_MEMBER only, whatever the administrator's own roles.
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("account-b", null, "ROLE_MEMBER"));
        try (var user = impersonating()) {
            controller.memberHistory(null, null, impersonation);
        }

        verify(access).accessibleDog("member-b", null);
        verify(history).history("member-b", null, null);
    }

    @Test void T_10_12_aSaveLocksItsKeyBeforeTheWorkAndStoresThe200() {
        var events = new ArrayList<String>();
        when(transactions.write(any(), any())).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
        when(saves.save(eq("class-1"), eq(3L), any(), any())).thenAnswer(call -> {
            events.add("save");
            return Map.of();
        });

        try (var user = signedIn();
             var scope = IdempotentOperation.open(() -> events.add("lock"), (status, body) -> events.add("complete:" + status))) {
            controller.saveAttendance("class-1", saveRequest(), UUID.randomUUID(), jwt);
        }

        assertThat(events).containsExactly("lock", "save", "complete:200");
    }

    /** What CurrentUserFilter:39 opens for {@link #jwt} (no `imp`): its subject and name, no impersonation, the INSTRUCTOR origin (lines 26-27). */
    CurrentUser.Scope signedIn() {
        return CurrentUser.open(new CurrentUser(jwt.getSubject(), jwt.getClaimAsString("name"), null, DomainEvent.Origin.INSTRUCTOR));
    }

    /**
     * What CurrentUserFilter:28-39 opens for {@link #impersonation}: the impersonated account, the grant's impersonation
     * (ImpersonationService:128) and the BACKOFFICE origin; the authorities are ROLE_MEMBER only (CurrentUserFilter:37).
     */
    CurrentUser.Scope impersonating() {
        return CurrentUser.open(new CurrentUser(impersonation.getSubject(), impersonation.getClaimAsString("name"),
                new CurrentUser.Impersonation("account-admin", "Eva Roca", impersonation.getClaimAsString("impersonatedMemberId")), DomainEvent.Origin.BACKOFFICE));
    }

    static InstructorContracts.AttendanceSaveRequest saveRequest() {
        return new InstructorContracts.AttendanceSaveRequest(3L, List.of(new InstructorContracts.AttendanceSaveItem("booking-1", AttendanceState.PRESENT)));
    }

    static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
