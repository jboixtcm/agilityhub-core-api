package com.agilityhub.core.clubs.messaging.application.ports;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E7-T02 step 1: the ports' value types — what an owner may tell the engine (`NotificationFacts`, closed to the catalog's
 * audiences and channels), the trigger's payload helpers, the subject ids, the contacts (never printed, R-14-18) — and the
 * null objects an application without the census, the catalogs or the bookings starts with.
 */
class MessagingPortsTest {
    @Test void WP_11_B_factsSpeakTheCatalogAndDefaultToThePayload() {
        var facts = NotificationFacts.builder().occurrence("N-33:week-a").audiences("MEMBER", "ADMINS").member("member-a", "dog-a")
                .member(new NotificationFacts.MemberSubject("member-b", null, Map.of("x", 1), NotificationSubject.booking("booking-b"), "N-46:entry:1"))
                .instructors("class-a", null).applicant(new SignupContactPort.ApplicantContact("a@example.test", "ca", "A"))
                .value("kept", "v").value("dropped", null).values(Map.of("more", 2)).value("ADMINS", "only_admins", true).value("ADMINS", "ignored", null)
                .subject(NotificationSubject.classSession("class-a")).subject(NotificationSubject.dog("dog-a")).exclude("EMAIL").enable("SMS").category("CLUB_CHANGES").build();
        assertThat(facts.occurrence()).isEqualTo("N-33:week-a"); assertThat(facts.audiences()).containsExactlyInAnyOrder("MEMBER", "ADMINS");
        assertThat(facts.members()).extracting(NotificationFacts.MemberSubject::memberId).containsExactly("member-a", "member-b");
        assertThat(facts.members().get(1).occurrence()).isEqualTo("N-46:entry:1"); assertThat(facts.members().getFirst().values()).isEmpty();
        assertThat(facts.instructors().instructorIds()).isEmpty(); assertThat(facts.applicant().email()).isEqualTo("a@example.test");
        assertThat(facts.values()).containsOnlyKeys("kept", "more"); assertThat(facts.valuesOf("ADMINS")).containsExactly(Map.entry("only_admins", true));
        assertThat(facts.valuesOf("MEMBER")).isEmpty();
        assertThat(facts.subject()).isEqualTo(new NotificationSubject("dog-a", null, "class-a", null, null, null, null, null, null));
        assertThat(facts.excludedChannels()).containsExactly("EMAIL"); assertThat(facts.enabledChannels()).containsExactly("SMS"); assertThat(facts.categoryOverride()).isEqualTo("CLUB_CHANGES");
        assertThatThrownBy(() -> NotificationFacts.builder().audiences("STUDENTS")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationFacts.builder().exclude("WHATSAPP")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationFacts.builder().enable("FAX")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationFacts.MemberSubject(null, null)).isInstanceOf(NullPointerException.class);
        // Nothing said: every default applies (the payload's members, all audiences, no subject).
        var empty = NotificationFacts.builder().build();
        assertThat(empty.members()).isNull(); assertThat(empty.audiences()).isNull(); assertThat(empty.subject()).isEqualTo(NotificationSubject.NONE);
        assertThat(NotificationFacts.builder().noMembers().build().members()).isEmpty();
        var bare = new NotificationFacts(null, null, null, null, null, null, null, null, null, null, null);
        assertThat(bare.values()).isEmpty(); assertThat(bare.excludedChannels()).isEmpty(); assertThat(bare.enabledChannels()).isEmpty(); assertThat(bare.valuesOf("MEMBER")).isEmpty();
        assertThat(new NotificationFacts.MemberSubject("m", null, null, null).subject()).isEqualTo(NotificationSubject.NONE);
    }

