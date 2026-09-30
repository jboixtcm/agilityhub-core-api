package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.Dog;
import com.agilityhub.core.clubs.census.persistence.Member;
import com.agilityhub.core.clubs.messaging.application.ports.MemberContact;
import com.agilityhub.core.clubs.messaging.application.ports.MemberContactsWriterPort;
import com.agilityhub.core.clubs.messaging.application.ports.MemberDirectoryPort;
import com.agilityhub.core.clubs.messaging.application.ports.SignupContactPort;
import com.agilityhub.core.clubs.messaging.application.NotificationPreferences;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/**
 * The census as the S11 engine reads and writes it (E7-T02 step 1, the adapters of `MemberDirectoryPort`,
 * `MemberContactsWriterPort` and `SignupContactPort`): every contact address with its `bounced` mark, every phone in E.164
 * (`prefix` + `number`), the typed `notificationPreferences`, the member's own language (`signup.locale`) and the dogs. An
 * erased member is absent. The two writes (the bounce mark, the unsubscribe of `CLUB_NEWS`) bump the member's version.
 */
@Service
public class MessagingCensusDirectory implements MemberDirectoryPort, MemberContactsWriterPort, SignupContactPort {
    private final CensusAccess access; private final BookingMemberAccess bookingMembers;
    public MessagingCensusDirectory(CensusAccess access, BookingMemberAccess bookingMembers) { this.access = access; this.bookingMembers = bookingMembers; }

    @Override public Optional<MemberContact> find(String memberId) {
        if (memberId == null) { return Optional.empty(); }
        return access.members.findById(memberId).filter(m -> m.erasedAt == null).map(m -> contact(m, dogsOf(List.of(m.id))));
    }
    @Override public List<MemberContact> findAll(Collection<String> memberIds) {
        var ids = memberIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) { return List.of(); }
        var members = new ArrayList<Member>();
        for (int from = 0; from < ids.size(); from += 500) {
            members.addAll(access.members.matching(Criteria.where("_id").in(ids.subList(from, Math.min(ids.size(), from + 500))).and("erasedAt").is(null)));
        }
        var dogs = dogsOf(members.stream().map(m -> m.id).toList());
        return members.stream().map(m -> contact(m, dogs)).toList();
    }
    @Override public Optional<MemberContact> byAccount(String accountId) {
        if (accountId == null) { return Optional.empty(); }
        return access.members.matching(Criteria.where("accountId").is(accountId).and("erasedAt").is(null)).stream().findFirst()
                .map(m -> contact(m, dogsOf(List.of(m.id))));
    }
    @Override public Optional<MemberContact.DogContact> dog(String dogId) {
        if (dogId == null) { return Optional.empty(); }
        return access.dogs.findById(dogId).map(d -> new MemberContact.DogContact(d.id, d.name, d.sex, d.status));
    }
    @Override public List<String> membersWithEmail(String address) {
        if (address == null || address.isBlank()) { return List.of(); }
        var exact = Pattern.compile("^" + Pattern.quote(address.strip()) + "$", Pattern.CASE_INSENSITIVE);
        return access.members.matching(Criteria.where("contactEmails.email").regex(exact)).stream().map(m -> m.id).toList();
    }

    @Override public boolean markEmailBounced(String memberId, String address) {
        var member = access.members.findById(memberId).orElse(null);
        if (member == null || member.erasedAt != null) { return false; }
        var stored = rows(member.contactEmails).stream().map(row -> string(row.get("email"))).filter(Objects::nonNull)
                .filter(email -> email.equalsIgnoreCase(address.strip())).findFirst().orElse(null);
        if (stored == null) { return false; }
        return access.members.updateFirst(Criteria.where("_id").is(memberId).and("contactEmails").elemMatch(Criteria.where("email").is(stored).and("bounced").ne(true)),
                new Update().set("contactEmails.$.bounced", true));
    }
    @Override public boolean unsubscribeClubNews(String memberId, Instant at) {
        var member = access.members.findById(memberId).orElse(null);
        if (member == null || member.erasedAt != null || !NotificationPreferences.clubNewsEmail(member.notificationPreferences)) { return false; }
        return access.members.updateFirst(Criteria.where("_id").is(memberId),
                new Update().set("notificationPreferences", NotificationPreferences.clubNewsOff(member.notificationPreferences, at)));
    }

    /** E7-T03 (R-11-04): the whole preference block of 12/D10; the entity-class update bumps the member's `@Version`. */
    @Override public boolean savePreferences(String memberId, java.util.Map<String, Object> block) {
        var member = access.members.findById(memberId).orElse(null);
        if (member == null || member.erasedAt != null) { return false; }
        return access.members.updateFirst(Criteria.where("_id").is(memberId), new Update().set("notificationPreferences", block));
    }
    /** E7-T03 (R-11-11 `CHANGE_CLASS`): an `ACTIVE` dog the account's member may book for (S08 R-08-02, family group included). */
    @Override public boolean dogAccessible(String accountId, String dogId) {
        var member = bookingMembers.memberByAccount(accountId).orElse(null);
        var dog = bookingMembers.dog(dogId).orElse(null);
        return member != null && dog != null && dog.active() && bookingMembers.canAccess(member.id(), dog);
    }

    @Override public Optional<ApplicantContact> applicant(String memberId) {
        return access.members.findById(memberId).filter(m -> m.erasedAt == null).flatMap(m -> {
            var email = rows(m.contactEmails).stream().map(row -> string(row.get("email"))).filter(Objects::nonNull).findFirst();
            return email.map(address -> new ApplicantContact(address, string(map(m.signup).get("locale")), name(m)));
        });
    }

    private java.util.Map<String, List<Dog>> dogsOf(List<String> memberIds) {
        var byMember = new HashMap<String, List<Dog>>();
        if (memberIds.isEmpty()) { return byMember; }
        access.dogs.matching(Criteria.where("memberId").in(memberIds)).forEach(dog -> byMember.computeIfAbsent(dog.memberId, key -> new ArrayList<>()).add(dog));
        return byMember;
    }
    private static MemberContact contact(Member m, java.util.Map<String, List<Dog>> dogs) {
        var emails = rows(m.contactEmails).stream().filter(row -> row.get("email") != null)
                .map(row -> new MemberContact.Email(string(row.get("email")).strip(), Boolean.TRUE.equals(row.get("bounced")))).toList();
        var phones = rows(m.phones).stream().filter(row -> row.get("number") != null).map(MessagingCensusDirectory::e164).distinct().toList();
        var ownDogs = dogs.getOrDefault(m.id, List.of()).stream().map(d -> new MemberContact.DogContact(d.id, d.name, d.sex, d.status)).toList();
        return new MemberContact(m.id, m.accountId, name(m), m.firstName, m.gender, string(map(m.signup).get("locale")), emails, phones,
                m.notificationPreferences, m.status, ownDogs);
    }
    /** `prefix` + `number` without spaces (S03 phones; the country profile validated both). */
    static String e164(java.util.Map<String, Object> phone) {
        String number = Objects.toString(phone.get("number"), "").replaceAll("[\\s.-]", "");
        if (number.startsWith("+")) { return number; }
        return Objects.toString(phone.get("prefix"), "").replaceAll("\\s", "") + number;
    }
    private static String name(Member m) {
        return Stream.of(m.firstName, m.lastName1, m.lastName2).filter(Objects::nonNull).filter(part -> !part.isBlank()).collect(Collectors.joining(" "));
    }
}
