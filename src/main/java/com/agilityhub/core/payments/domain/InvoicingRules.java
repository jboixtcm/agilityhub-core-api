package com.agilityhub.core.payments.domain;

import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * S12 R-12-01…06 as pure rules (E8-T02): who enters the month `M`, the lines of each member by `Plan.type`, the family
 * group's single payer, the cash half-years and the next invoice date. No Spring, no Mongo: what the rules read beyond the
 * member comes through {@link Sources}, and the frozen line texts through {@link Describer} (R-12-30).
 *
 * <ul>
 * <li>R-12-01: `ACTIVE` (inside an inactivity period too), `nextInvoiceDate ≤` the last day of `M`, a payment method, and
 * `M ≤ lastInvoicedMonth` when the member is leaving; anything else is out of the month.</li>
 * <li>R-12-02/03: `MONTHLY` → one `MONTHLY_FEE` at the price current on the issue date, replaced by `INACTIVITY_FEE` when the
 * member owes one for the month, or `MAINTENANCE_FEE` when the plan's billing mode is `MAINTENANCE`; without a current price
 * the member is skipped (`NO_PRICE`), never invented. `PACK` → no periodic line. Every unbilled `PendingCharge` → one
 * `SINGLE_CLASS` line (they only exist for single classes). Total 0 or no line → no invoice, the date still advances.</li>
 * <li>R-12-04: with `FAMILY_GROUP` the holder pays: a non-holder gets no invoice; its inactivity fee and single classes are
 * separate lines on the holder's invoice, and its date advances with the holder's.</li>
 * <li>R-12-05: a `MANUAL` member of a `MONTHLY` plan with `billing.cashInvoicing = SEMESTER` gets one invoice with a line per
 * month from `month(nextInvoiceDate)` (never before the start of `M`'s natural period) to the end of `M`'s natural period of
 * `billing.cashPeriodMonths` (January–June, July–December), capped at the last month a leaving member is billed; its date
 * goes to the first month of the next period. `MONTHLY` cash is one line a month, like any other method.</li>
 * <li>R-12-06: the date advances to `billing.nextInvoiceDayOfMonth` of `M + 1` (clamped to the month's length).</li>
 * <li>R-12-07, R-12-28: a member whose provider is not enabled (`PROVIDER_DISABLED`), a `SEPA_DD` without an account
 * (`NO_BANK_ACCOUNT`), an invalid card (`CARD_INVALID`), a price in another currency (`CURRENCY_MISMATCH`) is skipped with
 * that incident — only when there is something to bill.</li>
 * </ul>
 */
public final class InvoicingRules {
    private InvoicingRules() { }

    public enum PlanType { MONTHLY, PACK, SINGLE_CLASS }
    public enum CashInvoicing { MONTHLY, SEMESTER }
    /** Why a member is not part of the month (R-12-01, R-12-04). */
    public enum Exclusion { NOT_ACTIVE, NOT_DUE, NO_PAYMENT_METHOD, AFTER_LEAVE, BILLED_VIA_HOLDER }

    /** The club's parameters and modules the rules read. `enabledMethods` = the payment methods whose provider is enabled. */
    public record Settings(String currency, int nextInvoiceDayOfMonth, CashInvoicing cashInvoicing, int cashPeriodMonths, boolean taxIncluded,
            boolean familyGroups, boolean inactivity, boolean singleClass, Set<PaymentMethodType> enabledMethods) {
        public Settings { enabledMethods = Set.copyOf(enabledMethods); }
    }
    /** `method` null = no payment method; `bankAccount` = an IBAN (clear or encrypted); `cardInvalid` = R-12-22. */
    public record Member(String id, String status, LocalDate nextInvoiceDate, PaymentMethodType method, boolean bankAccount, boolean cardInvalid,
            String planId) { }
    public record Plan(String id, PlanType type, boolean maintenance, String name) { }
    public record Price(String id, Money amount, BigDecimal taxPercent) { }
    /** An unbilled, unvoided `PendingCharge`: its frozen description (R-12-25) and the tax of its price. */
    public record Charge(String id, String bookingId, String priceId, Money amount, BigDecimal taxPercent, String description) { }
    public record Group(String holderMemberId, List<String> memberIds) {
        public Group { memberIds = List.copyOf(memberIds); }
    }

    public interface Sources {
        Optional<Plan> plan(String planId);
        /** `PriceResolver.current(planId, concept, day)` for `MONTHLY_FEE` or `MAINTENANCE_FEE`. */
        Optional<Price> price(String planId, InvoiceLineOrigin concept, LocalDate day);
        /** S13 R-13-08 (`InactivityFeePort`): the fee the member owes for the month, if any. */
        Optional<Money> inactivityFee(String memberId, YearMonth month);
        /** S13 R-13-11 (`LeaveBillingPort`): the last month a leaving member is billed; empty = not leaving. */
        Optional<YearMonth> lastInvoicedMonth(String memberId);
        List<Charge> charges(String memberId);
    }
    /** The frozen text of a periodic line, in the club's `defaultLocale` (R-12-30). */
    public interface Describer { String line(InvoiceLineOrigin origin, String planName, YearMonth month); }

    /** `forMemberId` = the family member a line of the holder's invoice is about (null = the payer). */
    public record Line(InvoiceLineOrigin origin, YearMonth month, String priceId, String bookingId, String chargeId, String description,
            InvoiceAmounts.Amounts amounts, String forMemberId) { }

    public sealed interface Outcome permits Excluded, Skipped, Billed { }
    public record Excluded(Exclusion reason) implements Outcome { }
    public record Skipped(BillingIncidentCode code) implements Outcome { }
    /** The member's lines (maybe none: no invoice) and the date `nextInvoiceDate` moves to. */
    public record Billed(List<Line> lines, LocalDate nextInvoiceDate) implements Outcome {
        public Billed { lines = List.copyOf(lines); }
        public Money total(String currency) { return InvoiceAmounts.sum(lines.stream().map(line -> line.amounts().total()).toList(), currency); }
    }

    /** One member's month (R-12-01…05), the method incidents included: T-12-01, T-12-03, T-12-28. */
    public static Outcome linesFor(Member member, YearMonth period, LocalDate issueDate, Settings settings, Sources sources, Describer describer) {
        var own = own(member, period, issueDate, settings, sources, describer);
        if (!(own instanceof Billed billed)) { return own; }
        var incident = methodIncident(member, billed.lines(), settings);
        return incident == null ? billed : new Skipped(incident);
    }

    /** An invoice to issue: the payer, its lines and the members whose date moves with it (the payer first). */
    public record Draft(String payerId, List<Line> lines, List<Advance> advances) {
        public Draft { lines = List.copyOf(lines); advances = List.copyOf(advances); }
    }
    public record Advance(String memberId, LocalDate from, LocalDate to) { }
    public record Incident(String memberId, BillingIncidentCode code) { }
    /**
     * The month: the invoices in the members' order, the advances of the included members without an invoice (total 0 or no
     * line), the skipped members with their incident and every member that is out of it with the reason.
     */
    public record Month(List<Draft> invoices, List<Advance> withoutInvoice, List<Incident> skipped, Map<String, Exclusion> excluded) {
        public List<Advance> advances() {
            var all = new ArrayList<Advance>();
            invoices.forEach(draft -> all.addAll(draft.advances())); all.addAll(withoutInvoice);
            return all;
        }
    }

    /** The whole month of the club (R-12-01…06), members in their numbering order: T-12-02 and the run (R-12-11). */
    public static Month month(List<Member> members, List<Group> groups, YearMonth period, LocalDate issueDate, Settings settings, Sources sources,
            Describer describer) {
        var byId = new LinkedHashMap<String, Member>();
        members.forEach(member -> byId.put(member.id(), member));
        var covered = new LinkedHashMap<String, List<Member>>();
        var payerOf = new HashMap<String, String>();
        if (settings.familyGroups()) {
            for (var group : groups) {
                if (!byId.containsKey(group.holderMemberId())) { continue; }
                for (String memberId : group.memberIds()) {
                    if (memberId.equals(group.holderMemberId()) || !byId.containsKey(memberId) || payerOf.containsKey(memberId)) { continue; }
                    payerOf.put(memberId, group.holderMemberId());
                    covered.computeIfAbsent(group.holderMemberId(), holder -> new ArrayList<>()).add(byId.get(memberId));
                }
            }
        }
        var invoices = new ArrayList<Draft>(); var without = new ArrayList<Advance>(); var skipped = new ArrayList<Incident>();
        var excluded = new LinkedHashMap<String, Exclusion>();
        for (var member : members) {
            if (payerOf.containsKey(member.id())) { excluded.put(member.id(), Exclusion.BILLED_VIA_HOLDER); continue; }
            var own = own(member, period, issueDate, settings, sources, describer);
            if (own instanceof Excluded out) { excluded.put(member.id(), out.reason()); continue; }
            if (own instanceof Skipped skip) { skipped.add(new Incident(member.id(), skip.code())); continue; }
            var billed = (Billed) own;
            var lines = new ArrayList<>(billed.lines());
            var advances = new ArrayList<Advance>();
            advances.add(new Advance(member.id(), member.nextInvoiceDate(), billed.nextInvoiceDate()));
            BillingIncidentCode familyIncident = null;
            for (var other : covered.getOrDefault(member.id(), List.of())) {
                var share = familyShare(other, period, settings, sources, describer);
                if (share == null) { continue; }
                if (share instanceof Skipped skip) { familyIncident = skip.code(); break; }
                lines.addAll(((Billed) share).lines());
                advances.add(new Advance(other.id(), other.nextInvoiceDate(), billed.nextInvoiceDate()));
            }
            var incident = familyIncident != null ? familyIncident : methodIncident(member, lines, settings);
            if (incident != null) { skipped.add(new Incident(member.id(), incident)); continue; }
            boolean issue = !lines.isEmpty() && InvoiceAmounts.sum(lines.stream().map(line -> line.amounts().total()).toList(), settings.currency()).amountMinor() > 0;
            if (issue) { invoices.add(new Draft(member.id(), lines, advances)); } else { without.addAll(advances); }
        }
        return new Month(invoices, without, skipped, excluded);
    }

    /** The months a cash `SEMESTER` invoice covers (R-12-05): T-12-03. */
    public static List<YearMonth> cashMonths(YearMonth period, LocalDate nextInvoiceDate, int periodMonths) {
        var start = periodStart(period, periodMonths);
        var first = YearMonth.from(nextInvoiceDate).isAfter(start) ? YearMonth.from(nextInvoiceDate) : start;
        if (first.isAfter(period)) { first = period; }
        var months = new ArrayList<YearMonth>();
        for (var month = first; !month.isAfter(periodEnd(period, periodMonths)); month = month.plusMonths(1)) { months.add(month); }
        return months;
    }
    /** The first month of `M`'s natural period of {@code periodMonths} months in its year (January, July for 6). */
    public static YearMonth periodStart(YearMonth month, int periodMonths) {
        int size = Math.max(1, Math.min(12, periodMonths));
        return YearMonth.of(month.getYear(), ((month.getMonthValue() - 1) / size) * size + 1);
    }
    public static YearMonth periodEnd(YearMonth month, int periodMonths) {
        int size = Math.max(1, Math.min(12, periodMonths));
        var end = periodStart(month, size).plusMonths(size - 1L);
        return end.getYear() > month.getYear() ? YearMonth.of(month.getYear(), 12) : end;
    }
    /** R-12-06: `billing.nextInvoiceDayOfMonth` of {@code month}, clamped to the month's length. */
    public static LocalDate invoiceDay(YearMonth month, int dayOfMonth) {
        return month.atDay(Math.max(1, Math.min(dayOfMonth, month.lengthOfMonth())));
    }

    private static Outcome own(Member member, YearMonth period, LocalDate issueDate, Settings settings, Sources sources, Describer describer) {
        var gate = gate(member, period, sources);
        if (gate != null) { return new Excluded(gate); }
        var lastBilled = sources.lastInvoicedMonth(member.id()).orElse(null);
        var plan = member.planId() == null ? null : sources.plan(member.planId()).orElse(null);
        if (plan == null) { return new Skipped(BillingIncidentCode.NO_PLAN); }
        var lines = new ArrayList<Line>();
        LocalDate next = invoiceDay(period.plusMonths(1), settings.nextInvoiceDayOfMonth());
        if (plan.type() == PlanType.MONTHLY) {
            boolean semester = settings.cashInvoicing() == CashInvoicing.SEMESTER && member.method() == PaymentMethodType.MANUAL && settings.cashPeriodMonths() > 1;
            var months = semester ? cashMonths(period, member.nextInvoiceDate(), settings.cashPeriodMonths()) : List.of(period);
            if (semester) { next = invoiceDay(periodEnd(period, settings.cashPeriodMonths()).plusMonths(1), settings.nextInvoiceDayOfMonth()); }
            var concept = plan.maintenance() ? InvoiceLineOrigin.MAINTENANCE_FEE : InvoiceLineOrigin.MONTHLY_FEE;
            Price price = null; boolean priced = false;
            for (var month : months) {
                if (lastBilled != null && month.isAfter(lastBilled)) { break; }
                var fee = settings.inactivity() ? sources.inactivityFee(member.id(), month).orElse(null) : null;
                if (!priced) { price = sources.price(plan.id(), concept, issueDate).orElse(null); priced = true; }
                if (fee == null && price == null) { return new Skipped(BillingIncidentCode.NO_PRICE); }
                Money amount = fee != null ? fee : price.amount();
                if (!amount.currency().equals(settings.currency())) { return new Skipped(BillingIncidentCode.CURRENCY_MISMATCH); }
                BigDecimal tax = price == null ? BigDecimal.ZERO : price.taxPercent();
                var origin = fee != null ? InvoiceLineOrigin.INACTIVITY_FEE : concept;
                lines.add(new Line(origin, month, fee != null ? null : price.id(), null, null, describer.line(origin, plan.name(), month),
                        InvoiceAmounts.fromPrice(amount, tax, settings.taxIncluded()), null));
            }
        }
        var charges = charges(member, settings, sources, null);
        if (charges == null) { return new Skipped(BillingIncidentCode.CURRENCY_MISMATCH); }
        lines.addAll(charges);
        return new Billed(lines, next);
    }
    /** R-12-04: what a non-holder adds to the holder's invoice (its inactivity fee, its single classes); null = nothing due. */
    private static Outcome familyShare(Member member, YearMonth period, Settings settings, Sources sources, Describer describer) {
        if (!"ACTIVE".equals(member.status()) || member.nextInvoiceDate() == null || member.nextInvoiceDate().isAfter(period.atEndOfMonth())) { return null; }
        var lastBilled = sources.lastInvoicedMonth(member.id()).orElse(null);
        if (lastBilled != null && period.isAfter(lastBilled)) { return null; }
        var lines = new ArrayList<Line>();
        var fee = settings.inactivity() ? sources.inactivityFee(member.id(), period).orElse(null) : null;
        if (fee != null) {
            if (!fee.currency().equals(settings.currency())) { return new Skipped(BillingIncidentCode.CURRENCY_MISMATCH); }
            lines.add(new Line(InvoiceLineOrigin.INACTIVITY_FEE, period, null, null, null, describer.line(InvoiceLineOrigin.INACTIVITY_FEE, null, period),
                    InvoiceAmounts.fromPrice(fee, BigDecimal.ZERO, settings.taxIncluded()), member.id()));
        }
        var charges = charges(member, settings, sources, member.id());
        if (charges == null) { return new Skipped(BillingIncidentCode.CURRENCY_MISMATCH); }
        lines.addAll(charges);
        return new Billed(lines, null);
    }
    private static List<Line> charges(Member member, Settings settings, Sources sources, String forMemberId) {
        if (!settings.singleClass()) { return List.of(); }
        var lines = new ArrayList<Line>();
        for (var charge : sources.charges(member.id())) {
            if (!charge.amount().currency().equals(settings.currency())) { return null; }
            lines.add(new Line(InvoiceLineOrigin.SINGLE_CLASS, null, charge.priceId(), charge.bookingId(), charge.id(), charge.description(),
                    InvoiceAmounts.fromPrice(charge.amount(), charge.taxPercent(), settings.taxIncluded()), forMemberId));
        }
        return lines;
    }
    private static Exclusion gate(Member member, YearMonth period, Sources sources) {
        if (!"ACTIVE".equals(member.status())) { return Exclusion.NOT_ACTIVE; }
        if (member.nextInvoiceDate() == null || member.nextInvoiceDate().isAfter(period.atEndOfMonth())) { return Exclusion.NOT_DUE; }
        if (member.method() == null) { return Exclusion.NO_PAYMENT_METHOD; }
        var lastBilled = sources.lastInvoicedMonth(member.id());
        if (lastBilled.isPresent() && period.isAfter(lastBilled.get())) { return Exclusion.AFTER_LEAVE; }
        return null;
    }
    /**
     * R-12-07, R-12-28: the payment method's incident, only when there is something to bill. The member's own data comes first
     * (`NO_BANK_ACCOUNT`, `CARD_INVALID`: D10 fixes it), then the club's provider (`PROVIDER_DISABLED`: D11 fixes it).
     */
    private static BillingIncidentCode methodIncident(Member member, List<Line> lines, Settings settings) {
        if (lines.isEmpty()) { return null; }
        if (member.method() == PaymentMethodType.SEPA_DD && !member.bankAccount()) { return BillingIncidentCode.NO_BANK_ACCOUNT; }
        if (member.method() == PaymentMethodType.CARD && member.cardInvalid()) { return BillingIncidentCode.CARD_INVALID; }
        if (!settings.enabledMethods().contains(member.method())) { return BillingIncidentCode.PROVIDER_DISABLED; }
        return null;
    }
}
