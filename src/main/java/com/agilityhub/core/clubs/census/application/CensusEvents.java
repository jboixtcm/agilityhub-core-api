package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.CensusEvent;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CensusEvents {
    private final EventPublisher publisher; private final AuditActorProvider actors; private final Clock clock;
    public CensusEvents(EventPublisher publisher, AuditActorProvider actors, Clock clock) { this.publisher = publisher; this.actors = actors; this.clock = clock; }
    public void emit(String type, String entity, String id, Map<String,Object> payload) {
        var actor = actors.current(); var user = CurrentUser.current();
        publisher.publish(new CensusEvent(type, TenantContext.require(), entity, id, clock.instant(), payload,
                actor.accountId(), actor.impersonatedMemberId(), user == null ? DomainEvent.Origin.SYSTEM : user.origin()));
    }
    public Map<String,Object> diff(Map<String,Object> before, Map<String,Object> after) {
        var result = new LinkedHashMap<String,Object>(); var keys = new LinkedHashSet<>(before.keySet()); keys.addAll(after.keySet());
        for (String key : keys) {
            if (!Objects.equals(before.get(key), after.get(key))) {
                var change = new LinkedHashMap<String,Object>(); change.put("before", before.get(key)); change.put("after", after.get(key)); result.put(key, change);
            }
        }
        return result;
    }
}
