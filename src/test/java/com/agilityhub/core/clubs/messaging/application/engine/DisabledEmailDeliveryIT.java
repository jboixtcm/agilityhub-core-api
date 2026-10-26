package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.application.EmailMessage;
import com.agilityhub.core.clubs.messaging.application.EmailSender;
import com.agilityhub.core.clubs.messaging.domain.DeliveryStatus;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.shared.application.TenantContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class DisabledEmailDeliveryIT extends EngineFixtures {
    private NotificationDispatcher disabledDispatcher(boolean idle) {
        EmailSender disabled = new EmailSender() {
            @Override public boolean deliveryDisabled() { return true; }
            @Override public SendResult send(EmailMessage message) { throw new AssertionError("Disabled email must never reach any sender"); }
        };
        return new NotificationDispatcher(notifications, subscriptions, disabled, sms, push, configs, emailSettings, usage, emails, unsubscribes,
                ports, accounts, events, new TransactionTemplate(transactions), owners, clock, "no-reply@example.test") {
            @Override public int dispatch(java.util.Collection<String> ids) { return idle ? 0 : super.dispatch(ids); }
        };
    }
    private String notificationEvent() {
        classSession(CLUB, "e7t02-class-a", "2026-10-08T18:50", List.of("instructor-marta"));
        return deliver(CLUB, "ClassAutoCancelled", Map.of("classId", "e7t02-class-a", "dogsCount", 1));
    }
    private void assertSkipped(int attempts) {
        var notices = stored(CLUB, "N-17");
        assertThat(notices).hasSize(3);
        for (var notice : notices) {
            assertThat(notice.deliveries()).filteredOn(d -> d.channel() == NotificationChannel.EMAIL).singleElement().satisfies(d -> {
                assertThat(d.status()).isEqualTo(DeliveryStatus.SKIPPED_NOT_ALLOWED);
                assertThat(d.attempts()).isEqualTo(attempts); assertThat(d.nextAttemptAt()).isNull();
                assertThat(d.sentAt()).isNull(); assertThat(d.deliveredAt()).isNull(); assertThat(d.providerRef()).isNull();
            });
            assertThat(notice.deliveries()).filteredOn(d -> d.channel() == NotificationChannel.APP)
                    .allSatisfy(d -> assertThat(d.status()).isEqualTo(DeliveryStatus.DELIVERED));
        }
        assertThat(mail.sent).isEmpty();
    }
    @Test void internalReviewCreatesTerminalSkipsEvenWhenDispatcherNeverRunsThenDoesNotReplayOnEnable() {
        engine = engine(disabledDispatcher(true));
        String event = notificationEvent();
        assertSkipped(0);
        assertThat(dispatcher.poll()).isZero();
        engine = engine(dispatcher);
        deliver(event, CLUB, "ClassAutoCancelled", Map.of("classId", "e7t02-class-a", "dogsCount", 1));
        assertSkipped(0);
        assertThat(outbox(CLUB, "NotificationSent")).isEmpty();
    }
    @Test void pendingEmailIsTerminallySkippedByDisabledDispatcherWithoutRenderingOrRetry() {
        engine = engine(idleDispatcher()); notificationEvent();
        var disabled = disabledDispatcher(false);
        assertThat(disabled.poll()).isEqualTo(3);
        assertSkipped(0);
        clock.advance(java.time.Duration.ofDays(2));
        assertThat(disabled.poll()).isZero(); assertThat(dispatcher.poll()).isZero();
        assertThat(outbox(CLUB, "NotificationSent")).isEmpty();
    }
    @Test void directTemplateTestDeliveryAlsoPersistsSkipsBeforeAnySchedulerRuns() {
        engine = engine(idleDispatcher()); notificationEvent();
        var pending = stored(CLUB, "N-17");
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), Notification.class);
        engine = engine(disabledDispatcher(true));
        try (var tenant = TenantContext.open(CLUB)) { new TransactionTemplate(transactions).executeWithoutResult(tx -> engine.deliver(pending)); }
        assertSkipped(0); assertThat(dispatcher.poll()).isZero();
    }
}
