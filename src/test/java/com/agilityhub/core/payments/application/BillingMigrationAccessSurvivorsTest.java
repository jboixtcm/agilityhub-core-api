package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.InvoiceKind;
import com.agilityhub.core.payments.domain.InvoiceLineOrigin;
import com.agilityhub.core.payments.domain.InvoiceStatus;
import com.agilityhub.core.payments.domain.PaymentMethodType;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.PackBalanceRepository;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link BillingMigrationAccess} (S18; T-18-05): an import's PLAYOFF numbers are checked only against
 * the PLAYOFF series, each with its source receipt id (empty when the invoice has none).
 */
class BillingMigrationAccessSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-08-25T08:00:00Z");
    final InvoiceRepository invoices = mock(InvoiceRepository.class);
    final BillingMigrationAccess access = new BillingMigrationAccess(invoices, mock(PackBalanceRepository.class), mock(PackBalanceService.class),
            mock(BillingSimulationService.class), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void T_18_05_receiptNumberSourcesReadOnlyThePlayoffSeriesWithEachSourceId() {
        when(invoices.findAll()).thenReturn(List.of(
                invoice("invoice-7", "PLAYOFF", 7, Map.of("playoffReceiptId", "playoff-receipt-7")),
                invoice("invoice-8", "PLAYOFF", 8, null),
                invoice("invoice-9", "2026", 9, Map.of("playoffReceiptId", "playoff-receipt-9"))));
        assertThat(access.receiptNumberSources()).isEqualTo(Map.of(7L, "playoff-receipt-7", 8L, ""));
    }

    static Invoice invoice(String id, String series, long number, Map<String, Object> sourceIds) {
        var total = new Money(4500, "EUR");
        return new Invoice(id, "club-a", series, number, series + "-" + number, "2025-06-01", "2025-06", "member-a", new Invoice.MemberSnapshot(1, "Laura Serra", null),
                List.of(new Invoice.Line(1, InvoiceLineOrigin.MIGRATED, null, null, "Quota", total, BigDecimal.ZERO, new Money(0, "EUR"), total)),
                total, new Money(0, "EUR"), total, new Invoice.PaymentMethodSnapshot(PaymentMethodType.MANUAL, null, null, null, null, null),
                InvoiceStatus.PAID, InvoiceKind.MIGRATED, null, null, false, null, null, null, null, null, null, new Money(0, "EUR"), sourceIds, 0L, NOW, null,
                NOW, null);
    }
}
