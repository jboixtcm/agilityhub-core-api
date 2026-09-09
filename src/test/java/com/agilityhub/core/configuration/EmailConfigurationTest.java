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
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

    @Test void T_01_08_localMailboxIsOptInPrivateAndAtomicallyPublished() throws Exception {
        var mailbox = directory.resolve("mailbox");
        runner("local").withPropertyValues("email.local-mailbox-directory=" + mailbox).run(context -> {
            var message = new EmailMessage("fixture@example.test", "Subject", "HTML", "Text", new EmailMessage.Address("sender@example.test", "Example"),
                    null, Locale.ENGLISH, Map.of("notificationId", "fixture"));
            var sender = context.getBean(EmailSender.class);
            assertThat(sender.send(message).sent()).isTrue();
            assertThat(sender.send(message).sent()).isTrue();
        });
        assertThat(java.nio.file.Files.getPosixFilePermissions(mailbox)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        try (var files = java.nio.file.Files.list(mailbox)) {
            var messages = files.toList(); assertThat(messages).hasSize(2);
            for (var file : messages) {
                assertThat(file.getFileName().toString()).startsWith("local-").endsWith(".json");
                assertThat(java.nio.file.Files.getPosixFilePermissions(file)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                assertThat(new ObjectMapper().readTree(file.toFile()).path("to").asText()).isEqualTo("fixture@example.test");
            }
        }
        runner("test").withPropertyValues("email.local-mailbox-directory=" + directory.resolve("unused"))
                .run(context -> assertThat(context.getBean(EmailSender.class)).isInstanceOf(FakeEmailSender.class));
        assertThat(directory.resolve("unused")).doesNotExist();
    }

    @Test void T_01_08_localMailboxFailureDoesNotClaimDeliveryOrExposeMessage() throws Exception {
        var file = java.nio.file.Files.writeString(directory.resolve("file"), "fixture");
        var message = new EmailMessage("fixture@example.test", "Subject", "HTML", "Text", new EmailMessage.Address("sender@example.test", "Example"),
                null, Locale.ENGLISH, Map.of());
        assertThat(new LogEmailSender(file, new ObjectMapper()).send(message)).isEqualTo(EmailSender.SendResult.failed("Local mailbox write failed"));
        var mapper = mock(ObjectMapper.class);
        org.mockito.Mockito.doThrow(new java.io.IOException("private message")).when(mapper).writeValue(org.mockito.ArgumentMatchers.any(java.io.File.class), org.mockito.ArgumentMatchers.any());
        var mailbox = directory.resolve("failed");
        assertThat(new LogEmailSender(mailbox, mapper).send(message)).isEqualTo(EmailSender.SendResult.failed("Local mailbox write failed"));
        try (var files = java.nio.file.Files.list(mailbox)) { assertThat(files).isEmpty(); }
    }

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
