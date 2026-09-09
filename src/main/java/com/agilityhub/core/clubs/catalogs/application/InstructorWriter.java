package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.domain.InstructorChanged;
import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstructorWriter {
    private final InstructorRepository instructors;
    private final EventPublisher events;
    private final AuditActorProvider actors;
    private final Clock clock;
    public InstructorWriter(InstructorRepository instructors, EventPublisher events, AuditActorProvider actors, Clock clock) {
        this.instructors = instructors; this.events = events; this.actors = actors; this.clock = clock;
    }
    public Map<String, Object> fields(Instructor item) {
        return item == null ? Map.of() : Map.of("memberId", item.memberId(), "shortName", item.shortName(), "color", item.color(), "active", item.active());
    }
    @Transactional
    @Audited(action = AuditAction.CATALOG_CHANGED, entityType = "'Instructor'", entity = "#id", before = "#before", member = "#memberId")
    public Map<String, Object> save(String id, String memberId, Map<String, Object> before, Instructor next) {
        var after = fields(next);
        if (before.equals(after)) { return after; }
        if (next == null) { instructors.deleteById(id); } else { instructors.replace(next); }
        Map<String, Object> diff = new LinkedHashMap<>();
        var keys = new LinkedHashSet<>(before.keySet()); keys.addAll(after.keySet());
        for (String key : keys) {
            if (!Objects.equals(before.get(key), after.get(key))) {
                Map<String, Object> change = new LinkedHashMap<>(); change.put("before", before.get(key)); change.put("after", after.get(key)); diff.put(key, change);
            }
        }
        String action = next == null ? "DELETED" : before.isEmpty() ? "CREATED"
                : Objects.equals(before.get("active"), next.active()) ? "UPDATED" : next.active() ? "REACTIVATED" : "DEACTIVATED";
        var actor = actors.current();
        events.publish(new InstructorChanged(TenantContext.require(), id, clock.instant(), Map.of("id", id, "action", action, "diff", diff),
                actor.accountId(), actor.accountId() == null ? DomainEvent.Origin.SYSTEM : DomainEvent.Origin.BACKOFFICE));
        return after;
    }
}
