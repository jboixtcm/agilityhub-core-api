package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.PaymentOperation;
import com.agilityhub.core.payments.persistence.PaymentOperationRepository;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/** R-12-21: failures back off and eventually leave the pending set; never log provider payloads or errors. */
@Service
public class PaymentRetryPolicy {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentRetryPolicy.class);
    private final ClubConfigService configs;
    private final PaymentOperationRepository operations;
    private final BillingTransactions tx;
    private final Clock clock;
    public PaymentRetryPolicy(ClubConfigService configs, PaymentOperationRepository operations, BillingTransactions tx, Clock clock) {
        this.configs = configs; this.operations = operations; this.tx = tx; this.clock = clock;
    }
    public int maxAttempts() { return configs.get(TenantContext.require()).get("billing.stripeMaxAttempts", Integer.class); }
    public void execute(PaymentOperation operation, Runnable action) {
        if (!operations.ready(operation.id())) { return; }
        try { action.run(); }
        catch (RuntimeException failure) {
            if (tx.run(() -> operations.failed(operation.id(), clock.instant(), maxAttempts()))) { warn("Payment operation", operation.id()); }
            throw failure;
        }
    }
    public void warn(String kind, String id) {
        String previous = MDC.get("traceId");
        try {
            if (previous == null || previous.isBlank()) { MDC.put("traceId", UUID.randomUUID().toString()); }
            LOG.warn("{} recovery exhausted: eventId={} traceId={}", kind, id, MDC.get("traceId"));
        } finally {
            if (previous == null) { MDC.remove("traceId"); } else { MDC.put("traceId", previous); }
        }
    }
}
