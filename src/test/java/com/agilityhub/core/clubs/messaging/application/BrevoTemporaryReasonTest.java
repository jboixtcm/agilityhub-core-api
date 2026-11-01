package com.agilityhub.core.clubs.messaging.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BrevoTemporaryReasonTest {
    @Test void keepsOnlySafeDiagnosticCategories() {
        assertThat(BrevoWebhookService.temporaryReason(null)).isEqualTo("TEMPORARY_REJECTION");
        assertThat(BrevoWebhookService.temporaryReason("554 blocked by SpamCop https://private.example/reset?token=secret"))
                .isEqualTo("SENDING_IP_BLOCKED_SPAMCOP");
        for (String reason : java.util.List.of("IP on blacklist", "IP blocklist", "Client host blocked")) {
            assertThat(BrevoWebhookService.temporaryReason(reason)).isEqualTo("SENDING_IP_BLOCKED");
        }
        for (String reason : java.util.List.of("mailbox full", "quota exceeded", "5.2.2", "4.2.2")) {
            assertThat(BrevoWebhookService.temporaryReason(reason)).isEqualTo("MAILBOX_FULL");
        }
        for (String reason : java.util.List.of("rate limit exceeded", "too many requests")) {
            assertThat(BrevoWebhookService.temporaryReason(reason)).isEqualTo("RATE_LIMITED");
        }
        assertThat(BrevoWebhookService.temporaryReason("private@example.test reset_token=secret")).isEqualTo("TEMPORARY_REJECTION");
        assertThat(BrevoWebhookService.temporaryReason("x".repeat(4096) + "SpamCop")).isEqualTo("TEMPORARY_REJECTION");
    }
}
