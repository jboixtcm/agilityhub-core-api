package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.application.AllowListEmailSender;
import com.agilityhub.core.clubs.messaging.domain.RetryPolicy;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HostedDemoEmailDeliveryTest {
    @Test void allowlistRefusalIsFinalWhileProviderOutagesStillRetry() {
        boolean retryable = NotificationDispatcher.emailRetryable(AllowListEmailSender.NOT_ALLOWED);
        assertThat(retryable).isFalse();
        assertThat(RetryPolicy.nextAttempt(1, retryable, Instant.EPOCH)).isEmpty();
        assertThat(NotificationDispatcher.emailRetryable("SendGrid timeout")).isTrue();
        assertThat(NotificationDispatcher.emailRetryable("SendGrid HTTP 429")).isTrue();
        assertThat(NotificationDispatcher.emailRetryable("SendGrid HTTP 502")).isTrue();
        assertThat(NotificationDispatcher.emailRetryable("SendGrid HTTP 400")).isFalse();
    }
}
