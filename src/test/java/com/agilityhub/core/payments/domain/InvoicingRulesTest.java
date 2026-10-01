package com.agilityhub.core.payments.domain;

import com.agilityhub.core.clubs.signup.domain.FirstMonthCalculator;
import com.agilityhub.core.payments.domain.InvoicingRules.*;
import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * S12 R-12-01…06 as pure rules (E8-T02): T-12-01 (who enters the month, the lines by plan type), T-12-02 (the family group's
 * single payer), T-12-03 (cash: `SEMESTER` and `MONTHLY`, both product, A29) and T-12-28 (no price, maintenance, zero
 * total) — no Spring, no Mongo. The fictional census of S12 §4's examples: Laura (family holder, «Abonat 2 gossos» 90 €),
 * Joan Antoni (her family member), Eva (inactive), Joan Vila (cash), Pau Riera (no price).
 */
class InvoicingRulesTest {
    static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    static final LocalDate RUN_DAY = LocalDate.of(2026, 8, 25);
    static final Settings CANIC = new Settings("EUR", 1, CashInvoicing.SEMESTER, 6, true, true, true, true,
            Set.of(PaymentMethodType.SEPA_DD, PaymentMethodType.MANUAL, PaymentMethodType.CARD));

    final Map<String, Plan> plans = new HashMap<>(Map.of(
            "abonat", new Plan("abonat", PlanType.MONTHLY, false, "Abonat"),
            "abonat2", new Plan("abonat2", PlanType.MONTHLY, false, "Abonat 2 gossos"),
            "pack10", new Plan("pack10", PlanType.PACK, false, "Pack 10"),
            "single", new Plan("single", PlanType.SINGLE_CLASS, false, "Classe individual"),
            "terapia", new Plan("terapia", PlanType.MONTHLY, true, "Teràpia"),
            "unpriced", new Plan("unpriced", PlanType.MONTHLY, false, "Sense tarifa")));
    final Map<String, Price> prices = new HashMap<>(Map.of(
            "abonat:MONTHLY_FEE", price("p-abonat", 6000), "abonat2:MONTHLY_FEE", price("p-abonat2", 9000),
            "terapia:MAINTENANCE_FEE", price("p-terapia", 3000)));
    final Map<String, Money> inactivity = new HashMap<>();
    final Map<String, YearMonth> leaving = new HashMap<>();
    final Map<String, List<Charge>> charges = new HashMap<>();
    final Sources sources = new Sources() {
        @Override public Optional<Plan> plan(String planId) { return Optional.ofNullable(plans.get(planId)); }
        @Override public Optional<Price> price(String planId, InvoiceLineOrigin concept, LocalDate day) { return Optional.ofNullable(prices.get(planId + ":" + concept)); }
        @Override public Optional<Money> inactivityFee(String memberId, YearMonth month) { return Optional.ofNullable(inactivity.get(memberId + ":" + month)); }
        @Override public Optional<YearMonth> lastInvoicedMonth(String memberId) { return Optional.ofNullable(leaving.get(memberId)); }
        @Override public List<Charge> charges(String memberId) { return charges.getOrDefault(memberId, List.of()); }
    };
    static final Describer TEXT = (origin, plan, month) -> origin + " " + plan + " " + month;

    static Price price(String id, long cents) { return new Price(id, eur(cents), BigDecimal.ZERO); }
    static Money eur(long cents) { return new Money(cents, "EUR"); }
    static Member sepa(String id, String plan, LocalDate next) { return new Member(id, "ACTIVE", next, PaymentMethodType.SEPA_DD, true, false, plan); }
    static Member cash(String id, String plan, LocalDate next) { return new Member(id, "ACTIVE", next, PaymentMethodType.MANUAL, false, false, plan); }
    Outcome lines(Member member, YearMonth period) { return InvoicingRules.linesFor(member, period, RUN_DAY, CANIC, sources, TEXT); }
    static Billed billed(Outcome outcome) { assertThat(outcome).isInstanceOf(Billed.class); return (Billed) outcome; }

