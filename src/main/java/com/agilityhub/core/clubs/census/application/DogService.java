package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.Dog;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

@Service
public class DogService {
    private final CensusAccess access; private final CensusValidation validation; private final CensusEvents events;
    private final Clock clock; private final ClubClock clubClock;
    public DogService(CensusAccess access, CensusValidation validation, CensusEvents events, Clock clock, ClubClock clubClock) {
        this.access = access; this.validation = validation; this.events = events; this.clock = clock; this.clubClock = clubClock;
    }
    public String owner(String id) { return access.dogs.require(id).memberId; }
    @Transactional
    @Audited(action = AuditAction.DOG_UPDATED, entityType = "'Dog'", entity = "#id", member = "owner(#id)")
    public void patch(String id, Map<String,Object> request) {
        var dog = access.mutableDog(id); allow(request, Set.of("name", "breed", "sex", "birthDate", "chip", "handlerName", "licenses", "version"));
        version(dog.version(), request.get("version")); var before = fields(dog);
        if (request.containsKey("name")) { dog.name = text(request.get("name"), "name", 40, true); }
        if (request.containsKey("breed")) { dog.breed = text(request.get("breed"), "breed", 60, true); }
        if (request.containsKey("sex")) {
            if (!Set.of("MALE", "FEMALE").contains(String.valueOf(request.get("sex")))) { throw invalid("sex", "INVALID_VALUE"); }
            dog.sex = string(request.get("sex"));
        }
        if (request.containsKey("birthDate")) {
            try { dog.birthDate = LocalDate.parse(string(request.get("birthDate"))); }
            catch (RuntimeException badDate) { throw invalid("birthDate", "INVALID_VALUE"); }
            if (dog.birthDate.isAfter(clubClock.today(TenantContext.require()))) { throw invalid("birthDate", "INVALID_VALUE"); }
        }
        if (request.containsKey("chip")) { dog.chip = text(request.get("chip"), "chip", 20, false); if (dog.chip != null && dog.chip.isEmpty()) { dog.chip = null; } }
        if (request.containsKey("handlerName")) { dog.handlerName = text(request.get("handlerName"), "handlerName", 80, false); if ("".equals(dog.handlerName)) { dog.handlerName = null; } }
        if (request.containsKey("licenses")) { dog.licenses = validation.licenses(request.get("licenses")); }
        var diff = events.diff(before, fields(dog)); if (diff.isEmpty()) { return; }
        access.dogs.save(dog); events.emit("DogUpdated", "Dog", id, object("dogId", id, "memberId", dog.memberId, "diff", diff));
    }
    private Map<String,Object> fields(Dog dog) {
        return object("name", dog.name, "breed", dog.breed, "sex", dog.sex, "birthDate", dog.birthDate, "chip", dog.chip,
                "handlerName", dog.handlerName, "licenses", dog.licenses);
    }
    @Transactional
    @Audited(action = AuditAction.DOG_LEVEL_CHANGED, entityType = "'Dog'", entity = "#id", member = "owner(#id)")
    public void level(String id, String levelId) {
        var dog = access.mutableDog(id); if (!access.levels()) { throw new ApiException(ErrorCode.LEVELS_DISABLED); }
        access.references.lockLevelCatalog(); var level = access.references.level(levelId);
        if (!Boolean.TRUE.equals(level.get("active"))) { throw new ApiException(ErrorCode.LEVEL_NOT_ACTIVE); }
        if (Objects.equals(dog.levelId, levelId)) { throw new ApiException(ErrorCode.LEVEL_UNCHANGED); }
        boolean previous = access.free(dog).allowed(); String old = dog.levelId;
        var history = new ArrayList<Map<String,Object>>();
        for (var row : rows(dog.levelHistory)) { var closed = new LinkedHashMap<>(row); if (closed.get("to") == null) { closed.put("to", clock.instant()); } history.add(closed); }
        if (history.isEmpty() && old != null) { history.add(object("levelId", old, "from", dog.levelAssignedAt == null ? dog.registeredAt : dog.levelAssignedAt, "to", clock.instant())); }
        history.add(object("levelId", levelId, "from", clock.instant(), "byAccountId", CurrentUser.current().accountId()));
        dog.levelHistory = history; dog.levelId = levelId; dog.levelAssignedAt = clock.instant();
        dog.freeTrainingAllowed = access.free(dog).allowed(); access.dogs.save(dog);
        events.emit("DogLevelChanged", "Dog", id, object("dogId", id, "before", old, "after", levelId));
        freeEvent(dog, previous);
    }
    @Transactional
    @Audited(action = AuditAction.DOG_FREE_TRAINING_CHANGED, entityType = "'Dog'", entity = "#id", member = "owner(#id)")
    public void free(String id, Boolean override) {
        var dog = access.mutableDog(id); access.require(Module.FREE_TRAINING);
        boolean before = access.free(dog).allowed(); if (Objects.equals(dog.freeTrainingOverride, override)) { return; }
        dog.freeTrainingOverride = override; dog.freeTrainingAllowed = access.free(dog).allowed(); access.dogs.save(dog); freeEvent(dog, before);
    }
    void freeEvent(Dog dog, boolean before) {
        var result = access.free(dog);
        if (access.enabled(Module.FREE_TRAINING) && before != result.allowed()) {
            events.emit("DogFreeTrainingChanged", "Dog", dog.id, object("dogId", dog.id, "allowed", result.allowed(), "source", result.source()));
        }
    }
    void noBookings(Dog dog) {
        var future = access.references.futureBookings(dog.id, clock.instant());
        if (!future.isEmpty()) { throw new ApiException(ErrorCode.DOG_HAS_FUTURE_BOOKINGS, object("bookings", future)); }
    }
    @Transactional
    @Audited(action = AuditAction.DOG_DEACTIVATED, entityType = "'Dog'", entity = "#id", member = "owner(#id)")
    public void deactivate(String id, String reason) {
        var dog = access.mutableDog(id); if (!"ACTIVE".equals(dog.status)) { throw new ApiException(ErrorCode.DOG_NOT_ACTIVE); }
        noBookings(dog); deactivate(dog, "CLUB");
    }
    @Transactional
    @Audited(action = AuditAction.DOG_DEACTIVATED, entityType = "'Dog'", entity = "#id", member = "owner(#id)")
    public void automaticDeactivation(String id, String reason) { deactivate(access.mutableDog(id), reason); }
    void deactivate(Dog dog, String reason) {
        dog.status = "INACTIVE"; dog.deactivatedAt = clock.instant(); dog.deactivationReason = reason; access.dogs.save(dog);
        clearSelections(dog.id);
        events.emit("DogDeactivated", "Dog", dog.id, object("dogId", dog.id, "memberId", dog.memberId, "reason", reason));
    }
    void clearSelections(String dogId) {
        for (var member : access.members.matching(new org.springframework.data.mongodb.core.query.Criteria().orOperator(
                org.springframework.data.mongodb.core.query.Criteria.where("lastDogForClass").is(dogId),
                org.springframework.data.mongodb.core.query.Criteria.where("lastDogForTraining").is(dogId)))) {
            if (member.erasedAt != null) { continue; }
            if (dogId.equals(member.lastDogForClass)) { member.lastDogForClass = null; }
            if (dogId.equals(member.lastDogForTraining)) { member.lastDogForTraining = null; }
            access.members.save(member);
        }
    }
    @Transactional
    @Audited(action = AuditAction.DOG_REACTIVATED, entityType = "'Dog'", entity = "#id", member = "owner(#id)")
    public void reactivate(String id) {
        var dog = access.mutableDog(id);
        if (!"ACTIVE".equals(access.members.require(dog.memberId).status)) { throw new ApiException(ErrorCode.TARGET_MEMBER_NOT_ACTIVE); }
        if (!"INACTIVE".equals(dog.status)) { throw new ApiException(ErrorCode.DOG_NOT_ACTIVE); }
        if (access.levels() && !Boolean.TRUE.equals(access.references.level(dog.levelId).get("active"))) { throw new ApiException(ErrorCode.LEVEL_NOT_ACTIVE); }
        dog.status = "ACTIVE"; dog.deactivatedAt = null; dog.deactivationReason = null; access.dogs.save(dog);
        events.emit("DogUpdated", "Dog", id, object("dogId", id, "memberId", dog.memberId, "diff", object("status", object("before", "INACTIVE", "after", "ACTIVE"))));
    }
    @Transactional
    @Audited(action = AuditAction.DOG_UPDATED, entityType = "'Dog'", entity = "#id", member = "owner(#id)")
    public void note(String id, String text) {
        access.require(Module.TASKS); var dog = access.ownDog(id, true);
        if (CurrentUser.current().impersonation() == null && (access.role("ADMIN") || access.role("INSTRUCTOR"))) { throw new ApiException(ErrorCode.FORBIDDEN); }
        if (text == null || text.length() > 2000) { throw invalid("text", "INVALID_VALUE"); }
        if (text.equals(map(dog.instructorNote).getOrDefault("text", ""))) { return; }
        dog.instructorNote = object("text", text, "updatedAt", clock.instant(), "updatedByAccountId", CurrentUser.current().accountId());
        access.dogs.save(dog); events.emit("MemberNoteChanged", "Dog", id, object("dogId", id, "memberId", dog.memberId));
    }
}
