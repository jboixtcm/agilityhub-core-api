package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.application.IdempotentOperation;
import com.agilityhub.core.shared.application.TransactionRetries;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S12 R-12-11/14/29 (E8-T02, modelled on `SchedulingTransactions`): every billing write — the run, the rollback, each invoice
 * state action, a manual invoice — runs in one Mongo transaction with its outbox events and audit entry, run again whole after
 * a write conflict (two writers of one invoice or of the club's receipt counter): at most {@value #ATTEMPTS} attempts with the
 * shared 50–150 ms backoff, counted under `core.transactions.retries{context=billing}`; then `409 STALE_VERSION`. The keyed
 * routes (`IdempotencyFilter.BILLING`) lock their `Idempotency-Key` row first and store the answer last, in the attempt that
 * commits, so a repeat returns the stored response (CONVENCIONS_API §7). Inside an outer transaction the work joins it.
 */
@Component
public class BillingTransactions {
    static final String CONTEXT = "billing";
    static final int ATTEMPTS = 6;
    private final TransactionTemplate transactions; private final TransactionRetries retries;
    private TransactionRetries.Backoff backoff = Thread::sleep;
    public BillingTransactions(PlatformTransactionManager manager, TransactionRetries retries) {
        this.transactions = new TransactionTemplate(manager); this.retries = retries;
    }
    void backoff(TransactionRetries.Backoff backoff) { this.backoff = backoff; }

    public <T> T run(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { return work.get(); }
        for (int attempt = 1; ; attempt++) {
            try { return transactions.execute(status -> work.get()); }
            catch (RuntimeException failure) {
                if (!TransactionRetries.conflict(failure)) { throw failure; }
                if (attempt >= ATTEMPTS) { retries.exhausted(CONTEXT); throw new ApiException(ErrorCode.STALE_VERSION); }
                retries.retried(CONTEXT, failure);
                try { backoff.pause(TransactionRetries.jitter()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw failure; }
            }
        }
    }
    /** A keyed write: the key's row is locked first, and {@code status} with the answer's bytes stored in the committing attempt. */
    public <T> T keyed(int status, Supplier<T> work, Function<T, byte[]> answer) {
        return run(() -> {
            IdempotentOperation.lock();
            T result = work.get();
            IdempotentOperation.complete(status, answer.apply(result));
            return result;
        });
    }
}
