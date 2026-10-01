package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.InvoiceLineOrigin;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.LocalizedText;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * S12 R-12-30, §10: the line descriptions frozen once, at generation, in the club's `defaultLocale` — never in the admin's —
 * from `billing.line.monthlyFee`, `maintenanceFee`, `inactivityFee`, `singleClass`, `pack` and `adjustment`: «Quota {pla} —
 * {Mes any}», «Quota inactivitat — {Mes any}», «Classe {data} — {gos}». The month is its stand-alone name, capitalised, and the
 * year («Setembre 2026», «September 2026»); the class date is `dd/MM`.
 */
@Component
public class BillingTexts {
    private final IcuMessageSource messages;
    public BillingTexts(IcuMessageSource messages) { this.messages = messages; }

    public String line(InvoiceLineOrigin origin, String planName, YearMonth month, Locale locale) {
        String key = switch (origin) {
            case MONTHLY_FEE -> "billing.line.monthlyFee";
            case MAINTENANCE_FEE -> "billing.line.maintenanceFee";
            case INACTIVITY_FEE -> "billing.line.inactivityFee";
            case PACK -> "billing.line.pack";
            default -> throw new IllegalArgumentException("Not a periodic line: " + origin);
        };
        return messages.format(key, Map.of("plan", planName == null ? "" : planName, "month", month(month, locale)), locale).strip();
    }
    /** R-12-25: «Classe 06/10 — Duna», frozen when the charge is created. */
    public String singleClass(LocalDate classDate, String dogName, Locale locale) {
        return messages.format("billing.line.singleClass", Map.of("date", String.format("%02d/%02d", classDate.getDayOfMonth(), classDate.getMonthValue()),
                "dog", dogName == null ? "" : dogName), locale).strip();
    }
    /** R-12-19: an adjustment line keeps the admin's own words. */
    public String adjustment(String description, Locale locale) {
        return messages.format("billing.line.adjustment", Map.of("description", description.strip()), locale);
    }
    /** «Setembre 2026»: the month's stand-alone name in {@code locale}, capitalised, and the year. */
    public String month(YearMonth month, Locale locale) {
        var format = com.ibm.icu.text.DateFormat.getInstanceForSkeleton("LLLL", locale);
        format.setTimeZone(com.ibm.icu.util.TimeZone.GMT_ZONE);
        String name = format.format(java.util.Date.from(month.atDay(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)));
        return name.substring(0, 1).toUpperCase(locale) + name.substring(1) + " " + month.getYear();
    }
    /** A catalog name in {@code locale} (its default language when it has none in it). */
    public static String text(LocalizedText text, Locale locale) {
        if (text == null) { return null; }
        var resolved = text.resolve(locale);
        return resolved == null ? null : resolved.value();
    }
}
