package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.messaging.application.ports.NotificationFacts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationSubject;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.payments.application.InvoiceActions;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.shared.domain.ApiException;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * S12 §8 (E8-T02): `InvoiceFailed` explained to the notification engine. `payments` cannot reach `clubs.messaging` (ArchUnit
 * `PAYMENTS_CLUB_DEPENDENCIES`), so the composition root adapts it, as `FollowupPortConfiguration` does for S10.
 * <ul>
 * <li>N-10 «Rebut impagat» to the admins (R-12-17, manual or card): `member_name` and `invoice_number` from the invoice's
 * snapshot, `amount` its total, `reason` the failure the admin wrote (or the provider's code).</li>
 * <li>N-35 to the member, only for a card (`provider = STRIPE`, which the engine's condition checks too): `amount`, `reason`
 * and `retry_link`, the app's receipts screen where the card banner is (R-12-22; the card-setup session itself is E8-T04's).</li>
 * </ul>
 * No other S12 event has an owner yet: `InvoicePaid` reaches nobody (its payload names no member — no N-30 for a receipt paid by
 * hand, S12 §13-10), and the remaining S12/S13 codes wait for E8-T04/E8-T05.
 */
@Component
public class BillingNotificationFacts implements NotificationFactsPort {
    private final InvoiceActions invoices; private final CensusClubSettings settings;
    public BillingNotificationFacts(InvoiceActions invoices, CensusClubSettings settings) { this.invoices = invoices; this.settings = settings; }

    @Override public Set<String> eventTypes() { return Set.of("InvoiceFailed"); }

    @Override public Optional<NotificationFacts> facts(NotificationTrigger trigger, String code) {
        String invoiceId = trigger.text("invoiceId");
        if (invoiceId == null) { return Optional.empty(); }
        InvoiceActions.InvoiceDetail detail;
        try { detail = invoices.detail(invoiceId); } catch (ApiException unknown) { return Optional.empty(); }
        var invoice = detail.invoice();
        String reason = invoice.failureReason() != null ? invoice.failureReason() : trigger.text("reason");
        var subject = NotificationSubject.member(invoice.memberId());
        return switch (code) {
            case "N-10" -> Optional.of(NotificationFacts.builder().audiences("ADMINS").value("member_name", invoice.memberSnapshot().fullName())
                    .value("invoice_number", invoice.displayNumber()).value("amount", invoice.total()).value("reason", reason).value("entityId", invoice.id())
                    .subject(subject).build());
            case "N-35" -> "STRIPE".equals(trigger.text("provider"))
                    ? Optional.of(NotificationFacts.builder().member(new NotificationFacts.MemberSubject(invoice.memberId(), null,
                            java.util.Map.of("amount", invoice.total(), "reason", reason == null ? "" : reason, "retry_link", receipts()), subject))
                            .value("entityId", invoice.id()).build())
                    : Optional.empty();
            default -> Optional.empty();
        };
    }
    /** The app's receipts screen (screen 12 «Rebuts», PLA_FRONTEND §6), absolute on the club's verified app host. */
    private String receipts() {
        try { return "https://" + settings.appHost() + "/rebuts"; } catch (ApiException noVerifiedHost) { return "/rebuts"; }
    }
}
