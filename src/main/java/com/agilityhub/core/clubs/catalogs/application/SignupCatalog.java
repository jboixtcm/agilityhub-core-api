package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.persistence.PlanRepository;
import com.agilityhub.core.clubs.catalogs.persistence.PriceRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

/** Tenant-scoped catalog snapshots and the common writer lock used by signup. */
@Service
public class SignupCatalog {
    private final PlanRepository plans;
    private final PriceRepository prices;
    public SignupCatalog(PlanRepository plans, PriceRepository prices) { this.plans = plans; this.prices = prices; }
    public List<SignupPlanData> at(LocalDate date) {
        return plans.findAll().stream().map(plan -> SignupPlanData.from(plan, prices.forPlan(plan.id()), date)).toList();
    }
    public void lock() { plans.lock(); }
}
