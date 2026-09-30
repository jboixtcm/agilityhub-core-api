package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.support.PushKeyFixtures;
import com.agilityhub.core.shared.application.TransactionRetries;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/**
 * E7-T03, the pieces of the S11 services without a database: R-11-07's endpoint and key checks and the device label read
 * from the User-Agent; the retried transaction of every E7-T03 write (a write conflict is retried, three are `409
 * STALE_VERSION`, any other failure passes through); the `diff` of `MessageTemplateChanged` and
 * `NotificationPreferencesChanged` (top-level fields, as the catalogs' events).
 */
class MessagingE7T03UnitTest {
    static String key(int size, int first) { var bytes = new byte[size]; bytes[0] = (byte) first; return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }

    @Test void R_11_07_theEndpointAndTheKeysOfAPushSubscription() {
        String valid = PushKeyFixtures.p256dh();
        assertThatCode(() -> PushSubscriptionService.validate("https://fcm.example.test/send/abc", valid, key(16, 1))).doesNotThrowAnyException();
        assertThatCode(() -> PushSubscriptionService.validate("https://fcm.example.test/send/abc", PushKeyFixtures.ROUTE_P256DH, PushKeyFixtures.auth()))
                .doesNotThrowAnyException();
        String padded = Base64.getUrlEncoder().encodeToString(Base64.getUrlDecoder().decode(valid));
        assertThatCode(() -> PushSubscriptionService.validate(" https://fcm.example.test/send/abc ", padded, key(16, 1))).doesNotThrowAnyException();
        // Round 2 (review #4): the right length and prefix are not enough — (0, 0), a point off the curve and a coordinate ≥ p are refused.
        var p = new java.math.BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);
        var invalid = Map.of("endpoint", new String[] {"http://fcm.example.test/x", "https://user@fcm.example.test/x", "https:///x", "not a url", "mailto:a@b.test"},
                "keys.p256dh", new String[] {key(64, 4), key(65, 3), "a+b/c", "", key(65, 4), PushKeyFixtures.zeroPoint(), PushKeyFixtures.offCurve(),
                        PushKeyFixtures.point(p, java.math.BigInteger.ONE)},
                "keys.auth", new String[] {key(15, 1), key(17, 1), "***"});
        invalid.forEach((field, values) -> {
            for (String value : values) {
                String endpoint = field.equals("endpoint") ? value : "https://fcm.example.test/x";
                String p256dh = field.equals("keys.p256dh") ? value : valid, auth = field.equals("keys.auth") ? value : key(16, 1);
                var failure = catchThrowableOfType(() -> PushSubscriptionService.validate(endpoint, p256dh, auth), ApiException.class);
                assertThat(failure).as(field + " " + value).isNotNull();
                assertThat(failure.code()).isEqualTo(ErrorCode.PUSH_SUBSCRIPTION_INVALID); assertThat(failure.code().httpStatus()).isEqualTo(422);
                assertThat(failure.details()).containsEntry("field", field);
            }
        });
    }

    @Test void R_11_07_theDeviceLabelOfTheUserAgent() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/129.0 Mobile/15E148 Safari/604.1", "iPad · Chrome");
        cases.put("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/131.0 Mobile/15E148 Safari/605.1.15", "iPhone · Firefox");
        cases.put("Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/25.0 Chrome/121.0 Mobile Safari/537.36", "Android · Samsung Internet");
        cases.put("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36 OPR/114.0", "Linux · Opera");
        cases.put("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15", "Mac · Safari");
        cases.put("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0", "Windows · Firefox");
        cases.put("SomeBot/1.0 (Windows NT 10.0)", "Windows");
        cases.put("curl/8.7.1 Chrome/1.0", "Chrome");
        cases.forEach((agent, label) -> assertThat(PushSubscriptionService.label(agent)).as(agent).isEqualTo(label));
        assertThat(PushSubscriptionService.label(null)).isNull(); assertThat(PushSubscriptionService.label(" ")).isNull();
        assertThat(PushSubscriptionService.label("curl/8.7.1")).isNull();
    }

    /** A template whose transaction is the callback itself (the unit has no database). */
    static final class Direct extends TransactionTemplate {
        @Override public <T> T execute(TransactionCallback<T> action) { TransactionStatus status = new SimpleTransactionStatus(); return action.doInTransaction(status); }
    }

    @Test void E7_T03_aWriteConflictIsRetriedAndThreeAreStaleVersion() {
        var registry = new SimpleMeterRegistry();
        var transactions = new MessagingTransactions(new Direct(), new TransactionRetries(registry));
        var attempts = new AtomicInteger();
        assertThat(transactions.write(() -> { if (attempts.incrementAndGet() < 3) { throw new DuplicateKeyException("E11000"); } return "saved"; })).isEqualTo("saved");
        assertThat(attempts).hasValue(3);
        var always = new AtomicInteger();
        var failure = catchThrowableOfType(() -> transactions.run(() -> { always.incrementAndGet(); throw new DuplicateKeyException("E11000"); }), ApiException.class);
        assertThat(failure.code()).isEqualTo(ErrorCode.STALE_VERSION); assertThat(always).hasValue(3);
        var retries = new TransactionRetries(registry);
        assertThat(retries.exhaustions("messaging")).isEqualTo(1); assertThat(retries.retries("messaging")).isEqualTo(4);
        // Any other failure is the caller's, at once.
        var once = new AtomicInteger();
        assertThatThrownBy(() -> transactions.run(() -> { once.incrementAndGet(); throw new ApiException(ErrorCode.TEMPLATE_MANDATORY); }))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.TEMPLATE_MANDATORY));
        assertThat(once).hasValue(1);
    }

    @Test void E66_playoffsEssentialOnlyReadsAsClubNewsWithoutEmailNorPushUntilTheMemberSavesTheirOwn() {
        var essential = com.agilityhub.core.clubs.messaging.domain.NotificationPreference.of(Map.of("essentialOnly", true));
        assertThat(essential.email(com.agilityhub.core.clubs.messaging.domain.NotificationCategory.CLUB_NEWS)).isFalse();
        assertThat(essential.pushClubNews()).isFalse();
        assertThat(essential.email(com.agilityhub.core.clubs.messaging.domain.NotificationCategory.PERSONAL)).isTrue();
        var saved = com.agilityhub.core.clubs.messaging.domain.NotificationPreference.of(Map.of("essentialOnly", true, "pushClubNews", true,
                "emailByCategory", Map.of("CLUB_NEWS", true)));
        assertThat(saved.email(com.agilityhub.core.clubs.messaging.domain.NotificationCategory.CLUB_NEWS)).isTrue(); assertThat(saved.pushClubNews()).isTrue();
        var notEssential = com.agilityhub.core.clubs.messaging.domain.NotificationPreference.of(Map.of("essentialOnly", false));
        assertThat(notEssential.email(com.agilityhub.core.clubs.messaging.domain.NotificationCategory.CLUB_NEWS)).isTrue(); assertThat(notEssential.pushClubNews()).isTrue();
    }

    /**
     * Round 2, review #3: D9 shows a template in the club's languages. A template stored with every product language shows
     * the club's ones (the club's default first); one already in the club's languages is the same object; a text with none of
     * the club's languages is shown as stored; one with only a non-default club language keeps it as its own default.
     */
    @Test void E7_T03_d9ShowsATemplateInTheClubsLanguages() {
        var club = new com.agilityhub.core.platform.application.ClubConfig(new com.agilityhub.core.platform.application.ClubConfig.ClubView("club", "club",
                "Club Agility Exemple", java.util.List.of("ca", "es"), "ca", "Europe/Madrid", "EUR", null, null, "ACTIVE", null), Map.of(), java.util.Set.of(), null, Map.of());
        java.util.function.Function<Map<String, String>, com.agilityhub.core.shared.domain.LocalizedText> text = values -> new com.agilityhub.core.shared.domain.LocalizedText(values, "ca");
        java.util.function.BiFunction<Map<String, String>, Map<String, String>, com.agilityhub.core.clubs.messaging.persistence.MessageTemplate> template = (title, sms) ->
                new com.agilityhub.core.clubs.messaging.persistence.MessageTemplate("t-1", "club", "N-09", com.agilityhub.core.clubs.messaging.domain.TemplateKind.CATALOG,
                        com.agilityhub.core.clubs.messaging.domain.NotificationCategory.PERSONAL, text.apply(title), text.apply(title), sms == null ? null : text.apply(sms),
                        com.agilityhub.core.clubs.messaging.domain.TemplateIcon.up, com.agilityhub.core.clubs.messaging.domain.TemplateColor.OK, Map.of(), true, false, false,
                        com.agilityhub.core.clubs.messaging.domain.TemplateStatus.ACTIVE, 0L, java.time.Instant.EPOCH, "seed", java.time.Instant.EPOCH, "seed");
        var every = template.apply(new java.util.LinkedHashMap<>(Map.of("en", "Up", "ca", "Puja", "es", "Sube")), Map.of("ca", "SMS", "en", "SMS en"));
        var shown = MessageTemplateService.normalized(every, club);
        assertThat(shown.title().values()).containsOnlyKeys("ca", "es"); assertThat(shown.title().values().keySet()).containsExactly("ca", "es");
        assertThat(shown.smsBody().values()).isEqualTo(Map.of("ca", "SMS")); assertThat(shown.title().defaultLocale()).isEqualTo("ca");
        assertThat(shown.id()).isEqualTo("t-1"); assertThat(shown.version()).isZero();
        var clubOnly = template.apply(Map.of("ca", "Puja", "es", "Sube"), null);
        assertThat(MessageTemplateService.normalized(clubOnly, club)).isSameAs(clubOnly);
        var foreign = template.apply(Map.of("en", "Up"), null);
        assertThat(MessageTemplateService.normalized(foreign, club)).isSameAs(foreign);
        var spanish = MessageTemplateService.normalized(template.apply(Map.of("es", "Sube", "en", "Up"), null), club);
        assertThat(spanish.title().values()).isEqualTo(Map.of("es", "Sube")); assertThat(spanish.title().defaultLocale()).isEqualTo("es");
    }

    @Test void E7_T03_theDiffNamesTheChangedTopLevelFieldsWithTheirWholeValues() {
        var before = new LinkedHashMap<String, Object>(); before.put("title", Map.of("ca", "A")); before.put("enabled", true); before.put("reminderMinutesBefore", 120);
        var after = new LinkedHashMap<String, Object>(); after.put("title", Map.of("ca", "B")); after.put("enabled", true); after.put("reminderMinutesBefore", null);
        after.put("icon", "x");
        var diff = TemplateDiff.between(before, after);
        assertThat(diff).containsOnlyKeys("title", "reminderMinutesBefore", "icon");
        assertThat(diff.get("title")).isEqualTo(Map.of("before", Map.of("ca", "A"), "after", Map.of("ca", "B")));
        var reminder = new LinkedHashMap<String, Object>(); reminder.put("before", 120); reminder.put("after", null);
        assertThat(diff.get("reminderMinutesBefore")).isEqualTo(reminder);
        assertThat(TemplateDiff.between(null, Map.of("icon", "x"))).containsOnlyKeys("icon");
        assertThat(TemplateDiff.between(Map.of("icon", "x"), null)).containsOnlyKeys("icon");
        assertThat(TemplateDiff.between(Map.of("icon", "x"), Map.of("icon", "x"))).isEmpty();
    }
}
