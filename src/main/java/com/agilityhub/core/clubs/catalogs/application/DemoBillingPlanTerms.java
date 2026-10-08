package com.agilityhub.core.clubs.catalogs.application;

import java.util.Map;
import org.springframework.stereotype.Service;

/** Catalog-owned historical terms for the demo's attendance-billed bookings. */
@Service
public class DemoBillingPlanTerms {
    private final PlanService plans;
    public DemoBillingPlanTerms(PlanService plans) { this.plans = plans; }

    public void attendance(String planId, Runnable seed) {
        var plan = plans.get(planId); var original = plan.singleClass();
        plans.update(planId, Map.of("version", plan.version(), "singleClass",
                Map.of("chargeMode", "CHARGE_ON_ATTENDANCE", "cancelPolicy", original.cancelPolicy().name())));
        try { seed.run(); }
        finally { plans.update(planId, Map.of("version", plans.get(planId).version(), "singleClass", original)); }
    }
}
