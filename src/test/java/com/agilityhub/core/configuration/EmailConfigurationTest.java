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

    @Test void T_11_25_brevoRequiresItsOwnCredentialsAndKeepsHostedRecipientRestriction() {
        String token = java.util.UUID.randomUUID().toString();
        runner("prod").withPropertyValues("email.provider=brevo", "email.platform-from=sender@example.test",
                "email.brevo.api-key=fictional", "email.brevo.webhook-token=" + token).run(context -> {
            assertThat(context).hasNotFailed(); assertThat(context.getBean(EmailSender.class)).isInstanceOf(BrevoEmailSender.class);
        });
        runner("prod").withPropertyValues("email.provider=brevo", "email.platform-from=sender@example.test",
                "email.brevo.api-key=fictional").run(context -> {
            assertThat(context).hasFailed(); assertThat(context.getStartupFailure()).hasRootCauseMessage("BREVO_WEBHOOK_TOKEN must contain 32 to 256 random URL-safe characters in staging/prod");
        });
        runner("prod").withPropertyValues("email.provider=brevo", "email.platform-from=sender@example.test").run(context -> {
            assertThat(context).hasFailed(); assertThat(context.getStartupFailure()).hasRootCauseMessage("BREVO_API_KEY is required in staging/prod");
        });
        runner("prod").withPropertyValues("email.provider=brevo", "email.platform-from=sender@example.test", "email.brevo.api-key=fictional",
                "email.brevo.webhook-token=" + token, "core.hosted-demo.enabled=true", "core.hosted-demo.email-allowlist=tester@example.test").run(context -> {
            assertThat(context).hasNotFailed(); assertThat(context.getBean(EmailSender.class)).isInstanceOf(AllowListEmailSender.class);
            assertThat(context.getBean(EmailSender.class).send(new EmailMessage("outside@example.test", "Subject", "body", "body",
                    new EmailMessage.Address("sender@example.test", "Example"), null, Locale.ENGLISH, Map.of())).error()).isEqualTo(AllowListEmailSender.NOT_ALLOWED);
        });
        runner("prod").withPropertyValues("email.provider=unknown").run(context -> {
            assertThat(context).hasFailed(); assertThat(context.getStartupFailure()).hasRootCauseMessage("EMAIL_PROVIDER must be sendgrid or brevo");
        });
    }

    @ParameterizedTest @ValueSource(strings = {"sendgrid", "brevo"})
    void internalReviewDisablesEverySenderWithoutProviderKeysOrRecipientList(String provider) {
        runner("prod").withPropertyValues("core.hosted-demo.enabled=true", "email.delivery-enabled=false", "email.provider=" + provider)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var sender = context.getBean(EmailSender.class);
                    assertThat(sender).isInstanceOf(DisabledEmailSender.class);
                    assertThat(sender.deliveryDisabled()).isTrue();
                    assertThat(sender.send(null)).isEqualTo(EmailSender.SendResult.failed(DisabledEmailSender.DISABLED));
                    assertThatThrownBy(() -> context.getBean(BrevoWebhookAuthentication.class).verify("Bearer anything"))
                            .isInstanceOf(com.agilityhub.core.shared.domain.ApiException.class);
                    assertThatThrownBy(() -> context.getBean(SendGridSignatureVerifier.class).verify(new byte[0], "1", "anything"))
                            .isInstanceOf(com.agilityhub.core.shared.domain.ApiException.class);
                    org.mockito.Mockito.verifyNoInteractions(context.getBean(BrevoWebhookRepository.class));
                });
    }
    @Test void internalReviewPreservesConfiguredBrevoWebhookAuthenticationWithoutOutboundCalls() {
        String token = java.util.UUID.randomUUID().toString();
        runner("prod").withPropertyValues("core.hosted-demo.enabled=true", "email.delivery-enabled=false", "email.provider=brevo",
                "email.brevo.api-key=fictional", "email.brevo.webhook-token=" + token).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EmailSender.class)).isInstanceOf(DisabledEmailSender.class);
            context.getBean(BrevoWebhookAuthentication.class).verify("Bearer " + token);
            assertThatThrownBy(() -> context.getBean(BrevoWebhookAuthentication.class).verify("Bearer wrong"))
                    .isInstanceOf(com.agilityhub.core.shared.domain.ApiException.class);
        });
    }

    @Test void internalReviewSwitchCannotSilentlyDisableNormalProduction() {
        runner("prod").withPropertyValues("email.delivery-enabled=false").run(context ->
                assertThat(context.getStartupFailure()).hasRootCauseMessage("EMAIL_DELIVERY_ENABLED=false requires HOSTED_DEMO_ENABLED=true"));
        runner("prod").withPropertyValues("email.delivery-enabled=off").run(context ->
                assertThat(context.getStartupFailure()).hasRootCauseMessage("EMAIL_DELIVERY_ENABLED must be true or false"));
        runner("local").withPropertyValues("email.delivery-enabled=false", "core.hosted-demo.enabled=true").run(context ->
                assertThat(context.getStartupFailure()).hasRootCauseMessage("HOSTED_DEMO_ENABLED requires only a deployed profile, without local/test"));
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
                .withBean(BrevoWebhookRepository.class, () -> mock(BrevoWebhookRepository.class))
                .withBean(SendGridWebhookReceiptRepository.class, () -> mock(SendGridWebhookReceiptRepository.class))
                .withBean(EventPublisher.class, () -> mock(EventPublisher.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(MongoTemplate.class, () -> mock(MongoTemplate.class)).withBean(Clock.class, Clock::systemUTC)
                // E7-T02: the webhook marks bounced contact addresses through the census ports.
                .withBean(com.agilityhub.core.clubs.messaging.application.ports.MemberDirectoryPort.class,
                        () -> mock(com.agilityhub.core.clubs.messaging.application.ports.MemberDirectoryPort.class))
                .withBean(com.agilityhub.core.clubs.messaging.application.ports.MemberContactsWriterPort.class,
                        () -> mock(com.agilityhub.core.clubs.messaging.application.ports.MemberContactsWriterPort.class));
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
