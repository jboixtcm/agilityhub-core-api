package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.activities.domain.*;
import com.agilityhub.core.clubs.activities.persistence.Activity;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.ActivityMemberAccess;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ActivityContext {
    final ClubConfigService configs; final PlanningCatalogAccess catalogs; final ActivityMemberAccess members;
    final Clock clock; final ClubClock clubClock; final IcuMessageSource messages; final PublicClubAccess clubs;
    public ActivityContext(ClubConfigService configs, PlanningCatalogAccess catalogs, ActivityMemberAccess members, Clock clock,
                           ClubClock clubClock, IcuMessageSource messages, PublicClubAccess clubs) {
        this.configs=configs; this.catalogs=catalogs; this.members=members; this.clock=clock; this.clubClock=clubClock; this.messages=messages; this.clubs=clubs;
    }
    public ClubConfig config() { return configs.get(TenantContext.require()); }
    public boolean enabled(Module module) { return config().modules().contains(module); }
    public boolean levels() { return Boolean.TRUE.equals(config().get("levels.enabled",Boolean.class)); }
    public void require() { if (!enabled(Module.ACTIVITIES)) throw new ApiException(ErrorCode.MODULE_DISABLED); }
    public ZoneId zone() { return ZoneId.of(config().club().timeZone()); }
    public ActivityTimes times(Activity a) { return ActivityTimes.of(a.date(),a.startTime(),a.endTime(),a.registrationFrom(),a.registrationTo(),zone()); }
    public String actor() {
        var user=CurrentUser.current(); return user==null?null:user.impersonation()==null?user.accountId():user.impersonation().actorAccountId();
    }
    public boolean impersonated() { return CurrentUser.current()!=null && CurrentUser.current().impersonation()!=null; }
    public LocalizedText text(Map<String,String> values,String field,int max,boolean required) {
        return ActivityRules.text(values,field,config().club().defaultLocale(),config().club().locales(),max,required);
    }
    void validate(ActivityEdit a,boolean publication) {
        var snapshot=catalogs.snapshot();
        if(!levels()) a.levelIds=List.of();
        if(!enabled(Module.WAITLIST)) a.waitlistEnabled=false;
        if(publication) ActivityRules.publish(a.input(),clubClock.today(TenantContext.require()));
        ActivityRules.validate(a.input(),config().get("classes.slotMinutes",Integer.class),
                snapshot.rings().stream().filter(PlanningCatalogAccess.ResourceView::active).map(PlanningCatalogAccess.ResourceView::id).collect(java.util.stream.Collectors.toSet()),
                snapshot.levels().stream().filter(PlanningCatalogAccess.LevelView::active).map(PlanningCatalogAccess.LevelView::id).collect(java.util.stream.Collectors.toSet()));
        var times=ActivityTimes.of(a.date,a.startTime,a.endTime,a.registrationFrom,a.registrationTo,zone());
        a.startsAt=times.startsAt(); a.endsAt=times.endsAt(); a.registrationOpensAt=times.registrationOpensAt(); a.registrationClosesAt=times.registrationClosesAt();
    }
    public com.agilityhub.core.clubs.scheduling.application.ActivityBlockRequest blockRequest(Activity a) {
        if(a.ringIds().isEmpty()) return new com.agilityhub.core.clubs.scheduling.application.ActivityBlockRequest(a.id(),List.of(),null,null,actor());
        var raw=config().get("club.openingHours",Map.class).get(a.date().getDayOfWeek().name());
        var hours=raw instanceof Map<?,?> map?map:Map.of();
        LocalTime open=hours.get("open")==null?null:LocalTime.parse(hours.get("open").toString()), close=hours.get("close")==null?null:LocalTime.parse(hours.get("close").toString());
        var window=RingBlockWindow.of(a.date(),a.startTime(),a.endTime(),a.ringBlockWindow()==null?null:a.ringBlockWindow().fromTime(),
                a.ringBlockWindow()==null?null:a.ringBlockWindow().toTime(),zone(),open,close,config().get("training.slotMinutes",Integer.class));
        return new com.agilityhub.core.clubs.scheduling.application.ActivityBlockRequest(a.id(),a.ringIds(),window.from(),window.to(),actor());
    }
    public ActivityEligibility.Member eligibility(String memberId) {
        var m=members.member(memberId);
        return new ActivityEligibility.Member(m.status(),m.membershipActive(),m.leaveDate(),m.bookingBlock(),members.actorBlock(),
                m.inactivity().stream().map(p -> new ActivityEligibility.Inactivity((LocalDate)p.get("from"),(LocalDate)p.get("to"))).toList(),
                m.dogs().stream().map(d -> new ActivityEligibility.Dog(d.id(),"ACTIVE".equals(d.status()),d.levelId())).toList());
    }
    public String deadlinePolicy() { return config().get("activities.cancelDeadline",String.class); }
}
