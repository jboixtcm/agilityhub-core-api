package com.agilityhub.core.clubs.dashboard.application;

import com.agilityhub.core.shared.application.DomainEventHandler;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.*;

/** Durable outbox subscriptions. Local cache eviction occurs only after consumer commit. */
@Configuration(proxyBeanMethods = false)
public class DashboardEvents {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(String type, String clubId, String aggregateType, String aggregateId, Instant occurredAt,
            Map<String, Object> payload, String actorAccountId, String impersonatedMemberId, DomainEvent.Origin origin) implements DomainEvent { }
    @Bean DomainEventHandler<Event> dashboardSignupSubmitted(DashboardQuery query) { return handler("SignupSubmitted", query); }
    @Bean DomainEventHandler<Event> dashboardMemberValidated(DashboardQuery query) { return handler("MemberValidated", query); }
    @Bean DomainEventHandler<Event> dashboardSignupRejected(DashboardQuery query) { return handler("SignupRejected", query); }
    @Bean DomainEventHandler<Event> dashboardClassCancelledByClub(DashboardQuery query) { return handler("ClassCancelledByClub", query); }
    @Bean DomainEventHandler<Event> dashboardClassAutoCancelled(DashboardQuery query) { return handler("ClassAutoCancelled", query); }
    @Bean DomainEventHandler<Event> dashboardClassAtRisk(DashboardQuery query) { return handler("ClassAtRisk", query); }
    @Bean DomainEventHandler<Event> dashboardClubModulesChanged(DashboardQuery query) { return handler("ClubModulesChanged", query); }
    @Bean DomainEventHandler<Event> dashboardParameterChanged(DashboardQuery query) { return handler("ParameterChanged", query); }
    // Add-dog validation emits DogRegistered; census/config edits can change visible rows too.
    @Bean DomainEventHandler<Event> dashboardDogRegistered(DashboardQuery query) { return handler("DogRegistered", query); }
    @Bean DomainEventHandler<Event> dashboardSignupEdited(DashboardQuery query) { return handler("SignupEdited", query); }
    @Bean DomainEventHandler<Event> dashboardMemberStatusChanged(DashboardQuery query) { return handler("MemberStatusChanged", query); }
    @Bean DomainEventHandler<Event> dashboardLevelChanged(DashboardQuery query) { return handler("LevelChanged", query); }
    @Bean DomainEventHandler<Event> dashboardClubUpdated(DashboardQuery query) { return handler("ClubUpdated", query); }
    private DomainEventHandler<Event> handler(String type, DashboardQuery query) {
        return new DomainEventHandler<>() {
            public String eventType() { return type; }
            public Class<Event> eventClass() { return Event.class; }
            public void handle(String eventId, Event event) { query.invalidateAfterCommit(event.clubId()); }
        };
    }
}
