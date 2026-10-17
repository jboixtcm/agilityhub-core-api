package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.BillingEvent;
import com.agilityhub.core.payments.persistence.BillingDocuments.CollectionRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.PendingChargeRepository;
import com.agilityhub.core.payments.persistence.PaymentOperation;
import com.agilityhub.core.payments.persistence.PaymentOperationRepository;
import com.agilityhub.core.payments.persistence.PendingCharge;
import com.agilityhub.core.payments.persistence.SignupCheckoutRepository;
import com.agilityhub.core.payments.persistence.StripeRefundRepository;
import com.agilityhub.core.payments.persistence.UpfrontPayment;
import com.agilityhub.core.payments.persistence.UpfrontPaymentRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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
 * E11-T06 session 10 PIT survivors of {@link PaymentRefunds} (S12 R-12-20/25; T-12-17): the ledger of a payment without
 * Stripe references (settled refunds, open refund commands, unbilled and voided credits), the consumption charge that is
 * never compensated, the overpayment of a billed credit, the failed and settled dashboard refunds of a late capture and the
 * zero refunds the webhook path refuses. Collaborators are mocks; fictional members only.
 */
class PaymentRefundsSurvivors3Test {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");
    static final Instant AT = Instant.parse("2026-09-01T07:59:00Z");
    static final String REASON = "requested_by_customer";

    final InvoiceRepository invoices = mock(InvoiceRepository.class);
    final CollectionRepository collections = mock(CollectionRepository.class);
    final UpfrontPaymentRepository upfront = mock(UpfrontPaymentRepository.class);
    final PaymentOperationRepository operations = mock(PaymentOperationRepository.class);
    final PaymentProviderRegistry provider = mock(PaymentProviderRegistry.class);
    final BillingTransactions tx = mock(BillingTransactions.class);
    final PaymentAudits audit = mock(PaymentAudits.class);
    final PaymentRetryPolicy retries = mock(PaymentRetryPolicy.class);
    final PendingChargeRepository charges = mock(PendingChargeRepository.class);
    final StripeRefundRepository refundStates = mock(StripeRefundRepository.class);
    final BillingEvents events = mock(BillingEvents.class);
    final SignupCheckoutRepository checkouts = mock(SignupCheckoutRepository.class);
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    PaymentRefunds refunds;

