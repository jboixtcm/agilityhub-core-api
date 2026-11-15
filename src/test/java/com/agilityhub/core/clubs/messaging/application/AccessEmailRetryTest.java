package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.clubs.messaging.persistence.NotificationRepository;
import com.agilityhub.core.platform.application.ClubEmailSettings;
import com.agilityhub.core.platform.application.ParameterCatalog;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AUTH-03 (S01 R-01-04, S03 R-03-06b): an access or recovery e-mail (N-25, N-27) goes once more after a provider timeout or
 * 5xx, and its final outcome sets or clears the account's mark that D10 shows. Every other system e-mail, and every other
 * failure, is sent once as before. No Spring context or Mongo; fictional data only.
 */
class AccessEmailRetryTest {
    static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    final NotificationAccounts accounts = mock(NotificationAccounts.class);
    final NotificationRepository notifications = mock(NotificationRepository.class);
    final EmailSender sender = mock(EmailSender.class);
    final EmailSender recovery = mock(EmailSender.class);
    final SystemEmailRenderer renderer = mock(SystemEmailRenderer.class);
    final TransactionTemplate transactions = mock(TransactionTemplate.class);
    final IcuMessageSource messages = mock(IcuMessageSource.class);
    final ParameterCatalog parameters = mock(ParameterCatalog.class);
    SystemNotificationService service;

    @BeforeEach void setUp() {
        TenantContext.clear();
        when(accounts.find("account-a")).thenReturn(Optional.of(new NotificationAccounts.Recipient("account-a", "person@example.test", "ca", null)));
        when(parameters.defaultInteger("auth.magicLinkMinutes")).thenReturn(15);
        when(messages.supports(any())).thenReturn(true);
        when(renderer.render(any(), any(), any(), any(), any(), any())).thenReturn(new EmailMessage("person@example.test", "Subject", "<p>Body</p>", "Body",
                null, null, Locale.forLanguageTag("ca"), Map.of()));
        when(notifications.findScoped(any())).thenReturn(Optional.empty());
        when(notifications.finish(any(), any(), any(), any(), any(), anyInt())).thenReturn(true);
        when(sender.forPasswordRecovery()).thenReturn(recovery);
        doAnswer(invocation -> { invocation.<Consumer<TransactionStatus>>getArgument(0).accept(null); return null; })
                .when(transactions).executeWithoutResult(any());
        service = new SystemNotificationService(accounts, mock(ClubEmailSettings.class), parameters, notifications, sender, renderer,
                mock(EventPublisher.class), transactions, messages, Clock.fixed(NOW, ZoneOffset.UTC), "no-reply@example.test");
    }
    static EmailSender.SendResult failed(String error) { return EmailSender.SendResult.failed(error); }

