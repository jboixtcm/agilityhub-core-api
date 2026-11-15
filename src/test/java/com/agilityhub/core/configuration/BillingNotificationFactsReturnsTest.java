package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.payments.application.InvoiceActions;
import com.agilityhub.core.payments.domain.InvoiceKind;
import com.agilityhub.core.payments.domain.InvoiceLineOrigin;
import com.agilityhub.core.payments.domain.InvoiceStatus;
import com.agilityhub.core.payments.domain.PaymentMethodType;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SEPA-04 (b), S12 §8 (owner decision of 13-11-2026): the returns of one remittance marked together give the admins ONE N-10 —
 * from `InvoicesFailed`, the members' names and the receipts' numbers listed and the amounts summed — and none from each
 * receipt's `InvoiceFailed{summarized}`; a single return keeps its own N-10.
 */
class BillingNotificationFactsReturnsTest {
    static final Instant NOW = Instant.parse("2026-09-15T08:00:00Z");
    final InvoiceActions invoices = mock(InvoiceActions.class);
    final BillingNotificationFacts facts = new BillingNotificationFacts(invoices, mock(CensusClubSettings.class));

    @Test void SEPA_04_b_theReturnsOfARemittanceMarkedTogetherGiveOneSummaryN10() {
        stub("inv-1", "Laura Example", "2026-0007", 4500);
        stub("inv-2", "Pau Example", "2026-0008", 6000);
        stub("inv-3", "Laura Example", "2026-0009", 1200);
        var trigger = trigger("InvoicesFailed", "remittance-1", Map.of("remittanceId", "remittance-1", "invoiceIds", List.of("inv-1", "inv-2", "inv-3"),
                "provider", "SEPA_XML", "reason", "BANK_RETURN"));
        assertThat(facts.eventTypes()).contains("InvoiceFailed", "InvoicesFailed");
        var summary = facts.facts(trigger, "N-10").orElseThrow();
        assertThat(summary.audiences()).containsExactly("ADMINS");
        assertThat(summary.values()).containsEntry("member_name", List.of("Laura Example", "Pau Example"))
                .containsEntry("invoice_number", List.of("2026-0007", "2026-0008", "2026-0009"))
                .containsEntry("amount", new Money(11700, "EUR")).containsEntry("reason", "Fons insuficients").containsEntry("entityId", "remittance-1");
        assertThat(facts.facts(trigger, "N-35")).isEmpty();
    }

    @Test void SEPA_04_b_aReturnInASummaryHasNoN10OfItsOwnAndASingleReturnKeepsIt() {
        stub("inv-1", "Laura Example", "2026-0007", 4500);
        var summarized = trigger("InvoiceFailed", "inv-1", Map.of("invoiceId", "inv-1", "provider", "SEPA_XML", "reason", "BANK_RETURN", "summarized", true));
        assertThat(facts.facts(summarized, "N-10")).isEmpty();
        var single = trigger("InvoiceFailed", "inv-1", Map.of("invoiceId", "inv-1", "provider", "SEPA_XML", "reason", "BANK_RETURN"));
        assertThat(facts.facts(single, "N-10").orElseThrow().values()).containsEntry("member_name", "Laura Example").containsEntry("invoice_number", "2026-0007");
    }

    void stub(String id, String name, String number, long minor) {
        var total = new Money(minor, "EUR");
        var invoice = new Invoice(id, "club-a", "2026", 7, number, "2026-09-01", "2026-09", "member-" + id, new Invoice.MemberSnapshot(1, name, null),
                List.of(new Invoice.Line(1, InvoiceLineOrigin.MONTHLY_FEE, "price-1", null, "Quota", total, BigDecimal.ZERO, new Money(0, "EUR"), total)),
                total, new Money(0, "EUR"), total, new Invoice.PaymentMethodSnapshot(PaymentMethodType.SEPA_DD, "ES** **** 1234", name, "MANDATE-1", null, null, NOW),
                InvoiceStatus.FAILED, InvoiceKind.PERIODIC, "run-1", "remittance-1", false, null, null, NOW, "Fons insuficients", null, null, new Money(0, "EUR"), null,
                4L, NOW, null, NOW, null);
        when(invoices.detail(id)).thenReturn(new InvoiceActions.InvoiceDetail(invoice, List.of()));
    }
    static NotificationTrigger trigger(String type, String aggregateId, Map<String, Object> payload) {
        return new NotificationTrigger("event-" + type, type, "club-a", type.equals("InvoicesFailed") ? "Remittance" : "Invoice", aggregateId, NOW, payload,
                "admin-1", null, null);
    }
}
