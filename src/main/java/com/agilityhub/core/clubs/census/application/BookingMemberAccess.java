package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.domain.Money;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/**
 * S08 reads census through this tenant-scoped boundary: members (owner and booker), dogs, the family group that
 * makes a dog accessible (R-08-02, module FAMILY_GROUP), plan terms (R-08-17/18) and the one field S08 writes,
 * `Member.lastDogForClass` (R-08-23).
 */
@Service
public class BookingMemberAccess {
    public record Member(String id, String accountId, String firstName, String displayName, String status, LocalDate leaveDate,
            boolean blocked, String blockReason, String planId, String lastDogForClass, String locale, String email, List<String> phones) { }
    public record Dog(String id, String name, String sex, String memberId, String levelId, String status) {
        public boolean active() { return "ACTIVE".equals(status); }
    }
    public record PlanTerms(String type, String chargeMode, Money price) { }
    private final CensusAccess access;
    public BookingMemberAccess(CensusAccess access) { this.access = access; }

    public Optional<Member> member(String id) {
        if (id == null) { return Optional.empty(); }
        return access.members.findById(id).filter(m -> m.erasedAt == null).map(m -> {
            var block = map(m.bookingBlock);
            return new Member(m.id, m.accountId, m.firstName,
                    java.util.stream.Stream.of(m.firstName, m.lastName1, m.lastName2).filter(Objects::nonNull).collect(java.util.stream.Collectors.joining(" ")),
                    m.status, m.leaveDate, Boolean.TRUE.equals(block.get("active")), string(block.get("reason")), m.planId, m.lastDogForClass,
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
    public Optional<Dog> dog(String id) {
        if (id == null) { return Optional.empty(); }
        return access.dogs.findById(id).map(d -> new Dog(d.id, d.name, d.sex, d.memberId, d.levelId, d.status));
    }
    public List<Dog> dogs(Collection<String> ids) {
        if (ids.isEmpty()) { return List.of(); }
        return access.dogs.matching(Criteria.where("_id").in(ids)).stream().map(d -> new Dog(d.id, d.name, d.sex, d.memberId, d.levelId, d.status)).toList();
    }
    /** The actor's own members plus, with FAMILY_GROUP and an ACTIVE group holding both, the other group members (R-08-02). */
    public Set<String> reachableMembers(String actorMemberId) {
        var result = new LinkedHashSet<String>(); result.add(actorMemberId);
        if (!access.enabled(Module.FAMILY_GROUP)) { return result; }
        var actor = access.members.findById(actorMemberId).orElse(null);
        if (actor == null || actor.familyGroupId == null) { return result; }
        access.groups.findById(actor.familyGroupId).filter(g -> "ACTIVE".equals(g.status) && g.memberIds != null && g.memberIds.contains(actorMemberId))
                .ifPresent(g -> result.addAll(g.memberIds));
        return result;
    }
    public boolean canAccess(String actorMemberId, Dog dog) { return dog != null && reachableMembers(actorMemberId).contains(dog.memberId()); }
    /** Every dog the actor may book for: own and, with FAMILY_GROUP, the group's. */
    public List<Dog> accessibleDogs(String actorMemberId) {
        return access.dogs.matching(Criteria.where("memberId").in(reachableMembers(actorMemberId))).stream()
                .map(d -> new Dog(d.id, d.name, d.sex, d.memberId, d.levelId, d.status)).toList();
    }
    public List<String> dogIds(String memberId) {
        return access.dogs.matching(Criteria.where("memberId").is(memberId)).stream().map(d -> d.id).toList();
    }
    /** `Plan.type`, the single-class charge mode and the member's price (S05/S12), read-only. */
    public PlanTerms planTerms(String memberId) {
        var member = access.members.findById(memberId).orElse(null);
        if (member == null || member.planId == null) { return new PlanTerms(null, null, null); }
        var plan = access.references.plan(member.planId); var price = map(access.references.price(member.priceId).get("amount"));
        Money amount = price.get("amountMinor") == null ? null : new Money(number(price.get("amountMinor")), string(price.get("currency")));
        return new PlanTerms(string(plan.get("type")), string(map(plan.get("singleClass")).get("chargeMode")), amount);
    }
    /** R-08-23: written on each booking of the person who booked. */
    public void lastDogForClass(String memberId, String dogId) { access.members.setField(memberId, "lastDogForClass", dogId); }
}
