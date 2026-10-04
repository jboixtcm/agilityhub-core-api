package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.*;
import com.agilityhub.core.shared.application.TenantContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Replays committed commands and deferred webhooks after a process failure. Provider keys and transactional checkpoints make overlap harmless. */
@Component
public class PaymentRecovery {
    @org.springframework.beans.factory.annotation.Autowired private PaymentPrivacy privacy;
    private final PaymentOperationRepository operations; private final StripeInbox inbox; private final CardPayments cards;
    private final PaymentRefunds refunds; private final StripeWebhooks webhooks;
    public PaymentRecovery(PaymentOperationRepository operations, StripeInbox inbox, CardPayments cards, PaymentRefunds refunds, StripeWebhooks webhooks) {
        this.operations = operations; this.inbox = inbox; this.cards = cards; this.refunds = refunds; this.webhooks = webhooks;
    }
    @Scheduled(fixedDelay = 10000)
    public void recover() {
        for (var operation : operations.pending()) {
            try (var tenant = TenantContext.open(operation.clubId())) {
                try { if (operation.kind().equals("CHARGE")) { cards.execute(operation.id()); } else if (operation.kind().equals("FORGET")) { privacy.execute(operation.id()); } else { refunds.execute(operation.id()); } }
                catch (RuntimeException deferred) { /* Keep the durable command for the next pass; never log provider customer data. */ }
            }
        }
        for (var event : inbox.pending()) { try (var tenant = TenantContext.open(event.clubId())) { webhooks.process(event.id()); } }
    }
}
