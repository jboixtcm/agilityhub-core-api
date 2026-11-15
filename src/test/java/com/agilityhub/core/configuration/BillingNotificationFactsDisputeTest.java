package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.messaging.application.ports.MemberContact;
import com.agilityhub.core.clubs.messaging.application.ports.MemberDirectoryPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.payments.application.InvoiceActions;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.shared.domain.Money;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PAY-06 (owner decision of 13 Nov 2026, S12 §8): `PaymentDisputed` gives the admins N-56 with the member's name, the disputed
 * amount and Stripe's reason, opening the member's record — for a Checkout payment and for an invoice's card collection alike.
 */
class BillingNotificationFactsDisputeTest {
    static final Instant NOW = Instant.parse("2026-10-20T09:00:00Z");
    final MemberDirectoryPort members = mock(MemberDirectoryPort.class);
    final BillingNotificationFacts facts = new BillingNotificationFacts(mock(InvoiceActions.class), mock(CensusClubSettings.class));

    @Test void PAY_06_aDisputeGivesTheAdminsN56WithTheMemberTheAmountAndTheReason() {
        ReflectionTestUtils.setField(facts, "members", members);
        var contact = mock(MemberContact.class);
        when(contact.displayName()).thenReturn("Laura Serra Example");
        when(members.find("member-1")).thenReturn(Optional.of(contact));
        assertThat(facts.eventTypes()).contains("PaymentDisputed");
        for (var payload : List.of(
                Map.<String, Object>of("disputeId", "dp_1", "memberId", "member-1", "amount", Map.of("amountMinor", 12000, "currency", "EUR"), "reason", "fraudulent",
                        "upfrontPaymentIds", List.of("row-1")),
                Map.<String, Object>of("disputeId", "dp_2", "memberId", "member-1", "amount", Map.of("amountMinor", 12000, "currency", "EUR"), "reason", "fraudulent",
                        "upfrontPaymentIds", List.of(), "invoiceId", "invoice-1"))) {
            var trigger = new NotificationTrigger("event-dispute", "PaymentDisputed", "club-a", "Member", "member-1", NOW, payload, null, null, null);
            var notice = facts.facts(trigger, "N-56").orElseThrow();
            assertThat(notice.audiences()).containsExactly("ADMINS");
            assertThat(notice.values()).containsEntry("member_name", "Laura Serra Example").containsEntry("amount", new Money(12000, "EUR"))
                    .containsEntry("reason", "fraudulent").containsEntry("entityId", "member-1");
        }
        // A member no longer in the directory (erased) gives no notice.
        when(members.find("member-2")).thenReturn(Optional.empty());
        var gone = new NotificationTrigger("event-gone", "PaymentDisputed", "club-a", "Member", "member-2", NOW,
                Map.of("disputeId", "dp_3", "memberId", "member-2", "amount", Map.of("amountMinor", 100, "currency", "EUR"), "reason", "general"), null, null, null);
        assertThat(facts.facts(gone, "N-56")).isEmpty();
    }
}
