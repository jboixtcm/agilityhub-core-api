package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.shared.application.FollowupCensusAccess;
import java.util.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/**
 * The census side of {@link FollowupCensusAccess} (S10 follow-up, E6-T03): one `$in` read per collection for a page of D14
 * rows or the recipients of a notification, the member's note, and the observations, written by {@link DogService}.
 * Never any bank data (MATRIU rule 1).
 */
@Service
public class FollowupCensusAdapter implements FollowupCensusAccess {
    private final CensusAccess access; private final DogService dogs;
    public FollowupCensusAdapter(CensusAccess access, DogService dogs) { this.access = access; this.dogs = dogs; }

    @Override public Map<String, Dog> dogsOf(String memberId) {
        return memberId == null ? Map.of() : dogs(access.dogs.matching(Criteria.where("memberId").is(memberId)).stream().map(dog -> dog.id).toList());
    }
    @Override public Map<String, Dog> dogs(Collection<String> dogIds) {
        var result = new LinkedHashMap<String, Dog>(); if (dogIds.isEmpty()) { return result; }
        boolean levels = access.levels(); var codes = new HashMap<String, String>();
        for (var dog : access.dogs.matching(Criteria.where("_id").in(new HashSet<>(dogIds)))) {
            String code = !levels || dog.levelId == null ? null : codes.computeIfAbsent(dog.levelId, id -> string(access.references.level(id).get("code")));
            result.put(dog.id, new Dog(dog.id, dog.name, dog.status, dog.memberId, code));
        }
        return result;
    }
    @Override public Map<String, Member> members(Collection<String> memberIds) {
        var result = new LinkedHashMap<String, Member>(); if (memberIds.isEmpty()) { return result; }
        for (var member : access.members.matching(Criteria.where("_id").in(new HashSet<>(memberIds)))) {
            if (member.erasedAt != null) { continue; }
            result.put(member.id, new Member(member.id, member.firstName, fullName(member), member.gender, member.accountId,
                    rows(member.contactEmails).stream().map(e -> string(e.get("email"))).filter(Objects::nonNull).findFirst().orElse(null),
                    string(map(member.signup).getOrDefault("locale", access.config().club().defaultLocale()))));
        }
        return result;
    }
    @Override public Optional<Note> instructorNote(String dogId) {
        return access.dogs.findById(dogId).map(dog -> {
            var note = map(dog.instructorNote);
            return new Note(string(note.get("text")), instant(note.get("updatedAt")), string(note.get("updatedByAccountId")));
        });
    }
    @Override public Optional<Remarks> remarks(String dogId) { return access.dogs.findById(dogId).map(FollowupCensusAdapter::remarks); }
    @Override public Remarks saveRemarks(String dogId, String text, long version, String updatedByName) {
        return remarks(dogs.observations(dogId, text, version, updatedByName));
    }
    private static Remarks remarks(com.agilityhub.core.clubs.census.persistence.Dog dog) {
        var meta = map(dog.remarksMeta);
        return new Remarks(dog.remarks, instant(meta.get("updatedAt")), string(meta.get("updatedByAccountId")), string(meta.get("updatedByName")),
                DogService.remarksVersion(dog));
    }
    private static String fullName(com.agilityhub.core.clubs.census.persistence.Member m) {
        return String.join(" ", java.util.stream.Stream.of(m.firstName, m.lastName1, m.lastName2).filter(s -> s != null && !s.isBlank()).toList());
    }
}
