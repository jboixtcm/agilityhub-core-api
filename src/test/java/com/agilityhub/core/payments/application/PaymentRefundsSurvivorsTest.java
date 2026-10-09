package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.CollectionProvider;
import com.agilityhub.core.payments.domain.CollectionStatus;
import com.agilityhub.core.payments.domain.InvoiceKind;
import com.agilityhub.core.payments.domain.InvoiceStatus;
import com.agilityhub.core.payments.domain.PaymentMethodType;
import com.agilityhub.core.payments.persistence.BillingDocuments.CollectionRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.PendingChargeRepository;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.payments.persistence.PaymentOperation;
import com.agilityhub.core.payments.persistence.PaymentOperationRepository;
import com.agilityhub.core.payments.persistence.PendingCharge;
import com.agilityhub.core.payments.persistence.StripeRefundRepository;
import com.agilityhub.core.payments.persistence.UpfrontPayment;
import com.agilityhub.core.payments.persistence.UpfrontPaymentRepository;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.IdempotentOperation;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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
 * E11-T06 PIT survivors of {@link PaymentRefunds} (S12 R-12-20/21; T-12-17): the provider guard, the key and payment locks, the
 * replayed reservations, the refunds already settled on a collection, the late refunds of nothing, the cancellation credit of
 * a payment already refunded or credited, the fence after the provider's answer, the reversal of a failed refund and its
 * audit, and the settlement of a refund once. Collaborators are mocks; fictional members only.
 */
class PaymentRefundsSurvivorsTest {
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
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    PaymentRefunds refunds;

