package com.agilityhub.core.clubs.scheduling.application;

import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.agilityhub.core.shared.application.TransactionRetries;
import com.agilityhub.core.shared.domain.*;

/**
 * Retry aborted Mongo transactions (at most 6 attempts, 50–150 ms randomised backoff like the S07/S08/S09 contexts),
 * never a committed business operation or external side effect. The backoff lets a concurrent writer of the same
 * documents (for example a training booking of the ring-slot a block covers, R-09-13) commit before the next attempt.
 * A `DuplicateKey` (11000) is a conflict too, as in S05 and S09: two transactions that upsert the same missing ring-slot
 * document may meet as one (E5-T15). Retries and exhaustions are counted under the `scheduling` context.
 */
@Service
public class SchedulingTransactions {
    static final int ATTEMPTS = 6;
    static final String CONTEXT = "scheduling";
    /** The wait before a retry, in milliseconds; injectable so a unit test does not sleep. */
    interface Backoff { void pause(long millis) throws InterruptedException; }
    private final TransactionTemplate transactions;
    private final PlanningContext context;
    private final TransactionRetries retries;
    private final java.util.function.LongSupplier jitter;
    private final Backoff backoff;
    @Autowired
    public SchedulingTransactions(TransactionTemplate transactions, PlanningContext context, TransactionRetries retries) {
        this(transactions, context, retries, SchedulingTransactions::jitter, Thread::sleep);
    }
    /** 50–150 ms, uniformly. */
    static long jitter() { return java.util.concurrent.ThreadLocalRandom.current().nextLong(50, 151); }
    SchedulingTransactions(TransactionTemplate transactions, PlanningContext context, TransactionRetries retries,
            java.util.function.LongSupplier jitter, Backoff backoff) {
        this.transactions = transactions; this.context = context; this.retries = retries; this.jitter = jitter; this.backoff = backoff;
    }
    public <T> T write(Supplier<T> action) { return write(action,ErrorCode.STALE_VERSION); }
    public <T> T write(Supplier<T> action,ErrorCode nestedConflict) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            try { context.lockReferences(); return action.get(); }
            catch (RuntimeException failure) { if(conflict(failure)) throw new ApiException(nestedConflict); throw failure; }
        }
        for (int attempt = 1; ; attempt++) {
            try { return transactions.execute(tx -> { context.lockReferences(); return action.get(); }); }
            catch (RuntimeException failure) {
                if (!conflict(failure)) { throw failure; }
                if (attempt >= ATTEMPTS) { retries.exhausted(CONTEXT); throw new ApiException(ErrorCode.STALE_VERSION); }
                retries.retried(CONTEXT, failure);
                try { backoff.pause(jitter.getAsLong()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
        }
    }
    /** A write conflict (112), a duplicate key (11000) or any error labelled `TransientTransactionError`. */
    static boolean conflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.dao.DuplicateKeyException) { return true; }
            if (cause instanceof com.mongodb.MongoException mongo
                    && (mongo.getCode() == 112 || mongo.getCode() == 11000 || mongo.hasErrorLabel("TransientTransactionError"))) { return true; }
        }
        return false;
    }
}
