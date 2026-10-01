package com.agilityhub.core.payments.persistence;

import java.util.List;

/**
 * E8-T02 (T-12-12): test-only repositories that break R-12-10, so that `InvoiceImmutabilityRuleTest` sees the ArchUnit rule
 * `INVOICE_LINES_IMMUTABLE` fail on them (and pass on the production classes, `ArchitectureTest`).
 */
final class InvoiceMutationFixtures {
    private InvoiceMutationFixtures() { }
    static class LinesRepository { void updateLines(String invoiceId, List<Invoice.Line> lines) { } }
    static class WholeInvoiceRepository { void saveInvoice(Invoice invoice) { } }
    static class StateRepository { boolean transition(String id, long version, BillingDocuments.InvoiceState state) { return true; } }
}
