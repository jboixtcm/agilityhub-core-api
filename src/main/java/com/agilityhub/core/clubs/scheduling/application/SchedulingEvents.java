package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.domain.SchedulingEvent;
import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SchedulingEvents {
    private final EventPublisher publisher; private final AuditActorProvider actors; private final Clock clock;
    public SchedulingEvents(EventPublisher publisher, AuditActorProvider actors, Clock clock) { this.publisher = publisher; this.actors = actors; this.clock = clock; }
    public String actor() { return actors.current().accountId(); }
    public void publish(SchedulingEvent.Kind kind, String id, Map<String, Object> payload) {
        var actor = actors.current(); var user = CurrentUser.current();
        publisher.publish(new SchedulingEvent(kind, TenantContext.require(), id, clock.instant(), payload, actor.accountId(), actor.impersonatedMemberId(),
                user == null ? DomainEvent.Origin.SYSTEM : user.origin()));
    }
}
