package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * COMM-03 (owner's decision of 13-11): a level change (N-09) or a payment-method edit (N-38) the ADMIN made with `notifyMember: false`
 * sends the member nothing; every other census notice, and those two without the flag, are unchanged.
 */
class CensusNoticeChoiceTest {
    static NotificationTrigger trigger(String type, Map<String, Object> payload) {
        return new NotificationTrigger("event-1", type, "club-a", "Dog", "dog-a", Instant.parse("2026-10-05T08:00:00Z"), payload, "admin-account", null,
                DomainEvent.Origin.BACKOFFICE);
    }

    @Test void COMM_03_onlyALevelOrPaymentMethodChangeWithNotifyFalseIsSilenced() {
        assertThat(CensusNotificationFacts.silenced(trigger("DogLevelChanged", Map.of("dogId", "dog-a", "notifyMember", false)))).isTrue();
        assertThat(CensusNotificationFacts.silenced(trigger("MemberPaymentMethodChanged", Map.of("memberId", "member-a", "notifyMember", false)))).isTrue();
        assertThat(CensusNotificationFacts.silenced(trigger("DogLevelChanged", Map.of("dogId", "dog-a")))).isFalse();
        assertThat(CensusNotificationFacts.silenced(trigger("DogLevelChanged", Map.of("dogId", "dog-a", "notifyMember", true)))).isFalse();
        assertThat(CensusNotificationFacts.silenced(trigger("MemberPaymentMethodChanged", Map.of("memberId", "member-a")))).isFalse();
        // Another census notice is never silenced by the flag.
        assertThat(CensusNotificationFacts.silenced(trigger("BookingBlockChanged", Map.of("memberId", "member-a", "notifyMember", false)))).isFalse();
        assertThat(CensusNotificationFacts.silenced(trigger("DogRegistered", Map.of("dogId", "dog-a", "notifyMember", false)))).isFalse();
    }
}
