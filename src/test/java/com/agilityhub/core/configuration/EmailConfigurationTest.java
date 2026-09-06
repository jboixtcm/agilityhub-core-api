package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.messaging.application.*;
import com.agilityhub.core.clubs.messaging.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.shared.application.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class EmailConfigurationTest {
    private ApplicationContextRunner runner(String... profiles) {
        return new ApplicationContextRunner().withUserConfiguration(EmailConfiguration.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                .withBean(ObjectMapper.class, ObjectMapper::new).withBean(IcuMessageSource.class, () -> {
                    try { return new IcuMessageSource(); } catch (Exception failure) { throw new IllegalStateException(failure); }
                }).withBean(NotificationAccounts.class, () -> mock(NotificationAccounts.class))
                .withBean(ClubEmailSettings.class, () -> mock(ClubEmailSettings.class))
                .withBean(ParameterCatalog.class, () -> mock(ParameterCatalog.class))
                .withBean(NotificationRepository.class, () -> mock(NotificationRepository.class))
                .withBean(SendGridWebhookReceiptRepository.class, () -> mock(SendGridWebhookReceiptRepository.class))
                .withBean(EventPublisher.class, () -> mock(EventPublisher.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(MongoTemplate.class, () -> mock(MongoTemplate.class)).withBean(Clock.class, Clock::systemUTC);
    }
    @ParameterizedTest @ValueSource(strings = {"staging", "prod"})
    void T_11_25_productionStartupFailsClearlyWithoutApiKey(String profile) {
        runner(profile, "local", "test").run(context -> {
            assertThat(context).hasFailed(); assertThat(context.getStartupFailure()).hasRootCauseMessage("SENDGRID_API_KEY is required in staging/prod");
        });
    }
    @Test void T_11_25_localAndTestSinksAndConfiguredSender() {
        runner("local").run(context -> {
            assertThat(context).hasNotFailed(); assertThat(context.getBean(EmailSender.class)).isInstanceOf(LogEmailSender.class);
            var message = new EmailMessage("fixture@example.test", "Subject", "HTML", "Text", new EmailMessage.Address("sender@example.test", "Example"),
                    null, Locale.ENGLISH, Map.of("notificationId", "fixture"));
            assertThat(context.getBean(EmailSender.class).send(message).sent()).isTrue();
            assertThat(message.toString()).isEqualTo("EmailMessage[redacted]");
        });
        runner("test").run(context -> assertThat(context.getBean(EmailSender.class)).isInstanceOf(FakeEmailSender.class));
        runner("local").withPropertyValues("email.sendgrid.api-key=" + java.util.UUID.randomUUID())
                .run(context -> assertThat(context.getBean(EmailSender.class)).isInstanceOf(SendGridEmailSender.class));
    }
    @Test void T_11_25_productionRequiresSenderAndVerificationKey() {
        var keyProperty = "email.sendgrid.api-key=" + java.util.UUID.randomUUID();
        runner("prod").withPropertyValues(keyProperty).run(context ->
                assertThat(context.getStartupFailure()).hasRootCauseMessage("MAIL_FROM_PLATFORM is required in staging/prod"));
        runner("prod").withPropertyValues(keyProperty, "email.platform-from=sender@example.test").run(context ->
                assertThat(context.getStartupFailure()).hasRootCauseMessage("SENDGRID_WEBHOOK_PUBLIC_KEY is required in staging/prod"));
    }
    @Test void T_11_25_rendererEscapesBrandingAndRejectsUnsafeAssets() throws Exception {
        var renderer = new EmailConfiguration().systemEmailRenderer(new IcuMessageSource());
        for (String logo : java.util.Arrays.asList("javascript:alert(1)", "not a URI", null)) {
            var settings = new ClubEmailSettings.Settings("<script>Example</script>", logo, "red;bad", null,
                    "sender@example.test", "Example", null, "en", 15);
            var message = renderer.render("N-25", "fixture@example.test", Locale.ENGLISH,
                    Map.of("link", "https://id.example.test/sign-in?a=1&b=2"), settings, Map.of());
            assertThat(message.html()).contains("&lt;script&gt;Example&lt;/script&gt;", "#2563eb", "#ffffff", "a=1&amp;b=2")
                    .doesNotContain("<script>", "javascript:", "<img", "red;bad");
            assertThat(message.text()).contains("a=1&b=2");
        }
    }
}