    @BeforeEach void setUp() {
        TenantContext.open(CLUB);
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(tx).run(any());
        refunds = new PaymentRefunds(invoices, collections, upfront, operations, provider, tx, clock, audit);
        ReflectionTestUtils.setField(refunds, "retries", retries);
        ReflectionTestUtils.setField(refunds, "charges", charges);
        ReflectionTestUtils.setField(refunds, "refundStates", refundStates);
        when(invoices.transition(anyString(), anyLong(), any())).thenReturn(true);
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    // --- provider guard -----------------------------------------------------------------------------------------------------

    @Test void T_12_17_anInvoiceRefundNeedsTheRefundCapability() {
        doThrow(new ApiException(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED)).when(provider).require(PaymentProvider.Capability.REFUND);

        assertThatThrownBy(() -> refunds.invoice("invoice-1", null, REASON, "key-1")).isInstanceOf(ApiException.class)
                .hasMessage("PAYMENT_PROVIDER_NOT_ENABLED");
        verifyNoInteractions(tx, invoices);
    }

    @Test void T_12_17_anUpfrontRefundNeedsTheRefundCapability() {
        doThrow(new ApiException(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED)).when(provider).require(PaymentProvider.Capability.REFUND);

        assertThatThrownBy(() -> refunds.upfront("pay-1", null, REASON, "key-1")).isInstanceOf(ApiException.class)
                .hasMessage("PAYMENT_PROVIDER_NOT_ENABLED");
        verifyNoInteractions(tx, upfront);
    }

    // --- invoice refunds ----------------------------------------------------------------------------------------------------

    @Test void T_12_17_anUnknownInvoiceIsNotFound() {
        assertThatThrownBy(() -> refunds.invoice("invoice-unknown", null, REASON, "key-1")).isInstanceOf(ApiException.class).hasMessage("NOT_FOUND");
    }

    @Test void T_12_17_anInvoiceRefundLocksItsKeyAndReservesAllOfACollectionWithoutRefunds() {
        var locks = new AtomicInteger();
        paidInvoice(collection(null));

        PaymentRefunds.Accepted accepted;
        try (var scope = IdempotentOperation.open(locks::incrementAndGet, (status, body) -> { }, "request-1")) {
            accepted = refunds.invoice("invoice-1", null, REASON, "key-1");
        }

        assertThat(locks).hasValue(1);
        // No refund yet (`refunds` absent): the whole collection can be refunded.
        assertThat(accepted.amount()).isEqualTo(new Money(4500, "EUR"));
        var inserted = ArgumentCaptor.forClass(PaymentOperation.class);
        verify(operations).insert(inserted.capture());
        assertThat(inserted.getValue().kind()).isEqualTo("REFUND_INVOICE");
        assertThat(inserted.getValue().providerRef()).isEqualTo("pi_1");
    }

    @Test void T_12_17_anInvoiceRefundCountsTheRefundsAlreadySettledOnItsCollection() {
        paidInvoice(collection(List.of(new Collection.Refund(new Money(1500, "EUR"), "re_0", AT, REASON, null))));

        // 4500 paid − 1500 refunded leaves 3000.
        assertThatThrownBy(() -> refunds.invoice("invoice-1", new Money(3500, "EUR"), REASON, "key-1")).isInstanceOf(ApiException.class)
                .hasMessage("REFUND_EXCEEDS_PAID");
        verify(operations, never()).insert(any());
    }

    @Test void T_12_17_aRepeatedInvoiceRefundAnswersItsReservation() {
        paidInvoice(collection(null));
        when(operations.forTarget("invoice-1")).thenReturn(List.of(operation("op-1", "REFUND_INVOICE", "invoice-1", 2000, "key-1", "re_1")));

        var accepted = refunds.invoice("invoice-1", new Money(2000, "EUR"), REASON, "key-1");

        assertThat(accepted).isEqualTo(new PaymentRefunds.Accepted("op-1", new Money(2000, "EUR"), "re_1"));
        verify(operations, never()).insert(any());
    }

    // --- upfront refunds ----------------------------------------------------------------------------------------------------

    @Test void T_12_17_anUnknownUpfrontPaymentIsNotFound() {
        assertThatThrownBy(() -> refunds.upfront("pay-unknown", null, REASON, "key-1")).isInstanceOf(ApiException.class).hasMessage("NOT_FOUND");
    }

    @Test void T_12_17_aRepeatedUpfrontRefundAnswersItsOwnCommand() {
        when(upfront.findById("pay-1")).thenReturn(Optional.of(payment("pay-1", "PAID", null, null)));
        when(operations.byKey("key-1")).thenReturn(Optional.of(operation("op-1", "REFUND_UPFRONT", "pay-1", 700, "key-1", "re_1")));

        var accepted = refunds.upfront("pay-1", new Money(700, "EUR"), REASON, "key-1");

        assertThat(accepted).isEqualTo(new PaymentRefunds.Accepted("op-1", new Money(700, "EUR"), "re_1"));
        verify(upfront, never()).lock(any());
        verify(operations, never()).insert(any());
    }

    @Test void T_12_17_anUpfrontRefundLocksItsKeyThenThePaymentBeforeReadingItsCapture() {
        var locks = new AtomicInteger();
        when(upfront.findById("pay-1")).thenReturn(Optional.of(payment("pay-1", "PAID", null, null)));

        PaymentRefunds.Accepted accepted;
        try (var scope = IdempotentOperation.open(locks::incrementAndGet, (status, body) -> { }, "request-1")) {
            accepted = refunds.upfront("pay-1", null, REASON, "key-1");
        }

        assertThat(locks).hasValue(1);
        var order = inOrder(upfront);
        order.verify(upfront).lock("pay-1");
        order.verify(upfront).captured("pay-1");
        assertThat(accepted.amount()).isEqualTo(new Money(1500, "EUR"));
    }

    // --- late completions (E34) ---------------------------------------------------------------------------------------------

    @Test void T_12_17_aLateCompletionOfNothingQueuesNoRefund() {
        refunds.late("session-1", "pi_late", new Money(0, "EUR"));

        verifyNoInteractions(operations);
    }

    @Test void T_12_17_aLateBookingCompletionOfNothingQueuesNoRefund() {
        refunds.lateBooking("pay-1", "pi_late", new Money(0, "EUR"));

        verifyNoInteractions(tx, upfront, operations);
    }

    @Test void T_12_17_aLateBookingCompletionLocksThePaymentBeforeReadingIt() {
        when(upfront.findById("pay-1")).thenReturn(Optional.of(payment("pay-1", "DUE", "booking-1", null)));

        refunds.lateBooking("pay-1", "pi_late", new Money(1500, "EUR"));

        var order = inOrder(upfront);
        order.verify(upfront).lock("pay-1");
        order.verify(upfront).findById("pay-1");
        var inserted = ArgumentCaptor.forClass(PaymentOperation.class);
        verify(operations).insert(inserted.capture());
        assertThat(inserted.getValue().kind()).isEqualTo("REFUND_LATE");
        assertThat(inserted.getValue().key()).isEqualTo("refund:pay-1");
    }

    @Test void T_12_17_aLateBookingCompletionOfABookingAlreadyCreditedQueuesNoRefund() {
        when(upfront.findById("pay-1")).thenReturn(Optional.of(payment("pay-1", "DUE", "booking-1", null)));
        when(charges.forBooking("booking-1")).thenReturn(Optional.of(credit()));

        refunds.lateBooking("pay-1", "pi_late", new Money(1500, "EUR"));

        verify(operations, never()).insert(any());
    }

    // --- cancellation compensation ------------------------------------------------------------------------------------------

    @Test void T_12_17_aCreditPolicyAddsNoCreditForAPaymentAlreadyFullyRefunded() {
        // The consumer saw the booking's payment PAID; an admin refund of all of it settled before the payment's lock was taken:
        // 1500 captured, 1500 refunded, so the row is REFUNDED (UpfrontPaymentRepository:49-51) and nothing remains (remaining == 0).
        when(upfront.findById("pay-1")).thenReturn(Optional.of(payment("pay-1", "REFUNDED", "booking-1",
                List.of(new UpfrontPayment.Refund(new Money(1500, "EUR"), "re_0", AT, REASON, null)))));

        refunds.compensate("pay-1", "CREDIT", false);

        var order = inOrder(upfront);
        order.verify(upfront).lock("pay-1");
        order.verify(upfront).findById("pay-1");
        verify(charges, never()).insert(any());
    }

    @Test void T_12_17_aCancellationAlreadyChargedAsCreditIsNotCompensatedAgain() {
        when(upfront.findById("pay-1")).thenReturn(Optional.of(payment("pay-1", "PAID", "booking-1", null)));
        when(charges.forBooking("booking-1")).thenReturn(Optional.of(credit()));

        refunds.compensate("pay-1", "REFUND", false);

        verify(upfront, never()).refundCompensation(anyString(), anyString());
        verify(charges, never()).insert(any());
    }

    // --- execute ------------------------------------------------------------------------------------------------------------

    @Test void T_12_17_aRefundAnswerAfterALostClaimChangesNothing() {
        // The real retry policy over the mocked command repository: the claim "token-1" is lost before the answer is written.
        ReflectionTestUtils.setField(refunds, "retries", new PaymentRetryPolicy(mock(ClubConfigService.class), operations, tx, clock));
        when(operations.findById("op-1")).thenReturn(Optional.of(operation("op-1", "REFUND_UPFRONT", "pay-1", 700, "key-1", null)));
        when(operations.claim("op-1", true)).thenReturn(new PaymentOperationRepository.Claim("token-1", false));
        when(provider.refund("pi_1", new Money(700, "EUR"), "key-1", REASON, "op-1")).thenReturn(new PaymentProvider.RefundResult("re_1", "pending"));
        when(operations.fence("op-1", "token-1")).thenReturn(false);

        assertThatThrownBy(() -> refunds.execute("op-1")).isInstanceOf(ApiException.class).hasMessage("STALE_VERSION");

        verify(operations, never()).completed(any(), any());
        verify(operations, never()).providerStatus(any(), any());
    }

    // --- reconcile (failed refunds) -----------------------------------------------------------------------------------------
    // A held refund was written by an earlier `succeeded` delivery, which recorded that status in the same transaction
    // (`settled` has no other caller than `reconcile`): Stripe later reports the same refund `failed`.

    @Test void T_12_17_aFailedUpfrontRefundRestoresOnlyThatRefundWarnsAndRecomputesUnderThePaymentLock() {
        succeededBefore("re_1", 700);
        var payment = payment("pay-1", "PAID", null, List.of(new UpfrontPayment.Refund(new Money(500, "EUR"), "re_0", AT, REASON, null),
                new UpfrontPayment.Refund(new Money(700, "EUR"), "re_1", AT, REASON, null)));
        when(upfront.forIntent("pi_1")).thenReturn(List.of(payment));
        when(upfront.findById("pay-1")).thenReturn(Optional.of(payment));

        assertThat(refunds.reconcile("pi_1", "re_1", new Money(700, "EUR"), "failed", AT, null, null)).isTrue();

        verify(upfront).reverseRefund("pay-1", "re_1");
        // The reversal audits minus the failed refund's own amount.
        verify(audit).refunded("UpfrontPayment", "pay-1", new Money(-700, "EUR"), "re_1", "REFUND_FAILED");
        verify(retries).warnRefund("re_1", "failed");
        // The compensation is recomputed under the payment's lock.
        var order = inOrder(upfront);
        order.verify(upfront).lock("pay-1");
        order.verify(upfront).findById("pay-1");
    }

    @Test void T_12_17_aFailedInvoiceRefundRestoresOnlyThatRefundAndRecountsTheOthers() {
        succeededBefore("re_1", 700);
        when(collections.byProviderReference("pi_1")).thenReturn(Optional.of(collection(List.of(
                new Collection.Refund(new Money(500, "EUR"), "re_0", AT, REASON, null), new Collection.Refund(new Money(700, "EUR"), "re_1", AT, REASON, null)))));

        assertThat(refunds.reconcile("pi_1", "re_1", new Money(700, "EUR"), "failed", AT, null, null)).isTrue();

        verify(collections).reverseRefund("col-1", "re_1");
        // The invoice keeps the other refund's 500 only.
        verify(invoices).refunded("invoice-1", new Money(500, "EUR"), NOW);
        verify(audit).refunded("Invoice", "invoice-1", new Money(-700, "EUR"), "re_1", "REFUND_FAILED");
    }

    @Test void T_12_17_aFailedLateRefundReversesItsCheckpointWithANegativeAudit() {
        succeededBefore("re_1", 1500);
        // The earlier delivery reconciled the command with its refund.
        when(operations.findById("op-1")).thenReturn(Optional.of(operation("op-1", "REFUND_LATE", "session-1", 1500, "late:pi_1", "re_1")));
        when(operations.reverseRefund("op-1", "re_1")).thenReturn(true);

        assertThat(refunds.reconcile("pi_1", "re_1", new Money(1500, "EUR"), "failed", AT, null, "op-1")).isTrue();

        verify(audit).refunded("CheckoutSession", "session-1", new Money(-1500, "EUR"), "re_1", "REFUND_FAILED");
    }

    // --- settled ------------------------------------------------------------------------------------------------------------

    @Test void T_12_17_aSettlementNamingAnUnknownCommandIsRejected() {
        assertThatThrownBy(() -> refunds.settled("pi_1", "re_1", new Money(700, "EUR"), AT, null, "op-unknown")).isInstanceOf(ApiException.class)
                .hasMessage("INVALID_STATE");
    }

    @Test void T_12_17_aLateRefundOfAnExpiredCheckoutSettlesOnItsSession() {
        when(operations.findById("op-1")).thenReturn(Optional.of(operation("op-1", "REFUND_LATE", "session-1", 1500, "late:pi_1", null)));
        when(operations.refundSettled(eq("op-1"), any())).thenReturn(true);

        assertThat(refunds.settled("pi_1", "re_1", new Money(1500, "EUR"), AT, null, "op-1")).isTrue();

        verify(operations).refundSettled("op-1", new UpfrontPayment.Refund(new Money(1500, "EUR"), "re_1", AT, "LATE_COMPLETION", null));
        // No upfront row: the audit names the checkout session.
        verify(audit).refunded("CheckoutSession", "session-1", new Money(1500, "EUR"), "re_1", "LATE_COMPLETION");
    }

    @Test void T_12_17_aLateRefundOfABookingPaymentSettlesOnItsUpfrontPayment() {
        when(operations.findById("op-1")).thenReturn(Optional.of(operation("op-1", "REFUND_LATE", "pay-1", 1500, "refund:pay-1", null)));
        when(operations.refundSettled(eq("op-1"), any())).thenReturn(true);
        when(upfront.findById("pay-1")).thenReturn(Optional.of(payment("pay-1", "CANCELLED", "booking-1", null)));

        assertThat(refunds.settled("pi_1", "re_1", new Money(1500, "EUR"), AT, null, "op-1")).isTrue();

        verify(audit).refunded("UpfrontPayment", "pay-1", new Money(1500, "EUR"), "re_1", "LATE_COMPLETION");
    }

    @Test void T_12_17_aRedeliveredLateRefundSettlementChangesNothing() {
        when(operations.findById("op-1")).thenReturn(Optional.of(operation("op-1", "REFUND_LATE", "session-1", 1500, "late:pi_1", null)));

        assertThat(refunds.settled("pi_1", "re_1", new Money(1500, "EUR"), AT, null, "op-1")).isFalse();

        verifyNoInteractions(audit);
    }

    @Test void T_12_17_anInvoiceRefundSettlesOnItsCollection() {
        when(collections.byProviderReference("pi_1")).thenReturn(Optional.of(collection(List.of())));

        assertThat(refunds.settled("pi_1", "re_1", new Money(700, "EUR"), AT, REASON, null)).isTrue();

        verify(collections).refund("col-1", new Collection.Refund(new Money(700, "EUR"), "re_1", AT, REASON, null), false);
        verify(invoices).refunded("invoice-1", new Money(700, "EUR"), NOW);
    }

    @Test void T_12_17_aRefundTheCollectionAlreadyHoldsIsNotSettledTwice() {
        when(collections.byProviderReference("pi_1")).thenReturn(Optional.of(collection(List.of(
                new Collection.Refund(new Money(700, "EUR"), "re_1", AT, REASON, null)))));

        assertThat(refunds.settled("pi_1", "re_1", new Money(700, "EUR"), AT, REASON, null)).isFalse();

        verify(collections, never()).refund(any(), any(), anyBoolean());
        verifyNoInteractions(audit);
    }

    @Test void T_12_17_anUpfrontRefundSettlesOnItsPayment() {
        when(upfront.forIntent("pi_1")).thenReturn(List.of(payment("pay-1", "PAID", null, null)));

        assertThat(refunds.settled("pi_1", "re_1", new Money(700, "EUR"), AT, REASON, null)).isTrue();

        verify(upfront).refund("pay-1", new UpfrontPayment.Refund(new Money(700, "EUR"), "re_1", AT, REASON, null), false);
    }

    @Test void T_12_17_aRefundThePaymentAlreadyHoldsIsNotSettledTwice() {
        when(upfront.forIntent("pi_1")).thenReturn(List.of(payment("pay-1", "PAID", null,
                List.of(new UpfrontPayment.Refund(new Money(700, "EUR"), "re_1", AT, REASON, null)))));

        assertThat(refunds.settled("pi_1", "re_1", new Money(700, "EUR"), AT, REASON, null)).isFalse();

        verify(upfront, never()).refund(any(), any(), anyBoolean());
        verifyNoInteractions(audit);
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    /** The refund's checkpoint as an earlier `succeeded` delivery of {@code refundId} on `pi_1` left it. */
    private void succeededBefore(String refundId, long amount) {
        when(refundStates.lock(refundId)).thenReturn(new StripeRefundRepository.State(CLUB + ":" + refundId, CLUB, "pi_1", new Money(amount, "EUR"), "succeeded", AT));
    }

    private void paidInvoice(Collection collection) {
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice()));
        when(collections.forInvoice("invoice-1")).thenReturn(List.of(collection));
    }

