package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S09 R-09-06: one Mongo transaction per mutation, retried whole (at most 3 attempts, 50–150 ms randomised backoff)
 * on `DuplicateKey` (the partial unique seat index) and `WriteConflict` / `TransientTransactionError` (the
 * `trainingSeq` `$inc`), never after a commit. Inside an outer transaction (S06/S07 callers, the idempotency filter)
 * the work joins it and the outer owner retries. The in-process lanes (fair locks, one per key hash, taken in lane
 * order) only bound contention inside one JVM — a burst on one slot queues instead of exhausting its retries; the
 * unique index and Mongo's write conflicts stay authoritative across replicas.
 */
@Service
public class TrainingTransactions {
    static final int ATTEMPTS = 3;
    private final TransactionTemplate transactions;
    private final ReentrantLock[] lanes = java.util.stream.IntStream.range(0, 256).mapToObj(i -> new ReentrantLock(true)).toArray(ReentrantLock[]::new);
    public TrainingTransactions(TransactionTemplate transactions) { this.transactions = transactions; }

    public <T> T write(Collection<String> keys, Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { return work.get(); }
        var taken = new TreeSet<Integer>();
        for (String key : keys) { if (key != null) { taken.add(Math.floorMod((TenantContext.require() + ":" + key).hashCode(), lanes.length)); } }
        var held = new ArrayList<ReentrantLock>();
        try {
            for (int lane : taken) { lanes[lane].lock(); held.add(lanes[lane]); }
            return retry(work);
        } finally { for (int i = held.size() - 1; i >= 0; i--) { held.get(i).unlock(); } }
    }
    private <T> T retry(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try { return transactions.execute(tx -> work.get()); }
            catch (RuntimeException failure) {
                if (!retryable(failure)) { throw failure; }
                if (attempt >= ATTEMPTS) { throw new ApiException(ErrorCode.STALE_VERSION); }
                try { Thread.sleep(ThreadLocalRandom.current().nextLong(50, 151)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
        }
    }
    /** `DuplicateKey` (11000) of the seat index, a write conflict (112) or any error labelled `TransientTransactionError`. */
    public static boolean retryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.dao.DuplicateKeyException) { return true; }
            if (cause instanceof com.mongodb.MongoException mongo
                    && (mongo.getCode() == 112 || mongo.getCode() == 11000 || mongo.hasErrorLabel("TransientTransactionError"))) { return true; }
        }
        return false;
    }
}
