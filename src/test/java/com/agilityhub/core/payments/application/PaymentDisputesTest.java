package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.BillingEvent;
import com.agilityhub.core.payments.domain.CollectionProvider;
import com.agilityhub.core.payments.domain.CollectionStatus;
import com.agilityhub.core.payments.persistence.BillingDocuments.CollectionRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.payments.persistence.PaymentDispute;
import com.agilityhub.core.payments.persistence.UpfrontPayment;
import com.agilityhub.core.payments.persistence.UpfrontPaymentRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.Money;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PAY-06 (owner decision of 13 Nov 2026, S12 R-12-21): a Stripe dispute flags the card payment it disputes — every row a Checkout
 * settled, or the card collection of an invoice — and its first event tells the admins (`PaymentDisputed`, N-56); later events only
 * bring the flag up to date, an older one never reopens a closed dispute, and a payment Core does not hold is left alone. Nothing
 * but the flag is written. Collaborators are mocks; fictional data.
 */
class PaymentDisputesTest {
    static final Instant OPENED = Instant.parse("2026-10-20T09:00:00Z"), CLOSED = Instant.parse("2026-12-02T09:00:00Z");
    static final Money AMOUNT = new Money(12000, "EUR");
    final UpfrontPaymentRepository upfront = mock(UpfrontPaymentRepository.class);
    final CollectionRepository collections = mock(CollectionRepository.class);
    final InvoiceRepository invoices = mock(InvoiceRepository.class);
    final BillingEvents events = mock(BillingEvents.class);
    final PaymentDisputes disputes = new PaymentDisputes(upfront, collections, invoices, events);
    final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach void tenant() { TenantContext.open("club-a"); }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test @SuppressWarnings("unchecked") void PAY_06_aDisputeFlagsEveryRowTheCheckoutSettledAndItsFirstEventTellsTheAdmins() {
        when(upfront.forIntent("pi_pack")).thenReturn(List.of(row("row-1", null), row("row-2", null)));

        assertThat(disputes.record(dispute("dp_1", "pi_pack", "needs_response"), OPENED)).isTrue();

        var expected = new PaymentDispute("dp_1", "needs_response", "fraudulent", AMOUNT, OPENED, null);
        verify(upfront).dispute(List.of("row-1", "row-2"), expected);
        var payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publish(eq(BillingEvent.Kind.PaymentDisputed), eq("member-1"), payload.capture());
        assertThat(payload.getValue()).containsExactly(entry("disputeId", "dp_1"), entry("memberId", "member-1"), entry("amount", AMOUNT),
                entry("reason", "fraudulent"), entry("upfrontPaymentIds", List.of("row-1", "row-2")), entry("invoiceId", null));
        // The flag only: no status, amount or refund of the rows is written, and the collections are not read for a Checkout's payment.
        verify(upfront, never()).update(any()); verify(upfront, never()).refund(any(), any(), anyBoolean());
        verifyNoInteractions(collections);
    }

    @Test void PAY_06_laterEventsOnlyUpdateTheFlagAndAnOlderOneNeverReopensAClosedDispute() {
        var open = new PaymentDispute("dp_1", "needs_response", "fraudulent", AMOUNT, OPENED, null);
        when(upfront.forIntent("pi_pack")).thenReturn(List.of(row("row-1", open)));
        // Under review: the flag follows, opened when Core first learnt of it; no second notice.
        assertThat(disputes.record(dispute("dp_1", "pi_pack", "under_review"), OPENED.plusSeconds(3600))).isTrue();
        verify(upfront).dispute(List.of("row-1"), new PaymentDispute("dp_1", "under_review", "fraudulent", AMOUNT, OPENED, null));
        // The same state again changes nothing.
        assertThat(disputes.record(dispute("dp_1", "pi_pack", "needs_response"), OPENED.plusSeconds(60))).isFalse();
        // Closed (lost): closedAt is the event's time.
        assertThat(disputes.record(dispute("dp_1", "pi_pack", "lost"), CLOSED)).isTrue();
        var lost = new PaymentDispute("dp_1", "lost", "fraudulent", AMOUNT, OPENED, CLOSED);
        verify(upfront).dispute(List.of("row-1"), lost);
        // An older `updated` delivered after the close never reopens it.
        when(upfront.forIntent("pi_pack")).thenReturn(List.of(row("row-1", lost)));
        assertThat(disputes.record(dispute("dp_1", "pi_pack", "under_review"), CLOSED.plusSeconds(60))).isFalse();
        verify(upfront, times(2)).dispute(any(), any());
        verify(events, never()).publish(any(), any(), any());
    }

    @Test @SuppressWarnings("unchecked") void PAY_06_aDisputedInvoiceChargeFlagsItsCardCollectionAndNamesTheInvoicesMember() {
        when(upfront.forIntent("pi_invoice")).thenReturn(List.of());
        when(collections.byProviderReference("pi_invoice")).thenReturn(Optional.of(collection(CollectionProvider.STRIPE)));
        var invoice = mock(Invoice.class);
        when(invoice.memberId()).thenReturn("member-7");
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice));

        assertThat(disputes.record(dispute("dp_2", "pi_invoice", "warning_needs_response"), OPENED)).isTrue();

        verify(collections).dispute("collection-1", new PaymentDispute("dp_2", "warning_needs_response", "fraudulent", AMOUNT, OPENED, null));
        var payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publish(eq(BillingEvent.Kind.PaymentDisputed), eq("member-7"), payload.capture());
        assertThat(payload.getValue()).containsEntry("invoiceId", "invoice-1").containsEntry("upfrontPaymentIds", List.of()).containsEntry("memberId", "member-7");
        verify(upfront, never()).dispute(any(), any());
    }

    @Test void PAY_06_aDisputeOfAPaymentCoreDoesNotHoldChangesNothing() {
        when(upfront.forIntent(anyString())).thenReturn(List.of());
        when(collections.byProviderReference("pi_unknown")).thenReturn(Optional.empty());
        // A SEPA or manual attempt never carries a Stripe PaymentIntent; one that did would not be flagged either.
        when(collections.byProviderReference("pi_sepa")).thenReturn(Optional.of(collection(CollectionProvider.SEPA_XML)));

        assertThat(disputes.record(dispute("dp_3", "pi_unknown", "needs_response"), OPENED)).isFalse();
        assertThat(disputes.record(dispute("dp_4", "pi_sepa", "needs_response"), OPENED)).isFalse();
        assertThat(disputes.record(mapper.createObjectNode().put("id", "dp_5").put("status", "needs_response"), OPENED)).as("no payment_intent").isFalse();

        verify(upfront, never()).dispute(any(), any()); verify(collections, never()).dispute(any(), any());
        verifyNoInteractions(events);
    }

    JsonNode dispute(String id, String intent, String status) {
        return mapper.valueToTree(Map.of("id", id, "payment_intent", intent, "status", status, "reason", "fraudulent", "amount", 12000, "currency", "eur"));
    }
    static UpfrontPayment row(String id, PaymentDispute dispute) {
        return new UpfrontPayment(id, "club-a", "member-1", "dog-1", "PACK", null, AMOUNT, AMOUNT, "PAID", "STRIPE", "session-1", OPENED, OPENED, null, null, null,
                null, "pack-1", new UpfrontPayment.StripeRefs("pi_pack", null), null, null, List.of(), null, dispute);
    }
    static Collection collection(CollectionProvider provider) {
        return new Collection("collection-1", "club-a", "invoice-1", provider, new Money(9000, "EUR"), CollectionStatus.SUCCEEDED, "pi_invoice", null, 1, null, null,
                List.of(), OPENED, OPENED);
    }
}
