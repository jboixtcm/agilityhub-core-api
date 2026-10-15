package com.agilityhub.core.platform.application;

import com.agilityhub.core.shared.application.TransactionRetries;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S17 console writes that share a tenant lock with the club's own writers (R-17-05 providers, R-17-09 administrators): a Mongo
 * `WriteConflict`, `DuplicateKey` (the first upsert of a lock) or `TransientTransactionError` reruns the whole unit (audit and
 * outbox included), so the loser re-reads under the winner's commit and answers its business error. Exhausted attempts answer
 * `409 STALE_VERSION` (CONVENCIONS_API §7), never a 500.
 */
@Service
public class ConsoleTransactions {
    static final String CONTEXT = "console";
    private static final int ATTEMPTS = 20;
    private final TransactionTemplate transactions; private final TransactionRetries retries;
    public ConsoleTransactions(TransactionTemplate transactions, TransactionRetries retries) { this.transactions = transactions; this.retries = retries; }

    public <T> T write(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { return work.get(); }
        for (int attempt = 1; ; attempt++) {
            try { return transactions.execute(status -> work.get()); }
            catch (RuntimeException failure) {
                if (!TransactionRetries.conflict(failure)) { throw failure; }
                if (attempt >= ATTEMPTS) { retries.exhausted(CONTEXT); throw new ApiException(ErrorCode.STALE_VERSION); }
                retries.retried(CONTEXT, failure);
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(ThreadLocalRandom.current().nextLong(25L, Math.min(500L, 50L << Math.min(attempt, 4)))));
            }
        }
    }
}
