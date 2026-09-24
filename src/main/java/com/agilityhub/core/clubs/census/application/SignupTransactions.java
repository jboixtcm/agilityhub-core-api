package com.agilityhub.core.clubs.census.application;

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
 * R-04-27 (E3-T09): one Mongo transaction per signup submission. Every submission `$inc`s the club's census lock, so two
 * concurrent ones conflict; a `WriteConflict` / `TransientTransactionError` runs the whole submission again (it then sees
 * the winner's member: `422 SIGNUP_ALREADY_PENDING`), with a randomised backoff that lets the winner commit. Exhausted
 * attempts answer `409 STALE_VERSION`, never a 500. The Idempotency-Key row commits in the same transaction
 * ({@code IdempotentOperation}).
 */
@Service
public class SignupTransactions {
    static final String CONTEXT = "signup";
    private static final int ATTEMPTS = 20;
    private final TransactionTemplate transactions; private final TransactionRetries retries;
    public SignupTransactions(TransactionTemplate transactions, TransactionRetries retries) { this.transactions = transactions; this.retries = retries; }

    public <T> T write(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { return work.get(); }
        for (int attempt = 1; ; attempt++) {
            try { return transactions.execute(status -> work.get()); }
            catch (RuntimeException failure) {
                if (!TransactionRetries.transientFailure(failure)) { throw failure; }
                if (attempt >= ATTEMPTS) { retries.exhausted(CONTEXT); throw new ApiException(ErrorCode.STALE_VERSION); }
                retries.retried(CONTEXT, failure);
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(ThreadLocalRandom.current().nextLong(25L, Math.min(500L, 50L << Math.min(attempt, 4)))));
            }
        }
    }
}
