package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.activities.domain.ActivityEvent;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ActivityEvents {
    private final EventPublisher publisher; private final ActivityContext context;
    public ActivityEvents(EventPublisher publisher,ActivityContext context) { this.publisher=publisher; this.context=context; }
    public void publish(ActivityEvent.Kind kind,String id,Map<String,Object> payload) {
        var user=CurrentUser.current();
        publisher.publish(new ActivityEvent(kind,TenantContext.require(),id,context.clock.instant(),payload,context.actor(),
                context.impersonated()?user.impersonation().memberId():null,user==null?DomainEvent.Origin.SYSTEM:user.origin()));
    }
    /**
     * An event of a change the system makes on its own (S07 §5 FIFO promotion, E5-T11): envelope origin SYSTEM, no
     * actor and no impersonation, whoever's request triggered it (that request's own event carries the actor).
     */
    public void publishBySystem(ActivityEvent.Kind kind,String id,Map<String,Object> payload) {
        publisher.publish(new ActivityEvent(kind,TenantContext.require(),id,context.clock.instant(),payload,null,null,DomainEvent.Origin.SYSTEM));
    }
}
