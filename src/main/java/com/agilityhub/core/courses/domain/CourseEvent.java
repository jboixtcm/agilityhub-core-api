package com.agilityhub.core.courses.domain;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;
/** S16 §7 / E77 event envelope; behavior tasks publish through the transactional outbox. */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record CourseEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt,
        Map<String,Object> payload, String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    public CourseEvent { payload = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload)); }
    @Override public String type() { return kind.name(); }
    @Override public String aggregateType() { return kind.aggregateType; }
    public enum Kind {
        CourseCreated("Course"),
        CourseUpdated("Course"),
        CourseImported("Course"),
        CourseDeleted("Course"),
        CourseCopied("Course"),
        RingGeometryChanged("Ring"),
        PlacementCreated("Placement"),
        PlacementUpdated("Placement"),
        RingSetupChanged("RingSetup"),
        BuildSessionStarted("BuildSession"),
        BuildSessionProgress("BuildSession"),
        BuildSessionFinished("BuildSession"),
        InventoryChanged("ObstacleInventory");
        private final String aggregateType;
        Kind(String aggregateType) { this.aggregateType = aggregateType; }
    }
}
