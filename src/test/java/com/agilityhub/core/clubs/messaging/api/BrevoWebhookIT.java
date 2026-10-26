package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.domain.*;
import com.agilityhub.core.clubs.messaging.persistence.*;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.platform.persistence.SecurityEvent;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class BrevoWebhookIT extends AbstractIntegrationTest {
    private static final String TOKEN = UUID.randomUUID().toString();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired AccountRepository accounts;
    @Autowired NotificationRepository notifications;
    @Autowired BrevoWebhookRepository receipts;
    @DynamicPropertySource static void emailProperties(DynamicPropertyRegistry registry) {
        registry.add("email.brevo.webhook-token", () -> TOKEN);
    }
    @BeforeEach void reset() {
        TenantContext.clear();
        for (Class<?> type : List.of(Notification.class, BrevoWebhookReceipt.class, BrevoEmailSuppression.class, SecurityEvent.class)) {
            mongo.remove(new Query(), type);
        }
        accounts.save(new Account("brevo-account", "recipient@example.test", "Example Person", "en", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
        mongo.insert(new Notification("brevo-notification", null, "brevo-account", "N-25", "EMAIL", Notification.Status.SENT,
                "brevo:provider@example.test", clock.instant(), null, "recipient@example.test", "en", clock.instant(), null));
    }
    private Map<String, Object> event(String kind, String notification, String club, String email) throws Exception {
        var custom = new LinkedHashMap<String, String>(); custom.put("notificationId", notification);
        if (club != null) { custom.put("clubId", club); }
        return new LinkedHashMap<>(Map.of("id", 123, "event", kind, "message-id", "<provider@example.test>", "email", email,
                "X-Mailin-custom", Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(custom))));
    }
    private Map<String, Object> event(String kind) throws Exception { return event(kind, "brevo-notification", null, "recipient@example.test"); }
    private void send(Object payload) throws Exception {
        mvc.perform(post("/webhooks/email/brevo").header("Host", "unregistered.example.test")
                .header("Authorization", "Bearer " + TOKEN).contentType("application/json").content(mapper.writeValueAsBytes(payload)))
                .andExpect(status().isOk());
        assertThat(TenantContext.current()).isNull();
    }
    @Test void T_11_22_deliveredUsesBearerWithoutApplicationLoginAndDeduplicatesByEventNotSubscriptionId() throws Exception {
        send(event("delivered")); send(event("delivered"));
        assertThat(notifications.findSystem("brevo-notification").orElseThrow().status()).isEqualTo(Notification.Status.DELIVERED);
        assertThat(mongo.count(new Query(), BrevoWebhookReceipt.class)).isEqualTo(1);
        send(event("hard_bounce")); send(event("delivered"));
        assertThat(mongo.count(new Query(), BrevoWebhookReceipt.class)).isEqualTo(2);
        assertThat(notifications.findSystem("brevo-notification").orElseThrow().status()).isEqualTo(Notification.Status.FAILED);
        assertThat(accounts.findById("brevo-account").orElseThrow().emailStatus()).isEqualTo(NotificationAccounts.EmailStatus.BOUNCED);
        assertThat(receipts.suppressed("RECIPIENT@example.test")).isTrue();
        assertThat(mongo.findAll(BrevoEmailSuppression.class)).singleElement().satisfies(item -> assertThat(item.id()).doesNotContain("@"));
    }
    @ParameterizedTest @ValueSource(strings = {"spam", "hard_bounce", "invalid_email", "blocked"})
    void T_11_08_permanentFailuresSuppressAndKeepAccountSemantics(String event) throws Exception {
        send(event(event));
        assertThat(receipts.suppressed("recipient@example.test")).isTrue();
        assertThat(notifications.findSystem("brevo-notification").orElseThrow().status()).isEqualTo(Notification.Status.FAILED);
        assertThat(accounts.findById("brevo-account").orElseThrow().emailStatus()).isEqualTo(event.equals("spam")
                ? NotificationAccounts.EmailStatus.COMPLAINED : NotificationAccounts.EmailStatus.BOUNCED);
    }
    @Test void T_11_08_unsubscribeSuppressesWithoutCallingItABounceOrRegressingDelivered() throws Exception {
        send(event("delivered")); send(event("unsubscribed")); send(event("unsubscribed"));
        assertThat(receipts.suppressed("recipient@example.test")).isTrue();
        assertThat(accounts.findById("brevo-account").orElseThrow().emailStatus()).isNull();
        assertThat(notifications.findSystem("brevo-notification").orElseThrow().status()).isEqualTo(Notification.Status.DELIVERED);
        assertThat(mongo.count(new Query(), BrevoEmailSuppression.class)).isEqualTo(1);
    }
    @Test void T_11_22_temporaryFailuresAndTrackingEventsNeverSuppressOrRegressDelivery() throws Exception {
        for (String kind : List.of("request", "deferred", "soft_bounce", "opened", "click", "proxy_open")) { send(event(kind)); }
        assertThat(mongo.count(new Query(), BrevoWebhookReceipt.class)).isZero();
        assertThat(receipts.suppressed("recipient@example.test")).isFalse();
        send(event("delivered"));
        assertThat(notifications.findSystem("brevo-notification").orElseThrow().status()).isEqualTo(Notification.Status.DELIVERED);
    }
    @Test void T_11_22_errorFailsOnlyThisDeliveryWithoutPermanentSuppression() throws Exception {
        send(event("error"));
        assertThat(notifications.findSystem("brevo-notification").orElseThrow().status()).isEqualTo(Notification.Status.FAILED);
        assertThat(receipts.suppressed("recipient@example.test")).isFalse();
        assertThat(accounts.findById("brevo-account").orElseThrow().emailStatus()).isNull();
    }
    @ParameterizedTest @ValueSource(strings = {"MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN"})
    void T_11_22_applicationRoleCannotBypassDedicatedWebhookToken(String role) throws Exception {
        mvc.perform(post("/webhooks/email/brevo").with(jwt().jwt(jwt -> jwt.claim("clubId", "club-a").claim("roles", List.of(role))))
                .contentType("application/json").content(mapper.writeValueAsBytes(event("hard_bounce"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
        assertThat(mongo.count(new Query(), BrevoWebhookReceipt.class)).isZero();
    }
    @Test void T_11_22_authenticationPrecedesParsingAndDoesNotLeakHeaders() throws Exception {
        for (String auth : List.of("Bearer wrong", "Basic " + TOKEN, TOKEN)) {
            mvc.perform(post("/webhooks/email/brevo").header("Authorization", auth).contentType("application/json").content("{"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"))
                    .andExpect(jsonPath("$.details").isEmpty());
        }
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isUnauthorized());
        assertThat(mongo.count(new Query(), BrevoWebhookReceipt.class)).isZero();
    }
    @Test void T_11_22_malformedAuthenticatedBatchCannotPartiallyApply() throws Exception {
        for (String body : List.of("{", "null", "42", mapper.writeValueAsString(List.of(event("hard_bounce"), "wrong")))) {
            mvc.perform(post("/webhooks/email/brevo").header("Authorization", "Bearer " + TOKEN).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        assertThat(mongo.count(new Query(), BrevoWebhookReceipt.class)).isZero();
        assertThat(receipts.suppressed("recipient@example.test")).isFalse();
    }
    @Test void T_11_22_wrongTenantRecipientReferenceAndCorrelationCannotModifyOtherRecords() throws Exception {
        send(event("hard_bounce", "brevo-notification", "club-b", "recipient@example.test"));
        send(event("hard_bounce", "brevo-notification", null, "other@example.test"));
        send(event("hard_bounce", "unknown", null, "recipient@example.test"));
        var other = event("hard_bounce"); other.put("message-id", "other@example.test"); send(other);
        for (String invalid : List.of("not-base64!", Base64.getUrlEncoder().encodeToString("null".getBytes()),
                Base64.getUrlEncoder().encodeToString("{}".getBytes()))) {
            var payload = event("hard_bounce"); payload.put("X-Mailin-custom", invalid); send(payload);
        }
        assertThat(mongo.count(new Query(), BrevoWebhookReceipt.class)).isZero();
        assertThat(mongo.count(new Query(), BrevoEmailSuppression.class)).isZero();
        assertThat(notifications.findSystem("brevo-notification").orElseThrow().status()).isEqualTo(Notification.Status.SENT);
    }
    @Test void T_11_22_callbackBeforeAcceptanceDoesNotRegressWhenSendCompletes() throws Exception {
        mongo.updateFirst(new Query(), new org.springframework.data.mongodb.core.query.Update().unset("providerMessageId").set("status", "QUEUED"), Notification.class);
        send(event("delivered"));
        notifications.finish("brevo-notification", Notification.Status.SENT, "brevo:provider@example.test", null, clock.instant());
        assertThat(notifications.findSystem("brevo-notification").orElseThrow().status()).isEqualTo(Notification.Status.DELIVERED);
        assertThat(notifications.findSystem("brevo-notification").orElseThrow().providerMessageId()).isEqualTo("brevo:provider@example.test");
    }
    @Test void T_11_08_tenantEngineUnsubscribeChangesOnlyItsOwnMemberPreference() throws Exception {
        mongo.save(new org.bson.Document("_id", "brevo-member").append("clubId", "club-a").append("accountId", "brevo-account")
                .append("status", "ACTIVE").append("firstName", "Example").append("lastName1", "Person")
                .append("contactEmails", List.of(new org.bson.Document("email", "recipient@example.test"))).append("version", 0), "members");
        var delivery = new Notification.Delivery(NotificationChannel.EMAIL, "recipient@example.test", DeliveryStatus.SENT, 1, null,
                "brevo:provider@example.test", null, clock.instant(), null, null);
        mongo.insert(new Notification("engine-brevo", "club-a", "N-09", NotificationCategory.PERSONAL, "template", 0L,
                "event", "DogLevelChanged", "brevo-dedup", NotificationAudience.MEMBER,
                new Notification.Recipient("brevo-account", "brevo-member", null, null, "Example Person"), "en", null,
                TemplateIcon.up, TemplateColor.OK, "Title", "Body", null, null, List.of(delivery), null, clock.instant(),
                null, null, null, null, null, null, null, null, Map.of()));
        send(List.of(event("unsubscribed", "engine-brevo", "club-b", "recipient@example.test"),
                event("unsubscribed", "engine-brevo", "club-a", "recipient@example.test")));
        var member = mongo.findById("brevo-member", org.bson.Document.class, "members");
        assertThat(member.getEmbedded(List.of("notificationPreferences", "emailByCategory", "CLUB_NEWS"), Boolean.class)).isFalse();
        assertThat(mongo.count(new Query(), BrevoWebhookReceipt.class)).isEqualTo(1);
        assertThat(accounts.findById("brevo-account").orElseThrow().emailStatus()).isNull();
    }
}
