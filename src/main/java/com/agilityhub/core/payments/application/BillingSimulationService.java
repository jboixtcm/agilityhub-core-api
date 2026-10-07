package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.BillingEvent;
import com.agilityhub.core.payments.domain.BillingIncidentCode;
import com.agilityhub.core.platform.application.BillingProviderSettings;
import com.agilityhub.core.payments.domain.InvoiceAmounts;
import com.agilityhub.core.payments.domain.InvoiceLineOrigin;
import com.agilityhub.core.payments.domain.InvoicingRules;
import com.agilityhub.core.payments.domain.PaymentMethodType;
import com.agilityhub.core.payments.persistence.BillingDocuments.BillingLockRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.BillingSimulationRepository;
import com.agilityhub.core.payments.persistence.BillingSimulation;
import com.agilityhub.core.shared.application.BillingCensusAccess.BillingMember;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.time.Clock;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * S12 R-12-07 «1 · SIMULA EL MES» (E8-T02): R-12-01…05 computed over the club without writing anything of business value —
 * the incidents (`NO_BANK_ACCOUNT`, `NO_PLAN`, `NO_PRICE`, `CARD_INVALID`, `CURRENCY_MISMATCH`, `PROVIDER_DISABLED`), the
 * active cash members with their planned leave, the preview of each invoice and the KPIs — kept as the month's only
 * `BillingSimulation` and announced with `RemittanceSimulated`. A month more than three months ahead is `400
 * VALIDATION_ERROR` (S12 §13); the club's billing lock held by a run or a rollback is `409 BILLING_BUSY`. E8-T07 (R-12-19):
 * the waiting manual `includeInNextRun` receipts the run will remit are previewed and counted too (so D6's confirmation is the
 * remittance), and the ones it cannot remit are incidents.
 */
@Service
public class BillingSimulationService {
    /** S12 §13: a month can be simulated at most three months ahead of today's. */
    static final int MAX_MONTHS_AHEAD = 3;
    private final InvoicingService invoicing; private final BillingSimulationRepository simulations; private final BillingLockRepository locks;
    private final BillingEvents events; private final BillingTransactions transactions; private final BillingProviderSettings providers; private final Clock clock;
    public BillingSimulationService(InvoicingService invoicing, BillingSimulationRepository simulations, BillingLockRepository locks, BillingEvents events,
            BillingTransactions transactions, BillingProviderSettings providers, Clock clock) {
        this.invoicing = invoicing; this.simulations = simulations; this.locks = locks; this.events = events; this.transactions = transactions;
        this.providers = providers; this.clock = clock;
    }

    public BillingSimulation simulate(YearMonth period) {
        var today = invoicing.context().issueDate();
        if (period.isAfter(YearMonth.from(today).plusMonths(MAX_MONTHS_AHEAD))) { throw BillingContractAccess.invalid("period"); }
        return locked("simulation", () -> transactions.run(() -> {
            var plan = invoicing.plan(period);
            var simulation = simulations.store(build(plan));
            var payload = new LinkedHashMap<String, Object>();
            payload.put("simulationId", simulation.id()); payload.put("period", period.toString());
            payload.put("incidents", simulation.incidents().stream().map(incident -> Map.of("memberId", incident.memberId(), "code", incident.code().name())).toList());
            payload.put("totals", Map.of("count", simulation.kpis().count(), "total", simulation.kpis().total()));
            events.publish(BillingEvent.Kind.RemittanceSimulated, simulation.id(), payload);
            return simulation;
        }));
    }

    /** R-12-11: the club's billing lock around {@code work}, taken outside its transaction (`409 BILLING_BUSY` while held). */
    <T> T locked(String kind, Supplier<T> work) {
        String holder = kind + ":" + UUID.randomUUID();
        if (!locks.acquire(holder, clock.instant())) { throw new ApiException(ErrorCode.BILLING_BUSY); }
        try { return work.get(); }
        finally { locks.release(holder); }
    }

