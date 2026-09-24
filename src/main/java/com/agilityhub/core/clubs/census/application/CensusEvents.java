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
    /**
     * R-14-09 (E3-T09): the diff of an event payload masked exactly like the audit: the same changes and the same masking of
     * the fields annotated `@Sensitive` in the snapshots (identity document, IBAN, holder tax id). A change at path
     * `a.b` is nested as `diff.a.b = {before, after}` (Mongo keys cannot hold dots).
     */
    @SuppressWarnings("unchecked")
    public Map<String,Object> maskedDiff(Map<String,Object> before, Map<String,Object> after) {
        var result = new LinkedHashMap<String,Object>();
        for (var change : com.agilityhub.core.platform.application.audit.AuditMasking.changes(before, after)) {
            Map<String,Object> node = result; var keys = change.path().split("\\.");
            for (int i = 0; i < keys.length - 1; i++) { node = (Map<String,Object>) node.computeIfAbsent(keys[i], key -> new LinkedHashMap<String,Object>()); }
            var values = new LinkedHashMap<String,Object>(); values.put("before", change.before()); values.put("after", change.after()); node.put(keys[keys.length - 1], values);
        }
        return result;
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
