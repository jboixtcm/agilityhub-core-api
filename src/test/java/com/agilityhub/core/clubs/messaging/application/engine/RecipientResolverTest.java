package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.application.ports.InMemoryMessagingPorts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFacts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationSubject;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.clubs.messaging.application.ports.SignupContactPort;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.clubs.messaging.domain.NotificationSpec;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static com.agilityhub.core.clubs.messaging.domain.NotificationAudience.*;
import static org.assertj.core.api.Assertions.*;

/** E7-T02 step 3: the recipients of R-11-02 over the in-memory ports (T-11-14, T-11-08). */
class RecipientResolverTest {
    private final InMemoryMessagingPorts ports = InMemoryMessagingPorts.s11Examples();
    private final RecipientResolver resolver = new RecipientResolver(ports, ports, ports);
    private static NotificationSpec spec(String code) { return NotificationCatalog.byCode(code).orElseThrow(); }
    private static NotificationTrigger trigger(String type, Map<String, Object> payload) {
        return new NotificationTrigger("event-a", type, "club-a", "Aggregate", "aggregate-a", Instant.parse("2026-10-07T08:00:00Z"), payload, "account-admin", null,
                DomainEvent.Origin.APP);
    }
    private static List<String> who(List<RecipientResolver.Recipient> recipients, NotificationAudience audience) {
        return recipients.stream().filter(r -> r.audience() == audience).map(r -> r.contact() != null && r.contact().memberId() != null ? r.contact().memberId()
                : r.accountId() != null ? r.accountId() : r.email()).toList();
    }

    @Test void T_11_14_n08aReachesEveryDogOfThePayloadTheClassInstructorsAndTheAdmins() {
        // 3 registrants (Laura with two dogs) + Pau waiting: one MEMBER notification per dog, the class's instructor, both admins.
        var facts = NotificationFacts.builder().instructors("class-a", List.of())
                .member(new NotificationFacts.MemberSubject("member-laura", "dog-duna", Map.of(), NotificationSubject.booking("booking-1")))
                .member(new NotificationFacts.MemberSubject("member-laura", "dog-rock", Map.of(), NotificationSubject.booking("booking-2")))
                .member(new NotificationFacts.MemberSubject("member-marc", "dog-ares", Map.of(), NotificationSubject.booking("booking-3")))
                .member(new NotificationFacts.MemberSubject("member-pau", "dog-nit", Map.of(), NotificationSubject.waitlistEntry("entry-1")))
                .member("member-unknown", "dog-x").build();
        var recipients = resolver.resolve(spec("N-08a"), trigger("ClassCancelledByClub", Map.of()), facts);
        assertThat(who(recipients, MEMBER)).containsExactly("member-laura", "member-laura", "member-marc", "member-pau");
        assertThat(recipients.stream().filter(r -> r.audience() == MEMBER).map(RecipientResolver.Recipient::dogId)).containsExactly("dog-duna", "dog-rock", "dog-ares", "dog-nit");
        assertThat(recipients.getFirst().subject()).isEqualTo(new NotificationSubject("dog-duna", "booking-1", null, null, null, null, null, null, "member-laura"));
        assertThat(who(recipients, INSTRUCTORS)).containsExactly("member-marta");
        assertThat(who(recipients, ADMINS)).containsExactly("member-admin", "account-admin2");
        assertThat(recipients.stream().filter(r -> r.audience() == ADMINS).map(RecipientResolver.Recipient::email)).containsExactly("admin@example.test", "admin2@example.test");
        // RISK_REVIEW: the owner limits the notice to MEMBER (S11 §7).
        var memberOnly = NotificationFacts.builder().audiences("MEMBER").member("member-marc", "dog-ares").build();
        assertThat(resolver.resolve(spec("N-08a"), trigger("ClassCancelledByClub", Map.of()), memberOnly)).extracting(RecipientResolver.Recipient::audience).containsExactly(MEMBER);
        // The same person in two audiences (an admin who booked) → two recipients, so two notifications.
        ports.update("member-admin", c -> c);
        var both = resolver.resolve(spec("N-08a"), trigger("ClassCancelledByClub", Map.of()), NotificationFacts.builder().instructors("class-a", List.of()).member("member-admin", "dog-a").build());
        assertThat(both).filteredOn(r -> "account-admin".equals(r.accountId())).extracting(RecipientResolver.Recipient::audience).containsExactly(MEMBER, ADMINS);
    }

