package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.domain.RetryPolicy;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * COMM-07 (owner decision of 13 Nov 2026): announcement e-mails go at the provider plan's pace, and a provider's quota answer
 * is retried for about a day instead of being final (S11 R-11-09, R-11-13, T-11-09).
 */
class AnnouncementPaceAndQuotaTest {
    @Test void T_11_18_COMM_07_atMostThePacePerClockMinuteTheNextOnesWaitForTheNextMinute() {
        var pace = new AnnouncementEmailPace(2);
        var start = Instant.parse("2026-11-04T10:15:20Z");
        assertThat(pace.acquire(start)).isEmpty();
        assertThat(pace.acquire(start.plusSeconds(5))).isEmpty();
        assertThat(pace.acquire(start.plusSeconds(10))).contains(Instant.parse("2026-11-04T10:16:00Z"));
        assertThat(pace.acquire(start.plusSeconds(39))).contains(Instant.parse("2026-11-04T10:16:00Z"));
        // The next minute has room again (a refused one never took a place).
        assertThat(pace.acquire(Instant.parse("2026-11-04T10:16:00Z"))).isEmpty();
        assertThat(pace.acquire(Instant.parse("2026-11-04T10:16:59Z"))).isEmpty();
        assertThat(pace.acquire(Instant.parse("2026-11-04T10:16:59Z"))).contains(Instant.parse("2026-11-04T10:17:00Z"));
        assertThat(pace.perMinute()).isEqualTo(2);
        assertThat(AnnouncementEmailPace.unlimited().acquire(start)).isEmpty();
        assertThatThrownBy(() -> new AnnouncementEmailPace(0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ANNOUNCEMENT_EMAILS_PER_MINUTE");
    }

    @Test void T_11_09_COMM_07_quotaAnswersAreRetriedAndOtherRejectionsStayFinal() {
        for (String quota : new String[] {"Brevo HTTP 429", "Brevo HTTP 402", "SendGrid HTTP 429", "Brevo HTTP 400 not_enough_credits", "Brevo HTTP 403 not_enough_credits"}) {
            assertThat(NotificationDispatcher.emailQuota(quota)).as(quota).isTrue();
            assertThat(NotificationDispatcher.emailRetryable(quota)).as(quota).isTrue();
        }
        for (String rejected : new String[] {"Brevo HTTP 400", "Brevo HTTP 401", "Brevo HTTP 403", "SendGrid HTTP 404"}) {
            assertThat(NotificationDispatcher.emailQuota(rejected)).as(rejected).isFalse();
            assertThat(NotificationDispatcher.emailRetryable(rejected)).as(rejected).isFalse();
        }
        for (String transientFailure : new String[] {"Brevo HTTP 503", "Brevo timeout", "Brevo transport failure"}) {
            assertThat(NotificationDispatcher.emailQuota(transientFailure)).as(transientFailure).isFalse();
            assertThat(NotificationDispatcher.emailRetryable(transientFailure)).as(transientFailure).isTrue();
        }
        assertThat(NotificationDispatcher.emailQuota(null)).isFalse();
    }

    @Test void T_11_09_COMM_07_aQuotaAnswerWaitsOneFiveFifteenSixtyMinutesThenEveryHourForAboutADay() {
        var now = Instant.parse("2026-11-04T10:00:00Z");
        long[] first = {1, 5, 15, 60};
        for (int attempts = 1; attempts <= 4; attempts++) {
            assertThat(RetryPolicy.nextQuotaAttempt(attempts, now)).contains(now.plus(Duration.ofMinutes(first[attempts - 1])));
        }
        long total = 1 + 5 + 15 + 60;
        for (int attempts = 5; attempts < RetryPolicy.QUOTA_MAX_ATTEMPTS; attempts++) {
            assertThat(RetryPolicy.nextQuotaAttempt(attempts, now)).contains(now.plus(Duration.ofMinutes(60)));
            total += 60;
        }
        assertThat(RetryPolicy.nextQuotaAttempt(RetryPolicy.QUOTA_MAX_ATTEMPTS, now)).isEmpty();
        assertThat(Duration.ofMinutes(total)).isGreaterThanOrEqualTo(Duration.ofHours(24)).isLessThan(Duration.ofHours(25));
        // An ordinary retryable failure keeps R-11-09: its 5th attempt is final.
        assertThat(RetryPolicy.nextAttempt(5, true, now)).isEmpty();
        assertThatThrownBy(() -> RetryPolicy.nextQuotaAttempt(0, now)).isInstanceOf(IllegalArgumentException.class);
    }
}
