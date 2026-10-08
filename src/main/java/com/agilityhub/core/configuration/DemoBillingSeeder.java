package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.census.application.*;
import com.agilityhub.core.payments.application.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.shared.application.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agilityhub.core.clubs.bookings.application.DemoBillingBookingsSeeder;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * E8-T06 step 7: the `billing` section of a demo seed, through the application services (E8-T05's lifecycle and packs, S12's
 * pending charges). Members are seed ordinals with no E4–E7 bookings or registrations, so the cases change nothing else; the
 * planning run that calls this step is recorded once, which keeps a second `seed:demo` a no-op (DemoPlanningService).
 */
@Service
public class DemoBillingSeeder implements DemoSeedStep {
    /**
     * `providers`: the fictional SEPA creditor (step 12, never in club:apply); `pendingInactivity`: a request for next month;
     * `activeInactivity`: approved for this month and the next one (fee snapshot frozen at approval); `leave`: a direct leave on
     * the next `leaveDay` (MM-DD); `expiredPack`: a 10-session pack that expired yesterday and its planned leave (S13);
     * `cardMember`: a CARD payer (fake Stripe ids); `pendingCharges`: one attended single class per entry.
     */
    public record Spec(Map<String,Object> providers, Integer pendingInactivity, Integer activeInactivity, Integer leave,
            String leaveDay, Integer expiredPack, Integer cardMember, List<DemoBillingBookingsSeeder.Spec> pendingCharges) {
        public Spec { providers=providers==null ? Map.of() : Map.copyOf(providers); pendingCharges=pendingCharges==null ? List.of() : List.copyOf(pendingCharges); }
    }
    private final ObjectMapper mapper; private final DemoBillingSettings settings; private final CensusQuery census;
    private final InactivityPeriodService inactivity; private final LeaveRequestService leaves;
    private final PackBalanceService packs; private final BillingCensusAccess billing; private final DemoBillingBookingsSeeder charges;
    public DemoBillingSeeder(ObjectMapper mapper, DemoBillingSettings settings, CensusQuery census, InactivityPeriodService inactivity,
            LeaveRequestService leaves, PackBalanceService packs, BillingCensusAccess billing, DemoBillingBookingsSeeder charges) {
        this.mapper=mapper; this.settings=settings; this.census=census; this.inactivity=inactivity;
        this.leaves=leaves; this.packs=packs; this.billing=billing; this.charges=charges;
    }
    @Override public int order() { return 60; }
    @Override public Map<String,Integer> apply(Input input) {
        var section=input.specification().get("billing"); if (section==null) { return Map.of(); }
        var spec=mapper.convertValue(section,Spec.class); var counts=new LinkedHashMap<String,Integer>();
        if (!spec.providers().isEmpty()) { settings.providers(spec.providers()); counts.put("billingProviders",spec.providers().size()); }
        var month=YearMonth.from(input.today());
        if (spec.pendingInactivity()!=null) {
            inactivity.request(input.member(spec.pendingInactivity()),month.plusMonths(1).toString(),null,"Fictional demo request",false,true);
            counts.put("pendingInactivity",1);
        }
        if (spec.activeInactivity()!=null) {
            inactivity.request(input.member(spec.activeInactivity()),month.toString(),month.plusMonths(1).toString(),"Fictional demo inactivity",true,true);
            counts.put("activeInactivity",1);
        }
        if (spec.leave()!=null) {
            var date=MonthDay.parse("--"+spec.leaveDay()).atYear(input.today().getYear());
            if (date.isBefore(input.today())) { date=date.plusYears(1); }
            leaves.direct(input.member(spec.leave()),date,null,"Fictional demo leave"); counts.put("plannedLeave",1);
        }
        if (spec.expiredPack()!=null) {
            String id=input.member(spec.expiredPack()); var member=billing.member(id).orElseThrow();
            var pack=packs.open(id,dog(id).get("id").toString(),member.planId(),null,input.today().minusMonths(7),10,input.today().minusDays(1),"DEMO_SEED");
            packs.expire(pack.id()); leaves.packExpired(id,pack.id()); counts.put("expiredPacks",1);
        }
        if (spec.cardMember()!=null) {
            billing.saveCard(input.member(spec.cardMember()),new BillingCensusAccess.Card("cus_demo_fictional","pm_demo_fictional","4242","visa",false));
            counts.put("cardMembers",1);
        }
        for (var charge : spec.pendingCharges()) {
            charges.seed(input, charge); counts.merge("pendingCharges", 1, Integer::sum);
        }
        return counts;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> dog(String member) {
        return ((List<Map<String,Object>>)census.member(member,false).get("dogs")).getFirst();
    }
}