    @ParameterizedTest @CsvSource({"N-25,Brevo HTTP 503", "N-25,SendGrid timeout", "N-27,Brevo timeout", "N-27,SendGrid HTTP 500"})
    void AUTH_03_anAccessEmailIsSentOnceMoreAfterATimeoutOr5xxAndItsSuccessClearsTheMark(String code, String error) {
        when(sender.send(any())).thenReturn(failed(error)).thenReturn(EmailSender.SendResult.sent("provider-1"));
        String id = service.send(code, "account-a", Map.of("link", "https://club-a.example.test/activacio?t=fictional"));
        verify(sender, times(2)).send(any());
        verify(notifications).finish(id, Notification.Status.SENT, "provider-1", null, NOW, 2);
        verify(accounts).markAccessEmailFailure("account-a", "person@example.test", null);
    }
    @Test void AUTH_03_anAccessEmailThatFailsTwiceIsFailedWithTwoAttemptsAndMarksTheAccount() {
        when(sender.send(any())).thenReturn(failed("Brevo HTTP 502")).thenReturn(failed("Brevo timeout"));
        String id = service.send("N-27", "account-a", Map.of("link", "https://club-a.example.test/activacio?t=fictional"));
        verify(sender, times(2)).send(any());
        verify(notifications).finish(id, Notification.Status.FAILED, null, "Brevo timeout", NOW, 2);
        verify(accounts).markAccessEmailFailure("account-a", "person@example.test", NOW);
    }
    @Test void AUTH_03_theRecoveryEmailIsRetriedOnItsOwnTransport() {
        when(recovery.send(any())).thenReturn(failed("Brevo HTTP 503")).thenReturn(failed("Brevo HTTP 503"));
        String id = service.sendPasswordRecovery("account-a", "https://club-a.example.test/activacio?t=fictional&purpose=reset");
        verify(recovery, times(2)).send(any());
        verify(sender, never()).send(any());
        verify(notifications).finish(id, Notification.Status.FAILED, null, "Brevo HTTP 503", NOW, 2);
        verify(accounts).markAccessEmailFailure("account-a", "person@example.test", NOW);
    }
    @ParameterizedTest @CsvSource({"Brevo HTTP 400", "Brevo HTTP 429", "Brevo transport failure", "Brevo interrupted", "Brevo recipient suppressed", "Brevo invalid response"})
    void AUTH_03_anyOtherFailureIsNotRetriedButStillMarksTheAccount(String error) {
        when(sender.send(any())).thenReturn(failed(error));
        String id = service.send("N-25", "account-a", Map.of("link", "https://club-a.example.test/activacio?t=fictional"));
        verify(sender, times(1)).send(any());
        verify(notifications).finish(id, Notification.Status.FAILED, null, error, NOW, 1);
        verify(accounts).markAccessEmailFailure("account-a", "person@example.test", NOW);
    }
    @Test void AUTH_03_aFirstAttemptAcceptedIsSentOnceAndClearsTheMark() {
        when(sender.send(any())).thenReturn(EmailSender.SendResult.sent("provider-1"));
        String id = service.send("N-25", "account-a", Map.of("link", "https://club-a.example.test/activacio?t=fictional"));
        verify(sender, times(1)).send(any());
        verify(notifications).finish(id, Notification.Status.SENT, "provider-1", null, NOW, 1);
        verify(accounts).markAccessEmailFailure("account-a", "person@example.test", null);
    }
    @Test void AUTH_03_otherSystemEmailsAreSentOnceAndNeverTouchTheMark() {
        when(sender.send(any())).thenReturn(failed("Brevo HTTP 503"));
        service.send("N-26", "account-a", Map.of("device", "Fictional browser"));
        service.sendEmailChange("account-a", "new-person@example.test", "https://club-a.example.test/activacio?t=fictional&purpose=email_change", 10080);
        service.sendOnceLocalized("welcome-1", "N-02", "account-a", "ca", Map.of("link", "https://club-a.example.test/activacio?t=fictional"));
        verify(sender, times(3)).send(any());
        verify(accounts, never()).markAccessEmailFailure(any(), any(), any());
    }
    @Test void AUTH_03_aSuppressedAddressIsNotSentAndMarksTheAccount() {
        when(accounts.find("account-a")).thenReturn(Optional.of(new NotificationAccounts.Recipient("account-a", "person@example.test", "ca",
                NotificationAccounts.EmailStatus.BOUNCED)));
        String id = service.send("N-25", "account-a", Map.of("link", "https://club-a.example.test/activacio?t=fictional"));
        verify(sender, never()).send(any());
        verify(notifications).finish(id, Notification.Status.FAILED, null, "Recipient email suppressed", NOW, 1);
        verify(accounts).markAccessEmailFailure("account-a", "person@example.test", NOW);
    }
    @Test void AUTH_03_theProvidersAnswerDecidesTheMarkWhenItsCallbackAlreadyMovedTheRow() {
        when(sender.send(any())).thenReturn(EmailSender.SendResult.sent("provider-1"));
        when(notifications.finish(any(), any(), any(), any(), any(), anyInt())).thenReturn(false);
        service.send("N-25", "account-a", Map.of("link", "https://club-a.example.test/activacio?t=fictional"));
        verify(accounts).markAccessEmailFailure("account-a", "person@example.test", null);
    }
    @Test void AUTH_03_theRetryableFailuresAreProviderTimeoutsAnd5xxOnly() {
        assertThat(SystemNotificationService.retryable(failed("Brevo timeout"))).isTrue();
        assertThat(SystemNotificationService.retryable(failed("SendGrid HTTP 599"))).isTrue();
        assertThat(SystemNotificationService.retryable(failed("Brevo HTTP 501"))).isTrue();
        assertThat(SystemNotificationService.retryable(failed("Brevo HTTP 429"))).isFalse();
        assertThat(SystemNotificationService.retryable(failed("Brevo HTTP 5000"))).isFalse();
        assertThat(SystemNotificationService.retryable(failed(null))).isFalse();
        assertThat(SystemNotificationService.retryable(EmailSender.SendResult.sent("provider-1"))).isFalse();
        assertThat(SystemNotificationService.access("N-25", Map.of())).isTrue();
        assertThat(SystemNotificationService.access("N-27", Map.of("variant", "ADMIN_WELCOME"))).isTrue();
        assertThat(SystemNotificationService.access("N-25", Map.of("variant", "EMAIL_CHANGE"))).isFalse();
        assertThat(SystemNotificationService.access("N-02", Map.of())).isFalse();
    }
}
