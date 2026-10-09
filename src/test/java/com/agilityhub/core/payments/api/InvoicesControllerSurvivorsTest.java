package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.BillingContractAccess;
import com.agilityhub.core.payments.application.BillingTransactions;
import com.agilityhub.core.payments.application.CardPayments;
import com.agilityhub.core.payments.application.InvoiceActions;
import com.agilityhub.core.payments.application.PaymentRefunds;
import com.agilityhub.core.payments.application.ReceiptPdf;
import com.agilityhub.core.payments.domain.InvoiceKind;
import com.agilityhub.core.payments.domain.InvoiceLineOrigin;
import com.agilityhub.core.payments.domain.InvoiceStatus;
import com.agilityhub.core.payments.domain.ManualChannel;
import com.agilityhub.core.payments.domain.PaymentMethodType;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.lists.ListEngine;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link InvoicesController} (S12 §6, T-12-15, T-12-17, T-12-21): another club's invoice is 404
 * before any action, provider call or receipt runs, and a retry or a refund answers what its keyed transaction returns.
 */
class InvoicesControllerSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-09-25T08:00:00Z");
    static final LocalDate DAY = LocalDate.parse("2026-09-25");

    final BillingContractAccess access = mock(BillingContractAccess.class);
    final ListEngine lists = mock(ListEngine.class);
    final InvoiceActions actions = mock(InvoiceActions.class);
    final BillingTransactions transactions = mock(BillingTransactions.class);
    final ReceiptPdf receipts = mock(ReceiptPdf.class);
    final CardPayments cards = mock(CardPayments.class);
    final PaymentRefunds refunds = mock(PaymentRefunds.class);
    final InvoicesController controller = controller();

    InvoicesController controller() {
        var created = new InvoicesController(access, lists, actions, transactions, receipts, mock(ClubConfigService.class), mock(ObjectMapper.class));
        ReflectionTestUtils.setField(created, "cards", cards);
        ReflectionTestUtils.setField(created, "refunds", refunds);
        return created;
    }

    @Test void T_12_21_anotherClubsInvoiceIsNotFoundBeforeAnyActionRuns() {
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(access).invoice("invoice-b");

        assertCode(() -> controller.invoice("invoice-b"), ErrorCode.NOT_FOUND);
        assertCode(() -> controller.markInvoicePaid("invoice-b", new BillingRequests.InvoicePaymentRequest(DAY, ManualChannel.CASH, null, 1L),
                UUID.randomUUID()), ErrorCode.NOT_FOUND);
        assertCode(() -> controller.markInvoiceFailed("invoice-b", new BillingRequests.InvoiceFailureRequest("Bank return", DAY, 1L), UUID.randomUUID()),
                ErrorCode.NOT_FOUND);
        assertCode(() -> controller.cancelInvoice("invoice-b", new BillingRequests.InvoiceCancellationRequest("Duplicate", 1L), UUID.randomUUID()),
                ErrorCode.NOT_FOUND);
        assertCode(() -> controller.invoiceDocument("invoice-b"), ErrorCode.NOT_FOUND);
        verifyNoInteractions(actions, transactions, receipts);
    }

    @Test void T_12_21_anotherClubsInvoiceIsNotFoundBeforeTheProviderIsCalled() {
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(access).invoice("invoice-b");

        assertCode(() -> controller.retryInvoice("invoice-b", new BillingRequests.InvoiceRetryRequest(1L), UUID.randomUUID()), ErrorCode.NOT_FOUND);
        assertCode(() -> controller.refundInvoice("invoice-b", new BillingRequests.RefundRequest(null, "Duplicate"), UUID.randomUUID()), ErrorCode.NOT_FOUND);
        verifyNoInteractions(cards, refunds, actions, transactions);
    }

    @Test void T_12_21_aBulkPaymentNamingAnotherClubsInvoiceIsNotFoundAndPaysNothing() {
        var ids = List.of("invoice-a", "invoice-b");
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(access).invoices(ids);

        assertCode(() -> controller.markInvoicesPaid(new BillingRequests.BulkPaymentRequest(ids, DAY, ManualChannel.TRANSFER), UUID.randomUUID()),
                ErrorCode.NOT_FOUND);
        verifyNoInteractions(actions, transactions);
    }

    @Test void T_12_21_anErasedMembersManualInvoiceIsRefusedBeforeItIsIssued() {
        doThrow(new ApiException(ErrorCode.MEMBER_ERASED)).when(access).mutableMember("member-erased");
        var request = new BillingRequests.ManualInvoiceRequest("member-erased",
                List.of(new BillingRequests.ManualInvoiceLine("Adjustment", new Money(-3000, "EUR"), BigDecimal.ZERO)), null, "Fictional adjustment");

        assertCode(() -> controller.createManualInvoice(request, UUID.randomUUID()), ErrorCode.MEMBER_ERASED);
        verifyNoInteractions(actions, transactions);
    }

    @Test void T_12_21_theInvoiceListRunsOnlyInsideTheCallersTenant() {
        doThrow(new ApiException(ErrorCode.NO_MEMBERSHIP)).when(access).tenant();

        assertCode(() -> controller.invoices(new LinkedMultiValueMap<>()), ErrorCode.NO_MEMBERSHIP);
        verifyNoInteractions(lists);
    }

    @Test void T_12_15_aRetryChargesTheCardAndAnswersTheInvoiceOfItsKeyedTransaction() {
        runKeyedWork();
        when(cards.retry("invoice-1", 2L)).thenReturn("operation-1");
        when(actions.detail("invoice-1")).thenReturn(new InvoiceActions.InvoiceDetail(invoice("invoice-1"), List.of()));

        var answer = controller.retryInvoice("invoice-1", new BillingRequests.InvoiceRetryRequest(2L), UUID.randomUUID());

        assertThat(answer).isNotNull();
        assertThat(answer.id()).isEqualTo("invoice-1");
        assertThat(answer.displayNumber()).isEqualTo("2026-0001");
        verify(cards).execute("operation-1");
    }

    @Test void T_12_17_aRefundAnswersTheAcceptedRefundOfItsKeyedTransaction() {
        runKeyedWork();
        var amount = new Money(1500, "EUR");
        var key = UUID.fromString("00000000-0000-4000-8000-000000000001");
        when(refunds.invoice("invoice-1", amount, "Duplicate", "invoice-refund:invoice-1:" + key))
                .thenReturn(new PaymentRefunds.Accepted("refund-1", amount, null));

        var answer = controller.refundInvoice("invoice-1", new BillingRequests.RefundRequest(amount, "Duplicate"), key);

        assertThat(answer).isEqualTo(new BillingContracts.RefundAccepted("refund-1", amount, null));
        verify(refunds).execute("refund-1");
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    /** The keyed transaction runs its work and answers its result (a first request, no stored replay). */
    void runKeyedWork() {
        when(transactions.keyed(anyInt(), any(), any())).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
    }

    static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    static Invoice invoice(String id) {
        var total = new Money(4500, "EUR");
        return new Invoice(id, "club-a", "2026", 1, "2026-0001", "2026-08-25", "2026-09", "member-a", new Invoice.MemberSnapshot(1, "Laura Serra", null),
                List.of(new Invoice.Line(1, InvoiceLineOrigin.MONTHLY_FEE, "price-1", null, "Quota", total, BigDecimal.ZERO, new Money(0, "EUR"), total)),
                total, new Money(0, "EUR"), total, new Invoice.PaymentMethodSnapshot(PaymentMethodType.CARD, null, "Laura Serra", null, "4242", null),
                InvoiceStatus.FAILED, InvoiceKind.PERIODIC, "run-1", null, false, null, null, null, null, null, null, new Money(0, "EUR"), null, 0L, NOW, null,
                NOW, null);
    }
}
