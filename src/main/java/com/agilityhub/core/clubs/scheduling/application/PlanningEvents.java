package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.shared.application.DomainEventHandler;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.*;

/** Durable outbox subscriptions. Local cache eviction occurs only after consumer commit. */
@Configuration(proxyBeanMethods = false)
public class PlanningEvents {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(String type, String clubId, String aggregateType, String aggregateId, Instant occurredAt,
            Map<String, Object> payload, String actorAccountId, String impersonatedMemberId, DomainEvent.Origin origin) implements DomainEvent { }
    @Bean DomainEventHandler<Event> planningWeekTemplateChanged(TemplateQuery query) { return handler("WeekTemplateChanged", query); }
    @Bean DomainEventHandler<Event> planningLevelChanged(TemplateQuery query) { return handler("LevelChanged", query); }
    @Bean DomainEventHandler<Event> planningRingChanged(TemplateQuery query) { return handler("RingChanged", query); }
    @Bean DomainEventHandler<Event> planningInstructorChanged(TemplateQuery query) { return handler("InstructorChanged", query); }
    @Bean DomainEventHandler<Event> planningParameterChanged(TemplateQuery query) { return handler("ParameterChanged", query); }
    private DomainEventHandler<Event> handler(String type, TemplateQuery query) {
        return new DomainEventHandler<>() {
            public String eventType() { return type; }
            public Class<Event> eventClass() { return Event.class; }
            public void handle(String eventId, Event event) { query.invalidateAfterCommit(event.clubId()); }
        };
    }
}