    static Invoice invoice() {
        var total = new Money(4500, "EUR");
        return new Invoice("invoice-1", CLUB, "2026", 1, "2026-0001", "2026-09-01", "2026-09", "member-1", new Invoice.MemberSnapshot(1, "Laura Serra", null),
                List.of(), total, new Money(0, "EUR"), total, new Invoice.PaymentMethodSnapshot(PaymentMethodType.CARD, null, null, null, "4242", null),
                InvoiceStatus.PAID, InvoiceKind.PERIODIC, "run-1", null, false, null, NOW, null, null, null, null, new Money(0, "EUR"), null, 3L, NOW, null, NOW, null);
    }

    static Collection collection(List<Collection.Refund> refunds) {
        return new Collection("col-1", CLUB, "invoice-1", CollectionProvider.STRIPE, new Money(4500, "EUR"), CollectionStatus.SUCCEEDED, "pi_1", null, 1,
                null, null, refunds, NOW, NOW);
    }

    static UpfrontPayment payment(String id, String status, String bookingId, List<UpfrontPayment.Refund> refunds) {
        return new UpfrontPayment(id, CLUB, "member-1", "dog-1", "SINGLE_CLASS", null, new Money(1500, "EUR"), new Money(1500, "EUR"), status, "STRIPE",
                "session-1", NOW, NOW, bookingId, null, null, null, null, new UpfrontPayment.StripeRefs("pi_1", "ch_1"), null, null, refunds, null);
    }

    static PaymentOperation operation(String id, String kind, String targetId, long amount, String key, String resultId) {
        String reason = "REFUND_LATE".equals(kind) ? "LATE_COMPLETION" : REASON;
        return new PaymentOperation(id, CLUB, kind, targetId, "pi_1", new Money(amount, "EUR"), key, reason, null, null, NOW, resultId);
    }

    static PendingCharge credit() {
        return new PendingCharge("charge-1", CLUB, "member-1", "dog-1", "booking-1", null, new Money(-1500, "EUR"), "SINGLE_CLASS", NOW, null, null);
    }
}
