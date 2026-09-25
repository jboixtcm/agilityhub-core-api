package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.shared.application.LocalLanes;
import com.agilityhub.core.shared.application.TransactionRetries;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S09 R-09-06: one Mongo transaction per mutation, retried whole (at most 3 attempts, 50–150 ms randomised backoff)
 * on `DuplicateKey` (the partial unique seat index) and `WriteConflict` / `TransientTransactionError` (the
 * `trainingSeq` and ring-slot `$inc`s), never after a commit. Inside an outer transaction (S06/S07 callers, the
 * idempotency filter) the work joins it and the outer owner retries. The {@link LocalLanes} (`dog:`, `member:`,
 * `slot:` keys) only bound contention inside one API instance — a burst on one slot queues instead of
 * exhausting its retries; the unique index and Mongo's write conflicts stay authoritative across replicas.
 */
@Service
public class TrainingTransactions {
    static final int ATTEMPTS = 3;
    static final String CONTEXT = "training";
    private final TransactionTemplate transactions; private final LocalLanes lanes; private final TransactionRetries retries;
    public TrainingTransactions(TransactionTemplate transactions, LocalLanes lanes, TransactionRetries retries) {
        this.transactions = transactions; this.lanes = lanes; this.retries = retries;
    }

    public <T> T write(Collection<String> keys, Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { return work.get(); }
        return lanes.hold(keys, () -> retry(work));
    }
    private <T> T retry(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try { return transactions.execute(tx -> work.get()); }
            catch (RuntimeException failure) {
                if (!retryable(failure)) { throw failure; }
                if (attempt >= ATTEMPTS) { retries.exhausted(CONTEXT); throw new ApiException(ErrorCode.STALE_VERSION); }
                retries.retried(CONTEXT, failure);
                try { Thread.sleep(ThreadLocalRandom.current().nextLong(50, 151)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
        }
    }
    /** `DuplicateKey` (11000) of the seat index, a write conflict (112) or any error labelled `TransientTransactionError`: {@link TransactionRetries#conflict}. */
    public static boolean retryable(Throwable failure) { return TransactionRetries.conflict(failure); }
}
