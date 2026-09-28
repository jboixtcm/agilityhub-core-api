package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.application.ports.MemberContact;
import com.agilityhub.core.clubs.messaging.application.ports.MemberDirectoryPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFacts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationSubject;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.clubs.messaging.application.ports.SignupContactPort;
import com.agilityhub.core.clubs.messaging.application.ports.StaffDirectoryPort;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.clubs.messaging.domain.NotificationSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * S11 R-11-02, the recipients per audience: `MEMBER` = the members of the payload (`affected[]`, else `memberId` with its
 * `dogId`) unless the owner lists them (waiting-list entries, an activity's audience, the owner **and** the booker of a
 * family dog); one notification per dog when the subject is a dog. `INSTRUCTORS` = the class's (`ClassSession.instructorIds`,
 * plus explicit ids: N-08b's former ones) when the event has a class, otherwise every active instructor. `ADMINS` = the
 * active memberships with ADMIN. `APPLICANT` = the signup's address. The same person in two audiences gets two
 * notifications (their `dedupKey`s differ in the audience). A `LEFT` member only receives what an event about them mandates
 * (N-28), never club news (N-24, N-32a) nor a notice reached through a list.
 */
public final class RecipientResolver {
    private final MemberDirectoryPort members; private final StaffDirectoryPort staff; private final SignupContactPort signups;

    public RecipientResolver(MemberDirectoryPort members, StaffDirectoryPort staff, SignupContactPort signups) {
        this.members = members; this.staff = staff; this.signups = signups;
    }

    /**
     * One recipient of one audience; `contact` is null for an `APPLICANT`, `applicant` for the others. `subjectDog` is the
     * notice's dog (`dogId`) whoever owns it: the recipient's own, or the owner's for the family member who booked it.
     */
    public record Recipient(NotificationAudience audience, MemberContact contact, SignupContactPort.ApplicantContact applicant, String dogId,
            Map<String, Object> values, NotificationSubject subject, String occurrence, MemberContact.DogContact subjectDog) {
        public Recipient(NotificationAudience audience, MemberContact contact, SignupContactPort.ApplicantContact applicant, String dogId,
                Map<String, Object> values, NotificationSubject subject) {
            this(audience, contact, applicant, dogId, values, subject, null, null);
        }
        public String accountId() { return contact != null ? contact.accountId() : applicant == null ? null : applicant.accountId(); }
        public String memberId() { return contact == null ? null : contact.memberId(); }
        public String email() {
            if (applicant != null) { return applicant.email(); }
            return contact == null || contact.emails().isEmpty() ? null : contact.emails().getFirst().address();
        }
    }

    public List<Recipient> resolve(NotificationSpec spec, NotificationTrigger trigger, NotificationFacts facts) {
        var recipients = new ArrayList<Recipient>();
        for (var audience : spec.audiences()) {
            if (facts.audiences() != null && !facts.audiences().contains(audience.name())) { continue; }
            switch (audience) {
                case MEMBER -> memberRecipients(spec, trigger, facts, recipients);
                case INSTRUCTORS -> staffRecipients(audience, instructors(trigger, facts), facts, recipients);
                case ADMINS -> staffRecipients(audience, staff.admins(), facts, recipients);
                case APPLICANT -> {
                    var applicant = facts.applicant() != null ? facts.applicant()
                            : trigger.text("memberId") == null ? null : signups.applicant(trigger.text("memberId")).orElse(null);
                    if (applicant != null && applicant.email() != null && !applicant.email().isBlank()) {
                        recipients.add(new Recipient(audience, null, applicant, null, Map.of(), facts.subject()));
                    }
                }
            }
        }
        return recipients;
    }

    private void memberRecipients(NotificationSpec spec, NotificationTrigger trigger, NotificationFacts facts, List<Recipient> out) {
        var subjects = facts.members() != null ? facts.members() : payloadMembers(trigger);
        var contacts = new LinkedHashMap<String, MemberContact>();
        members.findAll(subjects.stream().map(NotificationFacts.MemberSubject::memberId).distinct().toList())
                .forEach(contact -> contacts.put(contact.memberId(), contact));
        // R-11-05: the subject dog whoever owns it — among the event's members first (the owner beside the booker), else the census.
        var dogs = new java.util.HashMap<String, MemberContact.DogContact>();
        contacts.values().forEach(contact -> contact.dogs().forEach(dog -> dogs.putIfAbsent(dog.dogId(), dog)));
        var seen = new HashSet<String>();
        for (var subject : subjects) {
            var contact = contacts.get(subject.memberId());
            if (contact == null) { continue; }
            if (contact.left() && (spec.category() == NotificationCategory.CLUB_NEWS || !contact.memberId().equals(trigger.text("memberId")))) { continue; }
            if (!seen.add(contact.memberId() + "|" + Objects.toString(subject.dogId(), "") + "|" + Objects.toString(subject.occurrence(), ""))) { continue; }
            var ids = facts.subject().with(subject.subject()).with(NotificationSubject.member(contact.memberId()));
            if (subject.dogId() != null) { ids = ids.withDog(subject.dogId()); }
            var dog = subject.dogId() == null ? null : contact.dog(subject.dogId()).or(() -> java.util.Optional.ofNullable(dogs.get(subject.dogId())))
                    .or(() -> members.dog(subject.dogId())).orElse(null);
            out.add(new Recipient(NotificationAudience.MEMBER, contact, null, subject.dogId(), subject.values(), ids, subject.occurrence(), dog));
        }
    }
    /** R-11-02 default: `affected[{memberId, dogId, bookingId}]`, else the payload's `memberId` (and `dogId`). */
    static List<NotificationFacts.MemberSubject> payloadMembers(NotificationTrigger trigger) {
        var subjects = new ArrayList<NotificationFacts.MemberSubject>();
        for (var row : trigger.rows("affected")) {
            if (row.get("memberId") == null) { continue; }
            String bookingId = row.get("bookingId") == null ? null : row.get("bookingId").toString();
            subjects.add(new NotificationFacts.MemberSubject(row.get("memberId").toString(), row.get("dogId") == null ? null : row.get("dogId").toString(),
                    Map.of(), bookingId == null ? NotificationSubject.NONE : NotificationSubject.booking(bookingId)));
        }
        if (subjects.isEmpty() && trigger.text("memberId") != null) { subjects.add(new NotificationFacts.MemberSubject(trigger.text("memberId"), trigger.text("dogId"))); }
        return subjects;
    }
    private List<MemberContact> instructors(NotificationTrigger trigger, NotificationFacts facts) {
        var scope = facts.instructors();
        String classId = scope != null ? scope.classSessionId() : Objects.requireNonNullElse(trigger.text("classSessionId"), Objects.toString(trigger.text("classId"), ""));
        if (classId != null && classId.isBlank()) { classId = null; }
        if (classId == null && (scope == null || scope.instructorIds().isEmpty())) { return staff.instructors(); }
        var found = new ArrayList<MemberContact>();
        if (classId != null) { found.addAll(staff.instructorsOf(classId)); }
        if (scope != null && !scope.instructorIds().isEmpty()) { found.addAll(staff.instructorsByIds(scope.instructorIds())); }
        return found;
    }
    private void staffRecipients(NotificationAudience audience, List<MemberContact> people, NotificationFacts facts, List<Recipient> out) {
        var seen = new HashSet<String>();
        for (var person : people) {
            String key = person.accountId() != null ? person.accountId() : person.memberId();
            if (key == null || !seen.add(key)) { continue; }
            out.add(new Recipient(audience, person, null, null, Map.of(), facts.subject()));
        }
    }
}
