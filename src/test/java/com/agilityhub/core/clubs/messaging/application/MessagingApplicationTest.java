package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.application.ports.InMemoryMessagingPorts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.clubs.messaging.persistence.NotificationRepository;
import com.agilityhub.core.clubs.messaging.persistence.SendGridWebhookReceipt;
import com.agilityhub.core.clubs.messaging.persistence.SendGridWebhookReceiptRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** E7-T02: S11's own facts (N-49, N-51), the preference helpers other contexts use, and the webhook's input guards (R-11-08). */
class MessagingApplicationTest {
    private static NotificationTrigger trigger(String type, Map<String, Object> payload) {
        return new NotificationTrigger("event-a", type, "club-a", "Club", "club-a", Instant.EPOCH, payload, null, null, DomainEvent.Origin.WEBHOOK);
    }

    @Test void T_11_11_T_11_07_theFactsOfN51AndN49() {
        var facts = new MessagingNotificationFacts(InMemoryMessagingPorts.s11Examples(), null);
        assertThat(facts.eventTypes()).containsExactlyInAnyOrder("EmailBounced", "SmsCapReached", "AnnouncementSent");
        var bounced = facts.facts(trigger("EmailBounced", Map.of("memberId", "member-anna", "email", "anna@example.test", "type", "BOUNCE")), "N-51").orElseThrow();
        assertThat(bounced.values()).containsEntry("member_name", "Anna Soler").containsEntry("email", "anna@example.test");
        assertThat(bounced.members()).isEmpty(); assertThat(bounced.subject().memberId()).isEqualTo("member-anna");
        assertThat(facts.facts(trigger("EmailBounced", Map.of("memberId", "member-gone", "email", "x@example.test")), "N-51").orElseThrow().values())
                .containsEntry("member_name", "");
        assertThat(facts.facts(trigger("EmailBounced", Map.of("email", "x@example.test")), "N-51").orElseThrow().values()).containsEntry("member_name", "");
        var cap = facts.facts(trigger("SmsCapReached", Map.of("month", "2026-10", "cap", 1000)), "N-49").orElseThrow();
        assertThat(cap.values()).containsEntry("month", YearMonth.of(2026, 10)).containsEntry("cap", 1000);
        assertThat(facts.facts(trigger("SmsCapReached", Map.of("month", "October")), "N-49").orElseThrow().values()).containsEntry("month", "October").containsEntry("cap", 0);
        assertThat(facts.facts(trigger("SmsCapReached", Map.of()), "N-49").orElseThrow().values()).doesNotContainKey("month");
        assertThat(facts.facts(trigger("Other", Map.of()), "N-49")).isEmpty();
    }

    @Test void R_11_03_R_11_08_thePreferenceHelpersOfTheOtherContexts() {
        assertThat(NotificationPreferences.clubChangesChannels(null, true, true)).containsExactly("APP", "EMAIL", "SMS");
        assertThat(NotificationPreferences.clubChangesChannels(Map.of("emailByCategory", Map.of("CLUB_CHANGES", false)), true, false)).containsExactly("APP");
        assertThat(NotificationPreferences.clubChangesChannels(Map.of(), false, true)).containsExactly("APP", "SMS");
        assertThat(NotificationPreferences.clubNewsEmail(Map.of())).isTrue();
        assertThat(NotificationPreferences.clubNewsEmail(Map.of("emailByCategory", Map.of("CLUB_NEWS", false)))).isFalse();
        var off = NotificationPreferences.clubNewsOff(Map.of("emailByCategory", Map.of("PERSONAL", false), "essentialOnly", true, "reminderMinutesBefore", 60), Instant.EPOCH);
        assertThat(off).containsEntry("essentialOnly", true).containsEntry("reminderMinutesBefore", 60).containsEntry("updatedAt", Instant.EPOCH);
        @SuppressWarnings("unchecked") var email = (Map<String, Boolean>) off.get("emailByCategory");
        assertThat(email).containsEntry("CLUB_NEWS", false).containsEntry("PERSONAL", false).containsEntry("CLUB_CHANGES", true).containsEntry("OPERATIONAL", false);
    }

    @Test void T_11_22_theWebhookIgnoresIncompleteOrForeignEventsAndAConcurrentReplay() {
        var notifications = mock(NotificationRepository.class); var receipts = mock(SendGridWebhookReceiptRepository.class);
        var service = new SendGridWebhookService(notifications, receipts, mock(NotificationAccounts.class), mock(EventPublisher.class),
                new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), InMemoryMessagingPorts.s11Examples(),
                InMemoryMessagingPorts.s11Examples());
        for (var incomplete : List.of(new String[] {null, "bounce", "n", "c", "e"}, new String[] {" ", "bounce", "n", "c", "e"}, new String[] {"i", null, "n", "c", "e"},
                new String[] {"i", "open", "n", "c", "e"}, new String[] {"i", "bounce", null, "c", "e"}, new String[] {"i", "bounce", "n", "c", null},
                new String[] {"i", "bounce", "n", " ", "e"})) {
            service.accept(incomplete[0], incomplete[1], incomplete[2], incomplete[3], incomplete[4]);
        }
        verifyNoInteractions(notifications, receipts);
        // A replay that raced another delivery of the same event id: the other one's receipt wins, nothing is thrown.
        when(receipts.findById("dup")).thenReturn(Optional.empty(), Optional.of(new SendGridWebhookReceipt("dup", "club-a", "n", Instant.EPOCH)));
        when(notifications.findById("n")).thenThrow(new DuplicateKeyException("receipt"));
        assertThatCode(() -> service.accept("dup", "delivered", "n", "club-a", "e@example.test")).doesNotThrowAnyException();
        // Without a receipt the conflict is real.
        when(receipts.findById("lost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.accept("lost", "delivered", "n", "club-a", "e@example.test")).isInstanceOf(DuplicateKeyException.class);
        verify(receipts, never()).insert(any(SendGridWebhookReceipt.class));
    }
}
