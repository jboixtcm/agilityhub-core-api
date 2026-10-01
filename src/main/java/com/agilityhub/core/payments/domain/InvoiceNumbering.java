package com.agilityhub.core.payments.domain;

import java.time.LocalDate;

/**
 * S12 R-12-08: the series is `billing.invoiceSeriesPattern` resolved (`{YYYY}` → the year of the issue date), the number the
 * series counter's, `displayNumber = "{series}-{number:04d}"`. The counter key is the resolved series when numbering resets
 * yearly — a new year's series has no counter yet and starts at 1 — and the unresolved pattern otherwise, so the numbers go
 * on across years. A pattern without `{YYYY}` cannot reset (its series would repeat a number), so it always goes on.
 */
public final class InvoiceNumbering {
    private InvoiceNumbering() { }
    public static final String YEAR = "{YYYY}";

    public static String series(String pattern, LocalDate issueDate) {
        String resolved = (pattern == null || pattern.isBlank() ? YEAR : pattern).replace(YEAR, String.format("%04d", issueDate.getYear()));
        return resolved.strip();
    }
    public static String counterKey(String pattern, LocalDate issueDate, boolean resetYearly) {
        String effective = pattern == null || pattern.isBlank() ? YEAR : pattern;
        return resetYearly && effective.contains(YEAR) ? series(effective, issueDate) : effective;
    }
    public static String displayNumber(String series, long number) { return series + "-" + String.format("%04d", number); }
}
