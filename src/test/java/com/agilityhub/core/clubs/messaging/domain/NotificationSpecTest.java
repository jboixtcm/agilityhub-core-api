package com.agilityhub.core.clubs.messaging.domain;

import com.agilityhub.core.clubs.messaging.domain.NotificationSpec.DedupKeyRule;
import com.agilityhub.core.clubs.messaging.domain.NotificationSpec.RelevanceRule;
import java.time.Instant;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.agilityhub.core.clubs.messaging.domain.NotificationAudience.*;
import static org.assertj.core.api.Assertions.*;

/** E7-T01: the catalog's lookups and rules (R-11-09, R-11-16), the preference defaults (R-11-04) and the SMS month counter (R-11-06). */
class NotificationSpecTest {
    private static NotificationSpec spec(String code) { return NotificationCatalog.byCode(code).orElseThrow(); }

    @Test void WP_11_A_lookupsReturnTheRegisteredCodesAndOnlyR1CodesPerEvent() {
        assertThat(NotificationCatalog.codes()).hasSize(61).startsWith("N-01", "N-02").endsWith("N-54", "N-53");
        assertThat(NotificationCatalog.byCode("N-12")).isEmpty(); assertThat(NotificationCatalog.byCode(null)).isEmpty();
        assertThat(NotificationCatalog.specsFor("BookingCreated")).extracting(NotificationSpec::code).containsExactly("N-04", "N-36", "N-46");
        assertThat(NotificationCatalog.specsFor("BookingCancelled")).extracting(NotificationSpec::code).containsExactly("N-05", "N-36", "N-40");
        assertThat(NotificationCatalog.specsFor("ClassCancelledByClub")).extracting(NotificationSpec::code).containsExactly("N-08a");
        assertThat(NotificationCatalog.specsFor("InvoiceFailed")).extracting(NotificationSpec::code).containsExactly("N-10", "N-35");
        // LATER codes are registered but never emitted (N-44, N-45 R2; N-48 «fora de R1»).
        assertThat(NotificationCatalog.byCode("N-48")).isPresent();
        assertThat(NotificationCatalog.specsFor("MembershipChanged")).isEmpty();
        assertThat(NotificationCatalog.specsFor("ChallengePublished")).isEmpty();
        assertThat(NotificationCatalog.specsFor("NoSuchEvent")).isEmpty();
        assertThat(spec("N-01").action(ADMINS)).isEqualTo(NotificationActionType.OPEN_SIGNUP);
        assertThat(spec("N-01").action(APPLICANT)).isNull();
        assertThat(spec("N-15").caps(MEMBER)).contains(NotificationChannel.SMS);
        assertThat(spec("N-15").defaultChannels(MEMBER)).containsExactlyInAnyOrder(NotificationChannel.APP, NotificationChannel.SMS, NotificationChannel.PUSH);
        assertThat(spec("N-25").templated()).isFalse(); assertThat(spec("N-02").templated()).isTrue();
    }

