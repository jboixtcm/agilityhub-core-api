package com.agilityhub.core.payments.domain;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * S12 R-12-08, R-12-09, R-12-12 (collection date only) and R-12-25 as pure arithmetic (E8-T02): T-12-04, T-12-05, T-12-29b
 * and the domain half of T-12-08 — no Spring, no Mongo.
 */
class InvoiceArithmeticTest {
    static Money eur(long cents) { return new Money(cents, "EUR"); }

    @Test void T_12_04_theSeriesIsTheIssueYearAndTheNumberHasFourDigits() {
        String series = InvoiceNumbering.series("{YYYY}", LocalDate.of(2026, 8, 25));
        assertThat(series).isEqualTo("2026");
        assertThat(List.of(912L, 913L, 914L).stream().map(n -> InvoiceNumbering.displayNumber(series, n)).toList())
                .containsExactly("2026-0912", "2026-0913", "2026-0914");
        assertThat(InvoiceNumbering.displayNumber("2026", 12345)).isEqualTo("2026-12345");
        assertThat(InvoiceNumbering.series(null, LocalDate.of(2027, 1, 2))).isEqualTo("2027");
        assertThat(InvoiceNumbering.series("R{YYYY}", LocalDate.of(2027, 1, 2))).isEqualTo("R2027");
    }

    @Test void T_12_04_withAYearlyResetANewYearIsANewCounterWithoutItTheNumbersGoOn() {
        // resetYearly: December's run for January keeps the series of its issue date; the first 2027 invoice opens a new counter.
        assertThat(InvoiceNumbering.counterKey("{YYYY}", LocalDate.of(2026, 12, 24), true)).isEqualTo("2026");
        assertThat(InvoiceNumbering.counterKey("{YYYY}", LocalDate.of(2027, 1, 2), true)).isEqualTo("2027");
        // Without it one counter runs across the years (2027-0915 follows 2026-0914).
        assertThat(InvoiceNumbering.counterKey("{YYYY}", LocalDate.of(2026, 12, 24), false))
                .isEqualTo(InvoiceNumbering.counterKey("{YYYY}", LocalDate.of(2027, 1, 2), false));
        // A pattern without the year cannot reset: its series would repeat a number.
        assertThat(InvoiceNumbering.counterKey("R", LocalDate.of(2026, 12, 24), true)).isEqualTo(InvoiceNumbering.counterKey("R", LocalDate.of(2027, 1, 2), true));
    }

    @Test void T_12_05_taxIsRoundedHalfEvenPerLineAndTheInvoiceIsTheSumOfItsLines() {
        var line = InvoiceAmounts.fromBase(eur(4959), BigDecimal.valueOf(21));
        assertThat(line.tax()).isEqualTo(eur(1041));
        assertThat(line.total()).isEqualTo(eur(6000));
        // round_half_even: 50 × 21 % = 10.5 → 10; 150 × 21 % = 31.5 → 32.
        assertThat(InvoiceAmounts.fromBase(eur(50), BigDecimal.valueOf(21)).tax()).isEqualTo(eur(10));
        assertThat(InvoiceAmounts.fromBase(eur(150), BigDecimal.valueOf(21)).tax()).isEqualTo(eur(32));
        // billing.taxIncluded: the price is the total, 60,00 € at 21 % → 4959 + 1041 (S12 R-12-09's example), at 0 % (the Cànic, B3) all base.
        var included = InvoiceAmounts.fromPrice(eur(6000), BigDecimal.valueOf(21), true);
        assertThat(List.of(included.base(), included.tax(), included.total())).containsExactly(eur(4959), eur(1041), eur(6000));
        var canic = InvoiceAmounts.fromPrice(eur(6000), BigDecimal.ZERO, true);
        assertThat(List.of(canic.base(), canic.tax(), canic.total())).containsExactly(eur(6000), eur(0), eur(6000));
        var excluded = InvoiceAmounts.fromPrice(eur(1000), BigDecimal.valueOf(21), false);
        assertThat(excluded.total()).isEqualTo(eur(1210));
        // The invoice total is the sum of the line totals, never recomputed from a total: 3 × 10,00 € at 21 % included.
        var thirds = InvoiceAmounts.fromPrice(eur(1000), BigDecimal.valueOf(21), true);
        assertThat(InvoiceAmounts.sum(List.of(thirds.total(), thirds.total(), thirds.total()), "EUR")).isEqualTo(eur(3000));
        assertThat(InvoiceAmounts.sum(List.of(thirds.base(), thirds.base(), thirds.base()), "EUR").plus(
                InvoiceAmounts.sum(List.of(thirds.tax(), thirds.tax(), thirds.tax()), "EUR"))).isEqualTo(eur(3000));
        // A negative adjustment keeps the same arithmetic.
        assertThat(InvoiceAmounts.fromBase(eur(-3000), BigDecimal.ZERO).total()).isEqualTo(eur(-3000));
    }

