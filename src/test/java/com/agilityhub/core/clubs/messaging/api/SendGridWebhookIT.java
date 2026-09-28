package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.persistence.*;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.persistence.SecurityEvent;
import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class SendGridWebhookIT extends AbstractIntegrationTest {
    private static final KeyPair KEYS = keys();
    private static final String TIMESTAMP = "1788696000";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired AccountRepository accounts;
    @Autowired NotificationRepository notifications;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.agilityhub.core.shared.application.EventPublisher publisher;
    @DynamicPropertySource static void emailProperties(DynamicPropertyRegistry registry) {
        registry.add("email.sendgrid.webhook-public-key", () -> Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
    }
    private static KeyPair keys() {
        try { var generator = KeyPairGenerator.getInstance("EC"); generator.initialize(256); return generator.generateKeyPair(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    @BeforeEach void reset() {
        TenantContext.clear();
        mongo.remove(new Query(), Notification.class); mongo.remove(new Query(), SendGridWebhookReceipt.class);
        mongo.remove(new Query(), AuditEntry.class);
        mongo.remove(new Query(), SecurityEvent.class);
        accounts.save(new Account("webhook-account", "webhook@example.test", "Example Person", "en", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
        mongo.insert(new Notification("webhook-notification", null, "webhook-account", "N-25", "EMAIL", Notification.Status.SENT,
                "provider-example-id", clock.instant(), null, "webhook@example.test", "en", clock.instant(), null));
    }
    private byte[] event(String id, String type, String notification, String club, String email) throws Exception {
        var values = new java.util.HashMap<String, String>();
        values.put("sg_event_id", id); values.put("event", type); values.put("notificationId", notification); values.put("email", email);
        if (club != null) { values.put("clubId", club); }
        return mapper.writeValueAsBytes(List.of(values));
    }
    private byte[] event(String id, String type) throws Exception { return event(id, type, "webhook-notification", null, "webhook@example.test"); }
    private String sign(byte[] body) throws Exception {
        var signature = Signature.getInstance("SHA256withECDSA"); signature.initSign(KEYS.getPrivate());
        signature.update(TIMESTAMP.getBytes(StandardCharsets.UTF_8)); signature.update(body);
        return Base64.getEncoder().encodeToString(signature.sign());
    }
    private void send(byte[] body) throws Exception {
        mvc.perform(post("/webhooks/email/sendgrid").contentType("application/json").content(body)
                .header("Host", "unregistered.example.test").header("X-Twilio-Email-Event-Webhook-Timestamp", TIMESTAMP)
                .header("X-Twilio-Email-Event-Webhook-Signature", sign(body))).andExpect(status().isOk());
    }
    @Test @AuditCovers(AuditAction.ACCOUNT_EMAIL_STATUS_CHANGED)
    void T_11_22_validSignatureUpdatesAccountAuditAndDeliveryExactlyOnce() throws Exception {
        byte[] payload = event("event-bounce", "bounce"); send(payload); send(payload);
        assertThat(accounts.findById("webhook-account").orElseThrow().emailStatus()).isEqualTo(NotificationAccounts.EmailStatus.BOUNCED);
        assertThat(notifications.findSystem("webhook-notification").orElseThrow().status()).isEqualTo(Notification.Status.FAILED);
        assertThat(mongo.count(new Query(), SendGridWebhookReceipt.class)).isEqualTo(1);
        var audit = mongo.findAll(AuditEntry.class);
        assertThat(audit).hasSize(1); assertThat(audit.getFirst().entityType()).isEqualTo("Account");
        assertThat(audit.getFirst().action()).isEqualTo(AuditAction.ACCOUNT_EMAIL_STATUS_CHANGED);
        assertThat(audit.getFirst().entityId()).isEqualTo("webhook-account"); assertThat(audit.getFirst().actorRole()).isEqualTo("SYSTEM");
        assertThat(audit.getFirst().changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("emailStatus"); assertThat(change.before()).isNull(); assertThat(change.after()).isEqualTo("BOUNCED");
        });
        send(event("event-complaint", "spamreport")); send(event("event-dropped", "dropped")); send(event("event-late-delivery", "delivered"));
        assertThat(accounts.findById("webhook-account").orElseThrow().emailStatus()).isEqualTo(NotificationAccounts.EmailStatus.COMPLAINED);
        assertThat(mongo.findAll(AuditEntry.class)).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.action()).isEqualTo(AuditAction.ACCOUNT_EMAIL_STATUS_CHANGED);
            assertThat(entry.entityType()).isEqualTo("Account");
            assertThat(entry.changes()).extracting(change -> change.path()).containsExactly("emailStatus");
        });
        assertThat(mongo.count(new Query(), SecurityEvent.class)).isZero();
        assertThat(notifications.findSystem("webhook-notification").orElseThrow().status()).isEqualTo(Notification.Status.FAILED);
    }
    @Test void T_11_22_deliveredAndDeferredPreserveAccountState() throws Exception {
        send(event("deferred", "deferred")); assertThat(mongo.count(new Query(), SendGridWebhookReceipt.class)).isZero();
        send(event("delivery", "delivered")); send(event("another-delivery", "delivered"));
        assertThat(notifications.findSystem("webhook-notification").orElseThrow().status()).isEqualTo(Notification.Status.DELIVERED);
        assertThat(accounts.findById("webhook-account").orElseThrow().emailStatus()).isNull(); assertThat(mongo.count(new Query(), AuditEntry.class)).isZero();
    }
    @ParameterizedTest @ValueSource(strings = {"MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN"})
    void T_11_22_rolesCannotBypassSignatureVerification(String role) throws Exception {
        mvc.perform(post("/webhooks/email/sendgrid").with(jwt().jwt(jwt -> jwt.claim("clubId", "club-b").claim("roles", List.of(role))))
                .contentType("application/json").content(event("invalid", "bounce")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"))
                .andExpect(jsonPath("$.details").isEmpty());
        assertThat(mongo.count(new Query(), SendGridWebhookReceipt.class)).isZero();
        assertThat(mongo.findAll(SecurityEvent.class)).singleElement().satisfies(this::assertSignatureSecurityEvent);
    }
    @Test void T_11_22_invalidSignaturesAndTamperedRawBytesFailBeforeParsing() throws Exception {
        byte[] body = event("tampered", "bounce");
        for (String signature : List.of("not-base64", sign("different bytes".getBytes(StandardCharsets.UTF_8)))) {
            mvc.perform(post("/webhooks/email/sendgrid").contentType("application/json").content(body)
                    .header("X-Twilio-Email-Event-Webhook-Timestamp", TIMESTAMP).header("X-Twilio-Email-Event-Webhook-Signature", signature))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"))
                    .andExpect(jsonPath("$.details").isEmpty());
        }
        for (String malformed : List.of("{", "null", "{}")) {
            byte[] bytes = malformed.getBytes(StandardCharsets.UTF_8);
            mvc.perform(post("/webhooks/email/sendgrid").contentType("application/json").content(bytes)
                    .header("X-Twilio-Email-Event-Webhook-Timestamp", TIMESTAMP).header("X-Twilio-Email-Event-Webhook-Signature", sign(bytes)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        assertThat(accounts.findById("webhook-account").orElseThrow().emailStatus()).isNull();
        assertThat(mongo.findAll(SecurityEvent.class)).hasSize(2).allSatisfy(this::assertSignatureSecurityEvent);
    }
    private void assertSignatureSecurityEvent(SecurityEvent event) {
        assertThat(event.type()).isEqualTo(SecurityEvents.Type.WEBHOOK_SIGNATURE_INVALID);
        assertThat(event.route()).isEqualTo("other");
        assertThat(event.accountId()).isNull(); assertThat(event.clubId()).isNull();
        assertThat(event.details()).containsExactlyEntriesOf(Map.of("route", "other"));
    }
    @Test void T_11_22_unknownNotificationOtherClubAndChangedEmailAreIgnored() throws Exception {
        send(event("unknown", "bounce", "unknown", null, "webhook@example.test"));
        send(event("wrong-club", "bounce", "webhook-notification", "club-b", "webhook@example.test"));
        send(event("wrong-email", "bounce", "webhook-notification", null, "other@example.test"));
        assertThat(mongo.count(new Query(), SendGridWebhookReceipt.class)).isZero();
        mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is("webhook-account")),
                new org.springframework.data.mongodb.core.query.Update().set("email", "changed@example.test"), Account.class);
        send(event("old-email", "bounce"));
        assertThat(accounts.findById("webhook-account").orElseThrow().emailStatus()).isNull();
        assertThat(mongo.count(new Query(), AuditEntry.class)).isZero();
    }
    @Test void T_11_22_signedClubRoutingUsesStoredNotificationRatherThanRequestHost() throws Exception {
        mongo.updateFirst(new Query(), new org.springframework.data.mongodb.core.query.Update().set("clubId", "club-a"), Notification.class);
        send(event("club-bounce", "dropped", "webhook-notification", "club-a", "webhook@example.test"));
        try (var tenant = TenantContext.open("club-a")) { assertThat(notifications.findById("webhook-notification").orElseThrow().status()).isEqualTo(Notification.Status.FAILED); }
        assertThat(mongo.findAll(AuditEntry.class)).singleElement().satisfies(audit -> assertThat(audit.clubId()).isEqualTo("club-a"));
        assertThat(TenantContext.current()).isNull();
    }
    /** An engine notification of club-a (E7-T02) to the member's two contact addresses; the second is also the account's login address. */
    private void engineNotification() {
        mongo.remove(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("clubId").is("club-a")), "members");
        mongo.remove(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("clubId").is("club-a")), "domain_events");
        mongo.save(new org.bson.Document("_id", "webhook-member").append("clubId", "club-a").append("accountId", "webhook-account").append("status", "ACTIVE")
                .append("firstName", "Laura").append("lastName1", "Serra").append("contactEmails", List.of(new org.bson.Document("email", "laura.work@example.test"),
                        new org.bson.Document("email", "Webhook@example.test"))).append("version", 0), "members");
        var now = clock.instant();
        var deliveries = List.of(
                new Notification.Delivery(com.agilityhub.core.clubs.messaging.domain.NotificationChannel.APP, "webhook-account",
                        com.agilityhub.core.clubs.messaging.domain.DeliveryStatus.DELIVERED, 0, null, null, null, null, now, null),
                new Notification.Delivery(com.agilityhub.core.clubs.messaging.domain.NotificationChannel.EMAIL, "laura.work@example.test",
                        com.agilityhub.core.clubs.messaging.domain.DeliveryStatus.SENT, 1, null, "provider-a", null, now, null, null),
                new Notification.Delivery(com.agilityhub.core.clubs.messaging.domain.NotificationChannel.EMAIL, "Webhook@example.test",
                        com.agilityhub.core.clubs.messaging.domain.DeliveryStatus.SENT, 1, null, "provider-b", null, now, null, null));
        mongo.insert(new Notification("engine-notification", "club-a", "N-09", com.agilityhub.core.clubs.messaging.domain.NotificationCategory.PERSONAL, "template-a", 0L,
                "event-a", "DogLevelChanged", "event-a:N-09:MEMBER:webhook-account", com.agilityhub.core.clubs.messaging.domain.NotificationAudience.MEMBER,
                new Notification.Recipient("webhook-account", "webhook-member", null, null, "Laura Serra"), "ca", null,
                com.agilityhub.core.clubs.messaging.domain.TemplateIcon.up, com.agilityhub.core.clubs.messaging.domain.TemplateColor.OK, "La Duna puja de nivell!", "Body", null,
                null, deliveries, null, now, null, null, null, null, null, null, null, null, java.util.Map.of()));
    }
    private Notification.Delivery engineDelivery(String target) {
        try (var tenant = TenantContext.open("club-a")) {
            return notifications.findById("engine-notification").orElseThrow().deliveries().stream().filter(d -> target.equals(d.target())).findFirst().orElseThrow();
        }
    }
    private List<org.bson.Document> contactEmails() { return mongo.findById("webhook-member", org.bson.Document.class, "members").getList("contactEmails", org.bson.Document.class); }
    private List<org.bson.Document> clubEvents(String type) {
        return mongo.find(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("clubId").is("club-a").and("type").is(type)),
                org.bson.Document.class, "domain_events");
    }
    private byte[] bounce(String id, String event, String email, String type) throws Exception {
        var values = new java.util.HashMap<String, String>(Map.of("sg_event_id", id, "event", event, "notificationId", "engine-notification", "clubId", "club-a", "email", email));
        if (type != null) { values.put("type", type); }
        return mapper.writeValueAsBytes(List.of(values));
    }

    @Test @AuditCovers(AuditAction.ACCOUNT_EMAIL_STATUS_CHANGED)
    void T_11_11_T_11_22_engineMailDeliveredSoftAndHardBouncesPerContactAddressAndTheAccountMarkCoexists() throws Exception {
        engineNotification();
        // delivered → DELIVERED (that address only); deferred → nothing.
        send(bounce("e-delivered", "delivered", "laura.work@example.test", null));
        assertThat(engineDelivery("laura.work@example.test").status()).isEqualTo(com.agilityhub.core.clubs.messaging.domain.DeliveryStatus.DELIVERED);
        assertThat(engineDelivery("laura.work@example.test").deliveredAt()).isEqualTo(clock.instant());
        send(bounce("e-deferred", "deferred", "laura.work@example.test", null));
        assertThat(mongo.count(new Query(), SendGridWebhookReceipt.class)).isEqualTo(1);
        // A soft bounce (`blocked`) fails the delivery but marks nothing.
        send(bounce("e-soft", "bounce", "webhook@example.test", "blocked"));
        assertThat(engineDelivery("Webhook@example.test").status()).isEqualTo(com.agilityhub.core.clubs.messaging.domain.DeliveryStatus.FAILED);
        assertThat(contactEmails()).allSatisfy(e -> assertThat(e.get("bounced")).isNull());
        assertThat(clubEvents("EmailBounced")).isEmpty(); assertThat(accounts.findById("webhook-account").orElseThrow().emailStatus()).isNull();
        // A hard bounce → FAILED + `contactEmails[address].bounced` + EmailBounced{memberId, email, type} (→ N-51), exactly once per event id.
        byte[] hard = bounce("e-hard", "bounce", "LAURA.WORK@example.test", "bounce");
        send(hard); send(hard);
        assertThat(engineDelivery("laura.work@example.test").status()).isEqualTo(com.agilityhub.core.clubs.messaging.domain.DeliveryStatus.FAILED);
        assertThat(engineDelivery("laura.work@example.test").lastError()).isEqualTo("SendGrid bounce");
        assertThat(contactEmails()).extracting(e -> e.getString("email") + ":" + e.get("bounced")).containsExactly("laura.work@example.test:true", "Webhook@example.test:null");
        assertThat(clubEvents("EmailBounced")).singleElement().satisfies(e -> assertThat(e.get("payload", org.bson.Document.class))
                .containsEntry("memberId", "webhook-member").containsEntry("email", "laura.work@example.test").containsEntry("type", "BOUNCE"));
        assertThat(clubEvents("NotificationFailed")).hasSize(2);
        // Not the login address: the account keeps its status (decision E12 works per account, R-11-08 per contact address).
        assertThat(accounts.findById("webhook-account").orElseThrow().emailStatus()).isNull(); assertThat(mongo.count(new Query(), AuditEntry.class)).isZero();
        // A drop of the login address marks both: the contact address and the account (with its E1-T03 audit).
        send(bounce("e-dropped", "dropped", "webhook@example.test", null));
        assertThat(contactEmails()).extracting(e -> e.get("bounced")).containsExactly(true, true);
        assertThat(accounts.findById("webhook-account").orElseThrow().emailStatus()).isEqualTo(NotificationAccounts.EmailStatus.BOUNCED);
        assertThat(mongo.findAll(AuditEntry.class)).singleElement().satisfies(a -> assertThat(a.action()).isEqualTo(AuditAction.ACCOUNT_EMAIL_STATUS_CHANGED));
        assertThat(clubEvents("EmailBounced")).hasSize(2);
        // An address the notification never went to, another club, an unknown notification: 200 without effect.
        send(bounce("e-stranger", "bounce", "stranger@example.test", "bounce"));
        send(event("e-other-club", "bounce", "engine-notification", "club-b", "laura.work@example.test"));
        send(event("e-unknown", "bounce", "unknown-notification", "club-a", "laura.work@example.test"));
        assertThat(clubEvents("EmailBounced")).hasSize(2); assertThat(mongo.count(new Query(), SendGridWebhookReceipt.class)).isEqualTo(4);
    }

    @Test void T_11_22_spamReportOfAnEngineMailWithoutAMemberMarksTheMembersWithThatAddress() throws Exception {
        engineNotification();
        mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is("engine-notification")),
                new org.springframework.data.mongodb.core.query.Update().set("recipient.memberId", null).set("recipient.accountId", null), Notification.class);
        send(bounce("e-spam", "spamreport", "laura.work@example.test", null));
        assertThat(contactEmails()).extracting(e -> e.get("bounced")).containsExactly(true, null);
        assertThat(clubEvents("EmailBounced")).singleElement().satisfies(e -> assertThat(e.get("payload", org.bson.Document.class)).containsEntry("type", "SPAMREPORT"));
        assertThat(accounts.findById("webhook-account").orElseThrow().emailStatus()).isNull();
    }

    @Test void T_11_22_failedTransactionRollsBackReceiptAccountAuditAndDelivery() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("Simulated outbox failure")).when(publisher).publish(org.mockito.ArgumentMatchers.any());
        byte[] body = event("retry-after-failure", "bounce");
        mvc.perform(post("/webhooks/email/sendgrid").contentType("application/json").content(body)
                        .header("Host", "unregistered.example.test").header("X-Twilio-Email-Event-Webhook-Timestamp", TIMESTAMP)
                        .header("X-Twilio-Email-Event-Webhook-Signature", sign(body)))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        assertThat(mongo.count(new Query(), SendGridWebhookReceipt.class)).isZero();
        assertThat(mongo.count(new Query(), AuditEntry.class)).isZero();
        assertThat(accounts.findById("webhook-account").orElseThrow().emailStatus()).isNull();
        assertThat(notifications.findSystem("webhook-notification").orElseThrow().status()).isEqualTo(Notification.Status.SENT);
        org.mockito.Mockito.reset(publisher);
        send(event("retry-after-failure", "bounce"));
        assertThat(mongo.count(new Query(), SendGridWebhookReceipt.class)).isEqualTo(1);
    }

}
