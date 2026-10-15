package com.agilityhub.core.courses.application;

import com.agilityhub.core.shared.application.DomainEventHandler;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * S16 §7, consumed: `RingChanged{deactivated}` (S05) → the ring's active setup is `DISMANTLED` by the system, its
 * `activeSetupId` cleared and `RingSetupChanged{DISMANTLED}` published (E9-T03). A ring is deactivated when the catalog event's
 * `diff.active.after` is `false`. Idempotent: a ring without an active setup changes nothing.
 */
@Configuration(proxyBeanMethods = false)
public class RingSetupConsumers {
    /** The read-only envelope of S05's catalog event (never published from here). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RingEvent(String type, String clubId, String aggregateType, String aggregateId, Instant occurredAt, Map<String, Object> payload,
            String actorAccountId, String impersonatedMemberId, DomainEvent.Origin origin) { }

    @Bean DomainEventHandler<RingEvent> coursesRingChanged(RingSetupService setups) {
        return new DomainEventHandler<>() {
            @Override public String eventType() { return "RingChanged"; }
            @Override public Class<RingEvent> eventClass() { return RingEvent.class; }
            @Override public void handle(String eventId, RingEvent event) {
                if (event.clubId() == null || !deactivated(event.payload())) { return; }
                try (var tenant = TenantContext.open(event.clubId())) { setups.ringDeactivated(event.aggregateId()); }
            }
        };
    }
    static boolean deactivated(Map<String, Object> payload) {
        return payload != null && payload.get("diff") instanceof Map<?, ?> diff && diff.get("active") instanceof Map<?, ?> active
                && Boolean.FALSE.equals(active.get("after"));
    }
}