    @BeforeEach void setUp() {
        TenantContext.open(CLUB);
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(tx).run(any());
        refunds = new PaymentRefunds(invoices, collections, upfront, operations, provider, tx, clock, audit);
        ReflectionTestUtils.setField(refunds, "retries", retries);
        ReflectionTestUtils.setField(refunds, "charges", charges);
        ReflectionTestUtils.setField(refunds, "refundStates", refundStates);
        ReflectionTestUtils.setField(refunds, "events", events);
        ReflectionTestUtils.setField(refunds, "checkouts", checkouts);
        when(refundStates.refundId(any())).thenAnswer(call -> call.<StripeRefundRepository.State>getArgument(0).id().substring(CLUB.length() + 1));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    // --- the ledger of a payment without Stripe references (CapturedPaymentLedger) ----------------------------------------

    @Test void T_12_17_aCreditForAPaymentWithoutStripeRefsLeavesOutItsSettledRefundsAndItsOpenRefundCommands() {
        // 1500 captured; settled: re_0 300 (its command op-0 is linked) and re_9 100 (no command); op-1 200 is still open.
        when(upfront.findById("pay-1")).thenReturn(Optional.of(manualPayment("pay-1", "booking-1", List.of(
                new UpfrontPayment.Refund(new Money(300, "EUR"), "re_0", AT, REASON, null),
                new UpfrontPayment.Refund(new Money(100, "EUR"), "re_9", AT, REASON, null)))));
        when(upfront.compensation("pay-1")).thenReturn(Optional.of(new UpfrontPaymentRepository.Compensation("CREDIT", "BOOKING_CANCELLED", null)));
        when(operations.forTarget("pay-1")).thenReturn(List.of(operation("op-0", "REFUND_UPFRONT", "pay-1", 300, "key-0", "re_0"),
                operation("op-1", "REFUND_UPFRONT", "pay-1", 200, "key-1", null)));

        refunds.compensate("pay-1", "CREDIT", false);

        // D = 1500 − S 400 − R 200 − K 0 = 900: the settled op-0 is not reserved twice.
        var inserted = ArgumentCaptor.forClass(PendingCharge.class);
        verify(charges).insert(inserted.capture());
        assertThat(inserted.getValue().amount()).isEqualTo(new Money(-900, "EUR"));
        assertThat(inserted.getValue().bookingId()).isEqualTo("booking-1");
        verify(refundStates, never()).lockIntent(any());
    }

    @Test void T_12_17_aCreditForAPaymentWithoutStripeRefsGrowsTheUnbilledCreditByWhatItsLedgerLeaves() {
        when(upfront.findById("pay-1")).thenReturn(Optional.of(manualPayment("pay-1", "booking-1", null)));
        when(upfront.compensation("pay-1")).thenReturn(Optional.of(new UpfrontPaymentRepository.Compensation("CREDIT", "BOOKING_CANCELLED", null)));
        when(charges.forBooking("booking-1")).thenReturn(Optional.of(charge(-200, null)));
        when(charges.credit("charge-1", -200L, -1500L)).thenReturn(true);

        refunds.compensate("pay-1", "CREDIT", false);

        // K = 200 already credited: D = 1500 − 200 = 1300, so the credit grows from −200 to −1500.
        verify(charges).credit("charge-1", -200L, -1500L);
        verify(charges, never()).insert(any());
        verifyNoInteractions(audit);
    }

    @Test void T_12_17_aVoidedCreditLeavesTheWholePaymentOwedAsAnIntervention() {
        when(upfront.findById("pay-1")).thenReturn(Optional.of(manualPayment("pay-1", "booking-1", null)));
        when(upfront.compensation("pay-1")).thenReturn(Optional.of(new UpfrontPaymentRepository.Compensation("CREDIT", "BOOKING_CANCELLED", null)));
        when(charges.forBooking("booking-1")).thenReturn(Optional.of(charge(-200, AT)));
        // The repository refuses to grow a voided credit.
        when(charges.credit(anyString(), anyLong(), anyLong())).thenReturn(false);
        when(upfront.interventionOwed(eq("pay-1"), any())).thenReturn(true);
        when(upfront.intervention("pay-1", NOW)).thenReturn(true);

        refunds.compensate("pay-1", "CREDIT", false);

        // A voided credit compensates nothing: all 1500 is due, and owed once the credit cannot grow.
        verify(charges).credit("charge-1", -200L, -1700L);
        verify(upfront).interventionOwed("pay-1", new Money(1500, "EUR"));
        verify(audit).intervention("pay-1", "member-1", "COMPENSATION_INTERVENTION", "owed", new Money(1500, "EUR"));
        verify(retries).warnIntervention("pay-1");
    }

    @Test void T_12_17_aCreditForAPaymentWithoutBookingBecomesAnIntervention() {
        when(upfront.findById("pay-1")).thenReturn(Optional.of(manualPayment("pay-1", null, null)));
        when(upfront.compensation("pay-1")).thenReturn(Optional.of(new UpfrontPaymentRepository.Compensation("CREDIT", "BOOKING_CANCELLED", null)));
        when(upfront.interventionOwed(eq("pay-1"), any())).thenReturn(true);
        when(upfront.intervention("pay-1", NOW)).thenReturn(true);

        refunds.compensate("pay-1", "CREDIT", false);

        // No booking to hold a credit row: the admin repays the 1500 by hand (N-55).
        verify(charges, never()).insert(any());
        verify(upfront).interventionOwed("pay-1", new Money(1500, "EUR"));
        verify(retries).warnIntervention("pay-1");
        verify(audit).intervention("pay-1", "member-1", "COMPENSATION_INTERVENTION", "owed", new Money(1500, "EUR"));
        verify(events).publish(eq(BillingEvent.Kind.UpfrontRefundIntervention), eq("pay-1"), any());
    }

    // --- consumption charges (R-12-25) ------------------------------------------------------------------------------------

    @Test void T_12_17_aBookingChargedByConsumptionIsNotCompensated() {
        when(upfront.findById("pay-1")).thenReturn(Optional.of(payment("pay-1", "PAID", "booking-1", null)));
        when(charges.forBooking("booking-1")).thenReturn(Optional.of(charge(1500, null)));

        refunds.compensate("pay-1", "REFUND", false);

        verify(upfront, never()).compensation(anyString(), anyString(), anyString());
        verify(operations, never()).insert(any());
        verify(charges, never()).insert(any());
    }

    @Test void T_12_17_aFreeClassChargedAtZeroIsStillAConsumption() {
        when(upfront.findById("pay-1")).thenReturn(Optional.of(payment("pay-1", "PAID", "booking-1", null)));
        // A SINGLE_CLASS price of 0: the charge is non-negative, so it is a consumption charge, never a compensation.
        when(charges.forBooking("booking-1")).thenReturn(Optional.of(charge(0, null)));

        refunds.compensate("pay-1", "CREDIT", false);

        verify(upfront, never()).compensation(anyString(), anyString(), anyString());
        verify(charges, never()).insert(any());
        verify(charges, never()).credit(anyString(), anyLong(), anyLong());
    }

    // --- overpayment of a billed credit (E8-T11) ----------------------------------------------------------------------------

    @Test void T_12_17_aDashboardRefundBeyondTheCreditOfABilledBookingIsReportedAndWarned() {
        var settledRefunds = new AtomicReference<List<UpfrontPayment.Refund>>();
        when(upfront.forIntent("pi_1")).thenAnswer(call -> List.of(payment("pay-1", "PAID", "booking-1", settledRefunds.get())));
        when(upfront.findById("pay-1")).thenReturn(Optional.of(payment("pay-1", "PAID", "booking-1", null)));
        doAnswer(call -> { UpfrontPayment.Refund refund = call.getArgument(1); settledRefunds.set(List.of(refund)); return null; })
                .when(upfront).refund(eq("pay-1"), any(), anyBoolean());
        // All 1500 was already credited, and that credit is billed: it cannot shrink.
        when(charges.forBooking("booking-1")).thenReturn(Optional.of(new PendingCharge("charge-1", CLUB, "member-1", "dog-1", "booking-1", null,
                new Money(-1500, "EUR"), "SINGLE_CLASS", NOW, "invoice-9", null)));
        when(upfront.intervention("pay-1", NOW)).thenReturn(true);

        assertThat(refunds.settled("pi_1", "re_1", new Money(700, "EUR"), AT, REASON, null)).isTrue();

        verify(upfront).refund("pay-1", new UpfrontPayment.Refund(new Money(700, "EUR"), "re_1", AT, REASON, null), false);
        // S 700 + K 1500 − C 1500: 700 overpaid, reported once and warned to the admin.
        verify(audit).intervention("pay-1", "member-1", "REFUND_OVER_CREDIT", "overpaid", new Money(700, "EUR"));
        verify(retries).warnIntervention("pay-1");
        verify(charges, never()).credit(anyString(), anyLong(), anyLong());
    }

    // --- late captures (E34) ----------------------------------------------------------------------------------------------

    @Test void T_12_17_aFailedLateRefundWithoutAnUpfrontPaymentIsInvalid() {
        when(refundStates.lock("re_1")).thenReturn(new StripeRefundRepository.State(CLUB + ":re_1", CLUB, null, null, null, null));
        var late = operation("op-1", "REFUND_LATE", "session-1", 1500, "late:pi_1", null);
        when(operations.findById("op-1")).thenReturn(Optional.of(late));
        when(operations.refundsForIntent("pi_1")).thenReturn(List.of(late));
        when(operations.refundFailed("op-1")).thenReturn(true);
        // Neither an upfront row nor a checkout session resolves the command's target.

        assertThatThrownBy(() -> refunds.reconcile("pi_1", "re_1", new Money(1500, "EUR"), "failed", AT, null, "op-1"))
                .isInstanceOf(ApiException.class).hasMessage("INVALID_STATE");
        verify(audit, never()).intervention(any(), any(), any(), any(), any());
    }

    @Test void T_12_17_aFailedDashboardRefundOfALateCaptureReversesItsOwnAmount() {
        when(refundStates.lock("re_1")).thenReturn(new StripeRefundRepository.State(CLUB + ":re_1", CLUB, "pi_1", new Money(1500, "EUR"), "succeeded", AT));
        when(operations.refundsForIntent("pi_1")).thenReturn(List.of(operation("op-late", "REFUND_LATE", "session-1", 1500, "late:pi_1", null)));
        // Another refund of the same intent is listed first.
        when(refundStates.forIntent("pi_1")).thenReturn(List.of(state("re_0", 500, "succeeded"), state("re_1", 1500, "failed")));

        assertThat(refunds.reconcile("pi_1", "re_1", new Money(1500, "EUR"), "failed", AT, null, null)).isTrue();

        verify(audit).refunded("CheckoutSession", "session-1", new Money(-1500, "EUR"), "re_1", "REFUND_FAILED");
        verify(audit, never()).refunded(any(), any(), eq(new Money(-500, "EUR")), any(), any());
        verify(retries).warnRefund("re_1", "failed");
    }

    @Test void T_12_17_aDashboardRefundOfALateCaptureSettlesOnItsCheckoutSession() {
        when(operations.refundsForIntent("pi_1")).thenReturn(List.of(operation("op-late", "REFUND_LATE", "session-1", 1500, "late:pi_1", null)));
        when(refundStates.forIntent("pi_1")).thenReturn(List.of(state("re_1", 700, "succeeded")));

        assertThat(refunds.settled("pi_1", "re_1", new Money(700, "EUR"), AT, REASON, null)).isTrue();

        verify(audit).refunded("CheckoutSession", "session-1", new Money(700, "EUR"), "re_1", REASON);
    }

    @Test void T_12_17_aDashboardRefundBeyondALateCaptureIsRejected() {
        when(operations.refundsForIntent("pi_1")).thenReturn(List.of(operation("op-late", "REFUND_LATE", "session-1", 1500, "late:pi_1", null)));
        // 1000 + 700 succeeded on a 1500 capture.
        when(refundStates.forIntent("pi_1")).thenReturn(List.of(state("re_0", 1000, "succeeded"), state("re_1", 700, "succeeded")));

        assertThatThrownBy(() -> refunds.settled("pi_1", "re_1", new Money(700, "EUR"), AT, REASON, null)).isInstanceOf(ApiException.class)
                .hasMessage("REFUND_EXCEEDS_PAID");
        verifyNoInteractions(audit);
    }

    @Test void T_12_17_aZeroRefundOfALateCaptureIsRejected() {
        when(operations.refundsForIntent("pi_1")).thenReturn(List.of(operation("op-late", "REFUND_LATE", "session-1", 1500, "late:pi_1", null)));

        assertThatThrownBy(() -> refunds.settled("pi_1", "re_1", new Money(0, "EUR"), AT, REASON, null)).isInstanceOf(ApiException.class)
                .hasMessage("REFUND_EXCEEDS_PAID");
        verifyNoInteractions(audit);
    }

    // --- upfront settlement -------------------------------------------------------------------------------------------------

    @Test void T_12_17_aZeroRefundOfAnUpfrontPaymentIsRejected() {
        when(upfront.forIntent("pi_1")).thenReturn(List.of(payment("pay-1", "PAID", null, null)));

        assertThatThrownBy(() -> refunds.settled("pi_1", "re_1", new Money(0, "EUR"), AT, REASON, null)).isInstanceOf(ApiException.class)
                .hasMessage("CURRENCY_MISMATCH");
        verify(upfront, never()).refund(any(), any(), anyBoolean());
        verifyNoInteractions(audit);
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    static UpfrontPayment payment(String id, String status, String bookingId, List<UpfrontPayment.Refund> refunds) {
        return new UpfrontPayment(id, CLUB, "member-1", "dog-1", "SINGLE_CLASS", null, new Money(1500, "EUR"), new Money(1500, "EUR"), status, "STRIPE",
                "session-1", NOW, NOW, bookingId, null, null, null, null, new UpfrontPayment.StripeRefs("pi_1", "ch_1"), null, null, refunds, null);
    }

    /** A PAID row without Stripe references (no `stripe {paymentIntentId, chargeId}`): its own ledger, not the intent's. */
    static UpfrontPayment manualPayment(String id, String bookingId, List<UpfrontPayment.Refund> refunds) {
        return new UpfrontPayment(id, CLUB, "member-1", "dog-1", "SINGLE_CLASS", null, new Money(1500, "EUR"), new Money(1500, "EUR"), "PAID", "MANUAL",
                null, NOW, NOW, bookingId, null, null, null, null, null, "CASH", null, refunds, null);
    }

    static PaymentOperation operation(String id, String kind, String targetId, long amount, String key, String resultId) {
        String reason = "REFUND_LATE".equals(kind) ? "LATE_COMPLETION" : REASON;
        return new PaymentOperation(id, CLUB, kind, targetId, "pi_1", new Money(amount, "EUR"), key, reason, null, null, NOW, resultId);
    }

    static PendingCharge charge(long amount, Instant voidedAt) {
        return new PendingCharge("charge-1", CLUB, "member-1", "dog-1", "booking-1", null, new Money(amount, "EUR"), "SINGLE_CLASS", NOW, null, voidedAt);
    }

    static StripeRefundRepository.State state(String refundId, long amount, String status) {
        return new StripeRefundRepository.State(CLUB + ":" + refundId, CLUB, "pi_1", new Money(amount, "EUR"), status, AT);
    }
}
