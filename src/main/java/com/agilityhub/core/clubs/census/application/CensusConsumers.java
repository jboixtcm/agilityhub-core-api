package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.CensusEvent;
import com.agilityhub.core.clubs.census.persistence.*;
import com.agilityhub.core.identity.application.CensusIdentityService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.data.mongodb.core.query.Criteria;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/** Durable handler bean names are the outbox's per-consumer checkpoints. */
@Configuration
public class CensusConsumers {
    @Bean DomainEventHandler<CensusEvent> censusLastClassDog(CensusAccess access) { return handler("BookingCreated", event -> remember(access, event, false)); }
    @Bean DomainEventHandler<CensusEvent> censusLastTrainingDog(CensusAccess access) { return handler("TrainingBooked", event -> remember(access, event, true)); }
    @Bean DomainEventHandler<CensusEvent> censusMemberLeft(CensusAccess access, DogService dogs, ClubClock clock) {
        return handler("MemberStatusChanged", event -> {
            if (!"LEFT".equals(event.payload().get("after"))) { return; }
            Object date = event.payload().get("effectiveDate");
            if (date != null && LocalDate.parse(date.toString()).isAfter(clock.today(event.clubId()))) { return; }
            var member = access.members.findById(string(event.payload().get("memberId"))).orElse(null);
            if (member == null || member.erasedAt != null || !"LEFT".equals(member.status)) { return; }
            for (var dog : access.dogs.matching(Criteria.where("memberId").is(member.id).and("status").ne("INACTIVE"))) { dogs.automaticDeactivation(dog.id, "MEMBER_LEFT"); }
        });
    }
    @Bean DomainEventHandler<CensusEvent> censusSignupRejected(CensusAccess access, DogService dogs) {
        return handler("SignupRejected", event -> {
            var member = access.members.findById(string(event.payload().get("memberId"))).orElse(null);
            if (member == null || member.erasedAt != null || "ACTIVE".equals(member.status)) { return; }
            for (var dog : access.dogs.matching(Criteria.where("memberId").is(member.id).and("status").is("PENDING"))) { dogs.automaticDeactivation(dog.id, "SIGNUP_REJECTED"); }
        });
    }
    @Bean DomainEventHandler<CensusEvent> censusDogDocuments(DocumentService documents) {
        return handler("DogRegistered", event -> documents.registered(string(event.payload().get("dogId"))));
    }
    @Bean DomainEventHandler<CensusEvent> censusDogFreeTraining(CensusAccess access, DogService dogs) {
        return handler("DogLevelChanged", event -> {
            var dog = access.dogs.findById(string(event.payload().get("dogId"))).orElse(null);
            if (dog == null || dog.freeTrainingOverride != null || access.members.require(dog.memberId).erasedAt != null) { return; }
            boolean before = dog.freeTrainingAllowed != null ? dog.freeTrainingAllowed : Boolean.TRUE.equals(access.references.level(string(event.payload().get("before"))).get("grantsFreeTraining")) && access.levels();
            recalculate(access, dogs, dog, before);
        });
    }
    @Bean DomainEventHandler<CensusEvent> censusLevelFreeTraining(CensusAccess access, DogService dogs) {
        return handler("LevelChanged", event -> {
            var diff = map(event.payload().get("diff"));
            if (!diff.containsKey("grantsFreeTraining") && !diff.containsKey("active")) { return; }
            for (var dog : access.dogs.matching(Criteria.where("levelId").is(event.payload().get("id")).and("freeTrainingOverride").is(null))) {
                if (access.members.require(dog.memberId).erasedAt != null) { continue; }
                boolean before = dog.freeTrainingAllowed != null ? dog.freeTrainingAllowed : diff.containsKey("grantsFreeTraining") ? access.levels() && Boolean.TRUE.equals(map(diff.get("grantsFreeTraining")).get("before")) : access.free(dog).allowed();
                recalculate(access, dogs, dog, before);
            }
        });
    }
    @Bean DomainEventHandler<CensusEvent> censusAccessResent(CensusIdentityService identities) {
        return new DomainEventHandler<>() {
            public String eventType() { return "AccessResent"; }
            public Class<CensusEvent> eventClass() { return CensusEvent.class; }
            public void handle(String id, CensusEvent event) {
                try (var tenant = TenantContext.open(event.clubId())) { identities.sendAccess(id, string(event.payload().get("accountId"))); }
            }
        };
    }
    private void recalculate(CensusAccess access, DogService dogs, Dog dog, boolean before) {
        boolean after = access.free(dog).allowed();
        if (!Objects.equals(dog.freeTrainingAllowed, after)) { dog.freeTrainingAllowed = after; access.dogs.save(dog); }
        dogs.freeEvent(dog, before);
    }
    private void remember(CensusAccess access, CensusEvent event, boolean training) {
        if (training && !access.enabled(Module.FREE_TRAINING)) { return; }
        var member = access.members.findById(string(event.payload().get("memberId"))).orElse(null);
        var dog = access.dogs.findById(string(event.payload().get("dogId"))).orElse(null);
        if (member == null || member.erasedAt != null || dog == null || !"ACTIVE".equals(dog.status) || !"ACTIVE".equals(member.status)) { return; }
        if (!member.id.equals(dog.memberId)) {
            var group = member.familyGroupId == null ? null : access.groups.findById(member.familyGroupId).orElse(null);
            if (!access.enabled(Module.FAMILY_GROUP) || group == null || !"ACTIVE".equals(group.status) || !group.memberIds.contains(dog.memberId)) { return; }
        }
        if (dog.id.equals(training ? member.lastDogForTraining : member.lastDogForClass)) { return; }
        if (training) { member.lastDogForTraining = dog.id; } else { member.lastDogForClass = dog.id; }
        access.members.save(member);
    }
    private DomainEventHandler<CensusEvent> handler(String type, java.util.function.Consumer<CensusEvent> work) {
        return new DomainEventHandler<>() {
            public String eventType() { return type; }
            public Class<CensusEvent> eventClass() { return CensusEvent.class; }
            public void handle(String id, CensusEvent event) { try (var tenant = TenantContext.open(event.clubId())) { work.accept(event); } }
        };
    }
}
