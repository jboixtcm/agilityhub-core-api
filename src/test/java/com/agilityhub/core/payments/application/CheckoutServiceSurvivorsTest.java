package com.agilityhub.core.payments.application;

import com.agilityhub.core.identity.application.IdentityTransactions;
import com.agilityhub.core.payments.persistence.SignupCheckoutRepository;
import com.agilityhub.core.payments.persistence.SignupCheckoutSession;
import com.agilityhub.core.payments.persistence.UpfrontPayment;
import com.agilityhub.core.payments.persistence.UpfrontPaymentRepository;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.BookingOwnerAccess;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.IdempotentOperation;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
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
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link CheckoutService} (S04 R-04-23/26/27, S08 R-08-18, S12 R-12-20/22, S15 R-15-15/17; T-04-19,
 * T-04-22, T-08-24, T-12-31, T-15-23, T-15-25): the redirect checks, the key's lock, release and clean-up of `POST
 * /checkout-sessions`, the census lock of every write, the provider expiry after a rejection's commit, the webhook's amount
 * check and booking paths, the card setup and the late completions' refund amount. Collaborators are mocks; fictional data.
 */
class CheckoutServiceSurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-08-17T10:00:00Z");
    static final String SUCCESS = "https://club-a.example.test/signup/ok";
    static final String CANCEL = "https://club-a.example.test/signup/cancel";

    final SignupPaymentAccess members = mock(SignupPaymentAccess.class);
    final UpfrontPayments payments = mock(UpfrontPayments.class);
    final CensusClubSettings clubs = mock(CensusClubSettings.class);
    final ClubConfigService configs = mock(ClubConfigService.class);
    final SignupCheckoutRepository sessions = mock(SignupCheckoutRepository.class);
    @SuppressWarnings("unchecked") final ObjectProvider<PaymentProvider> gateways = mock(ObjectProvider.class);
    final PaymentProvider gateway = mock(PaymentProvider.class);
    final IdentityTransactions transactions = mock(IdentityTransactions.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final BillingCensusAccess billingCensus = mock(BillingCensusAccess.class);
    final UpfrontPaymentRepository upfront = mock(UpfrontPaymentRepository.class);
    final BookingOwnerAccess bookings = mock(BookingOwnerAccess.class);
    final PaymentRefunds refund = mock(PaymentRefunds.class);
    @SuppressWarnings("unchecked") final ObjectProvider<PaymentRefunds> refunds = mock(ObjectProvider.class);
    CheckoutService checkout;

    @BeforeEach void setUp() {
        TenantContext.open(CLUB);
        when(gateways.getIfAvailable()).thenReturn(gateway);
        when(refunds.getObject()).thenReturn(refund);
        when(clubs.providerEnabled("STRIPE")).thenReturn(true);
        when(clubs.appHost()).thenReturn("club-a.example.test");
        when(configs.get(CLUB)).thenReturn(config());
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(transactions).run(any());
        checkout = new CheckoutService(members, payments, clubs, configs, sessions, gateways, transactions, Clock.fixed(NOW, ZoneOffset.UTC),
                mock(IcuMessageSource.class), manager);
        ReflectionTestUtils.setField(checkout, "refunds", refunds);
        ReflectionTestUtils.setField(checkout, "billingCensus", billingCensus);
        ReflectionTestUtils.setField(checkout, "upfront", upfront);
        ReflectionTestUtils.setField(checkout, "bookings", bookings);
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    // --- create (POST /checkout-sessions) -----------------------------------------------------------------------------------

    @Test void T_04_22_anUnsafeCancelUrlIsRefusedBeforeAnySessionOpens() {
        assertThatThrownBy(() -> checkout.create("member-1", "token", SUCCESS, "http://club-a.example.test/signup/cancel", result -> new byte[0]))
                .isInstanceOf(ApiException.class).hasMessage("VALIDATION_ERROR");
        verify(members, never()).write(any());
    }

    @Test void T_04_22_aReplayedRequestLocksItsKeyAndTheCensusInBothWritesAndStoresTheAnswer() {
        var locks = new AtomicInteger();
        var statuses = new ArrayList<Integer>();
        var request = providerRequest();
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(members).write(any());
        when(members.member("member-1")).thenReturn(Map.of("status", "PENDING"));
        when(sessions.openFor("member-1", "request-1", NOW)).thenReturn(Optional.of(new SignupCheckoutSession("session-1", CLUB, "member-1", "PENDING",
                "payment", List.of("payment-1"), NOW.plusSeconds(86400), null, null, null, "request-1", request)));
        when(gateway.createCheckoutSession(request)).thenReturn("https://checkout.example.test/session-1");
        when(sessions.answered("session-1")).thenReturn(true);

        CheckoutService.Result result;
        try (var scope = IdempotentOperation.open(locks::incrementAndGet, (status, body) -> statuses.add(status), "request-1")) {
            result = checkout.create("member-1", "token", SUCCESS, CANCEL, answer -> new byte[] {1});
        }

        assertThat(result).isEqualTo(new CheckoutService.Result("https://checkout.example.test/session-1", "session-1"));
        // The session's preparation and the stored answer each lock the key's row and the census.
        assertThat(locks).hasValue(2);
        verify(members, times(2)).lock();
        assertThat(statuses).containsExactly(201);
    }

    @Test void T_04_22_aProviderFailureReleasesTheKeyAndExpiresTheSessionUnderTheCensusLock() {
        var failure = new IllegalStateException("provider down");
        var request = providerRequest();
        // The preparation answers the request; the abandonment runs its own unit of work.
        doReturn(request).doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(members).write(any());
        when(gateway.createCheckoutSession(request)).thenThrow(failure);
        when(sessions.finish("session-1", "EXPIRED", null)).thenReturn(true);

        try (var scope = IdempotentOperation.open(() -> { }, (status, body) -> { }, "request-1")) {
            assertThatThrownBy(() -> checkout.create("member-1", "token", SUCCESS, CANCEL, answer -> new byte[0])).isSameAs(failure);
            assertThat(scope.released()).isTrue();
        }
        verify(members).lock();
        verify(payments).checkout("member-1", "session-1", false);
        verify(gateway).expire("session-1");
    }

    @Test void T_04_22_aFailedCleanUpKeepsTheProviderFailureAsSuppressed() {
        var failure = new IllegalStateException("provider down");
        var cleanup = new IllegalStateException("claim taken over");
        doReturn(providerRequest()).doThrow(cleanup).when(members).write(any());
        when(gateway.createCheckoutSession(any())).thenThrow(failure);

        var thrown = catchThrowable(() -> checkout.create("member-1", "token", SUCCESS, CANCEL, answer -> new byte[0]));

        assertThat(thrown).isSameAs(cleanup);
        assertThat(thrown.getSuppressed()).containsExactly(failure);
        verify(gateway, never()).expire(any());
    }

    @Test void T_04_22_aRowAlreadyWaitingForACheckoutOpensNoOther() {
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(members).write(any());
        when(members.member("member-1")).thenReturn(Map.of("status", "PENDING"));
        var scope = List.of(new UpfrontPayments.Submission("dog-1", "submission-1"));
        when(members.submissions("member-1")).thenReturn(scope);
        when(payments.lines("member-1", scope)).thenReturn(List.of(new UpfrontPayments.Line("payment-1", "ENTRY_FEE", "dog-1", new Money(10000, "EUR"),
                new Money(0, "EUR"), "CHECKOUT_PENDING", "STRIPE", "submission-1")));
        when(payments.due("member-1", scope, "EUR")).thenReturn(new Money(10000, "EUR"));

        assertThatThrownBy(() -> checkout.create("member-1", "token", SUCCESS, CANCEL, answer -> new byte[0]))
                .isInstanceOf(ApiException.class).hasMessage("INVALID_STATE");
        verify(sessions, never()).insert(any());
        verify(gateway, never()).createCheckoutSession(any());
    }

    // --- rejection (R-04-23) ------------------------------------------------------------------------------------------------

    @Test void T_04_19_theProviderExpiryAfterARejectionRunsAfterTheCommitOutsideAnyTransaction() {
        when(sessions.openSignup("member-1")).thenReturn(List.of(session("PENDING", "payment", List.of("payment-1"), null)));
        when(sessions.finish("session-1", "EXPIRED", null)).thenReturn(true);

        TransactionSynchronizationManager.initSynchronization();
        try {
            checkout.rejected("member-1", List.of("payment-1"), false);
            verify(gateway, never()).expire(any());
            for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) { synchronization.afterCommit(); }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        verify(payments).checkout("member-1", "session-1", false);
        verify(gateway).expire("session-1");
    }

    @Test void T_04_19_aRejectionLeavesAnotherSubmissionsCheckoutOpenWhileTheMemberStays() {
        when(sessions.openSignup("member-1")).thenReturn(List.of(session("PENDING", "payment", List.of("payment-other"), null)));

        checkout.rejected("member-1", List.of("payment-1"), false);

        verify(sessions, never()).finish(any(), any(), any());
        verifyNoInteractions(payments, gateway);
    }

    // --- PAY_TO_BOOK (R-08-18) ----------------------------------------------------------------------------------------------

    @Test void T_08_24_aBookingCheckoutKeepsTheAmountItCharges() {
        when(payments.createForBooking(eq("member-1"), any(), eq("booking-1"))).thenReturn("payment-1");

        var booking = checkout.prepareBooking("member-1", "booking-1", new UpfrontPayments.Charge("SINGLE_CLASS", "dog-1", new Money(1500, "EUR")),
                NOW.plusSeconds(1800));

        verify(sessions).chargeAmount(booking.sessionId(), 1500L);
        verify(payments).pending("member-1", List.of("payment-1"), booking.sessionId());
    }

    // --- completeWebhook ----------------------------------------------------------------------------------------------------

    @Test void T_12_16_anUnknownSessionIsNotFound() {
        assertThatThrownBy(() -> checkout.completeWebhook("session-unknown", "pi_1", null, NOW, 1500L)).isInstanceOf(ApiException.class)
                .hasMessage("NOT_FOUND");
    }

    @Test void T_12_16_aPaymentWhoseAmountDiffersFromTheSessionsIsRefused() {
        when(sessions.findById("session-1")).thenReturn(Optional.of(session("PENDING", "payment", List.of("payment-1"), null)));
        when(sessions.chargeAmount("session-1")).thenReturn(Optional.of(2000L));

        assertThatThrownBy(() -> checkout.completeWebhook("session-1", "pi_1", null, NOW, 1999L)).isInstanceOf(ApiException.class)
                .hasMessage("INVALID_STATE");
        verify(members).lock();
        verify(sessions, never()).finish(any(), any(), any());
    }

    @Test void T_12_16_withoutAStoredAmountTheSessionCapturesWhatItsRowsStillOwe() {
        when(sessions.findById("session-1")).thenReturn(Optional.of(session("PENDING", "payment", List.of("payment-1"), null)));
        when(upfront.findById("payment-1")).thenReturn(Optional.of(row("payment-1", 3000, 1000, null)));
        when(payments.checkoutPending("member-1", "session-1", List.of("payment-1"))).thenReturn(true);

        // 3000 due − 1000 already paid.
        assertThatCode(() -> checkout.completeWebhook("session-1", "pi_1", null, NOW, 2000L)).doesNotThrowAnyException();
        verify(sessions).finish("session-1", "COMPLETE", "pi_1");
    }

    @Test void T_15_25_aPendingBookingCheckoutPaidAfterItsBookingWasCancelledExpiresAndIsRefunded() {
        when(sessions.findById("session-1")).thenReturn(Optional.of(session("PENDING", "payment", List.of("payment-1"), "booking-1")));
        when(sessions.chargeAmount("session-1")).thenReturn(Optional.of(1500L));
        when(bookings.cancelled("booking-1")).thenReturn(true);
        when(sessions.finish("session-1", "EXPIRED", "pi_1")).thenReturn(true);
        when(upfront.findById("payment-1")).thenReturn(Optional.of(row("payment-1", 1500, 0, "booking-1")));

        checkout.completeWebhook("session-1", "pi_1", null, NOW, 1500L);

        verify(members).lock();
        verify(sessions).finish("session-1", "EXPIRED", "pi_1");
        verify(payments).checkout("member-1", "session-1", false);
        verify(refund).lateBooking("payment-1", "pi_1", new Money(1500, "EUR"));
        verify(sessions, never()).finish("session-1", "COMPLETE", "pi_1");
    }

    @Test void T_15_25_anExpiredBookingCheckoutPaidAfterItsBookingWasCancelledIsOnlyRefunded() {
        when(sessions.findById("session-1")).thenReturn(Optional.of(session("EXPIRED", "payment", List.of("payment-1"), "booking-1")));
        when(sessions.chargeAmount("session-1")).thenReturn(Optional.of(1500L));
        when(bookings.cancelled("booking-1")).thenReturn(true);
        when(upfront.findById("payment-1")).thenReturn(Optional.of(row("payment-1", 1500, 0, "booking-1")));

        checkout.completeWebhook("session-1", "pi_1", null, NOW, 1500L);

        verify(sessions, never()).finish(any(), any(), any());
        verify(refund).lateBooking("payment-1", "pi_1", new Money(1500, "EUR"));
    }

    // --- completeSetup (R-12-22) --------------------------------------------------------------------------------------------

    @Test void T_12_31_aStandaloneCardSetupSavesTheCardOnTheCensusUnderItsLock() {
        var card = new BillingCensusAccess.Card("cus_1", "pm_1", "4242", "visa", false);
        when(sessions.standaloneCardSetup("session-1")).thenReturn(true);
        when(sessions.finish("session-1", "COMPLETE", null)).thenReturn(true);

        assertThat(checkout.completeSetup(session("PENDING", "setup", List.of(), null), card, NOW)).isTrue();

        verify(members).lock();
        verify(billingCensus).saveCard("member-1", card);
        verify(members, never()).card(any(), any());
    }

    @Test void T_12_31_aSetupTheSessionNoLongerWaitsForIsNotCompleted() {
        var card = new BillingCensusAccess.Card("cus_1", "pm_1", "4242", "visa", false);

        assertThat(checkout.completeSetup(session("PENDING", "setup", List.of(), null), card, NOW)).isFalse();
        verify(members).card(eq("member-1"), any());
    }

    @Test void T_12_31_aClosedSetupSessionTakesNoCard() {
        var card = new BillingCensusAccess.Card("cus_1", "pm_1", "4242", "visa", false);

        assertThat(checkout.completeSetup(session("COMPLETE", "setup", List.of(), null), card, NOW)).isFalse();
        verifyNoInteractions(members, billingCensus);
    }

    // --- complete / expire / expireLapsed -----------------------------------------------------------------------------------

    @Test void T_04_22_completingAnUnknownSessionIsNotFound() {
        assertThatThrownBy(() -> checkout.complete("session-unknown", "pi_1", Map.of(), NOW)).isInstanceOf(ApiException.class).hasMessage("NOT_FOUND");
    }

    @Test void T_04_22_aLateSignupCompletionRefundsWhatItsRowsWereDue() {
        when(sessions.findById("session-1")).thenReturn(Optional.of(session("EXPIRED", "payment", List.of("payment-1", "payment-2"), null)));
        when(upfront.findById("payment-1")).thenReturn(Optional.of(row("payment-1", 3000, 0, null)));
        when(upfront.findById("payment-2")).thenReturn(Optional.of(row("payment-2", 1500, 0, null)));

        checkout.complete("session-1", "pi_1", Map.of(), NOW);

        verify(members).lock();
        verify(refund).late("session-1", "pi_1", new Money(4500, "EUR"));
    }

    /** E34: the reconciliation warning names why the payment is refunded (the signup's 24 h or the booking's deadline). */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({",the signup checkout had expired", "booking-1,the checkout deadline passed"})
    void T_04_22_aLateCompletionWarnsWithItsCause(String bookingId, String cause) {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CheckoutService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var lapsed = new SignupCheckoutSession("session-1", CLUB, "member-1", "PENDING", "payment", List.of("payment-1"),
                    NOW.minusSeconds(60), bookingId);
            when(sessions.findById("session-1")).thenReturn(Optional.of(lapsed));
            when(payments.checkoutPending(eq("member-1"), eq("session-1"), any())).thenReturn(true);
            when(sessions.finish("session-1", "EXPIRED", "pi_1")).thenReturn(true);
            when(sessions.markLateCompletion(eq("session-1"), eq("pi_1"), any())).thenReturn(true);
            when(upfront.findById("payment-1")).thenReturn(Optional.of(row("payment-1", 3000, 0, bookingId)));

            checkout.complete("session-1", "pi_1", Map.of(), NOW);

            assertThat(appender.list).filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN).singleElement()
                    .satisfies(event -> assertThat(event.getFormattedMessage()).startsWith("Late provider completion to refund: " + cause + " "));
        } finally {
            logger.detachAppender(appender); appender.stop();
        }
    }

    @Test void T_04_22_expiringAnUnknownSessionIsNotFound() {
        assertThatThrownBy(() -> checkout.expire("session-unknown")).isInstanceOf(ApiException.class).hasMessage("NOT_FOUND");
    }

    @Test void T_15_23_aSessionNoLongerInScopeIsNotExpiredByStepH() {
        assertThat(checkout.expireLapsed("session-unknown", NOW)).isFalse();
        verify(members).lock();
    }

    @Test void T_15_23_aLapsedSessionClosedMeanwhileGivesNoRowsBack() {
        when(sessions.findById("session-1")).thenReturn(Optional.of(new SignupCheckoutSession("session-1", CLUB, "member-1", "PENDING", "payment",
                List.of("payment-1"), NOW.minusSeconds(60), null)));

        assertThat(checkout.expireLapsed("session-1", NOW)).isFalse();
        verify(payments, never()).checkout(any(), any(), anyBoolean());
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    static ClubConfig config() {
        var club = new ClubConfig.ClubView(CLUB, "club-a", "Example Club", List.of("ca"), "ca", "Europe/Madrid", "EUR", null, null, "ACTIVE", null);
        return new ClubConfig(club, Map.of(), Set.of(), null, Map.of());
    }

    static SignupCheckoutSession session(String status, String mode, List<String> paymentIds, String bookingId) {
        return new SignupCheckoutSession("session-1", CLUB, "member-1", status, mode, paymentIds, NOW.plusSeconds(3600), bookingId);
    }

    static PaymentProvider.Request providerRequest() {
        return new PaymentProvider.Request("session-1", CLUB, "member-1", "payment",
                List.of(new PaymentProvider.Item("payment-1", "Entry fee", new Money(10000, "EUR"))), "laura.serra@example.test", "member-1",
                Map.of("clubId", CLUB, "memberId", "member-1"), null, SUCCESS, CANCEL, NOW.plusSeconds(86400));
    }

    static UpfrontPayment row(String id, long due, long paid, String bookingId) {
        return new UpfrontPayment(id, CLUB, "member-1", "dog-1", bookingId == null ? "ENTRY_FEE" : "SINGLE_CLASS", null, new Money(due, "EUR"),
                new Money(paid, "EUR"), "CHECKOUT_PENDING", "STRIPE", "session-1", NOW, null, bookingId, null, null);
    }
}