    @Test void WP_11_B_triggerPayloadHelpersAndSubjects() {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("memberId", "member-a"); payload.put("late", true); payload.put("notifyEmail", "true"); payload.put("count", 3); payload.put("ids", List.of("a", "b"));
        payload.put("affected", List.of(Map.of("memberId", "m"), "not a row")); payload.put("diff", Map.of("startTime", Map.of("before", "18:50"))); payload.put("nothing", null);
        var trigger = new NotificationTrigger("event-a", "ClassSessionUpdated", "club-a", "ClassSession", "class-a", Instant.EPOCH, payload, null, null, DomainEvent.Origin.APP);
        assertThat(trigger.text("memberId")).isEqualTo("member-a"); assertThat(trigger.text("nothing")).isNull(); assertThat(trigger.text("absent")).isNull();
        assertThat(trigger.flag("late")).isTrue(); assertThat(trigger.flag("notifyEmail")).isTrue(); assertThat(trigger.flag("memberId")).isFalse();
        assertThat(trigger.number("count", 0)).isEqualTo(3); assertThat(trigger.number("memberId", 7)).isEqualTo(7);
        assertThat(trigger.ids("ids")).containsExactly("a", "b"); assertThat(trigger.ids("memberId")).isEmpty();
        assertThat(trigger.rows("affected")).containsExactly(Map.of("memberId", "m")); assertThat(trigger.rows("count")).isEmpty();
        assertThat(trigger.map("diff")).containsKey("startTime"); assertThat(trigger.map("ids")).isEmpty();
        assertThat(new NotificationTrigger("e", "T", null, null, null, null, null, null, null, null).payload()).isEmpty();
        assertThatThrownBy(() -> new NotificationTrigger(null, "T", null, null, null, null, null, null, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NotificationTrigger("e", null, null, null, null, null, null, null, null, null)).isInstanceOf(NullPointerException.class);
        // Subjects: every factory fills its own id; `with` keeps what the other lacks.
        assertThat(NotificationSubject.trainingBooking("t").trainingBookingId()).isEqualTo("t"); assertThat(NotificationSubject.activity("a").activityId()).isEqualTo("a");
        assertThat(NotificationSubject.task("k").taskId()).isEqualTo("k"); assertThat(NotificationSubject.waitlistEntry("w").waitlistEntryId()).isEqualTo("w");
        assertThat(NotificationSubject.member("m").with(null)).isEqualTo(NotificationSubject.member("m"));
        assertThat(NotificationSubject.booking("b").with(NotificationSubject.member("m")).withDog("d"))
                .isEqualTo(new NotificationSubject("d", "b", null, null, null, null, null, null, "m"));
    }

    @Test void R_14_18_contactsNeverPrintAddressesAndStoredViewsTellWhatReachedTheMember() {
        var contact = new MemberContact("member-a", "account-a", null, "Laura", null, null, null, null, null, "LEFT",
                List.of(new MemberContact.DogContact("dog-a", "Duna", "FEMALE", "ACTIVE")));
        assertThat(contact.displayName()).isEmpty(); assertThat(contact.emails()).isEmpty(); assertThat(contact.phones()).isEmpty(); assertThat(contact.preferences()).isEmpty();
        assertThat(contact.left()).isTrue(); assertThat(contact.dog("dog-a")).isPresent(); assertThat(contact.dog("dog-b")).isEmpty();
        assertThat(contact.toString()).doesNotContain("Laura"); assertThat(new MemberContact.Email("laura@example.test", false).toString()).doesNotContain("laura");
        assertThat(new MemberContact(null, null, "x", null, null, null, null, null, null, null, null).dogs()).isEmpty();
        assertThatThrownBy(() -> new MemberContact.Email(null, false)).isInstanceOf(NullPointerException.class);
        var view = new NotificationFactsPort.StoredNotification("n", "N-15", "WaitlistNotified", "MEMBER", "account-a", "member-a", NotificationSubject.NONE,
                List.of(new NotificationFactsPort.DeliveryView("APP", "SKIPPED_NO_CONTACT"), new NotificationFactsPort.DeliveryView("SMS", "QUEUED")), true);
        assertThat(view.has("SMS", "QUEUED")).isTrue(); assertThat(view.has("SMS", "SENT")).isFalse(); assertThat(view.reachable()).isTrue();
        assertThat(new NotificationFactsPort.StoredNotification("n", "N-15", null, null, null, null, null, null, false).reachable()).isFalse();
        // An owner that only explains facts: the two hooks do nothing.
        NotificationFactsPort owner = new NotificationFactsPort() {
            public Set<String> eventTypes() { return Set.of("X"); }
            public java.util.Optional<NotificationFacts> facts(NotificationTrigger trigger, String code) { return java.util.Optional.empty(); }
        };
        owner.stored(null, "N-15", List.of(view)); owner.sent(view, "SMS");
        assertThat(new SignupContactPort.ApplicantContact("a@example.test", "ca", "A").accountId()).isNull();
    }

    @Test void WP_11_B_theNullObjectsKnowNobodyAndWriteNothing() {
        var defaults = new MessagingPortDefaults();
        var members = defaults.memberDirectory();
        assertThat(members.find("m")).isEmpty(); assertThat(members.findAll(List.of("m"))).isEmpty(); assertThat(members.byAccount("a")).isEmpty();
        assertThat(members.membersWithEmail("a@example.test")).isEmpty(); assertThat(members.byIds(List.of("m"))).isEmpty(); assertThat(members.byFilters(List.of("status:eq:ACTIVE"), null)).isEmpty();
        assertThat(members.dog("d")).isEmpty();
        var writer = defaults.memberContactsWriter();
        assertThat(writer.markEmailBounced("m", "a@example.test")).isFalse(); assertThat(writer.unsubscribeClubNews("m", Instant.EPOCH)).isFalse();
        var staff = defaults.staffDirectory();
        assertThat(staff.admins()).isEmpty(); assertThat(staff.instructors()).isEmpty(); assertThat(staff.instructorsOf("c")).isEmpty(); assertThat(staff.instructorsByIds(List.of("i"))).isEmpty();
        assertThat(defaults.bookingRelevance().stillActive("b", null, Instant.EPOCH)).isFalse();
        assertThat(defaults.waitlistRelevance().stillNotified("w", Instant.EPOCH)).isFalse();
        assertThat(defaults.signupContacts().applicant("m")).isEmpty();
        // The in-memory doubles of the tests answer like the real adapters.
        var ports = InMemoryMessagingPorts.s11Examples();
        ports.notifiedEntries.put("entry-a", Instant.parse("2026-10-07T10:00:00Z"));
        assertThat(ports.stillNotified("entry-a", Instant.parse("2026-10-07T09:00:00Z"))).isTrue();
        assertThat(ports.stillNotified("entry-a", Instant.parse("2026-10-07T10:00:00Z"))).isFalse(); assertThat(ports.stillNotified("entry-b", Instant.EPOCH)).isFalse();
        assertThat(ports.markEmailBounced("member-laura", "LAURA@example.test")).isTrue(); assertThat(ports.markEmailBounced("member-laura", "laura@example.test")).isFalse();
        assertThat(ports.membersWithEmail("laura@example.test")).containsExactly("member-laura");
        assertThat(ports.unsubscribeClubNews("member-laura", Instant.EPOCH)).isTrue(); assertThat(ports.unsubscribeClubNews("member-laura", Instant.EPOCH)).isFalse();
        assertThat(ports.byAccount("account-marc").orElseThrow().memberId()).isEqualTo("member-marc"); assertThat(ports.find(null)).isEmpty();
        assertThat(ports.dog("dog-ares").orElseThrow().name()).isEqualTo("Ares"); assertThat(ports.dog("dog-unknown")).isEmpty();
    }
}