    @Test void T_12_01_aMonthlyMemberGetsOneLineAtThePriceCurrentOnTheIssueDateAndTheDateAdvances() {
        var laura = billed(lines(sepa("laura", "abonat2", LocalDate.of(2026, 9, 1)), SEPTEMBER));
        assertThat(laura.lines()).singleElement().satisfies(line -> {
            assertThat(line.origin()).isEqualTo(InvoiceLineOrigin.MONTHLY_FEE);
            assertThat(line.amounts().total()).isEqualTo(eur(9000));
            assertThat(line.priceId()).isEqualTo("p-abonat2");
            assertThat(line.description()).isEqualTo("MONTHLY_FEE Abonat 2 gossos 2026-09");
        });
        assertThat(laura.nextInvoiceDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test void T_12_01_anInactiveMonthIsBilledAsTheInactivityFeeInsteadOfTheMonthlyFee() {
        inactivity.put("eva:2026-09", eur(1000)); // the second month of her period (R-13-08: followingMonths)
        var eva = billed(lines(sepa("eva", "abonat", LocalDate.of(2026, 9, 1)), SEPTEMBER));
        assertThat(eva.lines()).singleElement().satisfies(line -> {
            assertThat(line.origin()).isEqualTo(InvoiceLineOrigin.INACTIVITY_FEE);
            assertThat(line.amounts().total()).isEqualTo(eur(1000));
            assertThat(line.priceId()).isNull();
        });
        // INACTIVITY off (R-12-28): never an INACTIVITY_FEE.
        var off = new Settings("EUR", 1, CashInvoicing.SEMESTER, 6, true, true, false, true, CANIC.enabledMethods());
        assertThat(billed(InvoicingRules.linesFor(sepa("eva", "abonat", LocalDate.of(2026, 9, 1)), SEPTEMBER, RUN_DAY, off, sources, TEXT)).lines())
                .singleElement().satisfies(line -> assertThat(line.origin()).isEqualTo(InvoiceLineOrigin.MONTHLY_FEE));
    }

    @Test void T_12_01_aPackHasNoPeriodicLineAndSingleClassesAreOneLineEach() {
        assertThat(billed(lines(sepa("marc", "pack10", LocalDate.of(2026, 9, 1)), SEPTEMBER)).lines()).isEmpty();
        charges.put("nuria", List.of(charge("c1", "06/10"), charge("c2", "13/10"), charge("c3", "20/10")));
        var nuria = billed(lines(sepa("nuria", "single", LocalDate.of(2026, 11, 1)), YearMonth.of(2026, 11)));
        assertThat(nuria.lines()).hasSize(3).allSatisfy(line -> {
            assertThat(line.origin()).isEqualTo(InvoiceLineOrigin.SINGLE_CLASS);
            assertThat(line.amounts().total()).isEqualTo(eur(1200));
        });
        assertThat(nuria.lines()).extracting(Line::description).containsExactly("Classe 06/10 — Duna", "Classe 13/10 — Duna", "Classe 20/10 — Duna");
        assertThat(nuria.lines()).extracting(Line::chargeId).containsExactly("c1", "c2", "c3");
        // SINGLE_CLASS off (R-12-28): the charges wait, nothing is billed.
        var off = new Settings("EUR", 1, CashInvoicing.SEMESTER, 6, true, true, true, false, CANIC.enabledMethods());
        assertThat(billed(InvoicingRules.linesFor(sepa("nuria", "single", LocalDate.of(2026, 11, 1)), YearMonth.of(2026, 11), RUN_DAY, off, sources, TEXT)).lines()).isEmpty();
    }
    static Charge charge(String id, String day) { return new Charge(id, "booking-" + id, "p-single", eur(1200), BigDecimal.ZERO, "Classe " + day + " — Duna"); }

    /** R-12-25 (ruling E87): a single-class member usually has no `nextInvoiceDate`; its charges are billed all the same. */
    @Test void T_12_01_R_12_25_unbilledChargesAreDueWithoutANextInvoiceDateAndTheRunGivesNoDate() {
        charges.put("nuria", List.of(charge("c1", "06/10"), charge("c2", "13/10")));
        var nuria = billed(lines(sepa("nuria", "single", null), YearMonth.of(2026, 11)));
        assertThat(nuria.lines()).extracting(Line::origin, Line::chargeId).containsExactly(org.assertj.core.groups.Tuple.tuple(InvoiceLineOrigin.SINGLE_CLASS, "c1"),
                org.assertj.core.groups.Tuple.tuple(InvoiceLineOrigin.SINGLE_CLASS, "c2"));
        assertThat(nuria.nextInvoiceDate()).isNull();
        // The month: her invoice, and no date written (a plan without a periodic fee gets none from the run).
        var month = InvoicingRules.month(List.of(sepa("nuria", "single", null)), List.of(), YearMonth.of(2026, 11), RUN_DAY, CANIC, sources, TEXT);
        assertThat(month.invoices()).singleElement().satisfies(draft -> {
            assertThat(draft.payerId()).isEqualTo("nuria");
            assertThat(draft.lines()).hasSize(2);
            assertThat(draft.advances()).isEmpty();
        });
        assertThat(month.advances()).isEmpty();
        // Without charges, or with SINGLE_CLASS off, a member without a date is not due.
        assertThat(lines(sepa("marc", "single", null), YearMonth.of(2026, 11))).isEqualTo(new Excluded(Exclusion.NOT_DUE));
        var off = new Settings("EUR", 1, CashInvoicing.SEMESTER, 6, true, true, true, false, CANIC.enabledMethods());
        assertThat(InvoicingRules.linesFor(sepa("nuria", "single", null), YearMonth.of(2026, 11), RUN_DAY, off, sources, TEXT)).isEqualTo(new Excluded(Exclusion.NOT_DUE));
        // A cash member billed by half-years (next date 01-01) with a class in October: the class now, the half-year's date kept.
        charges.put("vila", List.of(charge("c3", "20/10")));
        var vila = billed(lines(cash("vila", "abonat", LocalDate.of(2027, 1, 1)), YearMonth.of(2026, 11)));
        assertThat(vila.lines()).extracting(Line::origin).containsExactly(InvoiceLineOrigin.SINGLE_CLASS);
        assertThat(vila.nextInvoiceDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        var cashMonth = InvoicingRules.month(List.of(cash("vila", "abonat", LocalDate.of(2027, 1, 1))), List.of(), YearMonth.of(2026, 11), RUN_DAY, CANIC, sources, TEXT);
        assertThat(cashMonth.invoices()).singleElement().satisfies(draft -> assertThat(draft.advances()).isEmpty());
        // R-12-04: a family member's charges go on the holder's invoice even when neither has a date due; nobody's date moves.
        var laura = sepa("laura", "abonat2", LocalDate.of(2026, 12, 1));
        var group = new Group("laura", List.of("laura", "nuria"));
        var family = InvoicingRules.month(List.of(laura, sepa("nuria", "single", null)), List.of(group), YearMonth.of(2026, 11), RUN_DAY, CANIC, sources, TEXT);
        assertThat(family.invoices()).singleElement().satisfies(draft -> {
            assertThat(draft.payerId()).isEqualTo("laura");
            assertThat(draft.lines()).extracting(Line::origin, Line::forMemberId).containsOnly(org.assertj.core.groups.Tuple.tuple(InvoiceLineOrigin.SINGLE_CLASS, "nuria"));
            assertThat(draft.advances()).isEmpty();
        });
        // A due holder takes a dateless family member's charges, and only its own date moves.
        var due = InvoicingRules.month(List.of(sepa("laura", "abonat2", LocalDate.of(2026, 11, 1)), sepa("nuria", "single", null)), List.of(group),
                YearMonth.of(2026, 11), RUN_DAY, CANIC, sources, TEXT);
        assertThat(due.invoices().getFirst().lines()).extracting(Line::origin).containsExactly(InvoiceLineOrigin.MONTHLY_FEE, InvoiceLineOrigin.SINGLE_CLASS,
                InvoiceLineOrigin.SINGLE_CLASS);
        assertThat(due.advances()).containsExactly(new Advance("laura", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1)));
    }

    @Test void T_12_01_pendingLeftNotDueWithoutAMethodOrAfterTheLeaveAreOutOfTheMonth() {
        assertThat(lines(new Member("nuria", "PENDING", LocalDate.of(2026, 9, 1), PaymentMethodType.SEPA_DD, true, false, "abonat"), SEPTEMBER))
                .isEqualTo(new Excluded(Exclusion.NOT_ACTIVE));
        assertThat(lines(new Member("pere", "LEFT", LocalDate.of(2026, 9, 1), PaymentMethodType.SEPA_DD, true, false, "abonat"), SEPTEMBER))
                .isEqualTo(new Excluded(Exclusion.NOT_ACTIVE));
        assertThat(lines(sepa("laura", "abonat", LocalDate.of(2026, 10, 1)), SEPTEMBER)).isEqualTo(new Excluded(Exclusion.NOT_DUE));
        assertThat(lines(sepa("laura", "abonat", null), SEPTEMBER)).isEqualTo(new Excluded(Exclusion.NOT_DUE));
        assertThat(lines(new Member("laura", "ACTIVE", LocalDate.of(2026, 9, 1), null, false, false, "abonat"), SEPTEMBER))
                .isEqualTo(new Excluded(Exclusion.NO_PAYMENT_METHOD));
        // S13 R-13-11: leave on 10-11 with fullMonthIfLater → November is the last billed month; December is out.
        leaving.put("pere", YearMonth.of(2026, 11));
        assertThat(billed(lines(sepa("pere", "abonat", LocalDate.of(2026, 11, 1)), YearMonth.of(2026, 11))).lines()).hasSize(1);
        assertThat(lines(sepa("pere", "abonat", LocalDate.of(2026, 12, 1)), YearMonth.of(2026, 12))).isEqualTo(new Excluded(Exclusion.AFTER_LEAVE));
    }

    @Test void T_12_01_R_12_28_theMethodIncidentsSkipOnlyAMemberWithSomethingToBill() {
        assertThat(lines(new Member("joan", "ACTIVE", LocalDate.of(2026, 9, 1), PaymentMethodType.SEPA_DD, false, false, "abonat"), SEPTEMBER))
                .isEqualTo(new Skipped(BillingIncidentCode.NO_BANK_ACCOUNT));
        assertThat(lines(new Member("anna", "ACTIVE", LocalDate.of(2026, 9, 1), PaymentMethodType.CARD, false, true, "abonat"), SEPTEMBER))
                .isEqualTo(new Skipped(BillingIncidentCode.CARD_INVALID));
        var sepaOnly = new Settings("EUR", 1, CashInvoicing.SEMESTER, 6, true, true, true, true, Set.of(PaymentMethodType.SEPA_DD));
        assertThat(InvoicingRules.linesFor(cash("vila", "abonat", LocalDate.of(2026, 9, 1)), SEPTEMBER, RUN_DAY, sepaOnly, sources, TEXT))
                .isEqualTo(new Skipped(BillingIncidentCode.PROVIDER_DISABLED));
        // The member's own data comes first: an invalid card stays CARD_INVALID while STRIPE is off, a valid one is PROVIDER_DISABLED.
        assertThat(InvoicingRules.linesFor(new Member("anna", "ACTIVE", LocalDate.of(2026, 9, 1), PaymentMethodType.CARD, false, true, "abonat"), SEPTEMBER,
                RUN_DAY, sepaOnly, sources, TEXT)).isEqualTo(new Skipped(BillingIncidentCode.CARD_INVALID));
        assertThat(InvoicingRules.linesFor(new Member("sala", "ACTIVE", LocalDate.of(2026, 9, 1), PaymentMethodType.CARD, false, false, "abonat"), SEPTEMBER,
                RUN_DAY, sepaOnly, sources, TEXT)).isEqualTo(new Skipped(BillingIncidentCode.PROVIDER_DISABLED));
        assertThat(lines(sepa("mia", null, LocalDate.of(2026, 9, 1)), SEPTEMBER)).isEqualTo(new Skipped(BillingIncidentCode.NO_PLAN));
        assertThat(lines(sepa("mia", "gone", LocalDate.of(2026, 9, 1)), SEPTEMBER)).isEqualTo(new Skipped(BillingIncidentCode.NO_PLAN));
        prices.put("abonat:MONTHLY_FEE", new Price("p-usd", new Money(6000, "USD"), BigDecimal.ZERO));
        assertThat(lines(sepa("laura", "abonat", LocalDate.of(2026, 9, 1)), SEPTEMBER)).isEqualTo(new Skipped(BillingIncidentCode.CURRENCY_MISMATCH));
        // A pack member without an account has nothing to bill: no incident, the date still advances (R-12-02).
        assertThat(billed(lines(new Member("marc", "ACTIVE", LocalDate.of(2026, 9, 1), PaymentMethodType.SEPA_DD, false, false, "pack10"), SEPTEMBER))
                .nextInvoiceDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    /**
     * E8-T07 step 3 (R-12-07, R-12-19): a waiting `includeInNextRun` receipt rides the run only while its member can still be
     * debited for it under the mandate it froze; the member's own data first, then the club's provider.
     */
    @Test void R_12_07_R_12_19_aWaitingReceiptIsAnIncidentWhenItsMemberCanNoLongerBeDebitedForIt() {
        var eva = new Member("puig", "ACTIVE", LocalDate.of(2026, 9, 1), PaymentMethodType.SEPA_DD, true, false, "abonat");
        assertThat(InvoicingRules.waitingReceiptIncident("m-1", eva, "m-1", CANIC)).isNull();
        assertThat(InvoicingRules.waitingReceiptIncident("m-1", null, null, CANIC)).isEqualTo(BillingIncidentCode.NO_BANK_ACCOUNT);
        assertThat(InvoicingRules.waitingReceiptIncident("m-1", new Member("puig", "LEFT", null, PaymentMethodType.SEPA_DD, true, false, "abonat"), "m-1", CANIC))
                .isEqualTo(BillingIncidentCode.NO_BANK_ACCOUNT);
        assertThat(InvoicingRules.waitingReceiptIncident("m-1", new Member("puig", "ACTIVE", null, PaymentMethodType.MANUAL, false, false, "abonat"), null, CANIC))
                .isEqualTo(BillingIncidentCode.NO_BANK_ACCOUNT);
        assertThat(InvoicingRules.waitingReceiptIncident("m-1", new Member("puig", "ACTIVE", null, PaymentMethodType.SEPA_DD, false, false, "abonat"), "m-1", CANIC))
                .isEqualTo(BillingIncidentCode.NO_BANK_ACCOUNT);
        assertThat(InvoicingRules.waitingReceiptIncident("m-1", eva, "m-2", CANIC)).isEqualTo(BillingIncidentCode.NO_BANK_ACCOUNT);
        var cashOnly = new Settings("EUR", 1, CashInvoicing.SEMESTER, 6, true, true, true, true, Set.of(PaymentMethodType.MANUAL));
        assertThat(InvoicingRules.waitingReceiptIncident("m-1", eva, "m-1", cashOnly)).isEqualTo(BillingIncidentCode.PROVIDER_DISABLED);
        assertThat(InvoicingRules.waitingReceiptIncident("m-1", eva, "m-2", cashOnly)).isEqualTo(BillingIncidentCode.NO_BANK_ACCOUNT);
    }

    @Test void T_12_28_noCurrentPriceSkipsTheMemberMaintenanceBillsItsFeeAndAZeroTotalIssuesNothingButAdvances() {
        assertThat(lines(sepa("pau", "unpriced", LocalDate.of(2026, 9, 1)), SEPTEMBER)).isEqualTo(new Skipped(BillingIncidentCode.NO_PRICE));
        var terapia = billed(lines(sepa("teresa", "terapia", LocalDate.of(2026, 9, 1)), SEPTEMBER));
        assertThat(terapia.lines()).singleElement().satisfies(line -> {
            assertThat(line.origin()).isEqualTo(InvoiceLineOrigin.MAINTENANCE_FEE);
            assertThat(line.amounts().total()).isEqualTo(eur(3000));
            assertThat(line.priceId()).isEqualTo("p-terapia");
        });
        prices.put("abonat:MONTHLY_FEE", price("p-free", 0));
        var month = InvoicingRules.month(List.of(sepa("gratis", "abonat", LocalDate.of(2026, 9, 1))), List.of(), SEPTEMBER, RUN_DAY, CANIC, sources, TEXT);
        assertThat(month.invoices()).isEmpty();
        assertThat(month.withoutInvoice()).containsExactly(new Advance("gratis", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)));
    }

    @Test void T_12_02_theFamilyHolderPaysTheMembersGetNoInvoiceAndModuleOffBillsEveryoneOnTheirOwn() {
        var laura = sepa("laura", "abonat2", LocalDate.of(2026, 9, 1));
        var joanAntoni = sepa("joan-antoni", "abonat", LocalDate.of(2026, 9, 1));
        var group = new Group("laura", List.of("laura", "joan-antoni"));
        var month = InvoicingRules.month(List.of(joanAntoni, laura), List.of(group), SEPTEMBER, RUN_DAY, CANIC, sources, TEXT);
        assertThat(month.invoices()).singleElement().satisfies(draft -> {
            assertThat(draft.payerId()).isEqualTo("laura");
            assertThat(draft.lines()).extracting(line -> line.amounts().total()).containsExactly(eur(9000));
            assertThat(draft.advances()).extracting(Advance::memberId).containsExactly("laura", "joan-antoni");
        });
        assertThat(month.excluded()).containsEntry("joan-antoni", Exclusion.BILLED_VIA_HOLDER);
        // S13 R-13-08 / R-12-04: a member's inactivity fee is a separate line on the holder's invoice.
        inactivity.put("joan-antoni:2026-09", eur(1000));
        var inactive = InvoicingRules.month(List.of(laura, joanAntoni), List.of(group), SEPTEMBER, RUN_DAY, CANIC, sources, TEXT);
        assertThat(inactive.invoices().getFirst().lines()).extracting(Line::origin, Line::forMemberId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(InvoiceLineOrigin.MONTHLY_FEE, null),
                        org.assertj.core.groups.Tuple.tuple(InvoiceLineOrigin.INACTIVITY_FEE, "joan-antoni"));
        inactivity.clear();
        var alone = new Settings("EUR", 1, CashInvoicing.SEMESTER, 6, true, false, true, true, CANIC.enabledMethods());
        var own = InvoicingRules.month(List.of(laura, joanAntoni), List.of(group), SEPTEMBER, RUN_DAY, alone, sources, TEXT);
        assertThat(own.invoices()).extracting(Draft::payerId).containsExactly("laura", "joan-antoni");
        assertThat(own.invoices()).extracting(draft -> draft.lines().getFirst().amounts().total()).containsExactly(eur(9000), eur(6000));
        // A holder skipped with an incident takes the group with it: nobody's date moves.
        var skipped = InvoicingRules.month(List.of(new Member("laura", "ACTIVE", LocalDate.of(2026, 9, 1), PaymentMethodType.SEPA_DD, false, false, "abonat2"),
                joanAntoni), List.of(group), SEPTEMBER, RUN_DAY, CANIC, sources, TEXT);
        assertThat(skipped.invoices()).isEmpty();
        assertThat(skipped.advances()).isEmpty();
        assertThat(skipped.skipped()).containsExactly(new Incident("laura", BillingIncidentCode.NO_BANK_ACCOUNT));
    }

    @Test void T_12_03_cashBySemesterBillsTheRestOfTheNaturalHalfYearFromTheFirstUnpaidMonth() {
        // Signup on 17-08 (split day 16, invoice day 1): option A starts today, August is paid at signup → nextInvoiceDate 01-09.
        var options = FirstMonthCalculator.options(LocalDate.of(2026, 8, 17), 16, 1, eur(6000));
        LocalDate optionA = options.get(0).nextInvoiceDate(), optionB = options.get(1).nextInvoiceDate();
        assertThat(optionA).isEqualTo(LocalDate.of(2026, 9, 1));
        var vila = billed(lines(cash("vila", "abonat", optionA), SEPTEMBER));
        assertThat(vila.lines()).extracting(Line::month).containsExactly(YearMonth.of(2026, 9), YearMonth.of(2026, 10), YearMonth.of(2026, 11), YearMonth.of(2026, 12));
        assertThat(vila.total("EUR")).isEqualTo(eur(24000));
        assertThat(vila.nextInvoiceDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        // Option B starts on 1 September (paid at signup): October to December, 3 lines.
        assertThat(optionB).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(lines(cash("vila", "abonat", optionB), SEPTEMBER)).isEqualTo(new Excluded(Exclusion.NOT_DUE));
        assertThat(billed(lines(cash("vila", "abonat", optionB), YearMonth.of(2026, 10))).lines()).hasSize(3);
        // The January run: a full half-year, 6 lines, next date 01-07.
        var january = billed(lines(cash("vila", "abonat", LocalDate.of(2027, 1, 1)), YearMonth.of(2027, 1)));
        assertThat(january.lines()).hasSize(6);
        assertThat(january.nextInvoiceDate()).isEqualTo(LocalDate.of(2027, 7, 1));
        // A signup in October (August's rules, its first month paid) → November and December.
        assertThat(billed(lines(cash("rosa", "abonat", LocalDate.of(2026, 11, 1)), YearMonth.of(2026, 11))).lines())
                .extracting(Line::month).containsExactly(YearMonth.of(2026, 11), YearMonth.of(2026, 12));
        // A leaving member's half-year stops at the last billed month (S13 R-13-11): no automatic refund afterwards.
        leaving.put("vila", YearMonth.of(2026, 10));
        assertThat(billed(lines(cash("vila", "abonat", optionA), SEPTEMBER)).lines()).hasSize(2);
    }

    @Test void T_12_03_cashMonthlyIsOneLineAMonthAndSepaIsNeverBilledBySemester() {
        var monthly = new Settings("EUR", 1, CashInvoicing.MONTHLY, 6, true, true, true, true, CANIC.enabledMethods());
        var vila = billed(InvoicingRules.linesFor(cash("vila", "abonat", LocalDate.of(2026, 9, 1)), SEPTEMBER, RUN_DAY, monthly, sources, TEXT));
        assertThat(vila.lines()).singleElement().satisfies(line -> assertThat(line.month()).isEqualTo(SEPTEMBER));
        assertThat(vila.nextInvoiceDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(billed(lines(sepa("laura", "abonat", LocalDate.of(2026, 9, 1)), SEPTEMBER)).lines()).hasSize(1);
        // billing.cashPeriodMonths = 3 → natural quarters.
        var quarters = new Settings("EUR", 1, CashInvoicing.SEMESTER, 3, true, true, true, true, CANIC.enabledMethods());
        assertThat(billed(InvoicingRules.linesFor(cash("vila", "abonat", LocalDate.of(2026, 8, 1)), YearMonth.of(2026, 8), RUN_DAY, quarters, sources, TEXT)).lines())
                .extracting(Line::month).containsExactly(YearMonth.of(2026, 8), YearMonth.of(2026, 9));
    }

    @Test void R_12_06_theNextInvoiceDayIsClampedToTheMonthAndTheNaturalPeriodsNeverCrossTheYear() {
        assertThat(InvoicingRules.invoiceDay(YearMonth.of(2027, 2), 31)).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(InvoicingRules.periodStart(YearMonth.of(2026, 9), 6)).isEqualTo(YearMonth.of(2026, 7));
        assertThat(InvoicingRules.periodEnd(YearMonth.of(2026, 11), 5)).isEqualTo(YearMonth.of(2026, 12));
        assertThat(InvoicingRules.cashMonths(YearMonth.of(2026, 9), LocalDate.of(2026, 3, 1), 6)).first().isEqualTo(YearMonth.of(2026, 7));
    }
}
