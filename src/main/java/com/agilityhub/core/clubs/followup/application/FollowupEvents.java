package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.followup.domain.FollowupEvent;
import com.agilityhub.core.clubs.followup.persistence.Task;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * S10 §7 follow-up events through the outbox, in the writing transaction. The envelope's actor is the one who acted (the
 * admin behind an impersonation, with the member as `impersonatedMemberId`); the payload's `by` is the task's actor.
 */
@Component
public class FollowupEvents {
    private final EventPublisher publisher; private final Clock clock;
    public FollowupEvents(EventPublisher publisher, Clock clock) { this.publisher = publisher; this.clock = clock; }

    public String publish(FollowupEvent.Kind kind, String aggregateId, Map<String, Object> payload) {
        var user = CurrentUser.current();
        var impersonation = user == null ? null : user.impersonation();
        return publisher.publish(new FollowupEvent(kind, TenantContext.require(), aggregateId, clock.instant(), payload,
                user == null ? null : impersonation == null ? user.accountId() : impersonation.actorAccountId(),
                impersonation == null ? null : impersonation.memberId(), user == null ? DomainEvent.Origin.SYSTEM : user.origin()));
    }
    /** `TaskCreated/Updated/Deleted/Completed/Reopened{taskId, dogId, by{accountId, role}}` (+ `memberId` on Created and Completed). */
    public String task(FollowupEvent.Kind kind, Task task, Task.Actor by) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("taskId", task.id()); payload.put("dogId", task.dogId());
        if (kind == FollowupEvent.Kind.TaskCreated || kind == FollowupEvent.Kind.TaskCompleted) { payload.put("memberId", task.memberId()); }
        var actor = new LinkedHashMap<String, Object>(); actor.put("accountId", by.accountId()); actor.put("role", by.role().name());
        payload.put("by", actor);
        return publish(kind, task.id(), payload);
    }
}
