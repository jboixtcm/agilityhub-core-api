package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.messaging.application.MigrateNotificationsCommand;
import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.clubs.messaging.domain.*;
import com.agilityhub.core.clubs.messaging.persistence.*;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.LocalizedText;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.AuditCovers;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/**
 * E7-T01 step 3: the S11 §3 documents with their indexes, the E1–E6 helpers writing one S11 notification with a single
 * delivery (their semantics unchanged), `messaging:migrate-notifications` and the ANNOUNCEMENT_SENT audit action.
 */
class E7PersistenceIT extends AbstractIntegrationTest {
    static final String CLUB = "e7-persistence-a", OTHER = "e7-persistence-b";
    @Autowired MongoTemplate mongo;
    @Autowired NotificationRepository notifications;
    @Autowired MessageTemplateRepository templates;
    @Autowired PushSubscriptionRepository subscriptions;
    @Autowired SystemNotificationService service;
    @Autowired MigrateNotificationsCommand migrate;
    @Autowired AuditWriter audit;
    @Autowired PlatformTransactionManager manager;

    @BeforeEach void clean() {
        TenantContext.clear();
        for (String collection : List.of("message_templates", "notifications", "push_subscriptions", "audit_entries", "domain_events")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection);
        }
        mongo.remove(Query.query(Criteria.where("clubId").is(null).and("_id").regex("^e7p-")), "notifications");
        mongo.remove(Query.query(Criteria.where("_id").is("e7p-account")), Account.class);
        mongo.save(new Account("e7p-account", "e7p@example.test", "Example Person", "ca", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
    }
    private Map<String, Document> indexes(String collection) {
        var result = new LinkedHashMap<String, Document>();
        for (Document index : mongo.getCollection(collection).listIndexes()) { result.put(index.getString("name"), index); }
        return result;
    }
    private static Document keys(Object... pairs) {
        var keys = new Document();
        for (int i = 0; i < pairs.length; i += 2) { keys.append((String) pairs[i], pairs[i + 1]); }
        return keys;
    }
    private Document raw(String id) { return mongo.getCollection("notifications").find(new Document("_id", id)).first(); }

    @Test void T_11_19_T_11_26_collectionsDeclareTheS11IndexesAndUniqueGuards() {
        var log = indexes("notifications");
        assertThat(log.get("notification_club_dedup").get("key")).isEqualTo(keys("clubId", 1, "dedupKey", 1));
        assertThat(log.get("notification_club_dedup").getBoolean("unique")).isTrue();
        assertThat(log.get("notification_club_dedup").get("partialFilterExpression")).isEqualTo(new Document("dedupKey", new Document("$type", 2)));
        assertThat(log.get("notification_club_recipient_created").get("key")).isEqualTo(keys("clubId", 1, "recipient.accountId", 1, "createdAt", -1));
        assertThat(log.get("notification_club_recipient_read").get("key")).isEqualTo(keys("clubId", 1, "recipient.accountId", 1, "readAt", 1));
        assertThat(log.get("notification_club_created").get("key")).isEqualTo(keys("clubId", 1, "createdAt", -1));
        assertThat(log.get("notification_delivery_due").get("key")).isEqualTo(keys("deliveries.status", 1, "deliveries.nextAttemptAt", 1));
        var templateIndexes = indexes("message_templates");
        assertThat(templateIndexes.get("template_club_code").get("key")).isEqualTo(keys("clubId", 1, "code", 1));
        assertThat(templateIndexes.get("template_club_code").getBoolean("unique")).isTrue();
        assertThat(templateIndexes.get("template_club_code").get("partialFilterExpression")).isEqualTo(new Document("code", new Document("$type", 2)));
        assertThat(templateIndexes.get("template_club_category_status").get("key")).isEqualTo(keys("clubId", 1, "category", 1, "status", 1));
        var push = indexes("push_subscriptions");
        assertThat(push.get("push_club_endpoint").get("key")).isEqualTo(keys("clubId", 1, "endpointHash", 1));
        assertThat(push.get("push_club_endpoint").getBoolean("unique")).isTrue();
        assertThat(push.get("push_club_account_status").get("key")).isEqualTo(keys("clubId", 1, "accountId", 1, "status", 1));
    }

    @Test void T_11_08_T_11_29_documentsRoundTripInTheirTenantAndTheUniqueKeysHold() {
        Instant now = clock.instant();
        var title = new LocalizedText(Map.of("ca", "Classe anul·lada pel club", "es", "Clase anulada por el club"), "ca");
        var matrix = NotificationCatalog.byCode("N-08a").orElseThrow().defaultMatrix();
        var full = new Notification("e7p-n1", CLUB, "N-08a", NotificationCategory.CLUB_CHANGES, "e7p-t1", 3L, "event-a", "ClassCancelledByClub",
                "event-a:N-08a:MEMBER:account-a:dog-a", NotificationAudience.MEMBER, new Notification.Recipient("account-a", "member-a", null, null, "Laura Example"), "ca",
                new Notification.Subject("dog-a", "booking-a", "class-a", null, null, null, null, null, "member-a"), TemplateIcon.x, TemplateColor.ERROR,
                "Classe anul·lada pel club", "Dimecres 12 · 18:50", "Classe anul.lada", new Notification.Action(NotificationActionType.CHANGE_CLASS, Map.of("dogId", "dog-a")),
                List.of(new Notification.Delivery(NotificationChannel.APP, null, DeliveryStatus.DELIVERED, 0, null, null, null, null, now, null),
                        new Notification.Delivery(NotificationChannel.SMS, "+34600000001", DeliveryStatus.QUEUED, 1, now.plusSeconds(60), null, "HTTP 503", null, null, null),
                        new Notification.Delivery(NotificationChannel.SMS, "+34600000002", DeliveryStatus.SKIPPED_CAP, 0, null, null, null, null, null, null)),
                null, now, null, null, null, null, null, null, null, null);
        try (var scope = TenantContext.open(CLUB)) {
            notifications.queue(full);
            assertThat(notifications.findById("e7p-n1")).contains(full);
            // R-11-09: one notification per dedupKey and club.
            assertThatThrownBy(() -> notifications.queue(new Notification("e7p-n2", CLUB, "N-08a", NotificationCategory.CLUB_CHANGES, null, null, "event-a", null,
                    full.dedupKey(), NotificationAudience.MEMBER, full.recipient(), "ca", null, TemplateIcon.x, TemplateColor.ERROR, "t", "b", null, null, List.of(),
                    null, now, null, null, null, null, null, null, null, null))).isInstanceOf(DuplicateKeyException.class);
            assertThat(notifications.unreadApp("account-a")).isEqualTo(1);
            templates.insert(new MessageTemplate("e7p-t1", CLUB, "N-08a", TemplateKind.CATALOG, NotificationCategory.CLUB_CHANGES, title, title, title, TemplateIcon.x,
                    TemplateColor.ERROR, matrix, true, true, false, TemplateStatus.ACTIVE, null, now, "seed", now, "seed"));
            var stored = templates.findByCode("N-08a").orElseThrow();
            assertThat(stored.matrix()).isEqualTo(matrix); assertThat(stored.title()).isEqualTo(title); assertThat(stored.version()).isZero();
            assertThatThrownBy(() -> templates.insert(new MessageTemplate("e7p-t2", CLUB, "N-08a", TemplateKind.CATALOG, NotificationCategory.CLUB_CHANGES, title, title,
                    null, TemplateIcon.x, TemplateColor.ERROR, matrix, true, true, false, TemplateStatus.ACTIVE, null, now, null, now, null))).isInstanceOf(DuplicateKeyException.class);
            // Two CUSTOM templates (code null) never collide.
            for (String id : List.of("e7p-c1", "e7p-c2")) {
                templates.insert(new MessageTemplate(id, CLUB, null, TemplateKind.CUSTOM, NotificationCategory.CLUB_NEWS, title, title, null, TemplateIcon.flag,
                        TemplateColor.ACCENT, Map.of(), true, false, false, TemplateStatus.ACTIVE, null, now, "account-a", now, "account-a"));
            }
            assertThat(templates.findAll()).hasSize(3);
            var subscription = new PushSubscription("e7p-s1", CLUB, "account-a", "https://push.example.test/send/e7p", null, new PushSubscription.Keys("p", "a"),
                    "iPhone · Safari", "UA", PushSubscription.Status.ACTIVE, 0, null, null, null, now, "account-a", now, "account-a");
            subscriptions.insert(subscription);
            assertThat(subscriptions.findOwn("e7p-s1", "account-a")).get().extracting(PushSubscription::endpointHash).isEqualTo(subscription.endpointHash());
            assertThat(subscriptions.findOwn("e7p-s1", "account-b")).isEmpty();
            assertThatThrownBy(() -> subscriptions.insert(new PushSubscription("e7p-s2", CLUB, "account-b", "https://push.example.test/send/e7p", null,
                    new PushSubscription.Keys("p", "a"), null, null, PushSubscription.Status.ACTIVE, 0, null, null, null, now, null, now, null))).isInstanceOf(DuplicateKeyException.class);
        }
        try (var scope = TenantContext.open(OTHER)) {
            // The same dedupKey, code and endpoint in another club are another club's.
            notifications.queue(new Notification("e7p-n3", OTHER, "N-08a", NotificationCategory.CLUB_CHANGES, null, null, "event-a", null, full.dedupKey(),
                    NotificationAudience.MEMBER, full.recipient(), "ca", null, TemplateIcon.x, TemplateColor.ERROR, "t", "b", null, null, List.of(), null, now,
                    null, null, null, null, null, null, null, null));
            assertThat(notifications.findById("e7p-n1")).isEmpty(); assertThat(templates.findAll()).isEmpty(); assertThat(subscriptions.findOwn("e7p-s1", "account-a")).isEmpty();
            assertThat(notifications.unreadApp("account-a")).isZero();
            assertThatThrownBy(() -> templates.insert(new MessageTemplate("e7p-t9", CLUB, null, TemplateKind.CUSTOM, NotificationCategory.CLUB_NEWS, title, title, null,
                    TemplateIcon.flag, TemplateColor.ACCENT, Map.of(), true, false, false, TemplateStatus.ACTIVE, null, now, null, now, null))).isInstanceOf(ApiException.class);
        }
    }

    /** The helpers keep their signatures and semantics; each row is now one S11 document with a single delivery (dedupKey = the id). */
    @Test void WP_11_A_theHelpersWriteOneS11NotificationWithASingleDeliveryAndMoveItWithTheFlatFields() {
        try (var scope = TenantContext.open(CLUB)) {
            service.appOnce("e7p-app", "N-15", "e7p-account", Map.of("dog_name", "Duna", "action", "CLAIM_SEAT", "entityId", "entry-a"));
            service.smsIntentOnce("e7p-sms", "N-15", "e7p-account", "ca", List.of("+34600000001", "+34600000002"), "Plaça lliure", true,
                    Map.of("action", "CLAIM_SEAT", "entityId", "entry-a"));
            service.pushIntentOnce("e7p-push", "N-15", "e7p-account", "ca", false, Map.of("dog_name", "Duna"));
            service.appOnce("e7p-app", "N-15", "e7p-account", Map.of());
            assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), "notifications")).isEqualTo(3);
            var app = raw("e7p-app");
            assertThat(app).containsEntry("dedupKey", "e7p-app").containsEntry("code", "N-15").containsEntry("category", "OPERATIONAL")
                    .containsEntry("audience", "MEMBER").containsEntry("icon", "unlock").containsEntry("color", "ACCENT")
                    .containsEntry("accountId", "e7p-account").containsEntry("channel", "APP").containsEntry("status", "SENT");
            assertThat(app.get("recipient", Document.class)).containsEntry("accountId", "e7p-account");
            assertThat(app.getList("deliveries", Document.class)).singleElement().satisfies(delivery -> assertThat(delivery)
                    .containsEntry("channel", "APP").containsEntry("target", "e7p-account").containsEntry("status", "SENT"));
            assertThat(app.get("variables", Document.class)).containsEntry("dog_name", "Duna");
            var sms = raw("e7p-sms");
            assertThat(sms.getList("recipientPhones", String.class)).containsExactly("+34600000001", "+34600000002");
            assertThat(sms).containsEntry("body", "Plaça lliure").containsEntry("smsBody", "Plaça lliure").containsEntry("status", "QUEUED");
            assertThat(sms.get("action", Document.class)).isEqualTo(new Document("type", "CLAIM_SEAT").append("params", new Document("entityId", "entry-a")));
            assertThat(sms.getList("deliveries", Document.class)).singleElement().satisfies(delivery -> assertThat(delivery)
                    .containsEntry("channel", "SMS").containsEntry("status", "QUEUED"));
            assertThat(notifications.findById("e7p-sms").orElseThrow().action()).isEqualTo(new Notification.Action(NotificationActionType.CLAIM_SEAT, Map.of("entityId", "entry-a")));
            assertThat(raw("e7p-push").getList("deliveries", Document.class).getFirst()).containsEntry("status", "SKIPPED_MODULE_OFF");
            // Feed 11 / the bell: one APP delivery unread.
            assertThat(notifications.unreadApp("e7p-account")).isEqualTo(1);
            // NotificationQueued carries each row's channel.
            var channels = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("type").is("NotificationQueued")), Document.class, "domain_events")
                    .stream().map(e -> e.get("payload", Document.class).getString("channel")).toList();
            assertThat(channels).containsExactlyInAnyOrder("APP", "SMS", "PUSH");
        }
        // The SYSTEM e-mail (clubId null): QUEUED then SENT, the delivery and the flat fields together.
        String id = service.send("N-26", "e7p-account", Map.of());
        var email = mongo.getCollection("notifications").find(new Document("_id", id)).first();
        assertThat(email).containsEntry("status", "SENT").containsEntry("channel", "EMAIL").containsEntry("dedupKey", id).containsEntry("category", "SYSTEM");
        assertThat(email.getString("providerMessageId")).startsWith("fake-");
        assertThat(email.getList("deliveries", Document.class)).singleElement().satisfies(delivery -> {
            assertThat(delivery).containsEntry("channel", "EMAIL").containsEntry("target", "e7p@example.test").containsEntry("status", "SENT").containsEntry("attempts", 1);
            assertThat(delivery.getString("providerRef")).isEqualTo(email.getString("providerMessageId"));
            assertThat(delivery.getDate("sentAt")).isEqualTo(email.getDate("sentAt"));
        });
        var typed = notifications.findSystem(id).orElseThrow();
        assertThat(typed.status()).isEqualTo(Notification.Status.SENT); assertThat(typed.delivery().status()).isEqualTo(DeliveryStatus.SENT);
        // A webhook DELIVERED, then a late bounce: both representations move once each.
        assertThat(notifications.delivery(id, Notification.Status.DELIVERED, null)).isTrue();
        assertThat(notifications.findSystem(id).orElseThrow().delivery().deliveredAt()).isEqualTo(clock.instant());
        assertThat(notifications.delivery(id, Notification.Status.FAILED, "SendGrid bounce")).isTrue();
        var bounced = notifications.findSystem(id).orElseThrow();
        assertThat(bounced.status()).isEqualTo(Notification.Status.FAILED); assertThat(bounced.delivery().status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(bounced.delivery().lastError()).isEqualTo("SendGrid bounce"); assertThat(bounced.error()).isEqualTo("SendGrid bounce");
    }

    @Test void WP_11_A_aRowWrittenBeforeE7MovesItsFlatFieldsOnlyAndTheCommandConvertsIt() throws Exception {
        Instant now = clock.instant();
        mongo.insert(new Document("_id", "e7p-old-email").append("clubId", CLUB).append("accountId", "e7p-account").append("code", "N-20").append("channel", "EMAIL")
                .append("status", "QUEUED").append("recipientEmail", "e7p@example.test").append("locale", "ca").append("createdAt", Date.from(now)), "notifications");
        mongo.insert(new Document("_id", "e7p-old-sms").append("clubId", CLUB).append("accountId", "e7p-account").append("code", "N-36").append("channel", "SMS")
                .append("status", "QUEUED").append("recipientPhones", List.of("+34600000001")).append("body", "Club: canvi").append("action", "OPEN_BOOKING")
                .append("entityId", "booking-a").append("locale", "ca").append("createdAt", Date.from(now.plusSeconds(1))), "notifications");
        try (var scope = TenantContext.open(CLUB)) {
            assertThat(notifications.finish("e7p-old-email", Notification.Status.SENT, "ref-1", null, now)).isTrue();
            assertThat(raw("e7p-old-email")).containsEntry("status", "SENT").containsEntry("providerMessageId", "ref-1").doesNotContainKey("deliveries");
        }
        // Dry run by default: prints the legacy rows, never an address, writes nothing.
        String dry = run();
        assertThat(dry).contains("WOULD_CONVERT e7p-old-email club=" + CLUB + " code=N-20 channel=EMAIL status=SENT",
                "WOULD_CONVERT e7p-old-sms club=" + CLUB + " code=N-36 channel=SMS status=QUEUED", "dry run: nothing written").doesNotContain("e7p@example.test", "+346");
        assertThat(raw("e7p-old-email")).doesNotContainKey("deliveries");
        String applied = run("--apply");
        assertThat(applied).contains("CONVERTED e7p-old-email", "CONVERTED e7p-old-sms");
        try (var scope = TenantContext.open(CLUB)) {
            var email = notifications.findById("e7p-old-email").orElseThrow();
            assertThat(email.dedupKey()).isEqualTo("e7p-old-email"); assertThat(email.category()).isEqualTo(NotificationCategory.PERSONAL);
            assertThat(email.delivery()).isEqualTo(new Notification.Delivery(NotificationChannel.EMAIL, "e7p@example.test", DeliveryStatus.SENT, 1, null, "ref-1", null,
                    now, null, null));
            assertThat(email.status()).isEqualTo(Notification.Status.SENT);
            var sms = notifications.findById("e7p-old-sms").orElseThrow();
            assertThat(sms.action()).isEqualTo(new Notification.Action(NotificationActionType.OPEN_BOOKING, Map.of("entityId", "booking-a")));
            assertThat(sms.smsBody()).isEqualTo("Club: canvi"); assertThat(sms.audience()).isEqualTo(NotificationAudience.MEMBER);
            // A converted row moves like a helper row.
            assertThat(notifications.finish("e7p-old-sms", Notification.Status.FAILED, null, "Twilio 400", now)).isTrue();
            assertThat(notifications.findById("e7p-old-sms").orElseThrow().delivery().status()).isEqualTo(DeliveryStatus.FAILED);
        }
        assertThat(run()).doesNotContain("e7p-old").contains("Legacy notification rows:");
        assertThatThrownBy(() -> migrate.run(new DefaultApplicationArguments("--dry-run", "--apply"))).hasMessageContaining("Usage");
        assertThatThrownBy(() -> migrate.run(new DefaultApplicationArguments("--club=canic"))).hasMessageContaining("Usage");
        assertThatThrownBy(() -> migrate.run(new DefaultApplicationArguments("extra"))).hasMessageContaining("Usage");
    }
    private String run(String... arguments) {
        var out = new ByteArrayOutputStream(); var original = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { migrate.run(new DefaultApplicationArguments(arguments)); } finally { System.setOut(original); }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** E7-T01 step 8 placeholder (E7-T04 writes it from `POST /message-templates/{id}/send`): the action exists, is written and read back per club. */
    @Test @AuditCovers(AuditAction.ANNOUNCEMENT_SENT)
    void T_11_18_announcementSentIsAnAuditActionOfTheClub() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("e7p-admin", null, "ROLE_ADMIN"));
        try (var scope = TenantContext.open(CLUB)) {
            new TransactionTemplate(manager).executeWithoutResult(tx -> audit.write(new AuditCommand(AuditAction.ANNOUNCEMENT_SENT, "Announcement", "e7p-batch", null,
                    null, Map.of("recipientCount", 37, "filters", List.of("planId:eq:plan-a")), null)));
        } finally { SecurityContextHolder.clearContext(); }
        var entry = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("action").is(AuditAction.ANNOUNCEMENT_SENT)), AuditEntry.class);
        assertThat(entry).isNotNull();
        assertThat(entry.entityType()).isEqualTo("Announcement"); assertThat(entry.entityId()).isEqualTo("e7p-batch");
        assertThat(entry.actorAccountId()).isEqualTo("e7p-admin"); assertThat(entry.actorRole()).isEqualTo("ADMIN");
        assertThat(entry.changes()).extracting(change -> change.path()).contains("recipientCount");
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(OTHER).and("action").is(AuditAction.ANNOUNCEMENT_SENT)), AuditEntry.class)).isZero();
    }
}
