package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.activities.domain.*;
import com.agilityhub.core.shared.application.*;
import java.util.*;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
public class ActivityConsumers {
    @Bean("activities.member-left") DomainEventHandler<ActivityExternalEvent> memberLeft(ActivityRegistrationService service) {
        return external("MemberStatusChanged",event -> {
            if(Set.of("LEFT","SUSPENDED").contains(Objects.toString(event.payload().get("after"),"")))
                {
                String memberId=Objects.toString(event.payload().get("memberId"),event.aggregateId());
                if(Set.of("LEFT","SUSPENDED").contains(service.context.members.member(memberId).status())) service.cancelForMemberLeft(memberId);
            }
        });
    }
    @Bean("activities.parameter-cache") DomainEventHandler<ActivityExternalEvent> parameterCache(PublicActivityService service) {
        var handler=external("ParameterChanged",event -> service.invalidate(event.clubId()));
        return new DomainEventHandler<>() {
            public String eventType() { return handler.eventType(); } public Class<ActivityExternalEvent> eventClass() { return handler.eventClass(); }
            public void handle(String id,ActivityExternalEvent event) throws Exception { handler.handle(id,event); }
            @Override public boolean evictsAfterCommit() { return true; }
        };
    }
    @Bean("activities.published-cache") DomainEventHandler<ActivityEvent> published(PublicActivityService service) { return own("ActivityPublished",service); }
    @Bean("activities.updated-cache") DomainEventHandler<ActivityEvent> updated(PublicActivityService service) { return own("ActivityUpdated",service); }
    @Bean("activities.cancelled-cache") DomainEventHandler<ActivityEvent> cancelled(PublicActivityService service) { return own("ActivityCancelled",service); }
    @Bean("activities.finished-cache") DomainEventHandler<ActivityEvent> finished(PublicActivityService service) { return own("ActivityFinished",service); }
    @Bean("activities.registration-cache") DomainEventHandler<ActivityEvent> registration(PublicActivityService service) { return own("ActivityRegistrationChanged",service); }
    private DomainEventHandler<ActivityEvent> own(String type,PublicActivityService service) {
        return new DomainEventHandler<>() {
            public String eventType() { return type; } public Class<ActivityEvent> eventClass() { return ActivityEvent.class; }
            public void handle(String id,ActivityEvent event) { service.invalidate(event.clubId()); }
            // E3-T09 step 5: the public page of an activity just published, changed or cancelled is fresh right after the commit.
            @Override public boolean evictsAfterCommit() { return true; }
        };
    }
    private DomainEventHandler<ActivityExternalEvent> external(String type,java.util.function.Consumer<ActivityExternalEvent> work) {
        return new DomainEventHandler<>() {
            public String eventType() { return type; } public Class<ActivityExternalEvent> eventClass() { return ActivityExternalEvent.class; }
            public void handle(String id,ActivityExternalEvent event) { try(var tenant=TenantContext.open(event.clubId())) { work.accept(event); } }
        };
    }
}
