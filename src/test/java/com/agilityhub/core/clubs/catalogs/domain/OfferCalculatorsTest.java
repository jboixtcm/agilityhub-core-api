package com.agilityhub.core.clubs.catalogs.domain;

import com.agilityhub.core.clubs.catalogs.application.PriceLineFormatter;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.agilityhub.core.clubs.catalogs.domain.OfferTerms.*;
import static org.assertj.core.api.Assertions.*;

class OfferCalculatorsTest {
    record Period(LocalDate validFrom, LocalDate validTo) implements PriceRules.Period { }
    LocalDate date(String value) { return LocalDate.parse(value); }
    @Test void T_05_02_inclusiveIntervalsAndClubDateStatuses() {
        var old = new Period(date("2026-01-01"), date("2026-12-31"));
        var next = new Period(date("2027-01-01"), null);
        assertThat(PriceRules.overlaps(old, next)).isFalse(); assertThat(PriceRules.overlaps(next, old)).isFalse();
        assertThat(PriceRules.overlaps(old, new Period(date("2026-12-31"), null))).isTrue();
        assertThat(PriceRules.overlaps(next, new Period(date("2027-02-01"), null))).isTrue();
        assertThat(PriceRules.status(old, date("2025-12-31"))).isEqualTo(PriceRules.Status.SCHEDULED);
        assertThat(PriceRules.status(old, date("2026-01-01"))).isEqualTo(PriceRules.Status.CURRENT);
        assertThat(PriceRules.status(old, date("2026-12-31"))).isEqualTo(PriceRules.Status.CURRENT);
        assertThat(PriceRules.status(old, date("2027-01-01"))).isEqualTo(PriceRules.Status.EXPIRED);
        assertThat(PriceRules.status(next, date("2027-01-01"))).isEqualTo(PriceRules.Status.CURRENT);
    }
    @Test void T_05_03_lockedTermsAndInvoicePeriodBoundaries() {
        var today = date("2026-09-15"); var future = new Period(date("2026-10-01"), null);
        assertThat(PriceRules.locked(future, today, false)).isFalse(); assertThat(PriceRules.locked(future, today, true)).isTrue();
        assertThat(PriceRules.locked(new Period(today, null), today, false)).isTrue();
        PriceRules.newStart(date("2026-09-01"), today, null);
        assertThatThrownBy(() -> PriceRules.newStart(date("2026-08-31"), today, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PriceRules.newStart(date("2026-09-30"), today, date("2026-09-30"))).isInstanceOf(ApiException.class);
        PriceRules.newStart(date("2026-10-01"), today, date("2026-09-30"));
        PriceRules.close(null, today, today); PriceRules.close(today, today, today);
        assertThatThrownBy(() -> PriceRules.close(today.minusDays(1), today, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PriceRules.close(date("2026-09-29"), today, date("2026-09-30"))).isInstanceOf(ApiException.class);
    }
    @Test void T_05_04_perDogEntryModesRoundingAndDiscountProposals() {
        var standard = new Money(10000, "EUR");
        assertThat(EntryFeeCalculator.perDog(new EntryFee(EntryFeeMode.STANDARD, null, null), standard, true)).isEqualTo(standard);
        assertThat(EntryFeeCalculator.perDog(new EntryFee(EntryFeeMode.PERCENT, null, 50), standard, true)).isEqualTo(new Money(5000, "EUR"));
        assertThat(EntryFeeCalculator.perDog(new EntryFee(EntryFeeMode.AMOUNT, new Money(2500, "EUR"), null), standard, true)).isEqualTo(new Money(2500, "EUR"));
        assertThat(EntryFeeCalculator.perDog(new EntryFee(EntryFeeMode.NONE, null, null), standard, true)).isEqualTo(new Money(0, "EUR"));
        assertThat(EntryFeeCalculator.perDog(null, null, false)).isNull();
        assertThat(EntryFeeCalculator.perDog(new EntryFee(EntryFeeMode.PERCENT, null, 50), new Money(101, "EUR"), true).amountMinor()).isEqualTo(50);
        assertThat(EntryFeeCalculator.familySuggestion(new Money(6000, "EUR"), 2, 50)).isEqualTo(new Money(9000, "EUR"));
        assertThat(EntryFeeCalculator.familySuggestion(standard, 1, 100)).isEqualTo(standard);
        for (int[] invalid : List.of(new int[]{0, 50}, new int[]{1, -1}, new int[]{2, 101})) {
            assertThatThrownBy(() -> EntryFeeCalculator.familySuggestion(standard, invalid[0], invalid[1])).isInstanceOf(IllegalArgumentException.class);
        }
        var proposal = EntryFeeCalculator.forPlanChange(standard, PlanType.PACK, PlanType.MONTHLY, 10, false, true, 10, 40);
        assertThat(proposal.amount().amountMinor()).isEqualTo(6000); assertThat(proposal.reason()).isEqualTo("PACK_TO_MEMBER");
        assertThat(EntryFeeCalculator.forPlanChange(standard, PlanType.PACK, PlanType.MONTHLY, 10, true, true, 10, 40).reason()).isNull();
        assertThat(EntryFeeCalculator.forPlanChange(standard, PlanType.PACK, PlanType.MONTHLY, 6, false, true, 10, 40).reason()).isNull();
        assertThat(EntryFeeCalculator.forPlanChange(standard, PlanType.MONTHLY, PlanType.MONTHLY, 10, false, true, 10, 40).reason()).isNull();
        assertThat(EntryFeeCalculator.forPlanChange(standard, PlanType.PACK, PlanType.PACK, 10, false, true, 10, 40).reason()).isNull();
        assertThat(EntryFeeCalculator.forPlanChange(standard, PlanType.PACK, PlanType.MONTHLY, 10, false, false, 10, 40).reason()).isNull();
        assertThat(EntryFeeCalculator.forPlanChange(null, PlanType.PACK, PlanType.MONTHLY, 10, false, true, 10, 40).amount()).isNull();
    }
    @Test void T_05_07_localizedPriceLinesForEveryTypeAndNoCurrentPrice() throws Exception {
        var formatter = new PriceLineFormatter(new IcuMessageSource());
        var amount = new Money(6000, "EUR");
        for (var entry : Map.of("ca", List.of("/mes", "/classe", "3 mesos", "segons tarifa"),
                "es", List.of("/mes", "/clase", "3 meses", "según tarifa"),
                "en", List.of("/month", "/class", "3 months", "according to tariff")).entrySet()) {
            var locale = Locale.forLanguageTag(entry.getKey()); var expected = entry.getValue();
            assertThat(formatter.line(PlanType.MONTHLY, amount, null, null, locale)).isEqualTo(amount.format(locale) + expected.get(0));
            assertThat(formatter.line(PlanType.SINGLE_CLASS, amount, null, null, locale)).isEqualTo(amount.format(locale) + expected.get(1));
            assertThat(formatter.line(PlanType.PACK, amount, 3, null, locale)).isEqualTo(amount.format(locale) + " · " + expected.get(2));
            assertThat(formatter.line(PlanType.MONTHLY, null, null, null, locale)).isEqualTo(expected.get(3));
            assertThat(formatter.line(PlanType.MONTHLY, null, null, "Example price label", locale)).isEqualTo("Example price label");
        }
    }
    @Test void T_05_03_priceValidationRejectsInvalidMoneyTaxesAndIntervals() {
        var money = new Money(0, "EUR"); var start = date("2026-09-01");
        PriceRules.validate(money, "EUR", BigDecimal.ZERO, start, null);
        PriceRules.validate(money, "EUR", new BigDecimal("100.00"), start, start);
        for (Money value : Arrays.asList(null, new Money(-1, "EUR"))) {
            assertThatThrownBy(() -> PriceRules.validate(value, "EUR", BigDecimal.ZERO, start, null)).isInstanceOf(ApiException.class);
        }
        for (BigDecimal tax : Arrays.asList(null, new BigDecimal("-1"), new BigDecimal("101"), new BigDecimal("0.001"))) {
            assertThatThrownBy(() -> PriceRules.validate(money, "EUR", tax, start, null)).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> PriceRules.validate(money, "EUR", BigDecimal.ZERO, null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PriceRules.validate(money, "EUR", BigDecimal.ZERO, start, start.minusDays(1))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PriceRules.validate(money, "USD", BigDecimal.ZERO, start, null)).isInstanceOf(ApiException.class);
    }
}
