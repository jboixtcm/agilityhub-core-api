package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.followup.domain.FollowupEvent;
import com.agilityhub.core.clubs.followup.domain.FollowupRules;
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
    /** `TaskUpdated/Deleted/Reopened{taskId, dogId, by{accountId, role}}`. */
    public String task(FollowupEvent.Kind kind, Task task, Task.Actor by) { return publish(kind, task.id(), payload(task, null, null, by)); }
    /**
     * `TaskCreated{taskId, dogId, memberId, textExcerpt, by}`: the owner and the excerpt of that moment. N-20 sends this
     * excerpt, never the task's current text, and only while `memberId` still owns the dog (R-10-10, E64).
     */
    public String created(Task task, Task.Actor by) {
        return publish(FollowupEvent.Kind.TaskCreated, task.id(), payload(task, task.memberId(), FollowupRules.excerpt(task.text()), by));
    }
    /** `TaskCompleted{taskId, dogId, memberId, by}`: `memberId` is the dog's owner at the completion, as the census holds it. */
    public String completed(Task task, String memberId, Task.Actor by) {
        return publish(FollowupEvent.Kind.TaskCompleted, task.id(), payload(task, memberId, null, by));
    }
    private static Map<String, Object> payload(Task task, String memberId, String excerpt, Task.Actor by) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("taskId", task.id()); payload.put("dogId", task.dogId());
        if (memberId != null) { payload.put("memberId", memberId); }
        if (excerpt != null) { payload.put("textExcerpt", excerpt); }
        var actor = new LinkedHashMap<String, Object>(); actor.put("accountId", by.accountId()); actor.put("role", by.role().name());
        payload.put("by", actor);
        return payload;
    }
}
