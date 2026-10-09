package com.agilityhub.core.payments.domain;

import com.agilityhub.core.payments.domain.InvoicingRules.*;
import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** E11-T06 PIT survivors of {@link InvoicingRules} (S12 R-12-02/04/05, R-12-25; T-12-02, T-12-05). Fictional members. */
class InvoicingRulesSurvivorsTest {
    static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
    static final LocalDate RUN_DAY = LocalDate.of(2026, 8, 25);
    static final Settings CANIC = new Settings("EUR", 1, CashInvoicing.SEMESTER, 6, true, true, true, true,
            Set.of(PaymentMethodType.SEPA_DD, PaymentMethodType.MANUAL, PaymentMethodType.CARD));
    static final Describer TEXT = (origin, plan, month) -> origin + " " + plan + " " + month;

    final Map<String, Plan> plans = Map.of(
            "abonat", new Plan("abonat", PlanType.MONTHLY, false, "Abonat"),
            "single", new Plan("single", PlanType.SINGLE_CLASS, false, "Classe individual"));
    final Map<String, Money> inactivity = new HashMap<>();
    final Map<String, YearMonth> leaving = new HashMap<>();
    final Map<String, List<Charge>> charges = new HashMap<>();
    final Sources sources = new Sources() {
        @Override public Optional<Plan> plan(String planId) { return Optional.ofNullable(plans.get(planId)); }
        @Override public Optional<Price> price(String planId, InvoiceLineOrigin concept, LocalDate day) {
            return "abonat".equals(planId) && concept == InvoiceLineOrigin.MONTHLY_FEE ? Optional.of(new Price("p-abonat", eur(6000), BigDecimal.ZERO)) : Optional.empty();
        }
        @Override public Optional<Money> inactivityFee(String memberId, YearMonth month) { return Optional.ofNullable(inactivity.get(memberId + ":" + month)); }
        @Override public Optional<YearMonth> lastInvoicedMonth(String memberId) { return Optional.ofNullable(leaving.get(memberId)); }
        @Override public List<Charge> charges(String memberId) { return charges.getOrDefault(memberId, List.of()); }
    };

    static Money eur(long cents) { return new Money(cents, "EUR"); }
    static Member sepa(String id, String plan, LocalDate next) { return new Member(id, "ACTIVE", next, PaymentMethodType.SEPA_DD, true, false, plan); }
    static Charge usdCharge(String id) { return new Charge(id, "booking-" + id, "p-single", new Money(1200, "USD"), BigDecimal.ZERO, "Classe 06/10 — Duna"); }
    final Group family = new Group("laura", List.of("laura", "joan-antoni"));
    Month month(YearMonth period, Member... members) { return InvoicingRules.month(List.of(members), List.of(family), period, RUN_DAY, CANIC, sources, TEXT); }

    /** R-12-04, R-12-25: a family member without charges does not make a holder that is not due part of the month. */
    @Test void T_12_02_aHolderNotDueStaysOutWhenItsFamilyHasNoCharges() {
        var result = month(SEPTEMBER, sepa("laura", "abonat", LocalDate.of(2026, 10, 1)), sepa("joan-antoni", "abonat", LocalDate.of(2026, 9, 1)));
        assertThat(result.excluded()).containsEntry("laura", Exclusion.NOT_DUE).containsEntry("joan-antoni", Exclusion.BILLED_VIA_HOLDER);
        assertThat(result.invoices()).isEmpty();
    }

    /** S13 R-13-11 with R-12-04: a leaving family member is still billed on the holder's invoice in its last billed month, not after it. */
    @Test void T_12_02_aLeavingFamilyMemberIsBilledThroughItsLastMonthOnly() {
        inactivity.put("joan-antoni:2026-09", eur(1000));
        leaving.put("joan-antoni", SEPTEMBER);
        var last = month(SEPTEMBER, sepa("laura", "abonat", LocalDate.of(2026, 9, 1)), sepa("joan-antoni", "abonat", LocalDate.of(2026, 9, 1)));
        assertThat(last.invoices()).singleElement().satisfies(draft -> assertThat(draft.lines()).extracting(Line::origin, Line::forMemberId)
                .containsExactly(Tuple.tuple(InvoiceLineOrigin.MONTHLY_FEE, null), Tuple.tuple(InvoiceLineOrigin.INACTIVITY_FEE, "joan-antoni")));
        leaving.put("joan-antoni", YearMonth.of(2026, 8));
        var after = month(SEPTEMBER, sepa("laura", "abonat", LocalDate.of(2026, 9, 1)), sepa("joan-antoni", "abonat", LocalDate.of(2026, 9, 1)));
        assertThat(after.invoices()).singleElement().satisfies(draft -> assertThat(draft.lines()).extracting(Line::origin)
                .containsExactly(InvoiceLineOrigin.MONTHLY_FEE));
    }

    /** R-12-04, R-12-09: a family member's inactivity fee in another currency skips the holder with CURRENCY_MISMATCH. */
    @Test void T_12_05_aFamilyMembersInactivityFeeInAnotherCurrencySkipsTheHolder() {
        inactivity.put("joan-antoni:2026-09", new Money(1000, "USD"));
        var result = month(SEPTEMBER, sepa("laura", "abonat", LocalDate.of(2026, 9, 1)), sepa("joan-antoni", "abonat", LocalDate.of(2026, 9, 1)));
        assertThat(result.invoices()).isEmpty();
        assertThat(result.skipped()).containsExactly(new Incident("laura", BillingIncidentCode.CURRENCY_MISMATCH));
    }

    /** R-12-04, R-12-25: a family member's single-class charge in another currency skips the holder with CURRENCY_MISMATCH. */
    @Test void T_12_05_aFamilyMembersChargeInAnotherCurrencySkipsTheHolder() {
        charges.put("joan-antoni", List.of(usdCharge("c1")));
        var result = month(SEPTEMBER, sepa("laura", "abonat", LocalDate.of(2026, 9, 1)), sepa("joan-antoni", "single", null));
        assertThat(result.invoices()).isEmpty();
        assertThat(result.skipped()).containsExactly(new Incident("laura", BillingIncidentCode.CURRENCY_MISMATCH));
    }

    /** R-12-25, R-12-09: a member's own single-class charge in another currency skips it with CURRENCY_MISMATCH. */
    @Test void T_12_05_aSingleClassChargeInAnotherCurrencySkipsTheMember() {
        charges.put("nuria", List.of(usdCharge("c1")));
        assertThat(InvoicingRules.linesFor(sepa("nuria", "single", null), NOVEMBER, RUN_DAY, CANIC, sources, TEXT))
                .isEqualTo(new Skipped(BillingIncidentCode.CURRENCY_MISMATCH));
    }
}
