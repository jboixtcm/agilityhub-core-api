package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.shared.application.HostedDemoPolicy;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AllowListEmailSenderTest {
    @Test void onlyExactRecipientsReachTheRealSenderAndItsFailureIsPreserved() {
        var environment = new MockEnvironment().withProperty("core.hosted-demo.enabled", "true")
                .withProperty("core.hosted-demo.email-allowlist", "Tester@example.test, second@example.test");
        environment.setActiveProfiles("prod");
        var delegate = mock(EmailSender.class);
        var sender = new AllowListEmailSender(delegate, HostedDemoPolicy.from(environment));
        for (String to : List.of("other@example.test", "tester@sub.example.test", "tester@example.test.attacker.test", "Tester <tester@example.test>",
                "tester@example.test,other@example.test", "*", "")) {
            var result = sender.send(message(to));
            assertThat(result.sent()).isFalse(); assertThat(result.providerMessageId()).isNull();
            assertThat(result.error()).isEqualTo("Email recipient not allowed in hosted demo");
        }
        verifyNoInteractions(delegate);
        var allowed = message("TESTER@example.test");
        when(delegate.send(allowed)).thenReturn(EmailSender.SendResult.sent("provider-id"));
        assertThat(sender.send(allowed)).isEqualTo(EmailSender.SendResult.sent("provider-id"));
        var second = message("second@example.test");
        when(delegate.send(second)).thenReturn(EmailSender.SendResult.failed("Provider unavailable"));
        assertThat(sender.send(second).sent()).isFalse();
        verify(delegate).send(allowed); verify(delegate).send(second); verifyNoMoreInteractions(delegate);
    }
    @Test void ordinaryDeploymentDoesNotRequireAListButInvalidDemoToggleIsRejected() {
        var environment = new MockEnvironment(); environment.setActiveProfiles("prod");
        assertThat(HostedDemoPolicy.from(environment).enabled()).isFalse();
        environment.setProperty("core.hosted-demo.enabled", "yes");
        assertThatThrownBy(() -> HostedDemoPolicy.from(environment)).isInstanceOf(IllegalStateException.class);
    }
    private EmailMessage message(String to) {
        return new EmailMessage(to, "Private subject", "Private HTML", "Private text", new EmailMessage.Address("sender@example.test", "Demo"),
                null, Locale.ENGLISH, Map.of());
    }
}
