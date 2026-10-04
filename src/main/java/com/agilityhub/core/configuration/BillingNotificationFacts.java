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
 * E8-T04 also owns Stripe receipts, upfront receipts and card-invalid notifications. Manual invoice payments emit no N-30.
 */
@Component
public class BillingNotificationFacts implements NotificationFactsPort {
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.payments.application.PaymentNotificationFacts payments;
    private final InvoiceActions invoices; private final CensusClubSettings settings;
    public BillingNotificationFacts(InvoiceActions invoices, CensusClubSettings settings) { this.invoices = invoices; this.settings = settings; }

    @Override public Set<String> eventTypes() { return Set.of("InvoiceFailed", "InvoicePaid", "UpfrontPaymentSucceeded", "MemberCardInvalidated"); }

    @Override public Optional<NotificationFacts> facts(NotificationTrigger trigger, String code) {
        String invoiceId = trigger.text("invoiceId");
        if (invoiceId == null) {
            if (!Set.of("UpfrontPaymentSucceeded", "MemberCardInvalidated").contains(trigger.type())
                    || trigger.type().equals("UpfrontPaymentSucceeded") && trigger.text("paymentId") == null) { return Optional.empty(); }
            String member = trigger.text("memberId"); if (member == null) { return Optional.empty(); }
            var values = new java.util.LinkedHashMap<String, Object>();
            if (code.equals("N-35")) { values.put("amount", ""); values.put("reason", java.util.Objects.toString(trigger.text("reason"), "")); values.put("retry_link", "/me/card-setup"); }
            else if (code.equals("N-30")) { values.putAll(payments.upfront(trigger.text("paymentId"))); }
            else { return Optional.empty(); }
            return Optional.of(NotificationFacts.builder().member(new NotificationFacts.MemberSubject(member, null, values, NotificationSubject.member(member))).build());
        }
        InvoiceActions.InvoiceDetail detail;
        try { detail = invoices.detail(invoiceId); } catch (ApiException unknown) { return Optional.empty(); }
        var invoice = detail.invoice();
        String reason = invoice.failureReason() != null ? invoice.failureReason() : trigger.text("reason");
        var subject = NotificationSubject.member(invoice.memberId());
        return switch (code) {
            case "N-10" -> Optional.of(NotificationFacts.builder().audiences("ADMINS").value("member_name", invoice.memberSnapshot().fullName())
                    .value("invoice_number", invoice.displayNumber()).value("amount", invoice.total()).value("reason", reason).value("entityId", invoice.id())
                    .subject(subject).build());
            case "N-30" -> "STRIPE".equals(trigger.text("provider")) ? Optional.of(NotificationFacts.builder()
                    .member(new NotificationFacts.MemberSubject(invoice.memberId(), null, java.util.Map.of("amount", invoice.total(), "concept", invoice.lines().getFirst().description(),
                            "invoice_number", invoice.displayNumber()), subject)).value("entityId", invoice.id()).build()) : Optional.empty();
            case "N-35" -> "STRIPE".equals(trigger.text("provider"))
                    ? Optional.of(NotificationFacts.builder().member(new NotificationFacts.MemberSubject(invoice.memberId(), null,
                            java.util.Map.of("amount", invoice.total(), "reason", reason == null ? "" : reason, "retry_link", "/me/card-setup"), subject))
                            .value("entityId", invoice.id()).build())
                    : Optional.empty();
            default -> Optional.empty();
        };
    }
}
