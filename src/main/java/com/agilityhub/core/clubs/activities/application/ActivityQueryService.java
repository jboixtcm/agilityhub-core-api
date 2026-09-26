package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.activities.persistence.*;
import com.agilityhub.core.clubs.activities.domain.*;
import com.agilityhub.core.clubs.scheduling.application.ports.ActivityTitlePort;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.activities.application.ActivityProjection.object;

@Service
public class ActivityQueryService implements ActivityTitlePort {
    private final ActivityRepository activities; private final ActivityRegistrationRepository registrations;
    private final ActivityContext context; private final ActivityProjection projection; private final DogOwnerAccess dogs;
    public ActivityQueryService(ActivityRepository activities,ActivityRegistrationRepository registrations,ActivityContext context,ActivityProjection projection,DogOwnerAccess dogs) {
        this.activities=activities; this.registrations=registrations; this.context=context; this.projection=projection; this.dogs=dogs;
    }
    public List<Map<String,Object>> bookableFor(String memberId,String dogId) {
        if(!context.enabled(Module.ACTIVITIES)) return List.of();
        var member=context.eligibility(memberId); var existing=registrations.forMember(memberId).stream().filter(r -> r.state()!=RegistrationState.CANCELLED).map(ActivityRegistration::activityId).collect(java.util.stream.Collectors.toSet());
        return activities.findAll().stream().filter(a -> projection.open(a) && !existing.contains(a.id()))
                .filter(a -> ActivityEligibility.admitted(member.dogs(),a.levelIds(),context.levels(),dogId))
                .sorted(Comparator.comparing(a -> context.times(a).startsAt())).map(a -> row(a,member,dogId)).toList();
    }
    private Map<String,Object> row(Activity a,ActivityEligibility.Member member,String dogId) {
        String state=projection.free(a)==null || projection.free(a)>0?"OPEN":projection.waitlist(a)?"FULL_WAITLIST":"FULL"; String reason=null;
        try { ActivityEligibility.check(a.state(),context.times(a),a.date(),member,a.levelIds(),context.levels(),context.enabled(Module.INACTIVITY),false,dogId,context.clock.instant()); }
        catch(ApiException error) { state="NOT_BOOKABLE"; reason=error.code().name(); }
        return object("id",a.id(),"title",projection.title(a),"typeLabel",projection.type(a),"startsAtLocal",projection.local(context.times(a).startsAt()),
                "endsAtLocal",projection.endsAtLocal(a),"startTime",a.startTime(),"endTime",a.endTime(),"placeLabel",projection.place(a),"rowState",state,"notBookableReason",reason,
                "freeSeats",projection.free(a),"waiting",a.counters().waiting(),"waitlistEnabled",projection.waitlist(a));
    }
    public List<Map<String,Object>> liveRegistrationsFor(String memberId) {
        if(!context.enabled(Module.ACTIVITIES)) return List.of();
        var result=new ArrayList<Map<String,Object>>();
        for(var r:registrations.forMember(memberId)) {
            var a=activities.require(r.activityId());
            if(r.state()==RegistrationState.CANCELLED || !context.times(a).endsAt().isAfter(context.clock.instant())) continue;
            var times=context.times(a); result.add(object("type","ACTIVITY","id",r.id(),"activityId",a.id(),"state",r.state()==RegistrationState.ACTIVE?"REGISTERED":"WAITLISTED",
                    "title",projection.title(a),"startsAt",times.startsAt(),"startsAtLocal",projection.local(times.startsAt()),"endsAtLocal",projection.endsAtLocal(a),"ringName",projection.place(a),"dogId",null));
        }
        result.sort(Comparator.comparing(r -> r.get("startsAtLocal").toString())); return result;
    }
    public List<Map<String,Object>> historyRowsFor(String memberId,Instant from,Instant to) {
        if(!context.enabled(Module.ACTIVITIES)) return List.of();
        var result=new ArrayList<Map<String,Object>>();
        Instant limit=context.clock.instant().atZone(context.zone()).minusMonths(context.config().get("history.monthsVisible",Integer.class)).toInstant();
        if(from.isBefore(limit)) from=limit;
        for(var r:registrations.forMember(memberId)) {
            var a=activities.require(r.activityId()); var start=context.times(a).startsAt();
            if(start.isBefore(from) || start.isAfter(to)) continue;
            String state=ActivityRows.historyState(r.state(),r.cancelReason(),a.state()); if(state==null) continue;
            result.add(object("type","ACTIVITY","id",r.id(),"activityId",a.id(),"title",projection.title(a),"state",state,"startsAtLocal",projection.local(start),
                    "ringName",projection.place(a),"dogId",null,"adminText",r.cancelReason()==RegistrationCancelReason.ACTIVITY_CANCELLED?a.cancellation().adminText():null));
        }
        return result;
    }
    @Override public Map<String,String> titles(Collection<String> ids,Locale locale) {
        if(!context.enabled(Module.ACTIVITIES)) return Map.of();
        try(var language=LocaleContext.open(locale)) {
            var result=new LinkedHashMap<String,String>(); activities.findAll().stream().filter(a -> ids.contains(a.id())).forEach(a -> result.put(a.id(),projection.title(a))); return result;
        }
    }
    public Map<String,Object> mine(String dogId) {
        context.require(); String memberId=context.members.me();
        if(dogId!=null) try { dogs.requireDog(dogId,true,false); } catch(ApiException denied) { throw new ApiException(ErrorCode.DOG_NOT_ACCESSIBLE); }
        var live=new LinkedHashMap<ActivityRegistration,Activity>();
        for(var r:registrations.forMember(memberId)) if(r.state()!=RegistrationState.CANCELLED) {
            var a=activities.require(r.activityId()); if(context.times(a).endsAt().isAfter(context.clock.instant())) live.put(r,a);
        }
        // R-07-08 (E5-T22, review E5-T20 #5): the waiting ranks of every listed activity in one read, not one read per waiting row.
        var ranks=projection.waitlistRanks(live.keySet().stream().filter(r -> r.state()==RegistrationState.WAITLISTED).map(ActivityRegistration::activityId).toList());
        var mine=new ArrayList<Map<String,Object>>();
        live.forEach((r,a) -> {
            var row=new LinkedHashMap<>(projection.registration(r,a,ranks.getOrDefault(a.id(),Map.of()))); row.remove("memberId"); row.remove("registeredBy"); row.remove("impersonation"); mine.add(row);
        });
        return object("bookable",bookableFor(memberId,dogId),"mine",mine);
    }
    public Map<String,Object> detail(String id) {
        context.require(); var a=activities.require(id);
        if(a.state()!=ActivityState.PUBLISHED && a.state()!=ActivityState.FINISHED) throw new ApiException(ErrorCode.NOT_FOUND);
        String memberId=context.members.me(); var result=new LinkedHashMap<>(projection.activity(a,false));
        // The member view is a strict subset of the staff projection.
        result.keySet().retainAll(Set.of("id","slug","state","type","typeDisplay","title","shortDescription","longDescriptionHtml","image","documents","location","rings","allRings","date","startTime","endTime","startsAt","endsAt","registrationFrom","registrationTo","registrationOpen","minPlaces","maxPlaces","levelNames","waitlistEnabled","freeSeats"));
        var registration=registrations.forMember(memberId).stream().filter(r -> r.activityId().equals(id) && r.state()!=RegistrationState.CANCELLED).findFirst().orElse(null);
        result.put("myRegistration",registration==null?null:projection.registration(registration,a)); result.put("rowState",row(a,context.eligibility(memberId),null).get("rowState"));
        result.put("cancellableUntil",CancellationDeadline.deadline(context.deadlinePolicy(),registration==null?RegistrationState.ACTIVE:registration.state(),context.times(a),context.impersonated())); return result;
    }
}
