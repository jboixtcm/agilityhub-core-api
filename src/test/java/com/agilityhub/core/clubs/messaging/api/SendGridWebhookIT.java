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
                "provider-example-id", clock.instant(), null, "webhook@example.test", "en", clock.instant()));
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
