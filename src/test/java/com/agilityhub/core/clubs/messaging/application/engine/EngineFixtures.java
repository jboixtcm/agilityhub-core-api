package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.application.EmailMessage;
import com.agilityhub.core.clubs.messaging.application.EmailSender;
import com.agilityhub.core.clubs.messaging.application.MessagingNotificationFacts;
import com.agilityhub.core.clubs.messaging.application.integrations.FakePushSender;
import com.agilityhub.core.clubs.messaging.application.integrations.FakeSmsSender;
import com.agilityhub.core.clubs.messaging.application.integrations.SmsSender;
import com.agilityhub.core.clubs.messaging.application.ports.InMemoryMessagingPorts;
import com.agilityhub.core.clubs.messaging.application.ports.MemberDirectoryPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFacts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationSubject;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationValues;
import com.agilityhub.core.clubs.messaging.domain.NotificationEventEnvelope;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplateRepository;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.clubs.messaging.persistence.NotificationRepository;
import com.agilityhub.core.clubs.messaging.persistence.PushSubscription;
import com.agilityhub.core.clubs.messaging.persistence.PushSubscriptionRepository;
import com.agilityhub.core.clubs.scheduling.application.SchedulingNotificationFacts;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.ClubEmailSettings;
import com.agilityhub.core.platform.application.ClubSmsUsage;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.Parameter;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E7-T02 steps 2, 8 and 12: the engine and the dispatcher built over the real Mongo documents, the real template provider,
 * renderer, e-mail layout, SMS counter and outbox, with the in-memory directories of {@link InMemoryMessagingPorts} and
 * doubles of the three providers (a scripted e-mail sender, `FakeSmsSender`, `FakePushSender`). The S06 owner of the class
 * notices is the real `SchedulingNotificationFacts` over a class stored here; the reminder's owner is a double
 * ({@link ReminderFacts}) that answers like S08/S09 do. Two clubs: Madrid and Buenos Aires.
 */
abstract class EngineFixtures extends AbstractIntegrationTest {
    static final String CLUB = "e7t02-engine", OTHER = "e7t02-ba", HOST = "engine.example.test";
    static final List<String> DATA = List.of("notifications", "message_templates", "push_subscriptions", "class_sessions", "waitlist_entries", "domain_events",
            "parameters", "members", "sendgrid_webhook_receipts");
    @Autowired MongoTemplate mongo; @Autowired ObjectMapper mapper; @Autowired ClubRepository clubs; @Autowired ClubConfigService configs;
    @Autowired IcuMessageSource messages; @Autowired TemplateProvider templates; @Autowired MessageTemplateRepository templateRepository;
    @Autowired NotificationRepository notifications; @Autowired PushSubscriptionRepository subscriptions; @Autowired NotificationAccounts accounts;
    @Autowired AccountRepository accountRepository; @Autowired EventPublisher events; @Autowired PlatformTransactionManager transactions;
    @Autowired ClubEmailSettings emailSettings; @Autowired ClubSmsUsage usage; @Autowired NotificationEmailRenderer emails; @Autowired UnsubscribeTokens unsubscribes;
    @Autowired SchedulingNotificationFacts scheduling;
    final ScriptedEmail mail = new ScriptedEmail();
    final FakeSmsSender sms = new FakeSmsSender();
    final FakePushSender push = new FakePushSender(null);
    InMemoryMessagingPorts ports; List<NotificationFactsPort> owners; NotificationDispatcher dispatcher; NotificationEngine engine;

