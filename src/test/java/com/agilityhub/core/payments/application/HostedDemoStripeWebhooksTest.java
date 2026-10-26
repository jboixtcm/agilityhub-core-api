package com.agilityhub.core.payments.application;

import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.domain.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostedDemoStripeWebhooksTest {
    @Test void aValidSignatureDoesNotAuthorizeLiveOrUnmarkedEventsInHostedDemo() throws Exception {
        var settings = mock(ClubPaymentProviders.class); var vault = mock(ProviderSecretVault.class); var events = mock(SecurityEvents.class);
        var service = new StripeWebhookSignatures(settings, vault, events, Clock.fixed(StripeWebhookSignaturesTest.NOW, ZoneOffset.UTC));
        when(vault.decrypt("encrypted", "club", "STRIPE", "webhookSecretEnc")).thenReturn(StripeWebhookSignaturesTest.SECRET);
        new ApplicationContextRunner().withPropertyValues("core.hosted-demo.enabled=true")
                .withBean(StripeWebhookSignatures.class, () -> service).run(context -> {
                    assertThat(context).hasNotFailed();
                    when(settings.stripe("club")).thenReturn(Optional.of(config("live")));
                    assertRejected(service, "{\"livemode\":false}");
                    verifyNoInteractions(vault);
                    when(settings.stripe("club")).thenReturn(Optional.of(config("test")));
                    for (String body : new String[] {"{\"livemode\":true}", "{}", "{\"livemode\":\"false\"}", "not-json", "null"}) {
                        assertRejected(service, body);
                    }
                    byte[] test = "{\"livemode\":false}".getBytes(StandardCharsets.UTF_8);
                    assertThatCode(() -> service.authenticate("club", signature(test), test)).doesNotThrowAnyException();
                    verify(events, times(6)).record(SecurityEvents.Type.WEBHOOK_SIGNATURE_INVALID, null, "club");
                });
    }
    private StripeProviderSettings config(String mode) { return new StripeProviderSettings(true, null, "secret", "encrypted", mode, null); }
    private void assertRejected(StripeWebhookSignatures service, String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.authenticate("club", signature(body), body)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ErrorCode.WEBHOOK_SIGNATURE_INVALID));
    }
    private String signature(byte[] body) throws Exception {
        long at = StripeWebhookSignaturesTest.NOW.getEpochSecond();
        return "t=" + at + ",v1=" + StripeWebhookSignaturesTest.v1(StripeWebhookSignaturesTest.SECRET, at, body);
    }
}
