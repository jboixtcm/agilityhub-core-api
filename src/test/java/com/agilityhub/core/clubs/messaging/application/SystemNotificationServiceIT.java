package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.clubs.messaging.persistence.NotificationRepository;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.Parameter;
import com.agilityhub.core.shared.application.LocaleContext;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.support.AbstractIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class SystemNotificationServiceIT extends AbstractIntegrationTest {
    @Autowired SystemNotificationService service;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean EmailSender sender;
    @Autowired AccountRepository accounts;
    @Autowired NotificationRepository notifications;
    @Autowired MongoTemplate mongo;
    @Autowired ClubConfigService configs;
    @Autowired PlatformTransactionManager manager;
    private FakeEmailSender mailbox() { return (FakeEmailSender) sender; }
    @BeforeEach void reset() {
        TenantContext.clear(); mailbox().clear();
        mongo.remove(new Query(), Notification.class);
        mongo.remove(Query.query(Criteria.where("id").is("email-account")), Account.class);
    }
    private void account(String locale) {
        accounts.save(new Account("email-account", "mailbox@example.test", "Example Person", locale, null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
    }
    @ParameterizedTest @CsvSource({
        "N-25,ca,Enllaç per entrar a AgilityHub", "N-25,es,Enlace para entrar en AgilityHub", "N-25,en,Your AgilityHub sign-in link",
        "N-26,ca,La contrasenya d’AgilityHub ha canviat", "N-26,es,La contraseña de AgilityHub ha cambiado", "N-26,en,Your AgilityHub password has changed",
        "N-27,ca,El teu accés a AgilityHub", "N-27,es,Tu acceso a AgilityHub", "N-27,en,Your AgilityHub access"})
    void T_11_25_systemMessagesUseAccountLocaleAndPersistDelivery(String code, String language, String title) throws Exception {
        account(language);
        String id;
        try (var ignored = LocaleContext.open(Locale.GERMAN)) {
            id = service.send(code, "email-account", Map.of("link", "https://id.example.test/sign-in/example", "expires_minutes", 999));
            assertThat(LocaleContext.current()).isEqualTo(Locale.GERMAN);
        }
        var email = mailbox().lastTo("MAILBOX@example.test");
        assertThat(email.subject()).isEqualTo(title); assertThat(email.locale().getLanguage()).isEqualTo(language);
        assertThat(email.html()).contains("lang=\"" + language + "\"", title, "AgilityHub").doesNotContain("Unsubscribe", "999", "[[", " th:");
        assertThat(email.text()).contains(title).doesNotContain("<p>");
        if (!code.equals("N-26")) { assertThat(email.html()).contains("15 min", "https://id.example.test/sign-in/example"); }
        else { assertThat(email.html()).doesNotContain("https://id.example.test/sign-in/example", "15 min"); }
        var stored = notifications.findSystem(id).orElseThrow();
        assertThat(stored.status()).isEqualTo(Notification.Status.SENT); assertThat(stored.sentAt()).isEqualTo(clock.instant());
        assertThat(stored.providerMessageId()).startsWith("fake-"); assertThat(stored.clubId()).isNull();
        assertThat(stored.code()).isEqualTo(code); assertThat(stored.channel()).isEqualTo("EMAIL"); assertThat(stored.error()).isNull();
        assertThat(stored.accountId()).isEqualTo("email-account"); assertThat(stored.locale()).isEqualTo(language);
        assertThat(mongo.getCollection("notifications").find(new org.bson.Document("_id", id)).first().toJson()).doesNotContain("sign-in/example");
        assertThat(mongo.getCollection("domain_events").countDocuments(new org.bson.Document("aggregateId", id))).isEqualTo(2);
        if (language.equals("ca")) { Files.createDirectories(Path.of("target/email-evidence")); Files.writeString(Path.of("target/email-evidence/" + code + "-ca.html"), email.html()); }
    }
    @Test void T_11_25_clubBrandingLocaleAndTenantIsolation() {
        account("en");
        mongo.remove(Query.query(Criteria.where("_id").is("club-a")), Club.class);
        mongo.insert(fixtures.read("fixtures/platform/club.json", Club.class));
        mongo.remove(Query.query(Criteria.where("clubId").is("club-a")), Parameter.class);
        parameter("messaging.email.fromName", "Example Sender", "string");
        parameter("messaging.email.fromAddress", "sender@app.example.test", "string");
        parameter("auth.magicLinkMinutes", 22, "int"); configs.invalidate("club-a");
        String id;
        try (var tenant = TenantContext.open("club-a")) {
            id = service.send("N-27", "email-account", Map.of("link", "https://id.example.test/access"));
            assertThat(notifications.findById(id)).isPresent();
            assertThatThrownBy(() -> notifications.findSystem(id)).isInstanceOf(ApiException.class);
        }
        var email = mailbox().lastTo("mailbox@example.test");
        assertThat(email.from()).isEqualTo(new EmailMessage.Address("sender@app.example.test", "Example Sender"));
        assertThat(email.replyTo()).isEqualTo("club@example.test");
        assertThat(email.html()).contains("Example Agility Club", "https://assets.example.test/logo.svg", "#3155A4", "22 min");
        assertThat(email.tags()).containsEntry("clubId", "club-a"); assertThat(email.locale()).isEqualTo(Locale.ENGLISH);
        try (var tenant = TenantContext.open("club-b")) { assertThat(notifications.findById(id)).isEmpty(); }
        assertThat(notifications.findSystem(id)).isEmpty();
    }
    private void parameter(String key, Object value, String type) {
        mongo.insert(new Parameter("email-" + key, "club-a", key, value, type, "club", null, List.of(), null, clock.instant()));
    }
    @Test void T_11_25_rejectsUnsupportedCodesBadLinksAndUnknownAccounts() {
        account(null);
        for (String link : List.of("", "javascript:alert(1)", "https://user:password@example.test/", "not a URI")) {
            assertThatThrownBy(() -> service.send("N-25", "email-account", Map.of("link", link))).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> service.send("N-25", "email-account", Map.of())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.send("N-02", "email-account", Map.of())).isInstanceOf(ApiException.class)
                .extracting(f -> ((ApiException) f).code()).isEqualTo(ErrorCode.TEMPLATE_NOT_SENDABLE);
        assertThatThrownBy(() -> service.send("N-26", "unknown", Map.of())).isInstanceOf(ApiException.class);
        assertThat(mailbox().messages()).isEmpty(); assertThat(mongo.count(new Query(), Notification.class)).isZero();
        service.send("N-26", "email-account", Map.of()); assertThat(mailbox().lastTo("mailbox@example.test").locale().getLanguage()).isEqualTo("ca");
    }
    @Test void T_11_25_suppressedAddressRecordsFailureAndLocaleFallback() {
        account("de"); accounts.markEmailStatus("email-account", "mailbox@example.test", NotificationAccounts.EmailStatus.BOUNCED);
        String id = service.send("N-26", "email-account", Map.of());
        var stored = notifications.findSystem(id).orElseThrow();
        assertThat(stored.status()).isEqualTo(Notification.Status.FAILED); assertThat(stored.error()).isEqualTo("Recipient email suppressed");
        assertThat(stored.locale()).isEqualTo("ca"); assertThat(stored.sentAt()).isNull(); assertThat(mailbox().messages()).isEmpty();
    }
    @Test void T_11_25_emailCannotEscapeAnUncommittedIdentityTransaction() {
        account("en");
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> service.send("N-26", "email-account", Map.of())))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(mailbox().messages()).isEmpty();
    }
    @Test void T_11_09_providerFailureIsRecordedAfterCommittedQueue() {
        account("en");
        org.mockito.Mockito.doAnswer(call -> {
            EmailMessage email = call.getArgument(0);
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(notifications.findSystem(email.tags().get("notificationId")).orElseThrow().status()).isEqualTo(Notification.Status.QUEUED);
            return EmailSender.SendResult.failed("SendGrid HTTP 503");
        }).when(sender).send(org.mockito.ArgumentMatchers.any());
        String id = service.send("N-26", "email-account", Map.of());
        assertThat(notifications.findSystem(id).orElseThrow().error()).isEqualTo("SendGrid HTTP 503");
        assertThat(notifications.findSystem(id).orElseThrow().status()).isEqualTo(Notification.Status.FAILED);
    }

    @Test void T_11_22_callbackBeforeSendResponsePreservesFinalStatusAndProviderReference() {
        account("en");
        org.mockito.Mockito.doAnswer(call -> {
            EmailMessage email = call.getArgument(0);
            notifications.delivery(email.tags().get("notificationId"), Notification.Status.DELIVERED, null);
            return EmailSender.SendResult.sent("provider-early-callback");
        }).when(sender).send(org.mockito.ArgumentMatchers.any());
        String id = service.send("N-26", "email-account", Map.of());
        var stored = notifications.findSystem(id).orElseThrow();
        assertThat(stored.status()).isEqualTo(Notification.Status.DELIVERED);
        assertThat(stored.providerMessageId()).isEqualTo("provider-early-callback");
        assertThat(stored.sentAt()).isEqualTo(clock.instant());
    }

}
