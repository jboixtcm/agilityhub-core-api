package com.agilityhub.core.clubs.messaging.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static com.agilityhub.core.clubs.messaging.domain.DeliveryStatus.*;
import static com.agilityhub.core.clubs.messaging.domain.NotificationAudience.*;
import static com.agilityhub.core.clubs.messaging.domain.NotificationChannel.*;
import static org.assertj.core.api.Assertions.*;

/** E7-T02 step 4: the R-11-03 truth table (T-11-02) and the preference rules of R-11-04 (T-11-03). */
class ChannelResolverTest {
    private static final AtomicInteger COMBINATIONS = new AtomicInteger(), CODES = new AtomicInteger();
    private static NotificationSpec spec(String code) { return NotificationCatalog.byCode(code).orElseThrow(); }
    private static ChannelResolver.Contact laura(NotificationPreference preferences, List<String> phones) {
        return new ChannelResolver.Contact("account-laura", List.of(new ChannelResolver.EmailAddress("laura@example.test", false)), phones, List.of(), preferences);
    }
    private static NotificationPreference email(NotificationCategory category, boolean on) {
        var byCategory = new EnumMap<NotificationCategory, Boolean>(NotificationCategory.class); byCategory.put(category, on);
        return new NotificationPreference(byCategory, null, true, null, null);
    }

    @ParameterizedTest(name = "{0}") @MethodSource("com.agilityhub.core.clubs.messaging.domain.ChannelTruthTable#codes")
    void T_11_02_everyCatalogCodeTimesEveryRowOfTheTruthTable(NotificationSpec spec) {
        var rows = ChannelTruthTable.rows(spec);
        assertThat(rows).hasSize(spec.audiences().size() * 3 * 2 * 2 * 2 * 2 * 3);
        for (var row : rows) {
            assertThat(ChannelResolver.resolve(spec, row.audience(), row.matrixRow(), row.modules(), row.contact())).as(row.toString())
                    .containsExactlyInAnyOrderElementsOf(row.expected());
        }
        COMBINATIONS.addAndGet(rows.size()); CODES.incrementAndGet();
    }
    @AfterAll static void report() {
        // The T-11-02 report quoted in the E7-T02 evidence.
        System.out.println("T-11-02 truth table: " + CODES.get() + " catalog codes, " + COMBINATIONS.get() + " code x audience x row combinations");
    }

    @Test void T_11_02_theS11ExamplesOfLaura() {
        var n08a = spec("N-08a"); var seed = n08a.defaultMatrix().get(MEMBER); var on = new ChannelResolver.Modules(true, true);
        var phones = List.of("+34600000001", "+34600000002");
        // N-08a, Laura (CLUB_CHANGES e-mail on, 2 phones, 1 e-mail), SMS on → APP + EMAIL + SMS×2.
        assertThat(ChannelResolver.resolve(n08a, MEMBER, seed, on, laura(email(NotificationCategory.CLUB_CHANGES, true), phones))).containsExactly(
                new ChannelResolver.Planned(APP, "account-laura", DELIVERED), new ChannelResolver.Planned(EMAIL, "laura@example.test", QUEUED),
                new ChannelResolver.Planned(SMS, "+34600000001", QUEUED), new ChannelResolver.Planned(SMS, "+34600000002", QUEUED));
        // The same Laura with CLUB_CHANGES e-mail off → APP + SMS×2, EMAIL SKIPPED_BY_PREFERENCE.
        assertThat(ChannelResolver.resolve(n08a, MEMBER, seed, on, laura(email(NotificationCategory.CLUB_CHANGES, false), phones))).containsExactly(
                new ChannelResolver.Planned(APP, "account-laura", DELIVERED), new ChannelResolver.Planned(EMAIL, null, SKIPPED_BY_PREFERENCE),
                new ChannelResolver.Planned(SMS, "+34600000001", QUEUED), new ChannelResolver.Planned(SMS, "+34600000002", QUEUED));
        // A club without SMS → APP + EMAIL, SMS SKIPPED_MODULE_OFF.
        assertThat(ChannelResolver.resolve(n08a, MEMBER, seed, new ChannelResolver.Modules(false, true), laura(NotificationPreference.DEFAULTS, phones))).containsExactly(
                new ChannelResolver.Planned(APP, "account-laura", DELIVERED), new ChannelResolver.Planned(EMAIL, "laura@example.test", QUEUED),
                new ChannelResolver.Planned(SMS, null, SKIPPED_MODULE_OFF));
        // The same addresses twice (another case), a blank one and a bounced one: one delivery per real address.
        var contact = new ChannelResolver.Contact("account-laura", List.of(new ChannelResolver.EmailAddress("Laura@Example.test", false),
                new ChannelResolver.EmailAddress(" laura@example.test ", false), new ChannelResolver.EmailAddress(" ", false),
                new ChannelResolver.EmailAddress("old@example.test", true)), List.of("+34600000001", "+34600000001"), null, null);
        assertThat(ChannelResolver.resolve(n08a, MEMBER, seed, on, contact)).containsExactly(new ChannelResolver.Planned(APP, "account-laura", DELIVERED),
                new ChannelResolver.Planned(EMAIL, "Laura@Example.test", QUEUED), new ChannelResolver.Planned(SMS, "+34600000001", QUEUED));
        // An audience the code does not have → nothing; a missing matrix row → only the code's push.
        assertThat(ChannelResolver.resolve(n08a, APPLICANT, seed, on, contact)).isEmpty();
        assertThat(ChannelResolver.resolve(spec("N-13"), MEMBER, null, on, laura(NotificationPreference.DEFAULTS, phones)))
                .containsExactly(new ChannelResolver.Planned(PUSH, null, SKIPPED_NO_CONTACT));
        assertThatThrownBy(() -> ChannelResolver.resolve(n08a, MEMBER, seed, null, contact)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChannelResolver.EmailAddress(null, false)).isInstanceOf(NullPointerException.class);
    }

