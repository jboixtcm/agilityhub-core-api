package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.application.EmailMessage;
import com.agilityhub.core.clubs.messaging.application.EmailSender;
import com.agilityhub.core.clubs.messaging.application.integrations.AllowListSmsSender;
import com.agilityhub.core.clubs.messaging.application.integrations.PushResult;
import com.agilityhub.core.clubs.messaging.application.integrations.SendResult;
import com.agilityhub.core.clubs.messaging.application.ports.InMemoryMessagingPorts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFacts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.clubs.messaging.domain.DeliveryStatus;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.clubs.messaging.persistence.PushSubscription;
import com.agilityhub.core.platform.application.ClubSmsUsage;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.ConcurrencySupport;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import static com.agilityhub.core.clubs.messaging.domain.NotificationAudience.*;
import static org.assertj.core.api.Assertions.*;

/**
 * E7-T02 steps 6–8 over real documents: the dispatcher's retries and backoff (T-11-09), its atomic claim (T-11-30), the SMS
 * monthly cap with the forced e-mail and N-49 (T-11-07, R-11-06 example), the club-local month (T-11-15), one SMS per phone
 * and the non-production allow-list (T-11-06), and the push outcomes (T-11-10).
 */
class NotificationDispatcherIT extends EngineFixtures {
    @org.springframework.beans.factory.annotation.Autowired com.agilityhub.core.clubs.messaging.application.SendGridWebhookService webhooks;
    @org.springframework.beans.factory.annotation.Autowired com.agilityhub.core.clubs.messaging.application.MigrateNotificationsCommand migrate;
    private static final Map<String, Object> NO_CLUB_CHANGES_EMAIL = Map.of("emailByCategory", Map.of("CLUB_CHANGES", false));

