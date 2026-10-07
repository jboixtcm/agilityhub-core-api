package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.census.application.*;
import com.agilityhub.core.payments.application.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.shared.application.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Composition of the E8 demo's public application services; the surrounding demo run owns idempotence. */
@Service
public class DemoBillingSeeder implements DemoSeedStep {
    public record Person(int member, String name) { }
    public record Spec(Map<String,Object> providers, Person pendingInactivity, Person activeInactivity, Person leave,
            String leaveDay, Person expiredPack, Integer cardMember, List<Integer> pendingCharges) {
        public Spec { providers=providers==null ? Map.of() : Map.copyOf(providers); pendingCharges=pendingCharges==null ? List.of() : List.copyOf(pendingCharges); }
    }
    private final ObjectMapper mapper; private final DemoBillingSettings settings; private final CensusQuery census;
    private final MemberService members; private final InactivityPeriodService inactivity; private final LeaveRequestService leaves;
    private final PackBalanceService packs; private final BillingCensusAccess billing; private final PendingChargeService charges;
    public DemoBillingSeeder(ObjectMapper mapper, DemoBillingSettings settings, CensusQuery census, MemberService members,
            InactivityPeriodService inactivity, LeaveRequestService leaves, PackBalanceService packs, BillingCensusAccess billing, PendingChargeService charges) {
        this.mapper=mapper; this.settings=settings; this.census=census; this.members=members; this.inactivity=inactivity;
        this.leaves=leaves; this.packs=packs; this.billing=billing; this.charges=charges;
    }
    public int order() { return 60; }
    public Map<String,Integer> apply(Input input) {
        var section=input.specification().get("billing"); if (section==null) { return Map.of(); }
        var spec=mapper.convertValue(section,Spec.class); var counts=new LinkedHashMap<String,Integer>();
        if (!spec.providers().isEmpty()) { settings.providers(spec.providers()); counts.put("billingProviders",spec.providers().size()); }
        var month=YearMonth.from(input.today());
        if (spec.pendingInactivity()!=null) {
            String id=rename(input,spec.pendingInactivity());
            inactivity.request(id,month.plusMonths(1).toString(),null,"Fictional demo request",false,true); counts.put("pendingInactivity",1);
        }
        if (spec.activeInactivity()!=null) {
            String id=rename(input,spec.activeInactivity());
            inactivity.request(id,month.toString(),month.plusMonths(1).toString(),"Fictional demo inactivity",true,true); counts.put("activeInactivity",1);
        }
        if (spec.leave()!=null) {
            String id=rename(input,spec.leave()); var date=MonthDay.parse("--"+spec.leaveDay()).atYear(input.today().getYear());
            if (date.isBefore(input.today())) { date=date.plusYears(1); }
            leaves.direct(id,date,null,"Fictional demo leave"); counts.put("plannedLeave",1);
        }
        if (spec.expiredPack()!=null) {
            String id=rename(input,spec.expiredPack()); var member=billing.member(id).orElseThrow(); var dog=dog(id);
            var pack=packs.open(id,dog.get("id").toString(),member.planId(),null,input.today().minusMonths(7),10,input.today().minusDays(1),"DEMO_SEED");
            packs.expire(pack.id()); leaves.packExpired(id,pack.id()); counts.put("expiredPacks",1);
        }
        if (spec.cardMember()!=null) {
            billing.saveCard(input.member(spec.cardMember()),new BillingCensusAccess.Card("cus_demo", "pm_demo", "4242", "visa",false)); counts.put("cardMembers",1);
        }
        for (int ordinal:spec.pendingCharges()) {
            String id=input.member(ordinal); var dog=dog(id);
            String booking=UUID.nameUUIDFromBytes((TenantContext.require()+":demo:charge:"+ordinal).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            if (charges.attendance(new PendingChargeService.ChargedBooking(booking,id,dog.get("id").toString(),dog.get("name").toString(),input.today()),"PRESENT","PENDING").isPresent()) {
                counts.merge("pendingCharges",1,Integer::sum);
            }
        }
        return counts;
    }
    private String rename(Input input, Person person) {
        String id=input.member(person.member()); var member=census.member(id,true);
        members.patch(id,Map.of("version",member.get("version"),"firstName",person.name()),false); return id;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> dog(String member) {
        return ((List<Map<String,Object>>)census.member(member,false).get("dogs")).getFirst();
    }
}
