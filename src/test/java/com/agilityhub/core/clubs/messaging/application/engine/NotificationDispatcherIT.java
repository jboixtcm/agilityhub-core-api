package com.agilityhub.core.clubs.messaging.application.engine;

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
}
