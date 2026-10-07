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

@Service
public class MemberPlanChangeService {
    private final BillingCensusAccess census; private final BillingCatalogAccess catalog; private final PackBalanceRepository packs;
    private final UpfrontPayments upfront; private final ClubClock clock;
    public MemberPlanChangeService(BillingCensusAccess census, BillingCatalogAccess catalog, PackBalanceRepository packs, UpfrontPayments upfront, ClubClock clock) {
        this.census = census; this.catalog = catalog; this.packs = packs; this.upfront = upfront; this.clock = clock;
    }
    @Transactional
    @Audited(action = AuditAction.MEMBER_PLAN_CHANGED, entityType = "'Member'", entity = "#memberId", member = "#memberId")
    public void change(String memberId, String planId, String priceId, String effectiveMonth) {
        var today = clock.today(TenantContext.require()); catalog.validateChange(planId, priceId, today);
        // This route writes the current plan. A future month must never silently change today's billing.
        if (effectiveMonth != null && !YearMonth.parse(effectiveMonth).equals(YearMonth.from(today))) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        String previous = census.lockPlan(memberId);
        var last = packs.of(memberId, null).stream().max(Comparator.comparing(PackBalance::openedOn).thenComparing(PackBalance::id));
        boolean used = last.map(p -> Boolean.TRUE.equals(p.sourceIds() == null ? null : p.sourceIds().get("entryDiscountApplied"))).orElse(false);
        var quote = previous == null || previous.equals(planId) ? null : catalog.changeQuote(previous, planId, last.map(PackBalance::sessionsTotal).orElse(0), used);
        census.changePlan(memberId, planId, priceId);
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