    @Test void T_11_03_preferencesOnlyRemoveWhatTheTemplateAllowsAndOnlyForMembers() {
        var on = new ChannelResolver.Modules(true, true); var phones = List.of("+34600000001");
        // emailByCategory.CLUB_CHANGES = false removes EMAIL and never SMS («+SMS» is fixed).
        assertThat(ChannelResolver.resolve(spec("N-08a"), MEMBER, spec("N-08a").defaultMatrix().get(MEMBER), on, laura(email(NotificationCategory.CLUB_CHANGES, false), phones)))
                .extracting(ChannelResolver.Planned::channel, ChannelResolver.Planned::status)
                .containsExactly(tuple(APP, DELIVERED), tuple(EMAIL, SKIPPED_BY_PREFERENCE), tuple(SMS, QUEUED));
        // pushClubNews = false removes PUSH on CLUB_NEWS and announcements only (N-24), never on another code (N-13, N-15).
        var noNewsPush = new NotificationPreference(Map.of(), null, false, null, null);
        var subscribed = new ChannelResolver.Contact("account-laura", List.of(), List.of(), List.of("subscription-a"), noNewsPush);
        assertThat(ChannelResolver.resolve(spec("N-24"), MEMBER, spec("N-24").defaultMatrix().get(MEMBER), on, subscribed))
                .contains(new ChannelResolver.Planned(PUSH, null, SKIPPED_BY_PREFERENCE));
        assertThat(ChannelResolver.resolve(spec("N-13"), MEMBER, spec("N-13").defaultMatrix().get(MEMBER), on, subscribed))
                .contains(new ChannelResolver.Planned(PUSH, "subscription-a", QUEUED));
        assertThat(ChannelResolver.resolve(spec("N-15"), MEMBER, spec("N-15").defaultMatrix().get(MEMBER), on, subscribed))
                .contains(new ChannelResolver.Planned(PUSH, "subscription-a", QUEUED));
        // E7-T04 round 2 (R-11-13, ruling E82): an announcement sent with a CUSTOM template of any category is still N-24, and
        // `pushClubNews` = false removes its PUSH too; with the preference on, the device gets it.
        var withNewsPush = new ChannelResolver.Contact("account-laura", List.of(), List.of(), List.of("subscription-a"), NotificationPreference.DEFAULTS);
        for (var category : List.of(NotificationCategory.OPERATIONAL, NotificationCategory.PERSONAL, NotificationCategory.CLUB_CHANGES, NotificationCategory.CLUB_NEWS)) {
            var custom = NotificationCatalog.variant(spec("N-24"), category);
            var row = Map.of(APP, true, EMAIL, false, SMS, false);
            assertThat(ChannelResolver.resolve(custom, MEMBER, row, on, subscribed)).as(category.name())
                    .containsExactly(new ChannelResolver.Planned(APP, "account-laura", DELIVERED), new ChannelResolver.Planned(PUSH, null, SKIPPED_BY_PREFERENCE));
            assertThat(ChannelResolver.resolve(custom, MEMBER, row, on, withNewsPush)).as(category.name())
                    .containsExactly(new ChannelResolver.Planned(APP, "account-laura", DELIVERED), new ChannelResolver.Planned(PUSH, "subscription-a", QUEUED));
        }
        // SYSTEM ignores preferences (every category off): the e-mail still goes.
        var allOff = new NotificationPreference(Map.of(NotificationCategory.OPERATIONAL, false, NotificationCategory.PERSONAL, false,
                NotificationCategory.CLUB_CHANGES, false, NotificationCategory.CLUB_NEWS, false), null, false, null, null);
        assertThat(allOff.email(NotificationCategory.SYSTEM)).isTrue();
        assertThat(ChannelResolver.resolve(spec("N-25"), MEMBER, Map.of(EMAIL, true), on, laura(allOff, phones)))
                .containsExactly(new ChannelResolver.Planned(EMAIL, "laura@example.test", QUEUED));
        // INSTRUCTORS and ADMINS ignore preferences entirely (N-17 APP + EMAIL with every e-mail category off).
        for (var staff : List.of(INSTRUCTORS, ADMINS)) {
            assertThat(ChannelResolver.resolve(spec("N-17"), staff, spec("N-17").defaultMatrix().get(staff), on, laura(allOff, phones)))
                    .containsExactly(new ChannelResolver.Planned(APP, "account-laura", DELIVERED), new ChannelResolver.Planned(EMAIL, "laura@example.test", QUEUED));
        }
        // OPERATIONAL e-mail is off by default (the mockup of 12): N-13 by e-mail only once the member switches it on.
        var n13 = spec("N-13"); var n13Row = n13.defaultMatrix().get(MEMBER);
        assertThat(ChannelResolver.resolve(n13, MEMBER, n13Row, on, laura(NotificationPreference.DEFAULTS, phones))).contains(new ChannelResolver.Planned(EMAIL, null, SKIPPED_BY_PREFERENCE));
        assertThat(ChannelResolver.resolve(n13, MEMBER, n13Row, on, laura(email(NotificationCategory.OPERATIONAL, true), phones)))
                .contains(new ChannelResolver.Planned(EMAIL, "laura@example.test", QUEUED));
    }

