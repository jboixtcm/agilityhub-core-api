package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.platform.application.Module;
import java.util.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/**
 * S09 reads census through this tenant-scoped boundary: dogs with their free-training fields, members (booker
 * conditions of R-09-08, contact data for N-06/N-07/N-47), the family group that widens the eligible dogs (R-09-01)
 * and the fields S09 writes: `Member.lastDogForTraining` (R-09-09) and the technical `trainingSeq` counters (R-09-06).
 */
@Service
public class TrainingMemberAccess {
    public record Dog(String id, String name, String memberId, String levelId, String status, Boolean freeTrainingOverride) {
        public boolean active() { return "ACTIVE".equals(status); }
    }
    public record Member(String id, String accountId, String firstName, String displayName, String status, boolean blocked, String blockReason,
            String lastDogForTraining, String locale, String email, List<String> phones) {
        public boolean active() { return "ACTIVE".equals(status); }
    }
    private final CensusAccess access; private final BookingMemberAccess members;
    public TrainingMemberAccess(CensusAccess access, BookingMemberAccess members) { this.access = access; this.members = members; }

    public Optional<Member> member(String id) {
        if (id == null) { return Optional.empty(); }
        return access.members.findById(id).filter(m -> m.erasedAt == null).map(m -> {
            var block = map(m.bookingBlock);
            return new Member(m.id, m.accountId, m.firstName,
                    java.util.stream.Stream.of(m.firstName, m.lastName1, m.lastName2).filter(Objects::nonNull).collect(java.util.stream.Collectors.joining(" ")),
                    m.status, Boolean.TRUE.equals(block.get("active")), string(block.get("reason")), m.lastDogForTraining,
                    string(map(m.signup).getOrDefault("locale", access.config().club().defaultLocale())),
                    rows(m.contactEmails).stream().map(e -> string(e.get("email"))).filter(Objects::nonNull).findFirst().orElse(null),
                    rows(m.phones).stream().map(p -> p.get("number") == null ? null : Objects.toString(p.get("prefix"), "") + p.get("number"))
                            .filter(Objects::nonNull).distinct().toList());
        });
    }
    public Optional<Member> memberByAccount(String accountId) {
        if (accountId == null) { return Optional.empty(); }
        return access.members.matching(Criteria.where("accountId").is(accountId).and("erasedAt").is(null)).stream().findFirst().flatMap(m -> member(m.id));
    }
    /** First names by member id (the «Pau + Blat» staff labels of R-09-12). */
    public Map<String, String> firstNames(Collection<String> ids) {
        var result = new HashMap<String, String>(); if (ids.isEmpty()) { return result; }
        access.members.matching(Criteria.where("_id").in(ids)).forEach(m -> result.put(m.id, Objects.toString(m.firstName, "")));
        return result;
    }
    public Optional<Dog> dog(String id) { return id == null ? Optional.empty() : access.dogs.findById(id).map(TrainingMemberAccess::view); }
    public List<Dog> dogs(Collection<String> ids) {
        if (ids.isEmpty()) { return List.of(); }
        return access.dogs.matching(Criteria.where("_id").in(ids)).stream().map(TrainingMemberAccess::view).toList();
    }
    /**
     * R-09-01: the member's own dogs and, with FAMILY_GROUP and an ACTIVE group holding both, the dogs of the other
     * group members whose `Member.status` is ACTIVE. The first element of each pair tells whether the dog is own.
     */
    public List<Map.Entry<Dog, Member>> reachableDogs(String memberId) {
        var reachable = members.reachableMembers(memberId);
        var owners = new HashMap<String, Member>();
        for (String id : reachable) { member(id).ifPresent(m -> owners.put(id, m)); }
        var result = new ArrayList<Map.Entry<Dog, Member>>();
        for (var dog : access.dogs.matching(Criteria.where("memberId").in(reachable))) {
            var owner = owners.get(dog.memberId);
            if (owner == null || !dog.memberId.equals(memberId) && !owner.active()) { continue; }
            result.add(Map.entry(view(dog), owner));
        }
        return result;
    }
    public boolean familyGroups() { return access.enabled(Module.FAMILY_GROUP); }
    /** R-09-09: written on each booking of the person who booked. */
    public void lastDogForTraining(String memberId, String dogId) { access.members.setField(memberId, "lastDogForTraining", dogId); }
    /** R-09-06 step 1: `$inc` of `Dog.trainingSeq` (unit DOG) or `Member.trainingSeq` (unit MEMBER) serialises the weekly counter. */
    public void touchTrainingSeq(boolean dogUnit, String id) {
        if (dogUnit) { access.dogs.increment(id, "trainingSeq"); } else { access.members.increment(id, "trainingSeq"); }
    }
    /** ACTIVE members and the ACTIVE dogs they own (S16's N-31 audience is computed from these in S09). */
    public List<Dog> activeDogs() { return access.dogs.matching(Criteria.where("status").is("ACTIVE")).stream().map(TrainingMemberAccess::view).toList(); }
    public List<String> activeMembers() {
        return access.members.matching(Criteria.where("status").is("ACTIVE").and("erasedAt").is(null)).stream().map(m -> m.id).toList();
    }
    public Set<String> reachableMembers(String memberId) { return members.reachableMembers(memberId); }
    private static Dog view(com.agilityhub.core.clubs.census.persistence.Dog d) {
        return new Dog(d.id, d.name, d.memberId, d.levelId, d.status, d.freeTrainingOverride);
    }
}