    @Test void T_11_08_dedupKeysAreStablePerEventRecipientAndDogAndFollowTheN13AndN24Exceptions() {
        var input = new DedupKeyRule.Input("event-a", MEMBER, "account-a", "member-a", "dog-a", Map.of());
        assertThat(spec("N-08a").dedupKey(input)).isEqualTo("event-a:N-08a:MEMBER:account-a:dog-a");
        // The same event reprocessed → the same key (no second document); two dogs of the same member → two keys.
        assertThat(spec("N-08a").dedupKey(input)).isEqualTo(spec("N-08a").dedupKey(new DedupKeyRule.Input("event-a", MEMBER, "account-a", "member-a", "dog-a", null)));
        assertThat(spec("N-08a").dedupKey(new DedupKeyRule.Input("event-a", MEMBER, "account-a", "member-a", "dog-b", Map.of()))).isNotEqualTo(spec("N-08a").dedupKey(input));
        // A person in two audiences (a booked member who is also an admin) → two notifications.
        assertThat(spec("N-08a").dedupKey(new DedupKeyRule.Input("event-a", ADMINS, "account-a", null, null, Map.of()))).isEqualTo("event-a:N-08a:ADMINS:account-a");
        // No account: `email:` + the normalized address.
        assertThat(DedupKeyRule.recipientKey(null, "  Laura@Example.TEST ")).isEqualTo("email:laura@example.test");
        assertThat(DedupKeyRule.recipientKey("account-a", "laura@example.test")).isEqualTo("account-a");
        assertThatThrownBy(() -> DedupKeyRule.recipientKey(null, null)).isInstanceOf(IllegalArgumentException.class);
        // N-13: one reminder per booking, whatever the number of ReminderDue events.
        assertThat(spec("N-13").dedupKey(new DedupKeyRule.Input("event-1", MEMBER, "account-a", "member-a", "dog-a", Map.of("bookingId", "booking-a"))))
                .isEqualTo(spec("N-13").dedupKey(new DedupKeyRule.Input("event-2", MEMBER, "account-a", "member-a", "dog-a", Map.of("bookingId", "booking-a"))))
                .isEqualTo("N-13:booking-a");
        assertThat(spec("N-13").dedupKey(new DedupKeyRule.Input("event-1", MEMBER, "account-a", "member-a", null, Map.of("trainingBookingId", "training-a"))))
                .isEqualTo("N-13:training-a");
        // N-24: {batchId}:{memberId}.
        assertThat(spec("N-24").dedupKey(new DedupKeyRule.Input("event-1", MEMBER, "account-a", "member-a", null, Map.of("batchId", "batch-a")))).isEqualTo("batch-a:member-a");
        assertThatThrownBy(() -> spec("N-24").dedupKey(new DedupKeyRule.Input("event-1", MEMBER, "account-a", null, null, Map.of("batchId", "batch-a"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> spec("N-13").dedupKey(new DedupKeyRule.Input("event-1", MEMBER, "account-a", "member-a", null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> spec("N-04").dedupKey(new DedupKeyRule.Input(null, MEMBER, "account-a", null, null, Map.of()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void T_11_13_aReminderIsOnlyRelevantForAnActiveBookingThatStillLiesAhead() {
        Instant now = Instant.parse("2026-09-28T07:00:00Z");
        var rule = spec("N-13").stillRelevantFn();
        assertThat(rule.stillRelevant(() -> Optional.of(new RelevanceRule.Booking(true, now.plusSeconds(60))), now)).isTrue();
        assertThat(rule.stillRelevant(() -> Optional.of(new RelevanceRule.Booking(false, now.plusSeconds(60))), now)).as("cancelled").isFalse();
        assertThat(rule.stillRelevant(() -> Optional.of(new RelevanceRule.Booking(true, now)), now)).as("started").isFalse();
        assertThat(rule.stillRelevant(() -> Optional.of(new RelevanceRule.Booking(true, null)), now)).isFalse();
        assertThat(rule.stillRelevant(Optional::empty, now)).as("unknown booking").isFalse();
        // ALWAYS never reads the booking.
        assertThat(spec("N-04").stillRelevantFn().stillRelevant(() -> { throw new AssertionError("not read"); }, now)).isTrue();
    }

    @Test void T_11_03_T_11_20_absentPreferencesReadAsTheProductDefaultsAndUnknownKeysSurviveAWrite() {
        var defaults = NotificationPreference.of(null);
        assertThat(defaults).isEqualTo(NotificationPreference.of(Map.of())).isEqualTo(NotificationPreference.DEFAULTS);
        assertThat(defaults.emailByCategory()).containsExactly(Map.entry(NotificationCategory.OPERATIONAL, false), Map.entry(NotificationCategory.PERSONAL, true),
                Map.entry(NotificationCategory.CLUB_CHANGES, true), Map.entry(NotificationCategory.CLUB_NEWS, true));
        assertThat(defaults.reminderMinutesBefore()).isNull(); assertThat(defaults.pushClubNews()).isTrue();
        assertThat(defaults.email(NotificationCategory.SYSTEM)).as("SYSTEM ignores preferences").isTrue();
        assertThat(defaults.email(NotificationCategory.OPERATIONAL)).isFalse();
        // A stored block with only some keys: the rest are the defaults; the Playoff migration's `essentialOnly` survives a write.
        Instant at = Instant.parse("2026-09-28T07:00:00Z");
        var stored = new LinkedHashMap<String, Object>(Map.of("essentialOnly", true, "emailByCategory", Map.of("CLUB_CHANGES", false, "SYSTEM", false, "X", true),
                "reminderMinutesBefore", 120, "pushClubNews", false, "updatedAt", java.util.Date.from(at), "updatedByAccountId", "account-a"));
        var read = NotificationPreference.of(stored);
        assertThat(read.email(NotificationCategory.CLUB_CHANGES)).isFalse(); assertThat(read.email(NotificationCategory.PERSONAL)).isTrue();
        assertThat(read.emailByCategory()).doesNotContainKey(NotificationCategory.SYSTEM);
        assertThat(read.reminderMinutesBefore()).isEqualTo(120); assertThat(read.pushClubNews()).isFalse();
        assertThat(read.updatedAt()).isEqualTo(at); assertThat(read.updatedByAccountId()).isEqualTo("account-a");
        var written = read.changedBy("account-b", at.plusSeconds(1)).toDocument(stored);
        assertThat(written).containsEntry("essentialOnly", true).containsEntry("reminderMinutesBefore", 120).containsEntry("pushClubNews", false)
                .containsEntry("updatedByAccountId", "account-b").containsEntry("updatedAt", at.plusSeconds(1));
        // E66 (E7-T03): `essentialOnly` reads as CLUB_NEWS without e-mail (the block names no CLUB_NEWS of its own).
        assertThat(written.get("emailByCategory")).isEqualTo(Map.of("OPERATIONAL", false, "PERSONAL", true, "CLUB_CHANGES", false, "CLUB_NEWS", false));
        assertThat(NotificationPreference.of(written)).isEqualTo(read.changedBy("account-b", at.plusSeconds(1)));
        assertThatThrownBy(() -> read.changedBy("account-b", null)).isInstanceOf(NullPointerException.class);
    }

    @Test void T_11_07_T_11_15_theSmsCounterCountsTheClubLocalMonthAndRestartsWithANewOne() {
        var september = YearMonth.of(2026, 9);
        assertThat(SmsMonthlyUsage.monthKey(september)).isEqualTo(202609L);
        assertThat(SmsMonthlyUsage.of(null, september)).isEqualTo(new SmsMonthlyUsage(0, 202609));
        assertThat(SmsMonthlyUsage.of(Map.of("membersActive", 184L), september).sent()).isZero();
        var stored = Map.of(SmsMonthlyUsage.SENT, 999L, SmsMonthlyUsage.MONTH, 202609L);
        var usage = SmsMonthlyUsage.of(stored, september);
        assertThat(usage.reached(1000)).as("999 → still sends").isFalse();
        assertThat(usage.afterOneMore()).containsExactly(Map.entry("smsSentMonth", 1000L), Map.entry("smsMonthKey", 202609L));
        assertThat(SmsMonthlyUsage.of(usage.afterOneMore(), september).reached(1000)).as("1000 → SKIPPED_CAP").isTrue();
        // The club-local month changes (Europe/Madrid and Buenos Aires can be in different months at the same instant): the count restarts.
        var october = SmsMonthlyUsage.of(stored, YearMonth.of(2026, 10));
        assertThat(october).isEqualTo(new SmsMonthlyUsage(0, 202610));
        assertThat(october.afterOneMore()).containsEntry("smsSentMonth", 1L).containsEntry("smsMonthKey", 202610L);
        Instant instant = Instant.parse("2026-10-01T01:30:00Z");
        assertThat(YearMonth.from(instant.atZone(java.time.ZoneId.of("Europe/Madrid")))).isEqualTo(YearMonth.of(2026, 10));
        assertThat(YearMonth.from(instant.atZone(java.time.ZoneId.of("America/Argentina/Buenos_Aires")))).isEqualTo(september);
    }

    @Test void WP_11_A_aSpecRejectsChannelsOutsideItsCapsAndMisplacedAudiences() {
        var base = spec("N-04");
        assertThatThrownBy(() -> new NotificationSpec("N-99", List.of(), NotificationCategory.OPERATIONAL, List.of(MEMBER), Map.of(MEMBER, Set.of(NotificationChannel.APP)),
                Map.of(MEMBER, Set.of(NotificationChannel.SMS)), Set.of(), Map.of(), List.of(), Set.of(), false, TemplateIcon.bell, TemplateColor.NEUTRAL,
                DedupKeyRule.DEFAULT, RelevanceRule.ALWAYS, Set.of(), NotificationSpec.Stage.R1)).hasMessageContaining("outside the caps");
        assertThatThrownBy(() -> new NotificationSpec("N-99", List.of(), NotificationCategory.OPERATIONAL, List.of(MEMBER), Map.of(MEMBER, Set.of(NotificationChannel.PUSH)),
                Map.of(), Set.of(), Map.of(), List.of(), Set.of(), false, TemplateIcon.bell, TemplateColor.NEUTRAL,
                DedupKeyRule.DEFAULT, RelevanceRule.ALWAYS, Set.of(), NotificationSpec.Stage.R1)).hasMessageContaining("PUSH");
        assertThatThrownBy(() -> new NotificationSpec("N-99", List.of(), NotificationCategory.OPERATIONAL, List.of(MEMBER), base.caps(), Map.of(MEMBER, Set.of()),
                Set.of(), Map.of(), List.of(), Set.of(), false, TemplateIcon.bell, TemplateColor.NEUTRAL,
                DedupKeyRule.DEFAULT, RelevanceRule.ALWAYS, Set.of(), NotificationSpec.Stage.R1)).hasMessageContaining("no channel");
        assertThatThrownBy(() -> new NotificationSpec("N-99", List.of(), NotificationCategory.OPERATIONAL, List.of(MEMBER), base.caps(), base.defaults(),
                Set.of(ADMINS), Map.of(), List.of(), Set.of(), false, TemplateIcon.bell, TemplateColor.NEUTRAL,
                DedupKeyRule.DEFAULT, RelevanceRule.ALWAYS, Set.of(), NotificationSpec.Stage.R1)).hasMessageContaining("an audience outside");
        assertThatThrownBy(() -> new NotificationSpec("N-99", List.of(), NotificationCategory.OPERATIONAL, List.of(MEMBER), base.caps(), base.defaults(),
                Set.of(), Map.of(), List.of("a"), Set.of("b"), false, TemplateIcon.bell, TemplateColor.NEUTRAL,
                DedupKeyRule.DEFAULT, RelevanceRule.ALWAYS, Set.of(), NotificationSpec.Stage.R1)).hasMessageContaining("required variable");
        assertThatThrownBy(() -> new NotificationSpec("N-99", List.of(), NotificationCategory.OPERATIONAL, List.of(), Map.of(), Map.of(), Set.of(), Map.of(), List.of(),
                Set.of(), false, TemplateIcon.bell, TemplateColor.NEUTRAL, DedupKeyRule.DEFAULT, RelevanceRule.ALWAYS, Set.of(), NotificationSpec.Stage.R1))
                .hasMessageContaining("no audience");
        assertThat(DeliveryStatus.SENT.reached()).isTrue(); assertThat(DeliveryStatus.DELIVERED.reached()).isTrue(); assertThat(DeliveryStatus.QUEUED.reached()).isFalse();
    }
}