    @Test void T_11_07_atTheCapTheEmailIsForcedToEveryAddressWithoutOneWhateverThePreference() {
        var contact = new ChannelResolver.Contact("account-laura", List.of(new ChannelResolver.EmailAddress("laura@example.test", false),
                new ChannelResolver.EmailAddress("laura.work@example.test", false), new ChannelResolver.EmailAddress("old@example.test", true)), List.of(), List.of(),
                email(NotificationCategory.CLUB_CHANGES, false));
        assertThat(ChannelResolver.capReached(contact, List.of())).containsExactly(new ChannelResolver.Planned(EMAIL, "laura@example.test", QUEUED),
                new ChannelResolver.Planned(EMAIL, "laura.work@example.test", QUEUED));
        // An address that already has a live EMAIL delivery (the first capped SMS forced it) gets no second one.
        assertThat(ChannelResolver.capReached(contact, List.of("laura@example.test"))).containsExactly(new ChannelResolver.Planned(EMAIL, "laura.work@example.test", QUEUED));
        assertThat(ChannelResolver.capReached(new ChannelResolver.Contact(null, null, null, null, null), List.of())).isEmpty();
        // Round 2 (review #4): one per address compared without case, as the first resolution writes them; a blank one is none.
        var twice = new ChannelResolver.Contact("account-laura", List.of(new ChannelResolver.EmailAddress(" Laura@Example.test ", false),
                new ChannelResolver.EmailAddress("laura@example.test", false), new ChannelResolver.EmailAddress(" ", false),
                new ChannelResolver.EmailAddress("LAURA.WORK@example.test", false)), List.of(), List.of(), null);
        assertThat(ChannelResolver.capReached(twice, List.of())).containsExactly(new ChannelResolver.Planned(EMAIL, "Laura@Example.test", QUEUED),
                new ChannelResolver.Planned(EMAIL, "LAURA.WORK@example.test", QUEUED));
        assertThat(ChannelResolver.capReached(twice, List.of("laura.work@example.test ", "LAURA@example.test"))).isEmpty();
    }
}
