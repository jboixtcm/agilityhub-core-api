package com.agilityhub.core.payments.application;

import com.agilityhub.core.clubs.catalogs.application.BillingCatalogAccess;
import com.agilityhub.core.payments.persistence.PackBalance;
import java.util.*;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

@Service
public class PackViews {
    private final BillingCatalogAccess catalog;
    public PackViews(BillingCatalogAccess catalog) { this.catalog = catalog; }
    public Map<String, Object> view(PackBalance p) {
        var out = new LinkedHashMap<String, Object>();
        out.put("id", p.id()); out.put("memberId", p.memberId()); out.put("dogId", p.dogId()); out.put("planId", p.planId());
        out.put("planName", catalog.plan(p.planId()).map(plan -> BillingTexts.text(plan.name(), LocaleContextHolder.getLocale())).orElse(""));
        out.put("upfrontPaymentId", p.upfrontPaymentId()); out.put("sessionsTotal", p.sessionsTotal()); out.put("consumed", p.consumed()); out.put("remaining", p.remaining());
        out.put("openedOn", p.openedOn()); out.put("expiresOn", p.expiresOn()); out.put("state", p.state()); out.put("movements", p.movements());
        out.put("expiryWarnedAt", p.expiryWarnedAt()); out.put("expiredAt", p.expiredAt()); return out;
    }
}
