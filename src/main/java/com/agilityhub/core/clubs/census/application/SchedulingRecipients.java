package com.agilityhub.core.clubs.census.application;

import java.util.*;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/** Tenant-scoped, minimal census projection for scheduling previews and notifications. */
@Service
public class SchedulingRecipients {
    public record Member(String id,String accountId,String name,String email,List<String> phones,String locale) { }
    public record Dog(String id,String name,String levelId) { }
    private final CensusAccess access;
    public SchedulingRecipients(CensusAccess access) { this.access=access; }
    public Optional<Member> member(String id) {
        return access.members.findById(id).filter(m -> m.erasedAt==null).map(m -> new Member(m.id,m.accountId,
                java.util.stream.Stream.of(m.firstName,m.lastName1,m.lastName2).filter(Objects::nonNull).collect(java.util.stream.Collectors.joining(" ")),
                rows(m.contactEmails).stream().map(e -> string(e.get("email"))).filter(Objects::nonNull).findFirst().orElse(null),
                rows(m.phones).stream().map(p -> p.get("number")==null?null:Objects.toString(p.get("prefix"),"")+p.get("number")).filter(Objects::nonNull).distinct().toList(),
                string(map(m.signup).getOrDefault("locale",access.config().club().defaultLocale()))));
    }
    public record Person(String firstName,String gender) { }
    /** First name (and gender, for the agreement of the D1 labels) for the short staff labels («Pau + Blat»). */
    public Optional<Person> person(String id) { return access.members.findById(id).filter(m -> m.erasedAt==null).map(m -> new Person(m.firstName,m.gender)); }
    /**
     * S15 R-15-11 N-33 audience: ACTIVE members with at least one ACTIVE dog and an app account (APP + PUSH only),
     * with the account's language falling back to the signup one.
     */
    public List<Member> activeWithActiveDog() {
        var owners = new HashSet<String>();
        access.dogs.matching(org.springframework.data.mongodb.core.query.Criteria.where("status").is("ACTIVE")).forEach(d -> owners.add(d.memberId));
        return access.members.matching(org.springframework.data.mongodb.core.query.Criteria.where("status").is("ACTIVE").and("erasedAt").is(null)).stream()
                .filter(m -> owners.contains(m.id)).map(m -> member(m.id).orElse(null)).filter(Objects::nonNull).toList();
    }
    public Optional<Dog> dog(String id) { return access.dogs.findById(id).map(d -> new Dog(d.id,d.name,d.levelId)); }
    /** {@link #person} of many members in one `$in` read (the D1 risk card, E5-T10); erased and unknown ids are absent. */
    public Map<String,Person> people(Collection<String> ids) {
        var result=new HashMap<String,Person>(); if(ids.isEmpty()) return result;
        access.members.matching(org.springframework.data.mongodb.core.query.Criteria.where("_id").in(ids)).stream().filter(m -> m.erasedAt==null)
                .forEach(m -> result.put(m.id,new Person(m.firstName,m.gender)));
        return result;
    }
    /** {@link #dog} of many dogs in one `$in` read (the D1 risk card, E5-T10); unknown ids are absent. */
    public Map<String,Dog> dogs(Collection<String> ids) {
        var result=new HashMap<String,Dog>(); if(ids.isEmpty()) return result;
        access.dogs.matching(org.springframework.data.mongodb.core.query.Criteria.where("_id").in(ids)).forEach(d -> result.put(d.id,new Dog(d.id,d.name,d.levelId)));
        return result;
    }
}
