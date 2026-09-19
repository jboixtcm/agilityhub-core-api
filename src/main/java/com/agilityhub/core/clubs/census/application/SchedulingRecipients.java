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
    public Optional<Dog> dog(String id) { return access.dogs.findById(id).map(d -> new Dog(d.id,d.name,d.levelId)); }
}
