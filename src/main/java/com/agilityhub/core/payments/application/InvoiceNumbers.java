package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.InvoiceNumbering;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.platform.application.InvoiceCounters;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * S12 R-12-08 (E8-T02 round 2, ruling E87): the numbers of the receipts a run or a manual invoice issues, taken from the
 * counter of the issue date's series (`billing.invoiceSeriesPattern`, `billing.invoiceResetYearly`). A number is never
 * reused except through a rollback (R-12-14):
 * <ul>
 * <li>a counter never hands out a number at or below the highest one its series has issued (the rolled-back receipts
 * aside), so toggling `billing.invoiceResetYearly` — which switches the counter key between `"2026"` and `"{YYYY}"` — goes on
 * from the series' last receipt in either direction;</li>
 * <li>a key without a counter yet also starts at the counter of another key of the same series: a resolved series key
 * (`"2026"`) always, a pattern key (`"{YYYY}"`, it runs across the years) only when the series already has receipts. A new
 * year's series with the yearly reset therefore starts at 1 (`2027-0001`).</li>
 * </ul>
 * Called inside the caller's billing transaction, so the block is taken atomically with the receipts that use it.
 */
@Component
public class InvoiceNumbers {
    /** {@code count} consecutive numbers from {@code first} of {@code series}, taken from the counter {@code counterKey}. */
    public record Block(String series, String counterKey, long first) { }

    private final InvoiceCounters counters; private final InvoiceRepository invoices;
    public InvoiceNumbers(InvoiceCounters counters, InvoiceRepository invoices) { this.counters = counters; this.invoices = invoices; }

    public Block reserve(InvoicingService.Context context, int count) {
        LocalDate issueDate = context.issueDate();
        String pattern = context.parameter("billing.invoiceSeriesPattern", String.class);
        boolean reset = !Boolean.FALSE.equals(context.parameter("billing.invoiceResetYearly", Boolean.class));
        String series = InvoiceNumbering.series(pattern, issueDate), key = InvoiceNumbering.counterKey(pattern, issueDate, reset);
        boolean used = invoices.seriesUsed(series);
        String storedSeries = InvoiceCounters.storedKey(series);
        long first = counters.reserve(key, count, invoices.highestNumber(series) + 1,
                stored -> sameSeries(stored, storedSeries, issueDate, used));
        return new Block(series, key, first);
    }

    /** Whether the stored counter key {@code stored} numbers {@code storedSeries} on {@code issueDate} (see the class comment). */
    static boolean sameSeries(String stored, String storedSeries, LocalDate issueDate, boolean seriesUsed) {
        if (!InvoiceCounters.storedKey(InvoiceNumbering.series(stored, issueDate)).equals(storedSeries)) { return false; }
        return !stored.contains(InvoiceNumbering.YEAR) || seriesUsed;
    }
}