    @Test void T_11_14_instructorsOfTheClassNewAndFormerElseEveryActiveOne() {
        // N-08b: the class's new instructors and the former ones of the diff.
        ports.classInstructors.put("class-a", List.of("instructor-estel"));
        var facts = NotificationFacts.builder().instructors("class-a", List.of("instructor-marta")).noMembers().build();
        assertThat(who(resolver.resolve(spec("N-08b"), trigger("ClassSessionUpdated", Map.of()), facts), INSTRUCTORS)).containsExactly("member-estel", "member-marta");
        // N-21 without a class: every active instructor.
        assertThat(who(resolver.resolve(spec("N-21"), trigger("TaskCompleted", Map.of("dogId", "dog-duna")), NotificationFacts.builder().build()), INSTRUCTORS))
                .containsExactly("member-marta", "member-estel");
        // A class id in the payload (no owner scope): that class's instructors; an unknown or blank one: nobody, or every one.
        assertThat(who(resolver.resolve(spec("N-17"), trigger("ClassAutoCancelled", Map.of("classId", "class-a")), NotificationFacts.builder().build()), INSTRUCTORS))
                .containsExactly("member-estel");
        assertThat(who(resolver.resolve(spec("N-17"), trigger("ClassAutoCancelled", Map.of("classSessionId", "class-z")), NotificationFacts.builder().build()), INSTRUCTORS)).isEmpty();
        assertThat(who(resolver.resolve(spec("N-17"), trigger("ClassAutoCancelled", Map.of("classId", " ")), NotificationFacts.builder().build()), INSTRUCTORS)).hasSize(2);
        // Only explicit ids, no class.
        assertThat(who(resolver.resolve(spec("N-17"), trigger("ClassAutoCancelled", Map.of()), NotificationFacts.builder().instructors(null, List.of("instructor-marta")).build()), INSTRUCTORS))
                .containsExactly("member-marta");
        // The same instructor twice (new and former) is one recipient.
        assertThat(who(resolver.resolve(spec("N-08b"), trigger("ClassSessionUpdated", Map.of()), NotificationFacts.builder().instructors("class-a", List.of("instructor-estel")).noMembers().build()),
                INSTRUCTORS)).containsExactly("member-estel");
    }

    @Test void T_11_14_leftMembersOnlyReceiveTheirOwnNoticeAndTheApplicantIsTheSignupAddress() {
        ports.update("member-marc", c -> InMemoryMessagingPorts.with(c, "LEFT", null, null, null));
        // N-28 (LeaveResolved about Marc) reaches him although he left.
        assertThat(who(resolver.resolve(spec("N-28"), trigger("LeaveResolved", Map.of("memberId", "member-marc")), NotificationFacts.builder().build()), MEMBER))
                .containsExactly("member-marc");
        // N-24 (club news) never reaches a LEFT member, even when selected; a notice about someone else reached through a list neither.
        assertThat(who(resolver.resolve(spec("N-24"), trigger("AnnouncementSent", Map.of("memberId", "member-marc")),
                NotificationFacts.builder().member("member-marc", null).member("member-laura", null).build()), MEMBER)).containsExactly("member-laura");
        assertThat(who(resolver.resolve(spec("N-08a"), trigger("ClassCancelledByClub", Map.of()), NotificationFacts.builder().audiences("MEMBER").member("member-marc", "dog-ares").build()),
                MEMBER)).isEmpty();
        // The payload default: affected[] rows (without memberId skipped), else memberId + dogId.
        var affected = trigger("ClassCancelledByClub", Map.of("affected", List.of(Map.of("memberId", "member-laura", "dogId", "dog-duna", "bookingId", "booking-1"),
                Map.of("dogId", "dog-orphan"), Map.of("memberId", "member-anna"))));
        var fromPayload = resolver.resolve(spec("N-08a"), affected, NotificationFacts.builder().audiences("MEMBER").build());
        assertThat(who(fromPayload, MEMBER)).containsExactly("member-laura", "member-anna");
        assertThat(fromPayload.getFirst().subject().bookingId()).isEqualTo("booking-1"); assertThat(fromPayload.get(1).dogId()).isNull();
        assertThat(who(resolver.resolve(spec("N-09"), trigger("DogLevelChanged", Map.of("memberId", "member-anna", "dogId", "dog-lluna")), NotificationFacts.builder().build()), MEMBER))
                .containsExactly("member-anna");
        // The same member and dog twice in one list is one notification.
        assertThat(who(resolver.resolve(spec("N-09"), trigger("DogLevelChanged", Map.of()), NotificationFacts.builder().member("member-anna", "dog-lluna").member("member-anna", "dog-lluna").build()),
                MEMBER)).hasSize(1);
        // APPLICANT: the facts' contact, else the signup of the payload's member; none without an address.
        var explicit = new SignupContactPort.ApplicantContact("applicant@example.test", "es", "Nova Persona");
        var applicant = resolver.resolve(spec("N-03"), trigger("SignupRejected", Map.of()), NotificationFacts.builder().applicant(explicit).build());
        assertThat(applicant).singleElement().satisfies(r -> { assertThat(r.email()).isEqualTo("applicant@example.test"); assertThat(r.memberId()).isNull(); assertThat(r.accountId()).isNull(); });
        ports.applicants.put("member-new", new SignupContactPort.ApplicantContact("new@example.test", "en", "New Person", "account-new"));
        assertThat(resolver.resolve(spec("N-03"), trigger("SignupRejected", Map.of("memberId", "member-new")), NotificationFacts.builder().build()))
                .singleElement().satisfies(r -> { assertThat(r.email()).isEqualTo("new@example.test"); assertThat(r.accountId()).isEqualTo("account-new"); });
        ports.applicants.put("member-blank", new SignupContactPort.ApplicantContact(" ", "en", "Blank"));
        assertThat(resolver.resolve(spec("N-03"), trigger("SignupRejected", Map.of("memberId", "member-blank")), NotificationFacts.builder().build())).isEmpty();
        assertThat(resolver.resolve(spec("N-03"), trigger("SignupRejected", Map.of()), NotificationFacts.builder().build())).isEmpty();
        assertThat(new SignupContactPort.ApplicantContact("a@example.test", "ca", "A").toString()).doesNotContain("a@example.test");
        // A member without any address has no `email()`.
        ports.update("member-anna", c -> InMemoryMessagingPorts.with(c, null, null, List.of(), null));
        assertThat(resolver.resolve(spec("N-09"), trigger("DogLevelChanged", Map.of("memberId", "member-anna")), NotificationFacts.builder().build()).getFirst().email()).isNull();
    }
}
