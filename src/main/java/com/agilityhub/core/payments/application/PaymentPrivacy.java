package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.application.ports.PaymentPrivacyPort;
import com.agilityhub.core.payments.persistence.*;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PaymentPrivacy implements PaymentPrivacyPort {
    private final PaymentOperationRepository operations; private final PaymentProviderRegistry provider; private final BillingTransactions tx; private final Clock clock;
    public PaymentPrivacy(PaymentOperationRepository operations, PaymentProviderRegistry provider, BillingTransactions tx, Clock clock) {
        this.operations = operations; this.provider = provider; this.tx = tx; this.clock = clock;
    }
    public void forgetCustomer(String customerId) {
        if (customerId == null || !provider.supports(PaymentProvider.Capability.FORGET_CUSTOMER)) { return; }
        tx.run(() -> {
            String key = "forget:" + customerId;
            if (operations.byKey(key).isEmpty()) { operations.insert(new PaymentOperation(UUID.randomUUID().toString(), TenantContext.require(), "FORGET", customerId,
                    customerId, null, key, null, null, null, clock.instant(), null)); }
            return null;
        });
    }
    public void execute(String id) {
        var op = operations.findById(id).orElseThrow(); if (op.resultId() != null) { return; }
        provider.forgetCustomer(op.providerRef()); tx.run(() -> { operations.completed(id, op.providerRef()); return null; });
    }
}
