package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.CensusRules;
import com.agilityhub.core.clubs.followup.application.AttachmentService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.ClubClock;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

/**
 * S10 reads census through this tenant-scoped boundary: the «{guia} + {gos}» pair of a dog (R-10-00:
 * `Dog.handlerName ?? Member.firstName`), the dog's photo as a 5-minute signed URL, its level and level date, and the
 * owner's name, gender and derived status (22/D13). Never `Dog.remarks` nor any bank data (MATRIU rule 1).
 */
@Service
public class AttendanceCensusAccess {
    /** @param memberFullName «Laura Serra»: the backoffice «(abonat: …)» when the handler differs (R-10-00) */
    public record Pair(String dogId, String dogName, String handlerName, String breed, String sex, LocalDate birthDate, String photoFileKey, String dogStatus,
            String levelId, Instant levelAssignedAt, String memberId, String memberFirstName, String memberFullName, String gender) {
        /** «{guia}»: the handler when there is one, the owner's first name otherwise (R-10-00). */
        public String guide() { return handlerName != null && !handlerName.isBlank() ? handlerName : memberFirstName; }
    }
    public record Status(String kind, LocalDate date) { }
    private final CensusAccess access; private final AttachmentService attachments; private final ClubClock clock;
    public AttendanceCensusAccess(CensusAccess access, AttachmentService attachments, ClubClock clock) {
        this.access = access; this.attachments = attachments; this.clock = clock;
    }
    /** One `$in` read per collection for the dogs of a sheet, a card, a history or a week. Unknown ids are absent. */
    public Map<String, Pair> pairs(Collection<String> dogIds) {
        if (dogIds.isEmpty()) { return Map.of(); }
        var dogs = access.dogs.matching(Criteria.where("_id").in(new HashSet<>(dogIds)));
        var members = new HashMap<String, com.agilityhub.core.clubs.census.persistence.Member>();
        access.members.matching(Criteria.where("_id").in(dogs.stream().map(d -> d.memberId).filter(Objects::nonNull).distinct().toList()))
                .forEach(m -> members.put(m.id, m));
        var result = new LinkedHashMap<String, Pair>();
        for (var d : dogs) {
            var m = members.get(d.memberId);
            result.put(d.id, new Pair(d.id, d.name, d.handlerName, d.breed, d.sex, d.birthDate, d.photoFileKey, d.status, d.levelId, d.levelAssignedAt, d.memberId,
                    m == null ? null : m.firstName, m == null ? null : fullName(m), m == null ? null : m.gender));
        }
        return result;
    }
    public Optional<Pair> pair(String dogId) { return Optional.ofNullable(pairs(List.of(dogId)).get(dogId)); }
    /** The dog's photo, signed for 5 minutes (R-10-11 assumption, `AttachmentService.url`); null without a photo. */
    public String photoUrl(Pair pair) { return pair == null ? null : attachments.url(pair.photoFileKey(), pair.dogName()); }
    public Integer ageYears(Pair pair) { return pair.birthDate() == null ? null : CensusRules.age(pair.birthDate(), today()); }
    /** S03 derived status of the owner (ACTIVE, INACTIVE_PERIOD, LEAVE_SCHEDULED, LEFT, …), as `GET /members/{id}` shows it. */
    public Status memberStatus(String memberId) {
        var member = access.members.findById(memberId).orElse(null);
        if (member == null) { return new Status("LEFT", null); }
        var status = CensusRules.status(member.status, member.leaveDate, access.enabled(Module.INACTIVITY)
                ? access.references.inactivityEnd(member.id, today()) : null, member.erasedAt, today());
        return new Status(status.kind(), status.date());
    }
    private LocalDate today() { return clock.today(TenantContext.require()); }
    private static String fullName(com.agilityhub.core.clubs.census.persistence.Member m) {
        return String.join(" ", java.util.stream.Stream.of(m.firstName, m.lastName1, m.lastName2).filter(s -> s != null && !s.isBlank()).toList());
    }
}
