package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.bookings.application.BookableClassesQuery;
import com.agilityhub.core.clubs.bookings.application.BookingActor;
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
import com.agilityhub.core.clubs.bookings.application.ports.SingleClassChargePort;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.IdempotentOperation;
import com.agilityhub.core.shared.application.lists.ListEngine;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link BookingsController} (S08 §6, T-08-15, T-08-16, T-08-18, T-08-19, T-08-26, T-08-27):
 * every handler checks the tenant before anything else, the role helpers read the request's identity (CurrentUser,
 * authorities) as R-01-07 says, and a keyed confirmation locks its idempotency key inside each transaction.
 * Every authenticated route runs behind `CurrentUserFilter` (SecurityConfiguration:79,111), which always opens a
 * {@link CurrentUser} for the bearer (CurrentUserFilter:23,39): each test opens the one the filter opens for its token.
 */
class BookingsControllerSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    final BookingContractAccess access = mock(BookingContractAccess.class);
    final BookingActors actors = mock(BookingActors.class);
    final SeatHoldService holds = mock(SeatHoldService.class);
    final BookingConfirmationService confirmations = mock(BookingConfirmationService.class);
    final BookingCancellationService cancellations = mock(BookingCancellationService.class);
    final BookingQueryService queries = mock(BookingQueryService.class);
    final BookingViews views = mock(BookingViews.class);
    final BookingTransactions transactions = mock(BookingTransactions.class);
    final ListEngine lists = mock(ListEngine.class);
    final ObjectMapper mapper = mock(ObjectMapper.class);
    final WaitlistService waitlist = mock(WaitlistService.class);
    final MemberHomeQuery home = mock(MemberHomeQuery.class);
    final BookableClassesQuery bookable = mock(BookableClassesQuery.class);
    final BookingsController controller = new BookingsController(access, actors, holds, confirmations, cancellations, queries, views, transactions,
            lists, mapper, waitlist, home, bookable);
    final Jwt jwt = Jwt.withTokenValue("test-only").header("alg", "none").subject("account-a").claim("memberId", "member-a")
            .claim("name", "Laura Serra").build();
    /** An impersonation token as ImpersonationService:68-72 issues it: the impersonated account and member, MEMBER only. */
    final Jwt impersonation = Jwt.withTokenValue("test-only").header("alg", "none").subject("account-b").claim("memberId", "member-b")
            .claim("imp", true).claim("actorAccountId", "account-admin").claim("impersonatedMemberId", "member-b").claim("name", "Pau Vidal").build();

    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    // --- tenant guard (T-08-26: club B never reaches club A) ----------------------------------------------------------------

    @Test void T_08_26_everyHandlerChecksTheTenantBeforeAnythingElse() {
        authenticate("ROLE_MEMBER");
        doThrow(new ApiException(ErrorCode.NO_MEMBERSHIP)).when(access).tenant();
        var response = mock(HttpServletResponse.class);
        var params = new LinkedMultiValueMap<String, String>();

        try (var user = signedIn(DomainEvent.Origin.APP)) {
            assertCode(() -> controller.memberHome(null, jwt));
            assertCode(() -> controller.bookableClasses(null, jwt));
            assertCode(() -> controller.holdSeat(new BookingRequests.SeatHoldRequest("class-1", "dog-1", null), jwt));
            assertCode(() -> controller.releaseSeat("hold-1", jwt));
            assertCode(() -> controller.confirmBooking(new BookingRequests.BookingRequest("hold-1", null), UUID.randomUUID(), jwt));
            assertCode(() -> controller.memberBookings(null, null, null, null, jwt));
            assertCode(() -> controller.booking("booking-1", jwt));
            assertCode(() -> controller.cancelBooking("booking-1", null, jwt));
            assertCode(() -> controller.bookings(params));
            assertCode(() -> controller.bookingFilterValues("state", null, params));
            assertCode(() -> controller.joinWaitlist(new BookingRequests.WaitlistEntryRequest("class-1", "dog-1"), jwt));
            assertCode(() -> controller.waitlistEntry("entry-1", jwt));
            assertCode(() -> controller.leaveWaitlist("entry-1", jwt));
            assertCode(() -> controller.claimSeat("entry-1", new BookingRequests.ClaimRequest("hold-1", null), UUID.randomUUID(), jwt));
            assertCode(() -> controller.classBookings("class-1"));
            assertCode(() -> controller.classWaitlist("class-1"));
        }
        // The .ics link is public and carries no bearer (SecurityConfiguration:59): no CurrentUser is open (CurrentUserFilter:23).
        assertCode(() -> controller.bookingCalendar("booking-1", "token-1", response));

        verifyNoInteractions(actors, holds, confirmations, cancellations, queries, views, transactions, lists, mapper, waitlist, home, bookable, response);
        verify(access, never()).classSession(any());
        verify(access, never()).waitlistModule(anyBoolean());
    }

    // --- .ics (T-08-15 calendarLinks) ---------------------------------------------------------------------------------------

    @Test void T_08_15_theCalendarIsServedAsAnIcsAttachment() {
        // Public signed link without a bearer (SecurityConfiguration:59): CurrentUserFilter:23 opens no CurrentUser.
        var response = mock(HttpServletResponse.class);
        when(queries.calendar("booking-1", "token-1")).thenReturn("BEGIN:VCALENDAR");

        assertThat(controller.bookingCalendar("booking-1", "token-1", response)).isEqualTo("BEGIN:VCALENDAR");
        verify(response).setContentType("text/calendar;charset=UTF-8");
        verify(response).setHeader("Content-Disposition", "attachment; filename=\"booking.ics\"");
    }

    // --- memberId (T-08-27: the impersonation token acts as the impersonated member) ------------------------------------------

    @Test void T_08_27_aMemberActsAsTheMemberOfTheirToken() {
        authenticate("ROLE_MEMBER");
        try (var user = signedIn(DomainEvent.Origin.APP)) {
            controller.memberHome("dog-1", jwt);
        }

        verify(home).home("member-a", "dog-1");
    }

    @Test void T_08_27_anImpersonationActsAsTheImpersonatedMember() {
        authenticate("ROLE_MEMBER");
        try (var user = impersonating()) {
            controller.memberHome(null, impersonation);
        }

        verify(home).home("member-b", null);
    }

    // --- staff() (T-08-26 detail as staff) --------------------------------------------------------------------------------------

    @Test void T_08_26_anInstructorReadsADetailAsStaff() {
        authenticate("ROLE_INSTRUCTOR");

        try (var user = signedIn(DomainEvent.Origin.INSTRUCTOR)) {
            controller.booking("booking-1", jwt);
        }

        verify(queries).detail("booking-1", "member-a", true);
    }

    @Test void T_08_26_anImpersonatedRequestNeverReadsADetailAsStaff() {
        // CurrentUserFilter:37 leaves an impersonation token ROLE_MEMBER only, whatever the administrator's own roles.
        authenticate("ROLE_MEMBER");
        try (var user = impersonating()) {
            controller.booking("booking-1", impersonation);
        }

        verify(queries).detail("booking-1", "member-b", false);
    }

    // --- cancelBooking: instructorOnly() and member() (R-01-07, T-08-18) ------------------------------------------------------

    @Test void T_08_18_anInstructorWithoutTheMemberRoleCancelsAsInstructorEvenWhenTheBookingIsReachable() {
        authenticate("ROLE_INSTRUCTOR");
        var instructor = BookingActor.instructor("account-i", "Marta Puig");
        when(actors.instructor()).thenReturn(instructor);
        // R-01-07: only the MEMBER role lets a reachable booking be cancelled as a member.
        when(queries.reachable("booking-1", "member-a")).thenReturn(true);
        var cancelled = mock(Booking.class);
        when(cancellations.cancel("booking-1", instructor, null)).thenReturn(cancelled);

        try (var user = signedIn(DomainEvent.Origin.INSTRUCTOR)) {
            controller.cancelBooking("booking-1", null, jwt);
        }

        verify(queries).visible("booking-1", "member-a", true);
        verify(cancellations).cancel("booking-1", instructor, null);
        verify(views).booking(cancelled, true, null);
        verify(actors, never()).member(any());
    }

    @Test void T_08_18_theCancellationMessageReachesTheService() {
        authenticate("ROLE_MEMBER");
        var member = BookingActor.member("account-a", "member-a", "Laura Serra");
        when(actors.member("member-a")).thenReturn(member);

        try (var user = signedIn(DomainEvent.Origin.APP)) {
            controller.cancelBooking("booking-1", new BookingRequests.BookingCancellationRequest("Fictional message"), jwt);
            controller.cancelBooking("booking-2", null, jwt);
        }

        verify(cancellations).cancel("booking-1", member, "Fictional message");
        verify(cancellations).cancel("booking-2", member, null);
    }

    // --- leaveWaitlist: adminOnly() and member() (R-01-07, T-08-19) -----------------------------------------------------------

    @Test void T_08_19_anAdministratorWithoutTheMemberRoleLeavesAnEntryAsAdministrator() {
        authenticate("ROLE_ADMIN");
        // Without MEMBER the administrator never acts as a member, whatever the entry's owner.
        when(waitlist.reachable("entry-1", "member-a")).thenReturn(true);

        try (var user = signedIn(DomainEvent.Origin.BACKOFFICE)) {
            controller.leaveWaitlist("entry-1", jwt);
        }

        verify(waitlist).visible("entry-1", "member-a", true);
        verify(actors).admin();
        verify(actors, never()).member(any());
    }

    @Test void T_08_19_anAdministratorWithTheMemberRoleLeavesTheirOwnEntryAsAMember() {
        authenticate("ROLE_ADMIN", "ROLE_MEMBER");
        when(waitlist.reachable("entry-1", "member-a")).thenReturn(true);

        try (var user = signedIn(DomainEvent.Origin.BACKOFFICE)) {
            controller.leaveWaitlist("entry-1", jwt);
        }

        verify(waitlist).visible("entry-1", "member-a", false);
        verify(actors).member("member-a");
        verify(actors, never()).admin();
    }

    // --- confirmed(): the key is locked inside each transaction (R-08-08, T-08-16) --------------------------------------------

    @Test void T_08_16_aConfirmationLocksItsKeyBeforeTheWorkAndStoresThe201() {
        var events = new ArrayList<String>();
        runWrites();
        var actor = BookingActor.member("account-a", "member-a", "Laura Serra");
        when(actors.member("member-a")).thenReturn(actor);
        when(confirmations.classes("hold-1", null)).thenReturn(List.of("class-1"));
        when(confirmations.confirm(actor, "hold-1", null)).thenAnswer(call -> {
            events.add("confirm");
            return new BookingConfirmationService.Confirmed(mock(Booking.class), null, null);
        });

        try (var user = signedIn(DomainEvent.Origin.APP);
             var scope = IdempotentOperation.open(() -> events.add("lock"), (status, body) -> events.add("complete:" + status))) {
            controller.confirmBooking(new BookingRequests.BookingRequest("hold-1", null), UUID.randomUUID(), jwt);
        }

        assertThat(events).containsExactly("lock", "confirm", "complete:201");
    }

    @Test void T_08_16_aPayToBookConfirmationLocksItsKeyAgainBeforeStoringTheCheckout() {
        var events = new ArrayList<String>();
        runWrites();
        var actor = BookingActor.member("account-a", "member-a", "Laura Serra");
        when(actors.member("member-a")).thenReturn(actor);
        var booking = mock(Booking.class);
        var pending = new SingleClassChargePort.Pending("session-1", "payment-1", "member-a", "booking-1", new Money(1200, "EUR"), "Class", NOW);
        when(confirmations.confirm(actor, "hold-1", null)).thenAnswer(call -> {
            events.add("confirm");
            return new BookingConfirmationService.Confirmed(booking, null, pending);
        });
        when(confirmations.openCheckout(any())).thenAnswer(call -> {
            events.add("checkout");
            return new BookingConfirmationService.Confirmed(booking, "https://checkout.example.test/session-1", pending);
        });

        try (var user = signedIn(DomainEvent.Origin.APP);
             var scope = IdempotentOperation.open(() -> events.add("lock"), (status, body) -> events.add("complete:" + status))) {
            controller.confirmBooking(new BookingRequests.BookingRequest("hold-1", null), UUID.randomUUID(), jwt);
        }

        assertThat(events).containsExactly("lock", "confirm", "checkout", "lock", "complete:201");
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    /** The booking transaction runs its work once (no retry). */
    void runWrites() {
        when(transactions.write(any(), any())).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
    }

    /** What CurrentUserFilter:39 opens for a bearer without `imp`: its subject and name, no impersonation, the origin of its roles (lines 26-27). */
    CurrentUser.Scope signedIn(DomainEvent.Origin origin) {
        return CurrentUser.open(new CurrentUser(jwt.getSubject(), jwt.getClaimAsString("name"), null, origin));
    }

    /**
     * What CurrentUserFilter:28-39 opens for {@link #impersonation}: the impersonated account, the grant's impersonation
     * (ImpersonationService:128) and the BACKOFFICE origin; the authorities are ROLE_MEMBER only (CurrentUserFilter:37).
     */
    CurrentUser.Scope impersonating() {
        return CurrentUser.open(new CurrentUser(impersonation.getSubject(), impersonation.getClaimAsString("name"),
                new CurrentUser.Impersonation("account-admin", "Eva Roca", impersonation.getClaimAsString("impersonatedMemberId")), DomainEvent.Origin.BACKOFFICE));
    }

    static void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("account-a", null, authorities));
    }

    static void assertCode(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NO_MEMBERSHIP));
    }
}