    BillingSimulation build(InvoicingService.MonthPlan plan) {
        var context = plan.context(); String currency = context.currency();
        var incidents = new ArrayList<>(plan.month().skipped().stream()
                .map(skip -> new BillingSimulation.Incident(skip.memberId(), name(plan.member(skip.memberId())), skip.code())).toList());
        var cash = plan.members().values().stream().filter(member -> member.paymentMethod() != null && "MANUAL".equals(member.paymentMethod().type()))
                .map(member -> new BillingSimulation.CashMember(member.id(), name(member), member.leaveDate() == null ? null : member.leaveDate().toString())).toList();
        var preview = new ArrayList<BillingSimulation.PreviewInvoice>();
        var byMethod = new EnumMap<PaymentMethodType, List<Money>>(PaymentMethodType.class);
        int inactive = 0;
        // E8-T03 (S12 §13, step 9): SEPA_XML enabled without its creditor data (identifier or IBAN missing) cannot remit. Its debtors
        // are PROVIDER_DISABLED here, so D6 shows it before the run, which answers 422 SEPA_NOT_CONFIGURED until D11 completes it.
        boolean sepaUnusable = context.settings().enabledMethods().contains(PaymentMethodType.SEPA_DD)
                && !providers.sepaCreditor().map(BillingProviderSettings.SepaCreditor::configured).orElse(false);
        for (var draft : plan.month().invoices()) {
            var payer = plan.member(draft.payerId());
            var method = InvoicingService.member(payer).method();
            if (sepaUnusable && method == PaymentMethodType.SEPA_DD) {
                incidents.add(new BillingSimulation.Incident(payer.id(), name(payer), BillingIncidentCode.PROVIDER_DISABLED));
                continue;
            }
            var total = total(draft, currency);
            preview.add(new BillingSimulation.PreviewInvoice(payer.id(), name(payer), method,
                    draft.lines().stream().map(line -> new BillingSimulation.PreviewLine(line.origin(), line.description(), line.amounts().total())).toList(), total));
            byMethod.computeIfAbsent(method, ignored -> new ArrayList<>()).add(total);
            inactive += (int) draft.lines().stream().filter(line -> line.origin() == InvoiceLineOrigin.INACTIVITY_FEE).count();
        }
        // E8-T07 step 3 (R-12-07, R-12-19): the waiting manual receipts the run remits are in the preview and the KPIs; the others
        // are its incidents (one per member and code).
        for (var waiting : plan.waiting()) {
            var receipt = waiting.invoice();
            var code = waiting.incident() != null ? waiting.incident() : sepaUnusable ? BillingIncidentCode.PROVIDER_DISABLED : null;
            if (code != null) {
                incidents.add(new BillingSimulation.Incident(receipt.memberId(), waiting.memberName(), code, receipt.id(), receipt.displayNumber()));
                continue;
            }
            preview.add(new BillingSimulation.PreviewInvoice(receipt.memberId(), waiting.memberName(), PaymentMethodType.SEPA_DD,
                    receipt.lines().stream().map(line -> new BillingSimulation.PreviewLine(line.origin(), line.description(), line.total())).toList(),
                    receipt.total(), receipt.id(), receipt.displayNumber()));
            byMethod.computeIfAbsent(PaymentMethodType.SEPA_DD, ignored -> new ArrayList<>()).add(receipt.total());
        }
        var all = byMethod.values().stream().flatMap(List::stream).toList();
        var enabled = context.settings().enabledMethods();
        var kpis = new BillingSimulation.Kpis(preview.size(), InvoiceAmounts.sum(all, currency),
                new BillingSimulation.ByProvider(totals(PaymentMethodType.SEPA_DD, byMethod, enabled, currency), totals(PaymentMethodType.CARD, byMethod, enabled, currency),
                        totals(PaymentMethodType.MANUAL, byMethod, enabled, currency)),
                byMethod.getOrDefault(PaymentMethodType.MANUAL, List.of()).size(),
                new BillingSimulation.InactivityFees(inactive, fee(context, "billing.inactivityFeeFirstMonth"), fee(context, "billing.inactivityFeeFollowingMonths")),
                collectionDate(plan).toString());
        return new BillingSimulation(UUID.randomUUID().toString(), context.clubId(), plan.period().toString(), clock.instant(), incidents, cash, preview, kpis,
                BillingEvents.actor(), chargeIds(plan), waitingInvoiceIds(plan));
    }
    /** R-12-12: the billed month's collection date, shared with run generation (club-local calendar date). */
    static java.time.LocalDate collectionDate(InvoicingService.MonthPlan plan) {
        Integer day = plan.context().parameter("billing.sepa.collectionDayOfMonth", Integer.class);
        return com.agilityhub.core.payments.domain.CollectionDates.defaultDate(plan.period(), day == null ? 1 : day);
    }
    /** R-12-07 (E90): exactly the charge ids and waiting receipts the run bills, never the excluded inputs. */
    static List<String> chargeIds(InvoicingService.MonthPlan plan) {
        return plan.month().invoices().stream().flatMap(draft -> draft.lines().stream()).map(InvoicingRules.Line::chargeId)
                .filter(Objects::nonNull).distinct().sorted().toList();
    }
    static List<String> waitingInvoiceIds(InvoicingService.MonthPlan plan) {
        return plan.remitted().stream().map(com.agilityhub.core.payments.persistence.Invoice::id).sorted().toList();
    }
    static Money total(InvoicingRules.Draft draft, String currency) {
        return InvoiceAmounts.sum(draft.lines().stream().map(line -> line.amounts().total()).toList(), currency);
    }
    static String name(BillingMember member) { return member == null ? "" : member.fullName(); }
    private static BillingSimulation.Totals totals(PaymentMethodType method, Map<PaymentMethodType, List<Money>> byMethod, Set<PaymentMethodType> enabled, String currency) {
        var amounts = byMethod.getOrDefault(method, List.of());
        if (amounts.isEmpty() && !enabled.contains(method)) { return null; }
        return new BillingSimulation.Totals(amounts.size(), InvoiceAmounts.sum(amounts, currency));
    }
    private static Money fee(InvoicingService.Context context, String key) {
        Money fee = context.parameter(key, Money.class);
        return fee == null ? new Money(0, context.currency()) : fee;
    }
}
