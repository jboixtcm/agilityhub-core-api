package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.activities.persistence.*;
import com.agilityhub.core.clubs.activities.domain.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ActivityRegistrationService {
    final ActivityRepository activities; final ActivityRegistrationRepository registrations; final ActivityContext context;
    private final ActivityTransactions transactions; private final ActivityEvents events; private final ActivityAudit audit;
    public ActivityRegistrationService(ActivityRepository activities,ActivityRegistrationRepository registrations,ActivityContext context,
            ActivityTransactions transactions,ActivityEvents events,ActivityAudit audit) {
        this.activities=activities; this.registrations=registrations; this.context=context; this.transactions=transactions; this.events=events; this.audit=audit;
    }
    public ActivityRegistration require(String id,boolean staff) {
        context.require(); var r=registrations.require(id);
        if(!staff && !r.memberId().equals(context.members.me())) throw new ApiException(ErrorCode.NOT_FOUND);
        return r;
    }
    public ActivityRegistration register(String activityId,boolean joinWaitlist) {
        context.require();
        try { return transactions.write(List.of(activityId),() -> {
            var a=activities.lock(activityId); String memberId=context.members.me(); var member=context.members.member(memberId);
            var live=registrations.live(a.id());
            ActivityEligibility.check(a.state(),context.times(a),a.date(),context.eligibility(memberId),a.levelIds(),context.levels(),context.enabled(Module.INACTIVITY),
                    live.stream().anyMatch(r -> r.memberId().equals(memberId)),null,context.clock.instant());
            boolean full=a.maxPlaces()!=null && a.counters().active()>=a.maxPlaces();
            boolean waitlist=context.enabled(Module.WAITLIST) && a.waitlistEnabled();
            if(full && (!waitlist || !joinWaitlist)) throw new ApiException(ErrorCode.ACTIVITY_FULL,Map.of("waitlistAvailable",waitlist,"waiting",a.counters().waiting()));
            var now=context.clock.instant(); boolean impersonated=context.impersonated(); var user=CurrentUser.current();
            int position=live.stream().filter(r -> r.state()==RegistrationState.WAITLISTED).mapToInt(ActivityRegistration::position).max().orElse(0)+1;
            var r=registrations.insert(new ActivityRegistration(UUID.randomUUID().toString(),TenantContext.require(),a.id(),memberId,
                    full?RegistrationState.WAITLISTED:RegistrationState.ACTIVE,impersonated?RegistrationOrigin.BACKOFFICE:RegistrationOrigin.APP,now,
                    new ActivityRegistration.RegisteredBy(context.actor(),impersonated?memberId:null,impersonated?user.impersonation().actorName():member.name()),
                    full?position:null,null,null,null,null,context.times(a).startsAt(),null,0L,now,context.actor(),now,context.actor()));
            var edit=new ActivityEdit(a); edit.counters=new Activity.Counters(a.counters().active()+(full?0:1),a.counters().waiting()+(full?1:0)); saveCounters(edit);
            changed(r,false); if(impersonated) audit.registered(r); return r;
        }); } catch(org.springframework.dao.DuplicateKeyException duplicate) { throw new ApiException(ErrorCode.ALREADY_REGISTERED); }
    }
    /** The activity of registration {@code id} (the lane of its writes), or null when it does not exist (the write answers NOT_FOUND). */
    public String activityOf(String id) { context.require(); return registrations.findById(id).map(ActivityRegistration::activityId).orElse(null); }
    public ActivityRegistration cancel(String id,String reason) {
        context.require();
        return transactions.write(Collections.singletonList(activityOf(id)),() -> {
            var initial=require(id,false); var a=activities.lock(initial.activityId()); var r=require(id,false);
            CancellationDeadline.check(context.clock.instant(),context.deadlinePolicy(),r.state(),context.times(a),context.impersonated(),reason);
            var after=cancelled(r,context.impersonated()?RegistrationCancelReason.ADMIN:RegistrationCancelReason.MEMBER,
                    context.impersonated()?ActivityRegistration.CancelledByRole.ADMIN:ActivityRegistration.CancelledByRole.MEMBER);
            var edit=new ActivityEdit(a); recount(edit);
            if(r.state()==RegistrationState.ACTIVE) promote(edit);
            saveCounters(edit); if(context.impersonated()) audit.registrationCancelled(r,after,reason); return after;
        });
    }
    ActivityRegistration cancelled(ActivityRegistration r,RegistrationCancelReason reason,ActivityRegistration.CancelledByRole role) {
        ActivityTransitions.registration(r.state(),RegistrationState.CANCELLED); var now=context.clock.instant();
        var after=new ActivityRegistration(r.id(),r.clubId(),r.activityId(),r.memberId(),RegistrationState.CANCELLED,
                role==ActivityRegistration.CancelledByRole.ADMIN?RegistrationOrigin.BACKOFFICE:r.origin(),r.registeredAt(),r.registeredBy(),r.position(),r.promotedAt(),now,
                new ActivityRegistration.CancelledBy(context.actor(),role),reason,r.activityStartsAt(),r.upfrontPaymentId(),r.version()+1,r.createdAt(),r.createdByAccountId(),now,context.actor());
        registrations.update(after,r.version());
        if(reason!=RegistrationCancelReason.ACTIVITY_CANCELLED) changed(after,false);
        return after;
    }
    void promote(ActivityEdit edit) {
        if(edit.state!=ActivityState.PUBLISHED || !context.clock.instant().isBefore(CancellationDeadline.deadline(context.deadlinePolicy(),RegistrationState.ACTIVE,context.times(edit.snapshot()),false))) return;
        var waiting=registrations.live(edit.id).stream().filter(r -> r.state()==RegistrationState.WAITLISTED).sorted(Comparator.comparing(ActivityRegistration::position).thenComparing(ActivityRegistration::id)).toList();
        for(var r:waiting) {
            if(edit.maxPlaces!=null && edit.counters.active()>=edit.maxPlaces) break;
            var now=context.clock.instant();
            var after=new ActivityRegistration(r.id(),r.clubId(),r.activityId(),r.memberId(),RegistrationState.ACTIVE,r.origin(),r.registeredAt(),r.registeredBy(),null,now,null,null,null,
                    context.times(edit.snapshot()).startsAt(),r.upfrontPaymentId(),r.version()+1,r.createdAt(),r.createdByAccountId(),now,context.actor());
            registrations.update(after,r.version()); edit.counters=new Activity.Counters(edit.counters.active()+1,edit.counters.waiting()-1); changed(after,true);
        }
    }
    void recount(ActivityEdit edit) {
        var live=registrations.live(edit.id); edit.counters=new Activity.Counters((int)live.stream().filter(r -> r.state()==RegistrationState.ACTIVE).count(),(int)live.stream().filter(r -> r.state()==RegistrationState.WAITLISTED).count());
    }
    private void saveCounters(ActivityEdit edit) {
        long expected=edit.version; edit.version++; edit.updatedAt=context.clock.instant(); edit.updatedByAccountId=context.actor(); activities.update(edit.snapshot(),expected);
    }
    void refreshStartsAt(Activity a) {
        for(var r:registrations.forActivity(a.id())) if(!Objects.equals(r.activityStartsAt(),a.startsAt())) registrations.update(new ActivityRegistration(r.id(),r.clubId(),r.activityId(),r.memberId(),r.state(),r.origin(),r.registeredAt(),r.registeredBy(),r.position(),r.promotedAt(),r.cancelledAt(),r.cancelledBy(),r.cancelReason(),a.startsAt(),r.upfrontPaymentId(),r.version()+1,r.createdAt(),r.createdByAccountId(),context.clock.instant(),context.actor()),r.version());
    }
    public int cancelForInactivity(String memberId,LocalDate from,LocalDate to) {
        if(!context.enabled(Module.ACTIVITIES) || !context.enabled(Module.INACTIVITY) || !Boolean.TRUE.equals(context.config().get("inactivity.cancelBookingsOnApproval",Boolean.class))) return 0;
        return cancelMatching(memberId,RegistrationCancelReason.INACTIVITY,a -> !a.date().isBefore(from) && !a.date().isAfter(to));
    }
    public int cancelForMemberLeft(String memberId) {
        if(!context.enabled(Module.ACTIVITIES)) return 0;
        return cancelMatching(memberId,RegistrationCancelReason.MEMBER_LEFT,a -> context.times(a).startsAt().isAfter(context.clock.instant()));
    }
    private int cancelMatching(String memberId,RegistrationCancelReason reason,java.util.function.Predicate<Activity> match) {
        var lanes=registrations.forMember(memberId).stream().map(ActivityRegistration::activityId).distinct().toList();
        return transactions.write(lanes,() -> {
            int count=0;
            for(var initial:registrations.forMember(memberId)) if(initial.state()!=RegistrationState.CANCELLED) {
                var a=activities.lock(initial.activityId()); var r=registrations.require(initial.id());
                if(r.state()==RegistrationState.CANCELLED || !match.test(a)) continue;
                cancelled(r,reason,ActivityRegistration.CancelledByRole.SYSTEM); var edit=new ActivityEdit(a); recount(edit);
                if(r.state()==RegistrationState.ACTIVE) promote(edit); saveCounters(edit); count++;
            }
            return count;
        });
    }
    private void changed(ActivityRegistration r,boolean promoted) {
        var payload=new LinkedHashMap<String,Object>(); payload.put("registrationId",r.id()); payload.put("activityId",r.activityId()); payload.put("memberId",r.memberId());
        payload.put("state",r.state()); payload.put("origin",r.state()==RegistrationState.CANCELLED?(r.cancelReason()==RegistrationCancelReason.ADMIN?RegistrationOrigin.BACKOFFICE:RegistrationOrigin.APP):r.origin()); if(r.cancelReason()!=null) payload.put("cancelReason",r.cancelReason()); if(promoted) payload.put("promoted",true);
        events.publish(ActivityEvent.Kind.ActivityRegistrationChanged,r.id(),payload);
    }
}
