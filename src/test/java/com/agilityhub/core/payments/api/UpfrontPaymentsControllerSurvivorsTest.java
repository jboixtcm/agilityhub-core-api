package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.BillingContractAccess;
import com.agilityhub.core.payments.application.BillingTransactions;
import com.agilityhub.core.payments.application.ManualUpfrontPayments;
import com.agilityhub.core.payments.application.PaymentRefunds;
import com.agilityhub.core.payments.domain.ManualChannel;
import com.agilityhub.core.payments.domain.UpfrontConcept;
import com.agilityhub.core.payments.domain.UpfrontStatus;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link UpfrontPaymentsController} (S12 §6, R-12-20, R-12-23; T-12-17, T-12-21): D10's list passes
 * its optional status filter and answers every payment, an erased member records nothing, another club's payment is not
 * refunded, and a refund answers the accepted refund of its keyed transaction.
 */
class UpfrontPaymentsControllerSurvivorsTest {
    final BillingContractAccess access = mock(BillingContractAccess.class);
    final ManualUpfrontPayments manual = mock(ManualUpfrontPayments.class);
    final PaymentRefunds refunds = mock(PaymentRefunds.class);
    final BillingTransactions transactions = mock(BillingTransactions.class);
    final ObjectMapper mapper = mock(ObjectMapper.class);
    final UpfrontPaymentsController controller = controller();

    UpfrontPaymentsController controller() {
        var created = new UpfrontPaymentsController(access);
        ReflectionTestUtils.setField(created, "manual", manual);
        ReflectionTestUtils.setField(created, "refunds", refunds);
        ReflectionTestUtils.setField(created, "transactions", transactions);
        ReflectionTestUtils.setField(created, "mapper", mapper);
        return created;
    }

    @Test void E11_T06_theListPassesItsStatusFilterAndAnswersEveryPayment() {
        Map<String, Object> paid = Map.of("id", "upfront-paid");
        Map<String, Object> due = Map.of("id", "upfront-due");
        var paidView = payment("upfront-paid", UpfrontStatus.PAID);
        var dueView = payment("upfront-due", UpfrontStatus.DUE);
        when(manual.list("member-1", "PAID")).thenReturn(List.of(paid));
        when(manual.list("member-1", null)).thenReturn(List.of(due, paid));
        when(mapper.convertValue(paid, BillingContracts.UpfrontPayment.class)).thenReturn(paidView);
        when(mapper.convertValue(due, BillingContracts.UpfrontPayment.class)).thenReturn(dueView);

        var filtered = controller.upfrontPayments("member-1", UpfrontStatus.PAID);
        var all = controller.upfrontPayments("member-1", null);

        assertThat(filtered).isNotNull();
        assertThat(filtered.items()).containsExactly(paidView);
        assertThat(all.items()).containsExactly(dueView, paidView);
    }

    @Test void T_12_21_anErasedMemberRecordsNoPayment() {
        doThrow(new ApiException(ErrorCode.MEMBER_ERASED)).when(access).mutableMember("member-erased");
        var request = new BillingRequests.UpfrontPaymentRequest("member-erased", null, UpfrontConcept.ENTRY_FEE, new Money(3000, "EUR"),
                new Money(3000, "EUR"), ManualChannel.CASH, LocalDate.parse("2026-09-25"), null, null);

        assertCode(() -> controller.recordUpfrontPayment(request, UUID.randomUUID()), ErrorCode.MEMBER_ERASED);
        verifyNoInteractions(manual, transactions);
    }

    @Test void T_12_21_anotherClubsPaymentIsNotFoundBeforeTheRefund() {
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(access).upfrontPayment("upfront-b");

        assertCode(() -> controller.refundUpfrontPayment("upfront-b", new BillingRequests.RefundRequest(null, "Duplicate"), UUID.randomUUID()),
                ErrorCode.NOT_FOUND);
        verifyNoInteractions(refunds, transactions);
    }

    @Test void T_12_17_aRefundAnswersTheAcceptedRefundOfItsKeyedTransaction() {
        when(transactions.keyed(anyInt(), any(), any())).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
        var amount = new Money(1000, "EUR");
        var key = UUID.fromString("00000000-0000-4000-8000-000000000002");
        when(refunds.upfront("upfront-1", amount, "Duplicate", "upfront-refund:upfront-1:" + key)).thenReturn(new PaymentRefunds.Accepted("refund-1", amount, null));

        var answer = controller.refundUpfrontPayment("upfront-1", new BillingRequests.RefundRequest(amount, "Duplicate"), key);

        assertThat(answer).isEqualTo(new BillingContracts.RefundAccepted("refund-1", amount, null));
        verify(refunds).execute("refund-1");
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    static BillingContracts.UpfrontPayment payment(String id, UpfrontStatus status) {
        return new BillingContracts.UpfrontPayment(id, "member-1", null, UpfrontConcept.ENTRY_FEE, null, new Money(3000, "EUR"), new Money(3000, "EUR"),
                status, null, null, null, null, List.of(), null, null, null);
    }
}
