package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.CheckoutStatus;
import com.agilityhub.core.payments.persistence.SignupCheckoutRepository;
import com.agilityhub.core.payments.persistence.SignupCheckoutSession;
import com.agilityhub.core.payments.persistence.UpfrontPayment;
import com.agilityhub.core.payments.persistence.UpfrontPaymentRepository;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.BookingOwnerAccess;
import com.agilityhub.core.shared.application.IdempotentOperation;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link PaymentCheckouts} (S12 R-12-21/22, S08 R-08-18; T-12-16, T-12-31): the provider capability
 * and redirect guards, the booking checks before any write, the rows a checkout charges, the frozen provider request (its
 * reference and future usage), the standalone card setup mark, the key and census locks and the status the return screen
 * polls. Collaborators are mocks; fictional members only.
 */
class PaymentCheckoutsSurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");
    static final String SUCCESS = "https://club-a.example.test/pay/ok";
    static final String CANCEL = "https://club-a.example.test/pay/cancel";
    static final String URL = "https://checkout.example.test/session";

    final SignupCheckoutRepository sessions = mock(SignupCheckoutRepository.class);
    final UpfrontPaymentRepository rows = mock(UpfrontPaymentRepository.class);
    final UpfrontPayments payments = mock(UpfrontPayments.class);
    final CheckoutService signup = mock(CheckoutService.class);
    final SignupPaymentAccess members = mock(SignupPaymentAccess.class);
    final BillingTransactions tx = mock(BillingTransactions.class);
    final PaymentProviderRegistry provider = mock(PaymentProviderRegistry.class);
    final ClubConfigService configs = mock(ClubConfigService.class);
    final BookingOwnerAccess bookings = mock(BookingOwnerAccess.class);
    final List<CheckoutService.Result> answered = new ArrayList<>();
    PaymentCheckouts checkouts;

    @BeforeEach void setUp() {
        TenantContext.open(CLUB);
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(tx).run(any());
        // A keyed write: the work, then the answer's bytes, as BillingTransactions.keyed does.
        doAnswer(call -> {
            Object value = call.<Supplier<?>>getArgument(1).get();
            call.<Function<Object, byte[]>>getArgument(2).apply(value);
            return value;
        }).when(tx).keyed(anyInt(), any(), any());
        when(configs.get(CLUB)).thenReturn(config());
        when(provider.createCheckoutSession(any())).thenReturn(URL);
        when(sessions.answered(anyString())).thenReturn(true);
        checkouts = new PaymentCheckouts(sessions, rows, payments, signup, members, tx, provider, configs, Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(checkouts, "bookings", bookings);
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    private CheckoutService.Result create(String bookingId, List<String> paymentIds, boolean setup) {
        return checkouts.create("member-1", bookingId, paymentIds, setup, SUCCESS, CANCEL, result -> { answered.add(result); return new byte[] {1}; });
    }

    // --- provider and redirect guards ---------------------------------------------------------------------------------------

    @Test void T_12_16_aPaymentCheckoutNeedsTheCheckoutCapability() {
        doThrow(new ApiException(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED)).when(provider).require(PaymentProvider.Capability.CHECKOUT);

        assertThatThrownBy(() -> create(null, List.of("pay-1"), false)).isInstanceOf(ApiException.class).hasMessage("PAYMENT_PROVIDER_NOT_ENABLED");
        verifyNoInteractions(tx, signup, rows);
    }

    @Test void T_12_31_aCardSetupNeedsTheCardSetupCapability() {
        doThrow(new ApiException(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED)).when(provider).require(PaymentProvider.Capability.CARD_SETUP);

        assertThatThrownBy(() -> create(null, null, true)).isInstanceOf(ApiException.class).hasMessage("PAYMENT_PROVIDER_NOT_ENABLED");
        verifyNoInteractions(tx, signup);
    }

    @Test void T_12_16_anUnsafeSuccessUrlIsRefusedBeforeAnyWrite() {
        doThrow(new ApiException(ErrorCode.VALIDATION_ERROR)).when(signup).redirect(SUCCESS);

        assertThatThrownBy(() -> create(null, List.of("pay-1"), false)).isInstanceOf(ApiException.class).hasMessage("VALIDATION_ERROR");
        verifyNoInteractions(tx, rows);
    }

    @Test void T_12_16_anUnsafeCancelUrlIsRefusedBeforeAnyWrite() {
        doThrow(new ApiException(ErrorCode.VALIDATION_ERROR)).when(signup).redirect(CANCEL);

        assertThatThrownBy(() -> create(null, List.of("pay-1"), false)).isInstanceOf(ApiException.class).hasMessage("VALIDATION_ERROR");
        verifyNoInteractions(tx, rows);
    }

    // --- booking checks (R-08-18) -------------------------------------------------------------------------------------------

    @Test void T_12_16_aCancelledBookingOpensNoCheckout() {
        when(bookings.cancelled("booking-1")).thenReturn(true);

        assertThatThrownBy(() -> create("booking-1", null, false)).isInstanceOf(ApiException.class).hasMessage("INVALID_STATE");
        // Refused before anything is read or written (a later INVALID_STATE would come from the transaction).
        verifyNoInteractions(tx, rows);
    }

    @Test void T_12_16_aBookingWithAnOpenCheckoutAnswersItUnderTheKey() {
        when(rows.findById("pay-1")).thenReturn(Optional.of(row("pay-1", "member-1", "booking-1")));
        when(bookings.checkout("booking-1")).thenReturn(Optional.of(new BookingOwnerAccess.Checkout("session-9", "https://checkout.example.test/session-9")));

        var result = create("booking-1", List.of("pay-1"), false);

        var expected = new CheckoutService.Result("https://checkout.example.test/session-9", "session-9");
        assertThat(result).isEqualTo(expected);
        // The stored answer is the same session.
        assertThat(answered).containsExactly(expected);
        verify(tx).keyed(eq(201), any(), any());
        verify(tx, never()).run(any());
    }

    @Test void T_12_16_aPaymentOfAnotherMemberIsNotFoundForTheBooking() {
        when(rows.findById("pay-1")).thenReturn(Optional.of(row("pay-1", "member-2", "booking-1")));

        assertThatThrownBy(() -> create("booking-1", List.of("pay-1"), false)).isInstanceOf(ApiException.class).hasMessage("NOT_FOUND");
        verify(bookings, never()).checkout(any());
        verifyNoInteractions(tx);
    }

    @Test void T_12_16_aPaymentOfAnotherBookingIsNotFound() {
        when(rows.findById("pay-1")).thenReturn(Optional.of(row("pay-1", "member-1", "booking-2")));

        assertThatThrownBy(() -> create("booking-1", List.of("pay-1"), false)).isInstanceOf(ApiException.class).hasMessage("NOT_FOUND");
        verify(bookings, never()).checkout(any());
        verifyNoInteractions(tx);
    }

    @Test void T_12_16_anUnknownPaymentOfTheBookingIsNotFound() {
        assertThatThrownBy(() -> create("booking-1", List.of("pay-unknown"), false)).isInstanceOf(ApiException.class).hasMessage("NOT_FOUND");
        verifyNoInteractions(tx);
    }

    // --- the rows and the provider request ----------------------------------------------------------------------------------

    @Test void T_12_16_withoutPaymentIdsTheCheckoutChargesTheMembersRowsOutsideAnyBookingUnderBothLocks() {
        var locks = new AtomicInteger();
        when(rows.member("member-1")).thenReturn(List.of(row("pay-1", "member-1", null), row("pay-2", "member-1", "booking-1")));

        CheckoutService.Result result;
        try (var scope = IdempotentOperation.open(locks::incrementAndGet, (status, body) -> { }, "request-1")) {
            result = create(null, null, false);
        }

        var request = sent();
        // Only the row without a booking is charged.
        assertThat(request.lines()).extracting(PaymentProvider.Item::paymentId).containsExactly("pay-1");
        assertThat(request.mode()).isEqualTo("payment");
        // Without a booking the provider's reference is the member, and the card is kept for later charges.
        assertThat(request.clientReferenceId()).isEqualTo("member-1");
        assertThat(request.setupFutureUsage()).isEqualTo("off_session");
        assertThat(result).isEqualTo(new CheckoutService.Result(URL, request.sessionId()));
        verify(payments).pending("member-1", List.of("pay-1"), request.sessionId());
        verify(sessions, never()).markStandaloneCardSetup(any());
        // The key's row and the census are locked before the session is read or written.
        assertThat(locks).hasValue(1);
        verify(members).lock();
    }

    @Test void T_12_16_anEmptyPaymentIdListChargesTheMembersRowsLikeNone() {
        when(rows.member("member-1")).thenReturn(List.of(row("pay-1", "member-1", null)));

        create(null, List.of(), false);

        assertThat(sent().lines()).extracting(PaymentProvider.Item::paymentId).containsExactly("pay-1");
        verify(rows, never()).findById(any());
    }

    @Test void T_12_16_explicitPaymentIdsOfABookingAreChargedWithTheBookingAsReference() {
        when(rows.findById("pay-2")).thenReturn(Optional.of(row("pay-2", "member-1", "booking-1")));

        create("booking-1", List.of("pay-2"), false);

        var request = sent();
        assertThat(request.lines()).extracting(PaymentProvider.Item::paymentId).containsExactly("pay-2");
        assertThat(request.clientReferenceId()).isEqualTo("booking-1");
        assertThat(request.setupFutureUsage()).isEqualTo("off_session");
        verify(rows, never()).member(any());
    }

    @Test void T_12_16_anUnknownExplicitPaymentIsNotFound() {
        assertThatThrownBy(() -> create(null, List.of("pay-unknown"), false)).isInstanceOf(ApiException.class).hasMessage("NOT_FOUND");
        verify(sessions, never()).insert(any());
    }

    @Test void T_12_31_aStandaloneCardSetupChargesNoRowAndIsMarked() {
        // The member owes a row, which a card setup never charges.
        when(rows.member("member-1")).thenReturn(List.of(row("pay-1", "member-1", null)));

        create(null, null, true);

        var request = sent();
        assertThat(request.mode()).isEqualTo("setup");
        assertThat(request.lines()).isEmpty();
        assertThat(request.setupFutureUsage()).isNull();
        verify(sessions).markStandaloneCardSetup(request.sessionId());
        verify(payments).pending("member-1", List.of(), request.sessionId());
    }

    @Test void T_12_16_aRetriedRequestSendsTheFrozenProviderRequestAgain() {
        var frozen = new PaymentProvider.Request("session-1", CLUB, "member-1", "payment",
                List.of(new PaymentProvider.Item("pay-1", "Class", new Money(1500, "EUR"))), null, "member-1",
                Map.of("clubId", CLUB, "memberId", "member-1"), "off_session", SUCCESS, CANCEL, NOW.plusSeconds(86400));
        when(sessions.openFor("member-1", "request-1", NOW)).thenReturn(Optional.of(new SignupCheckoutSession("session-1", CLUB, "member-1", "PENDING",
                "payment", List.of("pay-1"), NOW.plusSeconds(86400), null, null, null, "request-1", frozen)));

        CheckoutService.Result result;
        try (var scope = IdempotentOperation.open(() -> { }, (status, body) -> { }, "request-1")) {
            result = create(null, null, false);
        }

        verify(provider).createCheckoutSession(frozen);
        assertThat(result).isEqualTo(new CheckoutService.Result(URL, "session-1"));
        verify(sessions, never()).insert(any());
    }

    // --- status (GET /checkout-sessions/{id}) -------------------------------------------------------------------------------

    @Test void T_12_16_theReturnScreenSeesEachSessionStatus() {
        when(sessions.findById("session-complete")).thenReturn(Optional.of(session("session-complete", "COMPLETE")));
        when(sessions.findById("session-expired")).thenReturn(Optional.of(session("session-expired", "EXPIRED")));
        when(sessions.findById("session-pending")).thenReturn(Optional.of(session("session-pending", "PENDING")));

        assertThat(checkouts.status("session-complete")).isEqualTo(CheckoutStatus.PAID);
        assertThat(checkouts.status("session-expired")).isEqualTo(CheckoutStatus.EXPIRED);
        assertThat(checkouts.status("session-pending")).isEqualTo(CheckoutStatus.PENDING);
    }

    @Test void T_12_16_theStatusOfAnUnknownSessionIsNotFound() {
        assertThatThrownBy(() -> checkouts.status("session-unknown")).isInstanceOf(ApiException.class).hasMessage("NOT_FOUND");
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    private PaymentProvider.Request sent() {
        var request = ArgumentCaptor.forClass(PaymentProvider.Request.class);
        verify(provider).createCheckoutSession(request.capture());
        return request.getValue();
    }

    static ClubConfig config() {
        var club = new ClubConfig.ClubView(CLUB, "club-a", "Example Club", List.of("ca"), "ca", "Europe/Madrid", "EUR", null, null, "ACTIVE", null);
        return new ClubConfig(club, Map.of(), Set.of(), null, Map.of());
    }

    static UpfrontPayment row(String id, String memberId, String bookingId) {
        return new UpfrontPayment(id, CLUB, memberId, "dog-1", "SINGLE_CLASS", null, new Money(1500, "EUR"), new Money(0, "EUR"), "DUE", null, null,
                NOW, null, bookingId, null, null);
    }

    static SignupCheckoutSession session(String id, String status) {
        return new SignupCheckoutSession(id, CLUB, "member-1", status, "payment", List.of("pay-1"), NOW.plusSeconds(3600), null);
    }
}
