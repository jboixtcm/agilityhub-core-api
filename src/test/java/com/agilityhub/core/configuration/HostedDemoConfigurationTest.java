package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.messaging.application.integrations.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class HostedDemoConfigurationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockEnvironment demo(String recipients) {
        var environment = new MockEnvironment().withProperty("core.hosted-demo.enabled", "true")
                .withProperty("core.hosted-demo.email-allowlist", recipients);
        environment.setActiveProfiles("prod"); return environment;
    }
    @ParameterizedTest @ValueSource(strings = {"", "*", "@example.test", "*@example.test", "tester@example.test,", "tester@example.test;other@example.test"})
    void hostedDemoRequiresANonemptyExactRecipientAllowlist(String recipients) {
        assertThatThrownBy(() -> new EmailConfiguration().emailSender(demo(recipients), mapper, "key", "sender@example.test", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HOSTED_DEMO_EMAIL_ALLOWLIST");
    }
    @Test void hostedDemoCannotUseLocalOrTestProviderDoubles() {
        var environment = demo("tester@example.test"); environment.setActiveProfiles("local");
        assertThatThrownBy(() -> new EmailConfiguration().emailSender(environment, mapper, "", "", ""))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void hostedDemoSmsIsNotAllowedWithoutTwilioCredentials() {
        var sender = new MessagingConfiguration().smsSender(demo("tester@example.test"), mapper, "", "", "", "");
        assertThat(sender.send(null)).isEqualTo(SendResult.notAllowed());
    }
    @Test void hostedDemoPushIsUnavailableWithoutVapidCredentials() {
        var sender = new MessagingConfiguration().pushSender(demo("tester@example.test"), mapper, Clock.systemUTC(),
                mock(ClubConfigService.class), "", "", "");
        assertThat(sender.publicKey()).isNull();
        assertThat(sender.send(null, null).status()).isEqualTo(PushResult.Status.FAILED);
    }
    @Test void hostedDemoStillRequiresSendGridAndReturnsAGuardedRealSender() {
        var configuration = new EmailConfiguration(); var environment = demo("tester@example.test");
        assertThatThrownBy(() -> configuration.emailSender(environment, mapper, "", "sender@example.test", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SENDGRID_API_KEY");
        assertThatThrownBy(() -> configuration.emailSender(environment, mapper, "key", "", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_FROM_PLATFORM");
        assertThat(configuration.emailSender(environment, mapper, "key", "sender@example.test", ""))
                .isInstanceOf(com.agilityhub.core.clubs.messaging.application.AllowListEmailSender.class);
        assertThatThrownBy(() -> configuration.sendGridSignatures(environment, ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SENDGRID_WEBHOOK_PUBLIC_KEY");
    }
}