    private Notification memberNotice(String code, String memberId) {
        return stored(CLUB, code).stream().filter(n -> n.audience() == MEMBER && memberId.equals(n.recipient().memberId())).findFirst().orElseThrow();
    }
    private Notification.Delivery delivery(Notification n, NotificationChannel channel) {
        return n.deliveries().stream().filter(d -> d.channel() == channel).findFirst().orElseThrow();
    }
    private Notification reload(Notification n) { return mongo.findById(n.id(), Notification.class); }
    private void levelChange(String memberId, String dogId) { deliver(CLUB, "DogLevelChanged", Map.of("memberId", memberId, "dogId", dogId, "levelName", "C")); }
    private int dispatch(NotificationDispatcher using, Notification n) { try (var tenant = TenantContext.open(n.clubId())) { return using.dispatch(List.of(n.id())); } }
    private void usageOf(String clubId, long monthKey, long sent) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(clubId)), new Update().set(ClubSmsUsage.MONTH, monthKey).set(ClubSmsUsage.SENT, sent).unset(ClubSmsUsage.NOTICE), Club.class);
    }
    private Map<String, Long> usage(String clubId) { return clubs.findById(clubId).orElseThrow().usage(); }

    @Test void T_11_09_aRetryableFailureWaitsOneFiveFifteenSixtyMinutesAndTheFifthIsFinal() {
        for (int i = 0; i < 5; i++) { mail.script.add(EmailSender.SendResult.failed("SendGrid HTTP 503")); }
        levelChange("member-marc", "dog-ares");
        var n = memberNotice("N-09", "member-marc");
        Instant start = clock.instant();
        var email = delivery(reload(n), NotificationChannel.EMAIL);
        assertThat(email.status()).isEqualTo(DeliveryStatus.QUEUED); assertThat(email.attempts()).isEqualTo(1);
        assertThat(email.nextAttemptAt()).isEqualTo(start.plus(Duration.ofMinutes(1))); assertThat(email.lastError()).isEqualTo("SendGrid HTTP 503");
        assertThat(email.claimToken()).isNull(); assertThat(email.claimedUntil()).isNull();
        // Not due yet: nothing is claimed.
        assertThat(dispatch(dispatcher, n)).isZero();
        long[] waits = {1, 5, 15, 60};
        for (int attempt = 2; attempt <= 4; attempt++) {
            clock.advance(Duration.ofMinutes(waits[attempt - 2]));
            assertThat(dispatch(dispatcher, n)).isEqualTo(1);
            email = delivery(reload(n), NotificationChannel.EMAIL);
            assertThat(email.status()).isEqualTo(DeliveryStatus.QUEUED); assertThat(email.attempts()).isEqualTo(attempt);
            assertThat(email.nextAttemptAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(waits[attempt - 1])));
        }
        // The 5th failure is final: FAILED + NotificationFailed{notificationId, channel}.
        clock.advance(Duration.ofMinutes(60));
        assertThat(dispatch(dispatcher, n)).isEqualTo(1);
        email = delivery(reload(n), NotificationChannel.EMAIL);
        assertThat(email.status()).isEqualTo(DeliveryStatus.FAILED); assertThat(email.attempts()).isEqualTo(5); assertThat(email.failedAt()).isEqualTo(clock.instant());
        assertThat(outbox(CLUB, "NotificationFailed")).singleElement().satisfies(e -> {
            assertThat(e.getString("aggregateId")).isEqualTo(n.id()); assertThat(e.get("payload", Document.class).getString("channel")).isEqualTo("EMAIL");
        });
        assertThat(mail.to("marc@example.test")).isEmpty();
        clock.advance(Duration.ofDays(1)); assertThat(dispatch(dispatcher, n)).isZero();

        // A non-retryable answer (a rejected request, an invalid address) is final at once.
        mail.script.add(EmailSender.SendResult.failed("SendGrid HTTP 400"));
        levelChange("member-anna", "dog-lluna");
        var anna = memberNotice("N-09", "member-anna");
        assertThat(reload(anna).deliveries()).filteredOn(d -> d.channel() == NotificationChannel.EMAIL).extracting(Notification.Delivery::status)
                .containsExactlyInAnyOrder(DeliveryStatus.FAILED, DeliveryStatus.SENT);
        // A sender that throws is a transient failure; an SMS refused by the provider is retried too, its counted reservation given back.
        mail.during = () -> { throw new IllegalStateException("provider outage"); };
        sms.failNext(SendResult.retryable("Twilio HTTP 503"));
        classSession(CLUB, "e7t02-class-a", "2026-10-08T18:50", List.of("instructor-marta"));
        deliver(CLUB, "ClassCancelledByClub", Map.of("classId", "e7t02-class-a", "reason", "DELETED", "adminText", "x", "affected", affected("member-marc", "dog-ares", "b3")));
        var cancelled = reload(memberNotice("N-08a", "member-marc"));
        assertThat(delivery(cancelled, NotificationChannel.EMAIL)).satisfies(d -> { assertThat(d.status()).isEqualTo(DeliveryStatus.QUEUED); assertThat(d.lastError()).isEqualTo("IllegalStateException"); });
        assertThat(delivery(cancelled, NotificationChannel.SMS)).satisfies(d -> { assertThat(d.status()).isEqualTo(DeliveryStatus.QUEUED); assertThat(d.attempts()).isEqualTo(1); });
        assertThat(usage(CLUB).get("smsSentMonth")).isZero();
        assertThat(NotificationDispatcher.emailRetryable("SendGrid timeout")).isTrue(); assertThat(NotificationDispatcher.emailRetryable(null)).isTrue();
        assertThat(NotificationDispatcher.emailRetryable("SendGrid HTTP 429")).isTrue(); assertThat(NotificationDispatcher.emailRetryable("SendGrid HTTP 404")).isFalse();
        assertThat(NotificationDispatcher.emailRetryable("SendGrid HTTP 502")).isTrue();
    }

    /**
     * E6-T03 round 3 (S10 R-10-10, review of 27-09 21:19): before every attempt, retries included, the dispatcher asks the owners
     * of the notification's event whether it still applies ({@link NotificationFactsPort#deliverable}). A hook that throws is a
     * transient failure of that attempt; `false` ends the delivery as `SKIPPED_STALE`, with no provider call and no event, for
     * good; `true` sends as before. A notification without an event type has no owner to ask. Before the fix the stored
     * recipient was retried whatever its owner would answer.
     */
    @Test void R_10_10_everyAttemptAsksTheEventsOwnersWhetherTheNoticeStillApplies() {
        mail.script.add(EmailSender.SendResult.failed("SendGrid HTTP 503"));
        levelChange("member-marc", "dog-ares");
        var n = memberNotice("N-09", "member-marc");
        assertThat(delivery(reload(n), NotificationChannel.EMAIL).status()).isEqualTo(DeliveryStatus.QUEUED);
        var answers = new ArrayDeque<Object>(List.of(new IllegalStateException("census unavailable"), false, true));
        var asked = new ArrayList<String>();
        owners = List.of(new NotificationFactsPort() {
            @Override public Set<String> eventTypes() { return Set.of("DogLevelChanged"); }
            @Override public Optional<NotificationFacts> facts(NotificationTrigger trigger, String code) { return Optional.empty(); }
            @Override public boolean deliverable(StoredNotification notification, String channel) {
                asked.add(notification.code() + " " + notification.memberId() + " " + notification.subject().dogId() + " " + channel);
                var answer = answers.poll();
                if (answer instanceof RuntimeException failure) { throw failure; }
                return Boolean.TRUE.equals(answer);
            }
        });
        var checking = dispatcher(sms);
        // The hook throws: a transient failure of the attempt, retried 5 minutes later; nothing is sent.
        clock.advance(Duration.ofMinutes(1));
        assertThat(dispatch(checking, n)).isEqualTo(1);
        assertThat(delivery(reload(n), NotificationChannel.EMAIL)).satisfies(d -> {
            assertThat(d.status()).isEqualTo(DeliveryStatus.QUEUED); assertThat(d.attempts()).isEqualTo(2); assertThat(d.lastError()).isEqualTo("IllegalStateException");
        });
        // `false`: SKIPPED_STALE, no provider call, no NotificationSent/NotificationFailed, and nothing left to retry.
        clock.advance(Duration.ofMinutes(5));
        assertThat(dispatch(checking, n)).isEqualTo(1);
        assertThat(delivery(reload(n), NotificationChannel.EMAIL)).satisfies(d -> {
            assertThat(d.status()).isEqualTo(DeliveryStatus.SKIPPED_STALE); assertThat(d.attempts()).isEqualTo(2);
            assertThat(d.lastError()).isEqualTo("No longer relevant to its recipient"); assertThat(d.claimToken()).isNull();
        });
        assertThat(mail.to("marc@example.test")).isEmpty();
        for (String type : List.of("NotificationSent", "NotificationFailed")) {
            assertThat(outbox(CLUB, type)).as(type).noneSatisfy(e -> assertThat(e.getString("aggregateId")).isEqualTo(n.id()));
        }
        clock.advance(Duration.ofDays(1)); assertThat(dispatch(checking, n)).isZero();
        assertThat(asked).containsExactly("N-09 member-marc dog-ares EMAIL", "N-09 member-marc dog-ares EMAIL");
        // `true`: the retry is sent as before.
        mail.script.add(EmailSender.SendResult.failed("SendGrid HTTP 503"));
        levelChange("member-anna", "dog-lluna");
        var anna = memberNotice("N-09", "member-anna");
        clock.advance(Duration.ofMinutes(1));
        assertThat(dispatch(checking, anna)).isEqualTo(1);
        assertThat(reload(anna).deliveries()).filteredOn(d -> d.channel() == NotificationChannel.EMAIL).extracting(Notification.Delivery::status).containsOnly(DeliveryStatus.SENT);
        assertThat(asked).hasSize(3).last().isEqualTo("N-09 member-anna dog-lluna EMAIL");
        // Without an event type there is no owner to ask: sent.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(anna.id())), new Update().unset("eventType").push("deliveries", new Notification.Delivery(NotificationChannel.EMAIL,
                "late@example.test", DeliveryStatus.QUEUED, 0, clock.instant(), null, null, null, null, null)), Notification.class);
        assertThat(dispatch(checking, anna)).isEqualTo(1);
        assertThat(mail.to("late@example.test")).hasSize(1); assertThat(asked).hasSize(3);
    }

    @Test void T_11_30_twoDispatchersOnTheSameQueuedDeliveriesSendEachOnce() throws Exception {
        // 20 notices whose first e-mail attempt failed: 20 QUEUED deliveries due a minute later.
        var names = List.of("member-laura", "member-marc", "member-anna", "member-pau");
        for (int i = 0; i < 20; i++) { deliver(CLUB, "DogRegistered", Map.of("memberId", names.get(i % 4), "dogId", "dog-" + i)); }
        var ids = stored(CLUB, "N-37").stream().map(Notification::id).toList();
        assertThat(ids).hasSize(20);
        // N-37 is APP only (born DELIVERED): each notice gets one QUEUED e-mail delivery, due now, that nobody claimed yet.
        assertThat(stored(CLUB, "N-37")).allSatisfy(n -> assertThat(channels(n)).containsExactly("APP:DELIVERED"));
        for (var id : ids) { mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().push("deliveries", new Notification.Delivery(NotificationChannel.EMAIL,
                "target-" + id + "@example.test", DeliveryStatus.QUEUED, 0, clock.instant(), null, null, null, null, null)), Notification.class); }
        var second = dispatcher(sms);
        var results = ConcurrencySupport.parallel(2, i -> () -> { try (var tenant = TenantContext.open(CLUB)) { return (i == 0 ? dispatcher : second).dispatch(ids); } });
        assertThat(results.get(0) + results.get(1)).isEqualTo(20);
        assertThat(mail.sent).extracting(m -> m.tags().get("notificationId")).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(stored(CLUB, "N-37")).allSatisfy(n -> assertThat(delivery(n, NotificationChannel.EMAIL).status()).isEqualTo(DeliveryStatus.SENT));
        // While one dispatcher holds the lease (its provider call in flight), the other claims nothing; a lease left by a crashed
        // sender is claimable again once it expires.
        mail.sent.clear();
        var n = stored(CLUB, "N-37").getFirst();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(n.id())), new Update().push("deliveries", new Notification.Delivery(NotificationChannel.EMAIL,
                "late@example.test", DeliveryStatus.QUEUED, 0, clock.instant(), null, null, null, null, null)), Notification.class);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        mail.during = () -> { entered.countDown(); try { release.await(20, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } };
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> dispatch(dispatcher, n));
            assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatch(second, n)).isZero();
            release.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        } finally { pool.shutdownNow(); mail.during = () -> { }; }
        assertThat(mail.to("late@example.test")).hasSize(1);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(n.id())), new Update().push("deliveries", new Notification.Delivery(NotificationChannel.EMAIL,
                "crash@example.test", DeliveryStatus.QUEUED, 0, clock.instant(), null, null, null, null, null)), Notification.class);
        try (var tenant = TenantContext.open(CLUB)) { assertThat(notifications.claimDue(n.id(), clock.instant(), NotificationDispatcher.LEASE, "crashed-sender")).isPresent(); }
        assertThat(dispatch(second, n)).isZero();
        // The 5-second poll (every club, no tenant) takes it once the lease expired.
        clock.advance(NotificationDispatcher.LEASE);
        assertThat(second.poll()).isGreaterThanOrEqualTo(1);
        assertThat(mail.to("crash@example.test")).hasSize(1); assertThat(delivery(reload(n), NotificationChannel.EMAIL).status()).isEqualTo(DeliveryStatus.SENT);
        assertThat(reload(n).deliveries()).filteredOn(d -> "crash@example.test".equals(d.target())).singleElement()
                .satisfies(d -> { assertThat(d.status()).isEqualTo(DeliveryStatus.SENT); assertThat(d.claimToken()).isNull(); });
        // A result written with a lost lease changes nothing.
        try (var tenant = TenantContext.open(CLUB)) { assertThat(notifications.settle(n.id(), "crashed-sender", update -> update.set("deliveries.$.status", "FAILED"))).isFalse(); }
    }

    @Test void T_11_07_theMonthlyCapSkipsTheSmsForcesTheEmailAndNotifiesTheAdminsOncePerMonth() {
        // R-11-06 example: 998 sent this month, N-08a to 3 members with 2 phones each → 2 SMS go out, 4 are SKIPPED_CAP with a forced e-mail.
        classSession(CLUB, "e7t02-class-a", "2026-10-08T18:50", List.of("instructor-marta"));
        var noEmail = Map.<String, Object>of("emailByCategory", Map.of("CLUB_CHANGES", false));
        ports.update("member-laura", c -> InMemoryMessagingPorts.with(c, null, noEmail, null, null));
        ports.update("member-marc", c -> InMemoryMessagingPorts.with(c, null, noEmail, null, List.of("+34600000003", "+34600000013")));
        ports.update("member-pau", c -> InMemoryMessagingPorts.with(c, null, noEmail, null, List.of("+34600000004", "+34600000014")));
        usageOf(CLUB, 202610, 998);
        deliver(CLUB, "ClassCancelledByClub", Map.of("classId", "e7t02-class-a", "reason", "DELETED", "adminText", "x",
                "affected", affected("member-laura", "dog-duna", "b1", "member-marc", "dog-ares", "b2", "member-pau", "dog-nit", "b3")));
        var members = stored(CLUB, "N-08a").stream().filter(n -> n.audience() == MEMBER).map(this::reload).toList();
        var smsStatuses = members.stream().flatMap(n -> n.deliveries().stream()).filter(d -> d.channel() == NotificationChannel.SMS).map(Notification.Delivery::status).toList();
        assertThat(smsStatuses).containsExactlyInAnyOrder(DeliveryStatus.SENT, DeliveryStatus.SENT, DeliveryStatus.SKIPPED_CAP, DeliveryStatus.SKIPPED_CAP,
                DeliveryStatus.SKIPPED_CAP, DeliveryStatus.SKIPPED_CAP);
        assertThat(sms.messages()).hasSize(2); assertThat(usage(CLUB)).containsEntry("smsSentMonth", 1000L).containsEntry("smsMonthKey", 202610L);
        // Every member with a capped SMS gets the e-mail although the preference is off: one forced delivery per address, sent.
        for (var n : members) {
            boolean capped = n.deliveries().stream().anyMatch(d -> d.status() == DeliveryStatus.SKIPPED_CAP);
            var emails = n.deliveries().stream().filter(d -> d.channel() == NotificationChannel.EMAIL).map(d -> d.status() + ":" + d.target()).toList();
            if (capped) { assertThat(emails).containsExactly("SKIPPED_BY_PREFERENCE:null", "SENT:" + ports.members.get(n.recipient().memberId()).emails().getFirst().address()); }
            else { assertThat(emails).containsExactly("SKIPPED_BY_PREFERENCE:null"); }
        }
        assertThat(members.stream().filter(n -> n.deliveries().stream().anyMatch(d -> d.status() == DeliveryStatus.SKIPPED_CAP))).hasSize(2);
        // SmsCapReached{month, cap} once → N-49 to the admins (APP + EMAIL, preferences ignored), with the month written out.
        var capEvents = outbox(CLUB, "SmsCapReached");
        assertThat(capEvents).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class)).containsEntry("month", "2026-10").containsEntry("cap", 1000L));
        deliver(capEvents.getFirst().getString("_id"), CLUB, "SmsCapReached", Map.of("month", "2026-10", "cap", 1000));
        assertThat(stored(CLUB, "N-49")).hasSize(2).allSatisfy(n -> { assertThat(n.audience()).isEqualTo(ADMINS); assertThat(channels(n)).containsExactly("APP:DELIVERED", "EMAIL:SENT"); });
        assertThat(stored(CLUB, "N-49")).filteredOn(n -> "ca".equals(n.locale())).singleElement().satisfies(n -> assertThat(n.body()).contains("1.000", "octubre del 2026"));
        // A later capped SMS in the same month: SKIPPED_CAP again, no second SmsCapReached.
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-08a")), Notification.class);
        deliver(CLUB, "ClassCancelledByClub", Map.of("classId", "e7t02-class-a", "reason", "DELETED", "adminText", "y", "affected", affected("member-marc", "dog-ares", "b4")));
        assertThat(reload(memberNotice("N-08a", "member-marc")).deliveries()).filteredOn(d -> d.channel() == NotificationChannel.SMS)
                .extracting(Notification.Delivery::status).containsOnly(DeliveryStatus.SKIPPED_CAP);
        assertThat(outbox(CLUB, "SmsCapReached")).hasSize(1);
        // The club-local month changes (1 November, 00:30 in Madrid): the counter restarts and the SMS go out again.
        clock.setInstant(Instant.parse("2026-10-31T23:30:00Z"));
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-08a")), Notification.class);
        deliver(CLUB, "ClassCancelledByClub", Map.of("classId", "e7t02-class-a", "reason", "DELETED", "adminText", "z", "affected", affected("member-marc", "dog-ares", "b5")));
        assertThat(reload(memberNotice("N-08a", "member-marc")).deliveries()).filteredOn(d -> d.channel() == NotificationChannel.SMS)
                .extracting(Notification.Delivery::status).containsOnly(DeliveryStatus.SENT);
        assertThat(usage(CLUB)).containsEntry("smsMonthKey", 202611L).containsEntry("smsSentMonth", 2L);
        // T-11-07: 999 sends, 1000 is the cap (a cap of 0 sends nothing).
        usageOf(CLUB, 202611, 999);
        assertThat(usage.reserve(CLUB, YearMonth.of(2026, 11), 1000)).isTrue(); assertThat(usage.reserve(CLUB, YearMonth.of(2026, 11), 1000)).isFalse();
        assertThat(usage.sent(CLUB, YearMonth.of(2026, 11))).isEqualTo(1000); assertThat(usage.sent(CLUB, YearMonth.of(2026, 10))).isZero();
        assertThat(usage.reserve(CLUB, YearMonth.of(2026, 11), 0)).isFalse();
        usage.release(CLUB, YearMonth.of(2026, 11)); assertThat(usage.sent(CLUB, YearMonth.of(2026, 11))).isEqualTo(999);
        assertThat(usage.firstCapNotice(CLUB, YearMonth.of(2026, 11))).isTrue(); assertThat(usage.firstCapNotice(CLUB, YearMonth.of(2026, 11))).isFalse();
        assertThat(usage.sent("club-without-usage", YearMonth.of(2026, 11))).isZero();
    }

    @Test void T_11_15_theSmsCounterUsesEachClubsOwnMonth() {
        // 23:30 UTC on 31 October: already November in Madrid, still October in Buenos Aires.
        clock.setInstant(Instant.parse("2026-10-31T23:30:00Z"));
        classSession(CLUB, "e7t02-class-a", "2026-11-02T18:50", List.of("instructor-marta"));
        classSession(OTHER, "e7t02-class-ba", "2026-11-02T18:50", List.of("instructor-marta"));
        deliver(CLUB, "ClassCancelledByClub", Map.of("classId", "e7t02-class-a", "reason", "DELETED", "adminText", "x", "affected", affected("member-marc", "dog-ares", "b1")));
        deliver(OTHER, "ClassCancelledByClub", Map.of("classId", "e7t02-class-ba", "reason", "DELETED", "adminText", "x", "affected", affected("member-marc", "dog-ares", "b1")));
        assertThat(usage(CLUB)).containsEntry("smsMonthKey", 202611L).containsEntry("smsSentMonth", 1L);
        assertThat(usage(OTHER)).containsEntry("smsMonthKey", 202610L).containsEntry("smsSentMonth", 1L);
        // …and each club's own day: Monday's class is «mañana» from Madrid's Sunday and «lunes 2» from Buenos Aires's Saturday.
        assertThat(memberNotice("N-08a", "member-marc").body()).startsWith("Mañana · 18:50");
        assertThat(stored(OTHER, "N-08a").stream().filter(n -> n.audience() == MEMBER).findFirst().orElseThrow().body()).startsWith("Lunes 2 · 18:50");
    }

    @Test void T_11_06_oneSmsPerPhoneAndOutsideProductionOnlyTheAllowedNumbers() {
        classSession(CLUB, "e7t02-class-a", "2026-10-08T18:50", List.of("instructor-marta"));
        var guarded = dispatcher(new AllowListSmsSender(sms, Set.of("+34600000001")));
        engine = new NotificationEngine(configs, messages, templates, new RecipientResolver(ports, ports, ports), notifications, subscriptions, accounts, owners, ports, events,
                guarded, clock, transactions);
        String longText = "La pista central està mullada després de la tempesta de la nit i no és segura per als gossos: per això avui no podem fer la classe. "
                + "Podeu reservar-ne una altra des de l'app quan vulgueu.";
        deliver(CLUB, "ClassCancelledByClub", Map.of("classId", "e7t02-class-a", "reason", "DELETED", "adminText", longText, "affected", affected("member-laura", "dog-duna", "b1")));
        var laura = reload(memberNotice("N-08a", "member-laura"));
        assertThat(laura.deliveries()).filteredOn(d -> d.channel() == NotificationChannel.SMS).extracting(Notification.Delivery::target, Notification.Delivery::status)
                .containsExactly(tuple("+34600000001", DeliveryStatus.SENT), tuple("+34600000002", DeliveryStatus.SKIPPED_NOT_ALLOWED));
        assertThat(delivery(laura, NotificationChannel.SMS).providerRef()).startsWith("fake-sms-");
        assertThat(laura.deliveries()).filteredOn(d -> d.status() == DeliveryStatus.SKIPPED_NOT_ALLOWED).singleElement()
                .satisfies(d -> assertThat(d.lastError()).isEqualTo("Not in SMS_ALLOWED_NUMBERS"));
        assertThat(sms.messages()).extracting(m -> m.to()).containsExactly("+34600000001");
        assertThat(usage(CLUB).get("smsSentMonth")).isEqualTo(1L);
        // The SMS is cut at 160 in GSM-7; the app and the e-mail carry the whole text.
        var text = sms.lastTo("+34600000001").body();
        assertThat(text).hasSize(160).endsWith("...").doesNotContain("·", "’").startsWith("Club Agility Exemple: classe anul.lada demà 18:50 (B+C). La pista");
        assertThat(com.agilityhub.core.support.NotificationRows.gsm7(text)).isTrue();
        assertThat(sms.lastTo("+34600000001").senderId()).isEqualTo(configs.get(CLUB).get("messaging.sms.senderId", String.class));
        assertThat(laura.body()).contains(longText); assertThat(mail.to("laura@example.test").getFirst().text()).contains(longText);
        // An invalid number is a final failure of that delivery (no retry).
        sms.failNext(SendResult.failed("Twilio HTTP 400"));
        engine = new NotificationEngine(configs, messages, templates, new RecipientResolver(ports, ports, ports), notifications, subscriptions, accounts, owners, ports, events,
                dispatcher, clock, transactions);
        deliver(CLUB, "ClassCancelledByClub", Map.of("classId", "e7t02-class-a", "reason", "DELETED", "adminText", "x", "affected", affected("member-marc", "dog-ares", "b2")));
        assertThat(delivery(reload(memberNotice("N-08a", "member-marc")), NotificationChannel.SMS)).satisfies(d -> {
            assertThat(d.status()).isEqualTo(DeliveryStatus.FAILED); assertThat(d.attempts()).isEqualTo(1); assertThat(d.lastError()).isEqualTo("Twilio HTTP 400"); });
        assertThat(usage(CLUB).get("smsSentMonth")).isEqualTo(1L);
    }

    @Test void T_11_10_goneExpiresTheSubscriptionRetryableRetriesAndThreeFailuresExpireIt() {
        var startsAt = Instant.parse("2026-10-08T16:50:00Z");
        subscribe(CLUB, "subscription-laura", "account-laura");
        java.util.function.IntConsumer remind = i -> {
            ports.activeBookings.put("booking-" + i, startsAt);
            deliver(CLUB, "ReminderDue", Map.of("bookingId", "booking-" + i, "memberId", "member-laura", "dogId", "dog-duna", "startsAt", startsAt.toString()));
        };
        java.util.function.IntFunction<Notification> reminder = i -> reload(stored(CLUB, "N-13").stream().filter(n -> n.dedupKey().equals("N-13:booking-" + i)).findFirst().orElseThrow());
        java.util.function.Supplier<PushSubscription> subscription = () -> mongo.findById("subscription-laura", PushSubscription.class);
        // RETRYABLE → QUEUED with the backoff; then accepted → SENT, lastSuccessAt, failures back to 0.
        push.answer(PushResult.of(PushResult.Status.RETRYABLE, "Push HTTP 503"));
        remind.accept(1);
        assertThat(delivery(reminder.apply(1), NotificationChannel.PUSH)).satisfies(d -> { assertThat(d.status()).isEqualTo(DeliveryStatus.QUEUED); assertThat(d.attempts()).isEqualTo(1); });
        clock.advance(Duration.ofMinutes(1));
        dispatch(dispatcher, reminder.apply(1));
        assertThat(delivery(reminder.apply(1), NotificationChannel.PUSH).status()).isEqualTo(DeliveryStatus.SENT);
        assertThat(subscription.get().lastSuccessAt()).isEqualTo(clock.instant()); assertThat(subscription.get().failureCount()).isZero();
        // Three consecutive FAILED → EXPIRED (+ PushUnsubscribed) after the third; each delivery is FAILED, never retried.
        for (int i = 2; i <= 4; i++) {
            push.answer(PushResult.of(PushResult.Status.FAILED, "Push HTTP 400"));
            remind.accept(i);
            assertThat(delivery(reminder.apply(i), NotificationChannel.PUSH).status()).isEqualTo(DeliveryStatus.FAILED);
            assertThat(subscription.get().status()).isEqualTo(i < 4 ? PushSubscription.Status.ACTIVE : PushSubscription.Status.EXPIRED);
        }
        assertThat(subscription.get().failureCount()).isEqualTo(3);
        assertThat(outbox(CLUB, "PushUnsubscribed")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class))
                .containsEntry("accountId", "account-laura").containsEntry("endpoint", subscription.get().endpointHash()));
        // Without an active subscription → SKIPPED_NO_CONTACT.
        remind.accept(5);
        assertThat(channels(reminder.apply(5))).contains("PUSH:SKIPPED_NO_CONTACT");
        // GONE (404/410) → the subscription EXPIRED + PushUnsubscribed, the delivery FAILED, never retried.
        subscribe(CLUB, "subscription-laura-2", "account-laura");
        push.answer(PushResult.of(PushResult.Status.GONE, "Push HTTP 410"));
        remind.accept(6);
        assertThat(delivery(reminder.apply(6), NotificationChannel.PUSH)).satisfies(d -> { assertThat(d.status()).isEqualTo(DeliveryStatus.FAILED); assertThat(d.lastError()).isEqualTo("Push HTTP 410"); });
        assertThat(mongo.findById("subscription-laura-2", PushSubscription.class).status()).isEqualTo(PushSubscription.Status.EXPIRED);
        assertThat(outbox(CLUB, "PushUnsubscribed")).hasSize(2);
        clock.advance(Duration.ofHours(1)); assertThat(dispatch(dispatcher, reminder.apply(6))).isZero();
        // A subscription expired between the notice and its send: FAILED without calling the push service.
        subscribe(CLUB, "subscription-laura-3", "account-laura");
        push.answer(PushResult.of(PushResult.Status.RETRYABLE, "Push HTTP 503"));
        remind.accept(7);
        try (var tenant = TenantContext.open(CLUB)) { subscriptions.expire("subscription-laura-3", clock.instant()); }
        clock.advance(Duration.ofMinutes(1)); int calls = push.sent().size();
        dispatch(dispatcher, reminder.apply(7));
        assertThat(delivery(reminder.apply(7), NotificationChannel.PUSH)).satisfies(d -> { assertThat(d.status()).isEqualTo(DeliveryStatus.FAILED); assertThat(d.lastError()).isEqualTo("Push subscription expired"); });
        assertThat(push.sent()).hasSize(calls);
    }

    /**
     * Round 2, review #1 (R-11-08, S11 §5): the signed webhook of an e-mail can arrive before `EmailSender.send()` answers. Its
     * final state stays — `delivered` → DELIVERED, a hard `bounce` → FAILED with its error — and the settlement of the accepted
     * send only records `providerRef`, `sentAt` and the attempt and releases the lease, as E1's `finish()` does for the SYSTEM
     * rows. Before the fix the settlement matched the lease alone and wrote SENT over both, clearing the bounce's error.
     */
    @Test void R_11_08_aWebhookThatArrivesBeforeTheSendsAnswerKeepsItsFinalStateAndTheSendItsReference() {
        mail.inFlight = message -> webhooks.accept("sg-early-" + message.tags().get("notificationId"), message.to().startsWith("marc@") ? "delivered" : "bounce",
                message.tags().get("notificationId"), message.tags().get("clubId"), message.to(), "bounce");
        levelChange("member-marc", "dog-ares");
        levelChange("member-pau", "dog-nit");
        mail.inFlight = message -> { };
        var marc = memberNotice("N-09", "member-marc"); var pau = memberNotice("N-09", "member-pau");
        assertThat(delivery(reload(marc), NotificationChannel.EMAIL)).satisfies(d -> {
            assertThat(d.status()).isEqualTo(DeliveryStatus.DELIVERED); assertThat(d.deliveredAt()).isEqualTo(clock.instant()); assertThat(d.lastError()).isNull();
            assertThat(d.providerRef()).startsWith("mail-"); assertThat(d.sentAt()).isEqualTo(clock.instant()); assertThat(d.attempts()).isEqualTo(1);
            assertThat(d.claimToken()).isNull(); assertThat(d.claimedUntil()).isNull();
        });
        assertThat(delivery(reload(pau), NotificationChannel.EMAIL)).satisfies(d -> {
            assertThat(d.status()).isEqualTo(DeliveryStatus.FAILED); assertThat(d.lastError()).isEqualTo("SendGrid bounce"); assertThat(d.failedAt()).isEqualTo(clock.instant());
            assertThat(d.providerRef()).startsWith("mail-"); assertThat(d.sentAt()).isEqualTo(clock.instant()); assertThat(d.attempts()).isEqualTo(1);
            assertThat(d.claimToken()).isNull();
        });
        // Only the webhook's own event: the bounce's NotificationFailed; no NotificationSent over either final state.
        assertThat(outbox(CLUB, "NotificationSent")).noneSatisfy(e -> assertThat(e.getString("aggregateId")).isIn(marc.id(), pau.id()));
        assertThat(outbox(CLUB, "NotificationFailed")).filteredOn(e -> pau.id().equals(e.getString("aggregateId"))).hasSize(1);
        // Nothing is left to send: one provider call each.
        clock.advance(Duration.ofHours(1));
        assertThat(dispatch(dispatcher, marc) + dispatch(dispatcher, pau)).isZero();
        assertThat(mail.sent).extracting(EmailMessage::to).containsExactly("marc@example.test", "pau@example.test");
    }

    /**
     * Round 2, review #2 (R-11-09): a provider acceptance is never sent again. The settlement of an accepted send (its update and
     * its `NotificationSent`, one transaction) fails once — here the outbox write — and is retried with the accepted answer:
     * one provider call, the delivery SENT with its reference, one `NotificationSent`. Before the fix the failure was taken for
     * a retryable send failure, and the retry five minutes later sent the e-mail a second time.
     */
    @Test void R_11_09_aSettlementThatFailsAfterTheProviderAcceptedIsRetriedWithThatAnswerAndNeverSentAgain() {
        mail.script.add(EmailSender.SendResult.failed("SendGrid HTTP 503"));
        levelChange("member-marc", "dog-ares");
        var n = memberNotice("N-09", "member-marc");
        var outbox = new FailingOutbox(events, "NotificationSent", 1);
        var flaky = dispatcher(sms, ports, usage, outbox);
        clock.advance(Duration.ofMinutes(1));
        assertThat(dispatch(flaky, n)).isEqualTo(1);
        assertThat(outbox.failed).hasValue(1);
        assertThat(delivery(reload(n), NotificationChannel.EMAIL)).satisfies(d -> {
            assertThat(d.status()).isEqualTo(DeliveryStatus.SENT); assertThat(d.attempts()).isEqualTo(2); assertThat(d.providerRef()).startsWith("mail-");
            assertThat(d.lastError()).isNull(); assertThat(d.claimToken()).isNull();
        });
        assertThat(outbox(CLUB, "NotificationSent")).filteredOn(e -> n.id().equals(e.getString("aggregateId"))).hasSize(1);
        for (long minutes : new long[] {5, 15, 60}) { clock.advance(Duration.ofMinutes(minutes)); assertThat(dispatch(flaky, n)).isZero(); }
        assertThat(mail.to("marc@example.test")).hasSize(1);
        // A settlement that fails every one of its attempts waits in the dispatcher, its lease keeping the delivery from any
        // other claim, and is written before the next claims: still one provider call.
        var down = new FailingOutbox(events, "NotificationSent", NotificationDispatcher.SETTLE_ATTEMPTS);
        var patient = dispatcher(sms, ports, usage, down);
        mail.script.add(EmailSender.SendResult.failed("SendGrid HTTP 503"));
        levelChange("member-pau", "dog-nit");
        var pau = memberNotice("N-09", "member-pau");
        clock.advance(Duration.ofMinutes(1));
        assertThat(dispatch(patient, pau)).isEqualTo(1);
        assertThat(down.failed).hasValue(NotificationDispatcher.SETTLE_ATTEMPTS);
        assertThat(delivery(reload(pau), NotificationChannel.EMAIL)).satisfies(d -> { assertThat(d.status()).isEqualTo(DeliveryStatus.QUEUED); assertThat(d.claimToken()).isNotNull(); });
        assertThat(dispatch(dispatcher, pau)).isZero(); // the lease holds
        assertThat(dispatch(patient, pau)).isZero();    // the waiting settlement is written first; nothing is left to claim
        assertThat(delivery(reload(pau), NotificationChannel.EMAIL)).satisfies(d -> {
            assertThat(d.status()).isEqualTo(DeliveryStatus.SENT); assertThat(d.attempts()).isEqualTo(2); assertThat(d.claimToken()).isNull(); });
        assertThat(mail.to("pau@example.test")).hasSize(1);
        assertThat(outbox(CLUB, "NotificationSent")).filteredOn(e -> pau.id().equals(e.getString("aggregateId"))).hasSize(1);
    }

    /**
     * Round 2, review #3 (R-11-06, AGENTS rule 8): at the cap, the `SKIPPED_CAP` delivery, the forced e-mail, the month's
     * first-notice marker and `SmsCapReached` commit in one transaction; a failure between those writes rolls them back together
     * and the outcome is written again. (1) The member lookup of the forced e-mail fails once; (2) the marker is written and then
     * the transaction fails. Neither the forced e-mail nor N-49 is lost. Before the fix (1) left the SMS skipped and no e-mail
     * ever, and (2) left the marker without its event: no N-49 that month.
     */
    @Test void R_11_06_theCapsSkippedSmsForcedEmailMonthlyMarkerAndEventCommitTogether() {
        classSession(CLUB, "e7t02-class-a", "2026-10-08T18:50", List.of("instructor-marta"));
        ports.update("member-marc", c -> InMemoryMessagingPorts.with(c, null, NO_CLUB_CHANGES_EMAIL, null, null));
        ports.update("member-pau", c -> InMemoryMessagingPorts.with(c, null, NO_CLUB_CHANGES_EMAIL, null, null));
        usageOf(CLUB, 202610, 1000);
        // (1) The first lookup of Marc's contact for the forced e-mail fails.
        var lookups = new java.util.concurrent.atomic.AtomicInteger();
        var flakyDirectory = directory(memberId -> {
            if ("member-marc".equals(memberId) && lookups.getAndIncrement() == 0) { throw new IllegalStateException("census read failed (injected)"); }
        });
        engine = engine(dispatcher(sms, flakyDirectory, usage, events));
        deliver(CLUB, "ClassCancelledByClub", Map.of("classId", "e7t02-class-a", "reason", "DELETED", "adminText", "x", "affected", affected("member-marc", "dog-ares", "b1")));
        assertThat(lookups.get()).isGreaterThan(1);
        var marc = reload(memberNotice("N-08a", "member-marc"));
        assertThat(delivery(marc, NotificationChannel.SMS)).satisfies(d -> { assertThat(d.status()).isEqualTo(DeliveryStatus.SKIPPED_CAP); assertThat(d.claimToken()).isNull(); });
        assertThat(marc.deliveries()).filteredOn(d -> d.channel() == NotificationChannel.EMAIL).extracting(d -> d.status() + ":" + d.target())
                .containsExactly("SKIPPED_BY_PREFERENCE:null", "SENT:marc@example.test");
        assertThat(outbox(CLUB, "SmsCapReached")).hasSize(1); assertThat(usage(CLUB)).containsEntry("smsCapNoticeMonth", 202610L);
        // (2) A month without its notice yet: the marker is written, then the transaction fails before its event.
        usageOf(CLUB, 202610, 1000);
        var flakyUsage = org.mockito.Mockito.spy(usage); var thrown = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(call -> {
            Object marked = call.callRealMethod();
            if (thrown.compareAndSet(false, true)) { throw new IllegalStateException("failure after the marker (injected)"); }
            return marked;
        }).when(flakyUsage).firstCapNotice(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        engine = engine(dispatcher(sms, ports, flakyUsage, events));
        deliver(CLUB, "ClassCancelledByClub", Map.of("classId", "e7t02-class-a", "reason", "DELETED", "adminText", "y", "affected", affected("member-pau", "dog-nit", "b2")));
        assertThat(thrown).isTrue();
        var pau = reload(memberNotice("N-08a", "member-pau"));
        assertThat(delivery(pau, NotificationChannel.SMS).status()).isEqualTo(DeliveryStatus.SKIPPED_CAP);
        assertThat(pau.deliveries()).filteredOn(d -> d.channel() == NotificationChannel.EMAIL).extracting(d -> d.status() + ":" + d.target())
                .containsExactly("SKIPPED_BY_PREFERENCE:null", "SENT:pau@example.test");
        assertThat(outbox(CLUB, "SmsCapReached")).hasSize(2); assertThat(usage(CLUB)).containsEntry("smsCapNoticeMonth", 202610L);
        assertThat(sms.messages()).isEmpty();
    }

    /**
     * Round 2, review #4 (R-11-06, R-11-09): one forced e-mail per address. Marc has two phones; at the cap, two dispatchers settle
     * one phone each at the same moment (a gate makes both read the member together) → one e-mail delivery and one send: the
     * insertion matches no live EMAIL delivery to that address. Before the fix both appended their e-mail and both were sent.
     */
    @Test void R_11_06_twoPhonesAtTheCapDispatchedAtOnceForceOneEmail() throws Exception {
        classSession(CLUB, "e7t02-class-a", "2026-10-08T18:50", List.of("instructor-marta"));
        ports.update("member-marc", c -> InMemoryMessagingPorts.with(c, null, NO_CLUB_CHANGES_EMAIL, null, List.of("+34600000003", "+34600000013")));
        usageOf(CLUB, 202610, 1000);
        // The notice is stored without its inline dispatch: both SMS deliveries wait, due.
        engine = engine(idleDispatcher());
        deliver(CLUB, "ClassCancelledByClub", Map.of("classId", "e7t02-class-a", "reason", "DELETED", "adminText", "x", "affected", affected("member-marc", "dog-ares", "b1")));
        var n = reload(memberNotice("N-08a", "member-marc"));
        assertThat(n.deliveries()).filteredOn(d -> d.channel() == NotificationChannel.SMS).extracting(Notification.Delivery::status)
                .containsExactly(DeliveryStatus.QUEUED, DeliveryStatus.QUEUED);
        var gate = new CountDownLatch(2); var gated = java.util.concurrent.ConcurrentHashMap.<Thread>newKeySet();
        var together = directory(memberId -> {
            if ("member-marc".equals(memberId) && gated.add(Thread.currentThread())) {
                gate.countDown();
                try { gate.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        var first = dispatcher(sms, together, usage, events); var second = dispatcher(sms, together, usage, events);
        ConcurrencySupport.parallel(2, i -> () -> dispatch(i == 0 ? first : second, n));
        assertThat(gate.getCount()).isZero();
        var after = reload(n);
        assertThat(after.deliveries()).filteredOn(d -> d.channel() == NotificationChannel.SMS).extracting(Notification.Delivery::status)
                .containsExactly(DeliveryStatus.SKIPPED_CAP, DeliveryStatus.SKIPPED_CAP);
        assertThat(after.deliveries()).filteredOn(d -> d.channel() == NotificationChannel.EMAIL).extracting(d -> d.status() + ":" + d.target())
                .containsExactly("SKIPPED_BY_PREFERENCE:null", "SENT:marc@example.test");
        assertThat(mail.to("marc@example.test")).hasSize(1);
        assertThat(outbox(CLUB, "SmsCapReached")).hasSize(1);
    }

    /**
     * Round 2, review #6 (R-11-08, decision E12): the forced e-mail of the cap uses the engine's own suppression check — the
     * contact address's `bounced` mark **and** the account's `emailStatus`. Marc's only contact address is his login address, and
     * a SYSTEM e-mail bounced it (the account's mark only) → no e-mail delivery to it at the cap. Before the fix the forced e-mail
     * read the member's mark only and wrote to the bounced address.
     */
    @Test void R_11_08_theForcedEmailOfTheCapSkipsAnAddressOnlyTheAccountMarkedAsBounced() {
        classSession(CLUB, "e7t02-class-a", "2026-10-08T18:50", List.of("instructor-marta"));
        String login = accountEmail("account-marc");
        ports.update("member-marc", c -> InMemoryMessagingPorts.with(c, null, null,
                List.of(new com.agilityhub.core.clubs.messaging.application.ports.MemberContact.Email(login, false)), null));
        accounts.markEmailStatus("account-marc", login, com.agilityhub.core.shared.application.NotificationAccounts.EmailStatus.BOUNCED);
        usageOf(CLUB, 202610, 1000);
        deliver(CLUB, "ClassCancelledByClub", Map.of("classId", "e7t02-class-a", "reason", "DELETED", "adminText", "x", "affected", affected("member-marc", "dog-ares", "b1")));
        var marc = reload(memberNotice("N-08a", "member-marc"));
        assertThat(channels(marc)).containsExactlyInAnyOrder("APP:DELIVERED", "EMAIL:SKIPPED_NO_CONTACT", "SMS:SKIPPED_CAP");
        assertThat(mail.to(login)).isEmpty();
        assertThat(outbox(CLUB, "SmsCapReached")).hasSize(1);
    }

    /**
     * Round 2, review #5 as ruled (E69): the SMS and PUSH intents E4–E6 wrote before the engine (`QUEUED`, no destination, no
     * due time) are never sent. `messaging:migrate-notifications --apply` closes each such delivery as `SKIPPED_STALE`
     * («Written before the notification engine»): a flat row written before E7-T01 (an SMS intent with two stored phones, a PUSH
     * intent) and a row E7-T01's conversion already gave one target-less delivery. Nothing claims them; no provider is called.
     * Before the fix the conversion kept them `QUEUED` without a target.
     */
    @Test void R_11_06_theIntentsWrittenBeforeTheEngineAreClosedByTheMigrationAndNeverSent() {
        var created = java.util.Date.from(clock.instant());
        mongo.insert(new Document("_id", "e7t02-old-sms").append("clubId", CLUB).append("accountId", "account-laura").append("code", "N-15").append("channel", "SMS")
                .append("status", "QUEUED").append("recipientPhones", List.of("+34600000001", "+34600000002")).append("body", "Club: plaça lliure")
                .append("action", "CLAIM_SEAT").append("entityId", "entry-a").append("locale", "ca").append("createdAt", created), "notifications");
        mongo.insert(new Document("_id", "e7t02-old-push").append("clubId", CLUB).append("accountId", "account-laura").append("code", "N-13").append("channel", "PUSH")
                .append("status", "QUEUED").append("locale", "ca").append("createdAt", created), "notifications");
        mongo.insert(new Document("_id", "e7t02-converted-sms").append("clubId", CLUB).append("accountId", "account-marc").append("code", "N-36").append("channel", "SMS")
                .append("status", "QUEUED").append("recipientPhones", List.of("+34600000003")).append("dedupKey", "e7t02-converted-sms").append("category", "CLUB_CHANGES")
                .append("recipient", new Document("accountId", "account-marc")).append("smsBody", "Club: canvi").append("locale", "es").append("createdAt", created)
                .append("deliveries", List.of(new Document("channel", "SMS").append("target", null).append("status", "QUEUED").append("attempts", 0))), "notifications");
        var ids = List.of("e7t02-old-sms", "e7t02-old-push", "e7t02-converted-sms");
        assertThat(run("--dry-run")).contains("WOULD_CONVERT e7t02-old-sms", "WOULD_CONVERT e7t02-old-push", "WOULD_CLOSE e7t02-converted-sms").doesNotContain("+346");
        assertThat(mongo.getCollection("notifications").find(new Document("_id", "e7t02-converted-sms")).first().getString("status")).isEqualTo("QUEUED");
        assertThat(run("--apply")).contains("CONVERTED e7t02-old-sms", "CONVERTED e7t02-old-push", "CLOSED e7t02-converted-sms");
        for (String id : ids) {
            var raw = mongo.getCollection("notifications").find(new Document("_id", id)).first();
            assertThat(raw.getString("status")).as(id).isEqualTo("SKIPPED_STALE");
            assertThat(raw.getList("deliveries", Document.class)).as(id).singleElement().satisfies(d -> {
                assertThat(d.getString("status")).isEqualTo("SKIPPED_STALE"); assertThat(d.getString("lastError")).isEqualTo("Written before the notification engine");
                assertThat(d.get("target")).isNull();
            });
        }
        clock.advance(Duration.ofDays(1));
        try (var tenant = TenantContext.open(CLUB)) {
            for (String id : ids) { assertThat(notifications.claimDue(id, clock.instant(), NotificationDispatcher.LEASE, "lease-" + id)).as(id).isEmpty(); }
            assertThat(dispatcher.dispatch(ids)).isZero();
        }
        assertThat(sms.messages()).isEmpty(); assertThat(push.sent()).isEmpty();
        // Idempotent: nothing is left to convert or to close.
        assertThat(run("--apply")).doesNotContain("e7t02-");
    }

    private String run(String... arguments) {
        var out = new java.io.ByteArrayOutputStream(); var original = System.out;
        System.setOut(new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8));
        try { migrate.run(new org.springframework.boot.DefaultApplicationArguments(arguments)); } finally { System.setOut(original); }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
