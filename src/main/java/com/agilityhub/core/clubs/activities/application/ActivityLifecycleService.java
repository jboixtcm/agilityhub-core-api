package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.activities.persistence.*;
import com.agilityhub.core.clubs.activities.domain.*;
import com.agilityhub.core.clubs.scheduling.application.RingBlockService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.domain.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ActivityLifecycleService {
    private final ActivityService service; private final ActivityRegistrationService registrations;
    public ActivityLifecycleService(ActivityService service,ActivityRegistrationService registrations) { this.service=service; this.registrations=registrations; }
    public Activity publish(String id,boolean notifyEmail,RingBlockService.Options options) {
        service.context.require();
        return service.transactions.write(() -> {
            var before=service.activities.lock(id); ActivityTransitions.activity(before.state(),ActivityState.PUBLISHED);
            var edit=new ActivityEdit(before); service.context.validate(edit,true); service.sync(edit,options);
            edit.state=ActivityState.PUBLISHED; edit.publishedAt=service.context.clock.instant(); edit.publishedByAccountId=service.context.actor();
            var saved=service.save(edit,before.version());
            service.events.publish(ActivityEvent.Kind.ActivityPublished,id,Map.of("activityId",id,"levelIds",edit.levelIds,"notifyEmail",notifyEmail));
            service.audit.published(before,saved); return saved;
        });
    }
    public Activity unpublish(String id) {
        service.context.require();
        return service.transactions.write(() -> {
            var before=service.activities.lock(id); ActivityTransitions.activity(before.state(),ActivityState.DRAFT);
            if(before.counters().active()+before.counters().waiting()>0) throw new ApiException(ErrorCode.ACTIVITY_HAS_REGISTRATIONS);
            service.blocks.cancelForActivity(id); var edit=new ActivityEdit(before); edit.state=ActivityState.DRAFT; edit.ringBlockIds=List.of();
            var saved=service.save(edit,before.version()); service.updated(before,saved,Map.of("state",Map.of("before",before.state(),"after",saved.state()))); return saved;
        });
    }
    public Activity cancel(String id,ActivityCancellationReason reason,String adminText) {
        service.context.require();
        return service.transactions.write(() -> {
            var before=service.activities.lock(id); ActivityTransitions.activity(before.state(),ActivityState.CANCELLED);
            var live=registrations.registrations.live(id);
            if(!live.isEmpty() && (adminText==null || adminText.isBlank())) throw new ApiException(ErrorCode.ADMIN_TEXT_REQUIRED);
            var affected=new ArrayList<Map<String,Object>>();
            for(var r:live) {
                affected.add(Map.of("registrationId",r.id(),"memberId",r.memberId(),"state",r.state()));
                registrations.cancelled(r,RegistrationCancelReason.ACTIVITY_CANCELLED,ActivityRegistration.CancelledByRole.SYSTEM);
            }
            service.blocks.cancelForActivity(id); var edit=new ActivityEdit(before); edit.state=ActivityState.CANCELLED;
            edit.cancellation=new Activity.Cancellation(reason,adminText,service.context.clock.instant(),service.context.actor(),live.size());
            edit.counters=new Activity.Counters(0,0); edit.ringBlockIds=List.of(); var saved=service.save(edit,before.version());
            var payload=new LinkedHashMap<String,Object>(); payload.put("activityId",id); payload.put("reason",reason); payload.put("adminText",adminText); payload.put("affected",affected);
            service.events.publish(ActivityEvent.Kind.ActivityCancelled,id,payload); service.audit.cancelled(before,saved); return saved;
        });
    }
    public RingBlockService.Conflicts conflicts(String id) { return service.blocks.conflictsFor(service.context.blockRequest(service.require(id))); }
    public int finishEnded(Instant now) {
        if(!service.context.enabled(Module.ACTIVITIES)) return 0;
        int count=0;
        for(var candidate:service.activities.findPublished()) count+=service.transactions.write(() -> {
            var before=service.activities.lock(candidate.id());
            if(before.state()!=ActivityState.PUBLISHED || !service.context.times(before).endsAt().isBefore(now)) return 0;
            var edit=new ActivityEdit(before); edit.state=ActivityState.FINISHED; edit.finishedAt=now; service.save(edit,before.version());
            service.events.publish(ActivityEvent.Kind.ActivityFinished,before.id(),Map.of("activityId",before.id())); return 1;
        });
        return count;
    }
}
