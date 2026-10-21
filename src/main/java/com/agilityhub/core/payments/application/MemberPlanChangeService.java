package com.agilityhub.core.payments.application;

import com.agilityhub.core.clubs.catalogs.application.BillingCatalogAccess;
import com.agilityhub.core.payments.persistence.BillingDocuments.PackBalanceRepository;
import com.agilityhub.core.payments.persistence.PackBalance;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S12 §6 `POST /members/{id}/plan-change` (E8-T05 step 10, R-05-18b): the admin writes the member's current plan and price; a
 * `PACK` → `MONTHLY` change creates the `DUE` entry fee, discounted once per eligible pack. E11-T15 step 1 (the audit of 21-10,
 * F1; R-12-01, R-12-06): a member moving to a `MONTHLY` plan without `nextInvoiceDate` (every pack member) gets one — the
 * admin's, or R-12-06's default, `billing.nextInvoiceDayOfMonth` of next month — audited with the plan change; a member that
 * already has a date keeps it. A date before the club's current month is `400 VALIDATION_ERROR {field: nextInvoiceDate}`.
 */
@Service
public class MemberPlanChangeService {
    private final BillingCensusAccess census; private final BillingCatalogAccess catalog; private final PackBalanceRepository packs;
    private final UpfrontPayments upfront; private final ClubClock clock; private final com.agilityhub.core.platform.application.ClubConfigService configs;
    public MemberPlanChangeService(BillingCensusAccess census, BillingCatalogAccess catalog, PackBalanceRepository packs, UpfrontPayments upfront, ClubClock clock,
            com.agilityhub.core.platform.application.ClubConfigService configs) {
        this.census = census; this.catalog = catalog; this.packs = packs; this.upfront = upfront; this.clock = clock; this.configs = configs;
    }
    @Transactional
    @Audited(action = AuditAction.MEMBER_PLAN_CHANGED, entityType = "'Member'", entity = "#memberId", member = "#memberId")
    public void change(String memberId, String planId, String priceId, String effectiveMonth, LocalDate nextInvoiceDate) {
        String club = TenantContext.require(); var today = clock.today(club); catalog.validateChange(planId, priceId, today);
        // This route writes the current plan. A future month must never silently change today's billing.
        if (effectiveMonth != null && !YearMonth.parse(effectiveMonth).equals(YearMonth.from(today))) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        // E11-T15 step 1: a first invoice date is never in a month already closed.
        if (nextInvoiceDate != null && nextInvoiceDate.isBefore(today.withDayOfMonth(1))) { throw BillingContractAccess.invalid("nextInvoiceDate"); }
        String previous = census.lockPlan(memberId);
        var last = packs.of(memberId, null).stream().max(Comparator.comparing(PackBalance::openedOn).thenComparing(PackBalance::id));
        boolean used = last.map(p -> Boolean.TRUE.equals(p.sourceIds() == null ? null : p.sourceIds().get("entryDiscountApplied"))).orElse(false);
        var quote = previous == null || previous.equals(planId) ? null : catalog.changeQuote(previous, planId, last.map(PackBalance::sessionsTotal).orElse(0), used);
        // R-12-06 / R-18-13: a monthly plan needs a date; the run never invents one (NO_NEXT_INVOICE_DATE).
        LocalDate first = null;
        if ("MONTHLY".equals(catalog.plan(planId).orElseThrow().type()) && census.member(memberId).map(BillingCensusAccess.BillingMember::nextInvoiceDate).isEmpty()) {
            first = nextInvoiceDate != null ? nextInvoiceDate : com.agilityhub.core.payments.domain.InvoicingRules.invoiceDay(YearMonth.from(today).plusMonths(1),
                    configs.get(club).get("billing.nextInvoiceDayOfMonth", Integer.class));
        }
        census.changePlan(memberId, planId, priceId, first);
        if (quote != null && quote.amount() != null && "PACK".equals(catalog.plan(previous).orElseThrow().type()) && "MONTHLY".equals(catalog.plan(planId).orElseThrow().type())) {
            upfront.create(memberId, UUID.randomUUID().toString(), List.of(new UpfrontPayments.Charge("ENTRY_FEE", null, quote.amount())));
            if ("PACK_TO_MEMBER".equals(quote.reason()) && last.isPresent()) {
                var p = last.get(); var source = new LinkedHashMap<String,Object>(p.sourceIds() == null ? Map.of() : p.sourceIds()); source.put("entryDiscountApplied", true);
                packs.save(new PackBalance(p.id(), p.clubId(), p.memberId(), p.dogId(), p.planId(), p.upfrontPaymentId(), p.sessionsTotal(), p.consumed(), p.remaining(),
                        p.openedOn(), p.expiresOn(), p.state(), p.movements(), p.expiryWarnedAt(), p.expiredAt(), p.lowBalanceNotifiedAt(), source, p.version()+1, p.createdAt(), p.createdByAccountId()), p.version());
            }
        }
    }
}
