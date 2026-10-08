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
        execute(operation, action, () -> {});
    }
    public final class Execution {
        private final String id; private final String token;
        private Execution(String id, String token) { this.id = id; this.token = token; }
        /** Call inside every local transaction after a network response, before changing business state. */
        public void fence() {
            if (!operations.fence(id, token)) {
                throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.STALE_VERSION);
            }
        }
    }
    public void execute(PaymentOperation operation, java.util.function.Consumer<Execution> action) {
        execute(operation, action, () -> {});
    }
    /** The terminal business transition commits atomically with the exhausted command. */
    public void execute(PaymentOperation operation, Runnable action, Runnable exhausted) {
        execute(operation, ignored -> action.run(), exhausted);
    }
    public void execute(PaymentOperation operation, java.util.function.Consumer<Execution> action, Runnable exhausted) {
        boolean money = operation.kind().equals("CHARGE") || operation.kind().startsWith("REFUND");
        // Claim and uncertainty commit together before the network call; competing workers cannot clear either.
        var claim = tx.run(() -> operations.claim(operation.id(), money));
        if (claim == null) { return; }
        try { action.accept(new Execution(operation.id(), claim.token())); }
        catch (RuntimeException failure) {
            if (tx.run(() -> {
                if (!operations.fence(operation.id(), claim.token())) { return false; }
                if (money && !claim.previouslyUncertain() && failure instanceof PaymentNotSubmitted) {
                    operations.submissionUncertain(operation.id(), false);
                }
                boolean terminal = operations.failed(operation.id(), clock.instant(), maxAttempts());
                if (terminal) { exhausted.run(); }
                return terminal;
            })) { warn("Payment operation", operation.id()); }
            throw failure;
        } finally {
            tx.run(() -> { operations.release(operation.id(), claim.token()); return null; });
        }
    }
    public void warnRefund(String id, String status) { warning("Refund reconciliation status=" + status, id); }
    public void warn(String kind, String id) {
        warning(kind + " recovery exhausted", id);
    }
    private void warning(String message, String id) {
        String previous = MDC.get("traceId");
        try {
            if (previous == null || previous.isBlank()) { MDC.put("traceId", UUID.randomUUID().toString()); }
            LOG.warn("{}: eventId={} traceId={}", message, id, MDC.get("traceId"));
        } finally {
            if (previous == null) { MDC.remove("traceId"); } else { MDC.put("traceId", previous); }
        }
    }
}