    @BeforeEach void engineFixtures() {
        clock.setInstant(Instant.parse("2026-10-07T08:00:00Z")); // Wednesday 7 October, 10:00 in Madrid
        TenantContext.clear();
        for (String collection : DATA) { mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection); }
        club(CLUB, "Europe/Madrid", Module.WAITLIST, Module.PUSH, Module.SMS, Module.FREE_TRAINING, Module.BILLING, Module.TASKS);
        club(OTHER, "America/Argentina/Buenos_Aires", Module.WAITLIST, Module.PUSH, Module.SMS);
        ports = InMemoryMessagingPorts.s11Examples();
        ports.classInstructors.put("e7t02-class-a", List.of("instructor-marta"));
        for (var member : ports.members.values()) { account(member.accountId(), member.locale()); }
        account("account-admin2", "es");
        owners = List.of(scheduling, new MessagingNotificationFacts(ports), new ReminderFacts());
        dispatcher = dispatcher(sms);
        engine = new NotificationEngine(configs, messages, templates, new RecipientResolver(ports, ports, ports), notifications, subscriptions, accounts, owners, ports,
                events, dispatcher, clock, transactions);
    }
    NotificationDispatcher dispatcher(SmsSender smsSender) { return dispatcher(smsSender, ports, usage, events); }
    /** A dispatcher over other doubles: the member directory, the SMS counter or the outbox (the failure-injection tests of round 2). */
    NotificationDispatcher dispatcher(SmsSender smsSender, MemberDirectoryPort members, ClubSmsUsage smsUsage, EventPublisher outbox) {
        return new NotificationDispatcher(notifications, subscriptions, mail, smsSender, push, configs, emailSettings, smsUsage, emails, unsubscribes, members, accounts,
                outbox, new TransactionTemplate(transactions), owners, clock, "no-reply@example.test");
    }
    /** A dispatcher whose inline trigger does nothing: the engine stores the deliveries due, and the test dispatches them. */
    NotificationDispatcher idleDispatcher() {
        return new NotificationDispatcher(notifications, subscriptions, mail, sms, push, configs, emailSettings, usage, emails, unsubscribes, ports, accounts, events,
                new TransactionTemplate(transactions), owners, clock, "no-reply@example.test") {
            @Override public int dispatch(java.util.Collection<String> notificationIds) { return 0; }
        };
    }
    /** The engine sending through `using` right after its commit. */
    NotificationEngine engine(NotificationDispatcher using) {
        return new NotificationEngine(configs, messages, templates, new RecipientResolver(ports, ports, ports), notifications, subscriptions, accounts, owners, ports,
                events, using, clock, transactions);
    }

    /** The in-memory directory seen through a hook that runs before each member lookup (a gate, an injected failure). */
    MemberDirectoryPort directory(java.util.function.Consumer<String> beforeFind) {
        return new MemberDirectoryPort() {
            @Override public Optional<com.agilityhub.core.clubs.messaging.application.ports.MemberContact> find(String memberId) { beforeFind.accept(memberId); return ports.find(memberId); }
            @Override public List<com.agilityhub.core.clubs.messaging.application.ports.MemberContact> findAll(java.util.Collection<String> memberIds) { return ports.findAll(memberIds); }
            @Override public Optional<com.agilityhub.core.clubs.messaging.application.ports.MemberContact> byAccount(String accountId) { return ports.byAccount(accountId); }
            @Override public List<String> membersWithEmail(String address) { return ports.membersWithEmail(address); }
            @Override public Optional<com.agilityhub.core.clubs.messaging.application.ports.MemberContact.DogContact> dog(String dogId) { return ports.dog(dogId); }
        };
    }

    /** The outbox, failing the first `failures` publications of one event type (after nothing was written by them). */
    static final class FailingOutbox implements EventPublisher {
        final EventPublisher delegate; final String type; final java.util.concurrent.atomic.AtomicInteger left, failed = new java.util.concurrent.atomic.AtomicInteger();
        FailingOutbox(EventPublisher delegate, String type, int failures) { this.delegate = delegate; this.type = type; this.left = new java.util.concurrent.atomic.AtomicInteger(failures); }
        @Override public String publish(DomainEvent event) {
            if (type.equals(event.type()) && left.getAndDecrement() > 0) { failed.incrementAndGet(); throw new IllegalStateException("outbox write failed (injected)"); }
            return delegate.publish(event);
        }
    }
    void club(String id, String zone, Module... modules) {
        mongo.remove(Query.query(Criteria.where("_id").is(id)), Club.class);
        var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(id, id.equals(CLUB) ? HOST : "ba." + HOST, zone));
        tree.set("modules", mapper.valueToTree(modules)); tree.put("name", id.equals(CLUB) ? "Club Agility Exemple" : "Club Agility Sud");
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(id);
    }
    void modules(String clubId, Module... modules) {
        var tree = (ObjectNode) mapper.valueToTree(clubs.findById(clubId).orElseThrow()); tree.set("modules", mapper.valueToTree(modules));
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(clubId);
    }
    void parameter(String clubId, String key, Object value, String type) {
        mongo.remove(Query.query(Criteria.where("_id").is(clubId + ":" + key)), Parameter.class);
        mongo.insert(new Parameter(clubId + ":" + key, clubId, key, value, type, "club", null, List.of(), 0L, clock.instant())); configs.invalidate(clubId);
    }
    /**
     * The account of an example person. Its login address is unique to this suite (`{id}@accounts.e7t02.example.test`): the
     * test database is shared and `accounts.email` is unique; the engine reads the account for the language (R-11-01) and
     * the E1-T03 bounce mark of that address only.
     */
    void account(String id, String locale) {
        accountRepository.save(new Account(id, accountEmail(id), id, locale, null, Set.of(), Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(),
                false, clock.instant()));
    }
    static String accountEmail(String accountId) { return accountId + "@accounts.e7t02.example.test"; }
    /** A stored S06 class of the club (the owner of N-08a/N-17 reads it). */
    void classSession(String clubId, String id, String start, List<String> instructorIds) {
        var begins = java.time.LocalDateTime.parse(start); var starts = begins.atZone(java.time.ZoneId.of(configs.get(clubId).club().timeZone())).toInstant();
        var s = new LinkedHashMap<String, Object>();
        s.put("id", id); s.put("clubId", clubId); s.put("weekId", "week-a"); s.put("date", begins.toLocalDate().toString()); s.put("startTime", start.substring(11));
        s.put("endTime", begins.plusHours(1).toLocalTime().toString()); s.put("startsAt", starts.toString()); s.put("endsAt", starts.plusSeconds(3600).toString());
        s.put("state", "ACTIVE"); s.put("levelIds", List.of()); s.put("instructorIds", instructorIds); s.put("capacity", 6); s.put("capacityMode", "MANUAL");
        s.put("description", "B+C"); s.put("counters", Map.of("booked", 3, "waiting", 1)); s.put("risk", Map.of("exempt", false, "notifiedBookingIds", List.of()));
        s.put("version", 0);
        mongo.insert(mapper.convertValue(s, com.agilityhub.core.clubs.scheduling.persistence.ClassSession.class));
    }
    void waitlistEntry(String clubId, String id, String classId, String memberId, String dogId) {
        mongo.insert(new Document("_id", id).append("clubId", clubId).append("classSessionId", classId).append("memberId", memberId).append("dogId", dogId)
                .append("accountId", "account-" + memberId.substring("member-".length())).append("joinedAt", java.util.Date.from(clock.instant())).append("state", "ACTIVE")
                .append("position", 1).append("version", 0), "waitlist_entries");
    }
    PushSubscription subscribe(String clubId, String id, String accountId) {
        var subscription = new PushSubscription(id, clubId, accountId, "https://push.example.test/" + id, null, new PushSubscription.Keys("p256dh", "auth"), "iPhone · Safari",
                "UA", PushSubscription.Status.ACTIVE, 0, null, null, 0L, clock.instant(), accountId, clock.instant(), accountId);
        mongo.insert(subscription); return subscription;
    }

    /** The outbox delivery of one event: inside a transaction, the sends right after its commit (as `OutboxDispatcher` does). */
    void deliver(String eventId, String clubId, String type, Map<String, Object> payload) {
        var envelope = new NotificationEventEnvelope(null, type, clubId, "Aggregate", Optional.ofNullable(payload.get("classId")).map(Object::toString).orElse("aggregate-a"),
                clock.instant(), payload, "account-admin", null, DomainEvent.Origin.BACKOFFICE);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> engine.handle(eventId, envelope));
    }
    String deliver(String clubId, String type, Map<String, Object> payload) { String id = UUID.randomUUID().toString(); deliver(id, clubId, type, payload); return id; }

    List<Notification> stored(String clubId, String code) {
        return mongo.find(Query.query(Criteria.where("clubId").is(clubId).and("code").is(code)).with(org.springframework.data.domain.Sort.by("dedupKey")), Notification.class);
    }
    Notification only(String clubId, String code) { var found = stored(clubId, code); org.assertj.core.api.Assertions.assertThat(found).hasSize(1); return found.getFirst(); }
    List<String> channels(Notification n) { return n.deliveries().stream().map(d -> d.channel() + ":" + d.status()).toList(); }
    List<Document> outbox(String clubId, String type) {
        return mongo.find(Query.query(Criteria.where("clubId").is(clubId).and("type").is(type)), Document.class, "domain_events");
    }

    /** An e-mail sender that records every message and answers the scripted results first (T-11-09). */
    static final class ScriptedEmail implements EmailSender {
        final List<EmailMessage> sent = new CopyOnWriteArrayList<>();
        final Deque<SendResult> script = new ConcurrentLinkedDeque<>();
        volatile Runnable during = () -> { };
        /** Runs while the provider call is in flight, with the message (a webhook that arrives before the answer). */
        volatile java.util.function.Consumer<EmailMessage> inFlight = message -> { };
        @Override public SendResult send(EmailMessage message) {
            during.run();
            inFlight.accept(message);
            var next = script.poll();
            if (next != null) { return next; }
            sent.add(message); return SendResult.sent("mail-" + UUID.randomUUID());
        }
        List<EmailMessage> to(String address) { return sent.stream().filter(m -> m.to().equalsIgnoreCase(address)).toList(); }
    }

    /**
     * The reminder's owner as S08/S09 answer `ReminderDue{bookingId | trainingBookingId, memberId, dogId, startsAt, kind}`: the
     * class or training values (date, time, ring, description) and the member with the dog.
     */
    static final class ReminderFacts implements NotificationFactsPort {
        @Override public Set<String> eventTypes() { return Set.of("ReminderDue"); }
        @Override public Optional<NotificationFacts> facts(NotificationTrigger trigger, String code) {
            var startsAt = Instant.parse(trigger.text("startsAt"));
            boolean training = trigger.text("trainingBookingId") != null;
            var subject = training ? NotificationSubject.trainingBooking(trigger.text("trainingBookingId")) : NotificationSubject.booking(trigger.text("bookingId"));
            var builder = NotificationFacts.builder().value("kind", training ? "TRAINING" : "CLASS").value("date", startsAt)
                    .value("time", training ? new NotificationValues.TimeRange(startsAt, startsAt.plusSeconds(1800)) : startsAt).value("ring_name", "Central")
                    .value("class_description", "B+C").subject(subject);
            return Optional.of(builder.member(new NotificationFacts.MemberSubject(trigger.text("memberId"), trigger.text("dogId"), Map.of(), subject)).build());
        }
    }
    static List<Map<String, Object>> affected(String... memberDogBooking) {
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < memberDogBooking.length; i += 3) { rows.add(Map.of("memberId", memberDogBooking[i], "dogId", memberDogBooking[i + 1], "bookingId", memberDogBooking[i + 2])); }
        return rows;
    }
}