    @Test void T_12_05_anotherCurrencyIsCurrencyMismatch() {
        assertThatThrownBy(() -> InvoiceAmounts.sum(List.of(eur(100), new Money(100, "USD")), "EUR"))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.CURRENCY_MISMATCH));
        assertThatThrownBy(() -> InvoiceAmounts.requireCurrency(new Money(100, "GBP"), "EUR")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> InvoiceAmounts.fromBase(eur(100), BigDecimal.valueOf(101))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void T_12_29b_theCollectionDayIsADayOfTheBilledMonthAndZeroIsItsLastDay() {
        assertThat(CollectionDates.defaultDate(YearMonth.of(2026, 9), 1)).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(CollectionDates.defaultDate(YearMonth.of(2026, 9), 0)).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(CollectionDates.defaultDate(YearMonth.of(2026, 2), 28)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(CollectionDates.defaultDate(YearMonth.of(2028, 2), 0)).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThatThrownBy(() -> CollectionDates.defaultDate(YearMonth.of(2026, 9), 29)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void T_12_29b_twoBusinessDaysAfterTheGenerationDay() {
        // Tuesday 25-08 → Thursday 27-08 is the earliest: 01-09 is fine.
        assertThat(CollectionDates.earliest(LocalDate.of(2026, 8, 25))).isEqualTo(LocalDate.of(2026, 8, 27));
        assertThat(CollectionDates.tooSoon(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 8, 25))).isFalse();
        // Friday 28-08 → the weekend does not count: Tuesday 01-09 is the earliest, still fine.
        assertThat(CollectionDates.earliest(LocalDate.of(2026, 8, 28))).isEqualTo(LocalDate.of(2026, 9, 1));
        // Monday 31-08 → Wednesday 02-09: day 1 is too soon.
        assertThat(CollectionDates.tooSoon(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 8, 31))).isTrue();
    }

    @Test void T_12_08_presentChargesPendingVoidsASecondMarkNeverDuplicatesAndALateCancellationCharges() {
        var none = PendingChargeRules.Existing.NONE;
        assertThat(PendingChargeRules.onAttendance("PRESENT", "PENDING", none)).isEqualTo(PendingChargeRules.Action.CHARGE);
        assertThat(PendingChargeRules.onAttendance("NO_SHOW", "PENDING", none)).isEqualTo(PendingChargeRules.Action.CHARGE);
        assertThat(PendingChargeRules.onAttendance("PENDING", "PRESENT", PendingChargeRules.Existing.OPEN)).isEqualTo(PendingChargeRules.Action.VOID);
        assertThat(PendingChargeRules.onAttendance("PRESENT", "PENDING", PendingChargeRules.Existing.OPEN)).isEqualTo(PendingChargeRules.Action.NONE);
        assertThat(PendingChargeRules.onAttendance("PRESENT", "PENDING", PendingChargeRules.Existing.VOIDED)).isEqualTo(PendingChargeRules.Action.REINSTATE);
        assertThat(PendingChargeRules.onAttendance("PENDING", "NO_SHOW", PendingChargeRules.Existing.BILLED)).isEqualTo(PendingChargeRules.Action.NONE);
        assertThat(PendingChargeRules.onAttendance("NO_SHOW", "PRESENT", PendingChargeRules.Existing.OPEN)).isEqualTo(PendingChargeRules.Action.NONE);
        assertThat(PendingChargeRules.onAttendance("NOTIFIED", "PENDING", none)).isEqualTo(PendingChargeRules.Action.NONE);
        assertThat(PendingChargeRules.onCancellation(true, none)).isEqualTo(PendingChargeRules.Action.CHARGE);
        assertThat(PendingChargeRules.onCancellation(false, none)).isEqualTo(PendingChargeRules.Action.NONE);
        assertThat(PendingChargeRules.onCancellation(true, PendingChargeRules.Existing.OPEN)).isEqualTo(PendingChargeRules.Action.NONE);
        assertThat(PendingChargeRules.onCancellation(true, PendingChargeRules.Existing.VOIDED)).isEqualTo(PendingChargeRules.Action.REINSTATE);
    }
}
