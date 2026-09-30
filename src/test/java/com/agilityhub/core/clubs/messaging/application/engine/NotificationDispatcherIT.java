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
import com.agilityhub.core.clubs.messaging.support.PushKeyFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
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
    @org.springframework.beans.factory.annotation.Autowired com.agilityhub.core.clubs.messaging.application.PushSubscriptionService pushSubscriptions;
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
        assertThat(usage.reserve(CLUB, YearMonth.of(2026, 11), 1000).granted()).isTrue(); assertThat(usage.reserve(CLUB, YearMonth.of(2026, 11), 1000).granted()).isFalse();
        assertThat(usage.sent(CLUB, YearMonth.of(2026, 11))).isEqualTo(1000); assertThat(usage.sent(CLUB, YearMonth.of(2026, 10))).isZero();
        assertThat(usage.reserve(CLUB, YearMonth.of(2026, 11), 0).granted()).isFalse();
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
        // S11 §8's N-08a SMS (E7-T03); cut at 160 at most (the space before the «...» is dropped).
        assertThat(text).hasSizeBetween(157, 160).endsWith("...").doesNotContain("·", "’")
                .startsWith("Club Agility Exemple: la classe de demà a les 18:50 (B+C) queda anul.lada. La pista");
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
     * E7-T03 round 2, review #1 (R-11-07, E76): a push queued for Laura's account never reaches Marc, who signs in on the same
     * browser afterwards. Marc's `POST /push-subscriptions` of the same endpoint gets a row of its own (Laura's stays hers and
     * ends), so Laura's queued retry still names her row, which the dispatcher no longer sends to; and at every attempt the
     * dispatcher checks that the row is the recipient's — a row whose owner changed (as round 1's take-over left it) is never
     * sent to. Before the fix Marc's POST moved Laura's row to him, and her retry was pushed to his browser with her texts.
     */
    @Test void R_11_07_aPushQueuedForOneAccountNeverReachesTheNextAccountOnTheSameBrowser() {
        var startsAt = Instant.parse("2026-10-08T16:50:00Z");
        java.util.function.IntFunction<Notification> remind = i -> {
            ports.activeBookings.put("booking-" + i, startsAt);
            push.answer(PushResult.of(PushResult.Status.RETRYABLE, "Push HTTP 503"));
            deliver(CLUB, "ReminderDue", Map.of("bookingId", "booking-" + i, "memberId", "member-laura", "dogId", "dog-duna", "startsAt", startsAt.toString()));
            return reload(stored(CLUB, "N-13").stream().filter(n -> n.dedupKey().equals("N-13:booking-" + i)).findFirst().orElseThrow());
        };
        var laura = subscribe(CLUB, "subscription-laura", "account-laura");
        var first = remind.apply(1);
        assertThat(delivery(first, NotificationChannel.PUSH)).satisfies(d -> {
            assertThat(d.status()).isEqualTo(DeliveryStatus.QUEUED); assertThat(d.target()).isEqualTo("subscription-laura"); });
        // Laura signs out on this browser; Marc signs in on it and subscribes the same endpoint.
        String marc;
        try (var tenant = TenantContext.open(CLUB)) {
            try (var user = com.agilityhub.core.shared.application.CurrentUser.open(new com.agilityhub.core.shared.application.CurrentUser("account-laura", "Laura", null,
                    DomainEvent.Origin.APP))) { pushSubscriptions.unsubscribe("subscription-laura"); }
            try (var user = com.agilityhub.core.shared.application.CurrentUser.open(new com.agilityhub.core.shared.application.CurrentUser("account-marc", "Marc", null,
                    DomainEvent.Origin.APP))) { marc = pushSubscriptions.subscribe(laura.endpoint(), PushKeyFixtures.p256dh(), PushKeyFixtures.auth(), null, "UA"); }
        }
        assertThat(marc).isNotEqualTo("subscription-laura");
        assertThat(mongo.findById("subscription-laura", PushSubscription.class)).satisfies(s -> {
            assertThat(s.accountId()).isEqualTo("account-laura"); assertThat(s.status()).isEqualTo(PushSubscription.Status.EXPIRED); });
        assertThat(mongo.findById(marc, PushSubscription.class).accountId()).isEqualTo("account-marc");
        clock.advance(Duration.ofMinutes(1)); int calls = push.sent().size();
        assertThat(dispatch(dispatcher, first)).isEqualTo(1);
        assertThat(push.sent()).hasSize(calls);
        assertThat(delivery(reload(first), NotificationChannel.PUSH)).satisfies(d -> {
            assertThat(d.status()).isEqualTo(DeliveryStatus.FAILED); assertThat(d.lastError()).isEqualTo("Push subscription expired"); });
        // A row whose owner is not the delivery's recipient any more (round 1 moved rows in place) is never sent to either.
        subscribe(CLUB, "subscription-moved", "account-laura");
        var second = remind.apply(2);
        assertThat(delivery(second, NotificationChannel.PUSH).target()).isEqualTo("subscription-moved");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("subscription-moved")), new Update().set("accountId", "account-marc"), PushSubscription.class);
        clock.advance(Duration.ofMinutes(1)); calls = push.sent().size();
        assertThat(dispatch(dispatcher, second)).isEqualTo(1);
        assertThat(push.sent()).hasSize(calls);
        assertThat(delivery(reload(second), NotificationChannel.PUSH)).satisfies(d -> {
            assertThat(d.status()).isEqualTo(DeliveryStatus.FAILED); assertThat(d.lastError()).isEqualTo("Push subscription of another account"); });
        assertThat(push.sent()).noneSatisfy(sent -> assertThat(sent.subscriptionId()).isIn(marc, "subscription-moved"));
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

    // ---- E7-T05: E7-T02's round-2 review (the dispatcher's failure paths).

    /** A new N-09 to the member (each level change is its own occurrence). */
    private Notification levelNotice(String memberId, String dogId) {
        var before = stored(CLUB, "N-09").stream().map(Notification::id).collect(java.util.stream.Collectors.toSet());
        levelChange(memberId, dogId);
        return stored(CLUB, "N-09").stream().filter(n -> !before.contains(n.id()) && memberId.equals(n.recipient().memberId())).findFirst().orElseThrow();
    }
    /** The delivery of `channel` as stored (with the fields the dispatcher writes on it). */
    private Document storedDelivery(String notificationId, String channel) {
        return mongo.getCollection("notifications").find(new Document("_id", notificationId)).first().getList("deliveries", Document.class).stream()
                .filter(d -> channel.equals(d.getString("channel"))).findFirst().orElseThrow();
    }
    private List<Document> events(String type, String aggregateId) {
        return outbox(CLUB, type).stream().filter(e -> aggregateId.equals(e.getString("aggregateId"))).toList();
    }
    /** The SendGrid webhook over the suite's in-memory census, so that a hard bounce marks the member the dispatcher reads. */
    private com.agilityhub.core.clubs.messaging.application.SendGridWebhookService webhookOverPorts() {
        return new com.agilityhub.core.clubs.messaging.application.SendGridWebhookService(notifications, receipts, accounts, events,
                new org.springframework.transaction.support.TransactionTemplate(transactions), clock, ports, ports);
    }
    @org.springframework.beans.factory.annotation.Autowired com.agilityhub.core.clubs.messaging.persistence.SendGridWebhookReceiptRepository receipts;

    /**
     * E7-T05 step 1 (review #1, R-11-09): the provider accepts, and the settlement keeps failing past the claim's lease (here
     * its `NotificationSent` outbox write). The acceptance is recorded on the delivery at once (`acceptedAt`, `providerRef`), so
     * the second dispatcher that polls once the lease expired claims the delivery only to settle it: no second provider call
     * while the database still fails, and the delivery ends `SENT` (the acceptance's `sentAt`, one `NotificationSent`) once it
     * answers. The first dispatcher's own waiting settlement then finds its lease taken and writes nothing. Before the fix the
     * acceptance lived only in the first dispatcher's memory, and the second one sent the e-mail again.
     */
    @Test void R_11_09_anAcceptanceWhoseSettlementOutlivesItsLeaseIsSettledByTheNextDispatcherWithoutASecondSend() {
        mail.script.add(EmailSender.SendResult.failed("SendGrid HTTP 503"));
        var n = levelNotice("member-marc", "dog-ares");
        var down = new FailingOutbox(events, "NotificationSent", Integer.MAX_VALUE);
        var first = dispatcher(sms, ports, usage, down);
        clock.advance(Duration.ofMinutes(1));
        Instant acceptedAt = clock.instant();
        assertThat(dispatch(first, n)).isEqualTo(1);
        assertThat(down.failed).hasValue(NotificationDispatcher.SETTLE_ATTEMPTS);
        assertThat(mail.to("marc@example.test")).hasSize(1);
        // The lease expires while the database still fails: the second dispatcher's poll sends nothing.
        clock.advance(NotificationDispatcher.LEASE);
        var second = dispatcher(sms, ports, usage, down);
        second.poll();
        assertThat(mail.to("marc@example.test")).hasSize(1);
        var waiting = storedDelivery(n.id(), "EMAIL");
        assertThat(waiting.getString("status")).isEqualTo("QUEUED"); assertThat(waiting.getString("providerRef")).startsWith("mail-");
        assertThat(waiting.getDate("acceptedAt").toInstant()).isEqualTo(acceptedAt); assertThat(waiting.getInteger("attempts")).isEqualTo(1);
        // The database answers: the waiting settlement is written with the provider's answer.
        down.left.set(0);
        second.poll();
        first.poll();
        assertThat(delivery(reload(n), NotificationChannel.EMAIL)).satisfies(d -> {
            assertThat(d.status()).isEqualTo(DeliveryStatus.SENT); assertThat(d.attempts()).isEqualTo(2); assertThat(d.sentAt()).isEqualTo(acceptedAt);
            assertThat(d.providerRef()).isEqualTo(waiting.getString("providerRef")); assertThat(d.claimToken()).isNull(); assertThat(d.lastError()).isNull();
        });
        assertThat(storedDelivery(n.id(), "EMAIL")).doesNotContainKey("acceptedAt");
        assertThat(events("NotificationSent", n.id())).hasSize(1);
        clock.advance(Duration.ofHours(2));
        assertThat(dispatch(second, n) + dispatch(first, n)).isZero();
        assertThat(mail.to("marc@example.test")).hasSize(1);
    }

    /**
     * E7-T05 step 1: the same after the dispatcher is replaced — a new bean with empty memory, as after a restart. While the
     * first dispatcher's lease holds, the new one claims nothing; once it expired, the new one settles the recorded
     * acceptance without calling the provider: Marc's e-mail ends `SENT` with the acceptance's reference and time. Pau's was
     * delivered (webhook) before any settlement: that state stays and the settlement only adds the acceptance (R-11-08), with
     * no `NotificationSent`. Before the fix the new dispatcher sent Marc's e-mail again.
     */
    @Test void R_11_09_anAcceptanceSurvivesTheDispatchersReplacement() {
        mail.script.add(EmailSender.SendResult.failed("SendGrid HTTP 503"));
        var marc = levelNotice("member-marc", "dog-ares");
        mail.script.add(EmailSender.SendResult.failed("SendGrid HTTP 503"));
        var pau = levelNotice("member-pau", "dog-nit");
        var stopped = dispatcher(sms, ports, usage, new FailingOutbox(events, "NotificationSent", Integer.MAX_VALUE));
        clock.advance(Duration.ofMinutes(1));
        Instant acceptedAt = clock.instant();
        assertThat(dispatch(stopped, marc) + dispatch(stopped, pau)).isEqualTo(2);
        assertThat(mail.sent).extracting(EmailMessage::to).containsExactly("marc@example.test", "pau@example.test");
        String reference = storedDelivery(marc.id(), "EMAIL").getString("providerRef");
        webhooks.accept("sg-e7t05-delivered", "delivered", pau.id(), CLUB, "pau@example.test");
        // The instance restarts: a new dispatcher, a healthy outbox, nothing in memory.
        var restarted = dispatcher(sms);
        assertThat(dispatch(restarted, marc) + dispatch(restarted, pau)).isZero();
        clock.advance(NotificationDispatcher.LEASE);
        dispatch(restarted, marc); dispatch(restarted, pau);
        assertThat(mail.sent).extracting(EmailMessage::to).containsExactly("marc@example.test", "pau@example.test");
        assertThat(reference).startsWith("mail-");
        assertThat(delivery(reload(marc), NotificationChannel.EMAIL)).satisfies(d -> {
            assertThat(d.status()).isEqualTo(DeliveryStatus.SENT); assertThat(d.attempts()).isEqualTo(2); assertThat(d.sentAt()).isEqualTo(acceptedAt);
            assertThat(d.providerRef()).isEqualTo(reference); assertThat(d.claimToken()).isNull();
        });
        assertThat(events("NotificationSent", marc.id())).hasSize(1);
        assertThat(delivery(reload(pau), NotificationChannel.EMAIL)).satisfies(d -> {
            assertThat(d.status()).isEqualTo(DeliveryStatus.DELIVERED); assertThat(d.attempts()).isEqualTo(2); assertThat(d.sentAt()).isEqualTo(acceptedAt);
            assertThat(d.providerRef()).startsWith("mail-"); assertThat(d.claimToken()).isNull(); assertThat(d.claimedUntil()).isNull();
        });
        assertThat(storedDelivery(pau.id(), "EMAIL")).doesNotContainKey("acceptedAt");
        assertThat(events("NotificationSent", pau.id())).isEmpty();
        clock.advance(Duration.ofHours(2));
        assertThat(dispatch(restarted, marc) + dispatch(stopped, marc)).isZero();
        assertThat(mail.sent).hasSize(2);
    }

    /**
     * E7-T05 step 2 (review #2, R-11-08): a hard bounce stops, at their next attempt, the e-mails to that address that are
     * already queued and the pending retries: the dispatcher checks both bounce marks before every e-mail attempt. Three N-09
     * to Marc: a retry pending after a 503, and two queued; the first queued one is sent and hard-bounces (webhook) → the
     * second and the retry end `SKIPPED_NO_CONTACT`, as a delivery resolved without an address: no provider call, no
     * `NotificationSent` nor `NotificationFailed`. The account's mark alone (a SYSTEM e-mail bounced Pau's login address)
     * stops a retry too. Before the fix both were sent to the bounced address.
     */
    @Test void R_11_08_aHardBounceStopsTheQueuedEmailsAndThePendingRetriesToThatAddress() {
        mail.script.add(EmailSender.SendResult.failed("SendGrid HTTP 503"));
        var retry = levelNotice("member-marc", "dog-ares");
        assertThat(delivery(reload(retry), NotificationChannel.EMAIL)).satisfies(d -> { assertThat(d.status()).isEqualTo(DeliveryStatus.QUEUED); assertThat(d.attempts()).isEqualTo(1); });
        var inline = engine;
        engine = engine(idleDispatcher());
        var first = levelNotice("member-marc", "dog-ares"); var second = levelNotice("member-marc", "dog-ares");
        assertThat(dispatch(dispatcher, first)).isEqualTo(1);
        assertThat(mail.to("marc@example.test")).hasSize(1);
        webhookOverPorts().accept("sg-e7t05-bounce", "bounce", first.id(), CLUB, "marc@example.test", "bounce");
        assertThat(delivery(reload(first), NotificationChannel.EMAIL).status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(ports.find("member-marc").orElseThrow().emails()).singleElement().satisfies(e -> assertThat(e.bounced()).isTrue());
        assertThat(dispatch(dispatcher, second)).isEqualTo(1);
        clock.advance(Duration.ofMinutes(1));
        assertThat(dispatch(dispatcher, retry)).isEqualTo(1);
        for (var n : List.of(second, retry)) {
            assertThat(delivery(reload(n), NotificationChannel.EMAIL)).as(n.id()).satisfies(d -> {
                assertThat(d.status()).isEqualTo(DeliveryStatus.SKIPPED_NO_CONTACT); assertThat(d.lastError()).isEqualTo("Email address suppressed");
                assertThat(d.attempts()).isEqualTo(n == retry ? 1 : 0); assertThat(d.claimToken()).isNull(); assertThat(d.sentAt()).isNull();
            });
            assertThat(events("NotificationSent", n.id())).isEmpty(); assertThat(events("NotificationFailed", n.id())).isEmpty();
        }
        assertThat(mail.to("marc@example.test")).hasSize(1);
        clock.advance(Duration.ofHours(2)); assertThat(dispatch(dispatcher, second) + dispatch(dispatcher, retry)).isZero();
        // The account's mark alone: Pau's only address is his login address, which a SYSTEM e-mail bounced after his notice was queued.
        String login = accountEmail("account-pau");
        ports.update("member-pau", c -> InMemoryMessagingPorts.with(c, null, null, List.of(new com.agilityhub.core.clubs.messaging.application.ports.MemberContact.Email(login, false)), null));
        engine = inline;
        mail.script.add(EmailSender.SendResult.failed("SendGrid HTTP 503"));
        var pau = levelNotice("member-pau", "dog-nit");
        assertThat(delivery(reload(pau), NotificationChannel.EMAIL)).satisfies(d -> { assertThat(d.status()).isEqualTo(DeliveryStatus.QUEUED); assertThat(d.target()).isEqualTo(login); });
        accounts.markEmailStatus("account-pau", login, com.agilityhub.core.shared.application.NotificationAccounts.EmailStatus.BOUNCED);
        clock.advance(Duration.ofMinutes(1));
        assertThat(dispatch(dispatcher, pau)).isEqualTo(1);
        assertThat(delivery(reload(pau), NotificationChannel.EMAIL).status()).isEqualTo(DeliveryStatus.SKIPPED_NO_CONTACT);
        assertThat(mail.to(login)).isEmpty();
    }

    /**
     * E7-T05 step 3 (review #3, R-11-07): on `GONE` the subscription's `EXPIRED` and its `PushUnsubscribed` are written by the
     * settlement's transaction, which keeps the push service's answer. (1) The `PushUnsubscribed` outbox write fails once →
     * the settlement is written again with the same answer: one call to the push service, the delivery `FAILED`, the
     * subscription `EXPIRED` with exactly one `PushUnsubscribed`. (2) The failure outlasts the settlement's attempts → the
     * answer waits in the dispatcher and is written before its next claims: still one call. Before the fix the expiry ran
     * apart and its failure was swallowed: the subscription stayed `ACTIVE`, and nothing would ever expire it.
     */
    @Test void R_11_07_aGoneAnswersExpiryIsPartOfTheRetriedSettlementAndIsNeverLost() {
        var startsAt = Instant.parse("2026-10-08T16:50:00Z");
        engine = engine(idleDispatcher());
        java.util.function.IntFunction<Notification> remind = i -> {
            ports.activeBookings.put("booking-" + i, startsAt);
            deliver(CLUB, "ReminderDue", Map.of("bookingId", "booking-" + i, "memberId", "member-laura", "dogId", "dog-duna", "startsAt", startsAt.toString()));
            return reload(stored(CLUB, "N-13").stream().filter(n -> n.dedupKey().equals("N-13:booking-" + i)).findFirst().orElseThrow());
        };
        java.util.function.Function<String, PushSubscription> subscription = id -> mongo.findById(id, PushSubscription.class);
        var counted = new CountingPush(push);
        // (1) One failure.
        subscribe(CLUB, "subscription-gone-1", "account-laura");
        var once = remind.apply(1);
        assertThat(delivery(once, NotificationChannel.PUSH)).satisfies(d -> { assertThat(d.status()).isEqualTo(DeliveryStatus.QUEUED); assertThat(d.target()).isEqualTo("subscription-gone-1"); });
        push.answer(PushResult.of(PushResult.Status.GONE, "Push HTTP 410"));
        var outbox = new FailingOutbox(events, "PushUnsubscribed", 1);
        dispatch(dispatcher(sms, ports, usage, outbox, counted), once);
        assertThat(outbox.failed).hasValue(1); assertThat(counted.calls).hasValue(1);
        assertThat(subscription.apply("subscription-gone-1").status()).isEqualTo(PushSubscription.Status.EXPIRED);
        assertThat(events("PushUnsubscribed", "subscription-gone-1")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class))
                .containsEntry("accountId", "account-laura").containsEntry("endpoint", subscription.apply("subscription-gone-1").endpointHash()));
        assertThat(delivery(reload(once), NotificationChannel.PUSH)).satisfies(d -> {
            assertThat(d.status()).isEqualTo(DeliveryStatus.FAILED); assertThat(d.lastError()).isEqualTo("Push HTTP 410"); assertThat(d.attempts()).isEqualTo(1); });
        assertThat(events("NotificationFailed", once.id())).hasSize(1);
        clock.advance(Duration.ofHours(1)); dispatch(dispatcher(sms, ports, usage, events, counted), once);
        assertThat(counted.calls).hasValue(1);
        // (2) The failure outlasts the settlement's attempts.
        subscribe(CLUB, "subscription-gone-2", "account-laura");
        var later = remind.apply(2);
        assertThat(delivery(later, NotificationChannel.PUSH).target()).isEqualTo("subscription-gone-2");
        push.answer(PushResult.of(PushResult.Status.GONE, "Push HTTP 404"));
        var down = new FailingOutbox(events, "PushUnsubscribed", NotificationDispatcher.SETTLE_ATTEMPTS);
        var patient = dispatcher(sms, ports, usage, down, counted);
        dispatch(patient, later);
        assertThat(down.failed).hasValue(NotificationDispatcher.SETTLE_ATTEMPTS); assertThat(counted.calls).hasValue(2);
        assertThat(subscription.apply("subscription-gone-2").status()).isEqualTo(PushSubscription.Status.ACTIVE);
        assertThat(delivery(reload(later), NotificationChannel.PUSH)).satisfies(d -> { assertThat(d.status()).isEqualTo(DeliveryStatus.QUEUED); assertThat(d.claimToken()).isNotNull(); });
        assertThat(dispatch(patient, later)).isZero();
        assertThat(subscription.apply("subscription-gone-2").status()).isEqualTo(PushSubscription.Status.EXPIRED);
        assertThat(events("PushUnsubscribed", "subscription-gone-2")).hasSize(1);
        assertThat(delivery(reload(later), NotificationChannel.PUSH)).satisfies(d -> { assertThat(d.status()).isEqualTo(DeliveryStatus.FAILED); assertThat(d.claimToken()).isNull(); });
        assertThat(counted.calls).hasValue(2);
    }

    /**
     * E7-T05 step 4 (review #4, R-11-06): the monthly counter and the notice marker only move forward. November (club-local)
     * began: another sender counted 5 SMS and wrote November's N-49 marker. A sender that computed October reaches
     * `reserve()` now → it counts in November (6) and never restarts October over November; its October `firstCapNotice` is
     * refused and November's marker stays, so November gets no second N-49. At November's cap the late sender is refused
     * too, and through the dispatcher its SMS is `SKIPPED_CAP` with `SmsCapReached{2026-11}`. Before the fix the late
     * reservation overwrote November with October/1 (the next November send restarted it again, so the cap no longer held),
     * and the late notice moved the marker back to October.
     */
    @Test void R_11_06_aSenderLateOnTheOldMonthNeverMovesTheCounterNorTheNoticeMarkerBack() {
        var october = YearMonth.of(2026, 10); var november = YearMonth.of(2026, 11);
        usageOf(CLUB, 202611, 5);
        assertThat(usage.firstCapNotice(CLUB, november)).isTrue();
        var late = usage.reserve(CLUB, october, 1000);
        assertThat(late).isEqualTo(new ClubSmsUsage.Reservation(november, true));
        assertThat(usage(CLUB)).containsEntry("smsMonthKey", 202611L).containsEntry("smsSentMonth", 6L).containsEntry("smsCapNoticeMonth", 202611L);
        assertThat(usage.firstCapNotice(CLUB, october)).isFalse(); assertThat(usage.firstCapNotice(CLUB, november)).isFalse();
        assertThat(usage(CLUB)).containsEntry("smsCapNoticeMonth", 202611L);
        // A failed late send gives back its reservation in the month it was counted in.
        usage.release(CLUB, late.month());
        assertThat(usage.sent(CLUB, november)).isEqualTo(5);
        // The cap still holds, whichever month the sender computed.
        usageOf(CLUB, 202611, 999);
        assertThat(usage.reserve(CLUB, october, 1000)).isEqualTo(new ClubSmsUsage.Reservation(november, true));
        assertThat(usage.reserve(CLUB, october, 1000)).isEqualTo(new ClubSmsUsage.Reservation(november, false));
        assertThat(usage.reserve(CLUB, november, 1000)).isEqualTo(new ClubSmsUsage.Reservation(november, false));
        assertThat(usage(CLUB)).containsEntry("smsMonthKey", 202611L).containsEntry("smsSentMonth", 1000L);
        // Through the dispatcher: its clock still reads 23:59:59 on 31 October in Madrid, a second behind the sender that began November.
        clock.setInstant(Instant.parse("2026-10-31T22:59:59Z"));
        classSession(CLUB, "e7t05-class", "2026-11-02T18:50", List.of("instructor-marta"));
        deliver(CLUB, "ClassCancelledByClub", Map.of("classId", "e7t05-class", "reason", "DELETED", "adminText", "x", "affected", affected("member-marc", "dog-ares", "b1")));
        assertThat(delivery(reload(memberNotice("N-08a", "member-marc")), NotificationChannel.SMS).status()).isEqualTo(DeliveryStatus.SKIPPED_CAP);
        assertThat(sms.messages()).isEmpty();
        assertThat(outbox(CLUB, "SmsCapReached")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class)).containsEntry("month", "2026-11"));
        assertThat(usage(CLUB)).containsEntry("smsMonthKey", 202611L).containsEntry("smsSentMonth", 1000L).containsEntry("smsCapNoticeMonth", 202611L);
    }

    /**
     * E7-T05 step 6 (a row of the failure-path table): an attempt whose preparation outlived its claim's lease never calls the
     * provider. The first dispatcher's `deliverable` read stalls past its lease; meanwhile a second dispatcher claims the
     * delivery and sends it. When the first one resumes, less than {@code PROVIDER_WINDOW} of its lease is left, so it
     * abandons the attempt: one e-mail and, for an SMS, one message counted once (the abandoned reservation is given back).
     * Before the fix the first dispatcher sent a second e-mail and a second SMS.
     */
    @Test void R_11_09_anAttemptWhoseLeaseRanOutBeforeTheProviderCallNeverSends() {
        mail.script.add(EmailSender.SendResult.failed("SendGrid HTTP 503"));
        var n = levelNotice("member-marc", "dog-ares");
        classSession(CLUB, "e7t02-class-a", "2026-10-08T18:50", List.of("instructor-marta"));
        engine = engine(idleDispatcher());
        deliver(CLUB, "ClassCancelledByClub", Map.of("classId", "e7t02-class-a", "reason", "DELETED", "adminText", "x", "affected", affected("member-pau", "dog-nit", "b1")));
        var cancelled = memberNotice("N-08a", "member-pau");
        var reads = new ArrayList<String>(); var stallOn = new String[] {"EMAIL"}; var stalled = new java.util.concurrent.atomic.AtomicBoolean();
        var other = new NotificationDispatcher[1];
        owners = List.of(new NotificationFactsPort() {
            @Override public Set<String> eventTypes() { return Set.of("DogLevelChanged", "ClassCancelledByClub"); }
            @Override public Optional<NotificationFacts> facts(NotificationTrigger trigger, String code) { return Optional.empty(); }
            @Override public boolean deliverable(StoredNotification notification, String channel) {
                reads.add(notification.code() + " " + channel);
                // The first read of the channel stalls past the lease, while the other dispatcher claims the delivery and sends it.
                if (channel.equals(stallOn[0]) && stalled.compareAndSet(false, true)) {
                    clock.advance(NotificationDispatcher.LEASE);
                    other[0].dispatch(List.of(notification.notificationId()));
                }
                return true;
            }
        });
        var slow = dispatcher(sms); other[0] = dispatcher(sms);
        clock.advance(Duration.ofMinutes(1));
        dispatch(slow, n);
        assertThat(reads).containsExactly("N-09 EMAIL", "N-09 EMAIL");
        assertThat(mail.to("marc@example.test")).hasSize(1);
        assertThat(delivery(reload(n), NotificationChannel.EMAIL)).satisfies(d -> {
            assertThat(d.status()).isEqualTo(DeliveryStatus.SENT); assertThat(d.attempts()).isEqualTo(2); assertThat(d.claimToken()).isNull(); });
        assertThat(events("NotificationSent", n.id())).hasSize(1);
        // The SMS: the abandoned attempt gives its reservation back.
        mail.sent.clear(); reads.clear(); stallOn[0] = "SMS"; stalled.set(false);
        dispatch(slow, cancelled);
        assertThat(reads).filteredOn(read -> read.endsWith("SMS")).containsExactly("N-08a SMS", "N-08a SMS");
        assertThat(sms.messages()).extracting(m -> m.to()).containsExactly("+34600000004");
        assertThat(delivery(reload(cancelled), NotificationChannel.SMS).status()).isEqualTo(DeliveryStatus.SENT);
        assertThat(usage(CLUB)).containsEntry("smsSentMonth", 1L);
        assertThat(mail.to("pau@example.test")).hasSize(1);
    }

    private String run(String... arguments) {
        var out = new java.io.ByteArrayOutputStream(); var original = System.out;
        System.setOut(new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8));
        try { migrate.run(new org.springframework.boot.DefaultApplicationArguments(arguments)); } finally { System.setOut(original); }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
