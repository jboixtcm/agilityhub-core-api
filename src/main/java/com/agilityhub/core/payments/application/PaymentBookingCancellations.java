package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.UpfrontPaymentRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.PendingChargeRepository;
import com.agilityhub.core.payments.persistence.PendingCharge;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.Money;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;

/** R-12-20: the booking event only commits a refund command or a credit; the recovery worker owns network calls. */
@Component("payments.BookingCancelled")
public class PaymentBookingCancellations implements DomainEventHandler<PaymentBookingCancellations.Event> {
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(String clubId, String aggregateId, Map<String, Object> payload) { }
    private final UpfrontPaymentRepository payments; private final PaymentRefunds refunds; private final PendingChargeRepository charges;
    private final ClubConfigService configs; private final Clock clock;
    public PaymentBookingCancellations(UpfrontPaymentRepository payments, PaymentRefunds refunds, PendingChargeRepository charges, ClubConfigService configs, Clock clock) {
        this.payments = payments; this.refunds = refunds; this.charges = charges; this.configs = configs; this.clock = clock;
    }
    @Override public String eventType() { return "BookingCancelled"; }
    @Override public Class<Event> eventClass() { return Event.class; }
    @Override public void handle(String eventId, Event event) {
        try (var tenant = TenantContext.open(event.clubId())) {
            var config = configs.get(event.clubId());
            if (!config.modules().contains(Module.BILLING) || !config.modules().contains(Module.SINGLE_CLASS) || Boolean.TRUE.equals(event.payload().get("late"))) { return; }
            String booking = Objects.toString(event.payload().getOrDefault("bookingId", event.aggregateId()));
            for (var payment : payments.forBooking(booking)) {
                if (!payment.status().equals("PAID") || !"STRIPE".equals(payment.provider())) { continue; }
                String policy = config.get("billing.singleClassCancelPolicy", String.class);
                if (policy.equals("REFUND")) { refunds.upfront(payment.id(), null, "BOOKING_CANCELLED", "booking-cancel:" + payment.id()); }
                else if (policy.equals("CREDIT") && charges.forBooking(booking).isEmpty()) {
                    charges.insert(new PendingCharge(UUID.randomUUID().toString(), event.clubId(), payment.memberId(), payment.dogId(), booking, null,
                            new Money(-payment.amountPaid().amountMinor(), payment.amountPaid().currency()), payment.concept(), clock.instant(), null, null));
                }
            }
        }
    }
}
