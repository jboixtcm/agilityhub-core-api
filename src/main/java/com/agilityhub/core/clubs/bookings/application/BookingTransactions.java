package com.agilityhub.core.clubs.bookings.application;

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
 * S08 §6 booking transactions: one Mongo transaction per operation, retried on `WriteConflict` /
 * `TransientTransactionError` (at most 3 attempts, 50–150 ms randomised backoff), never after a commit.
 *
 * <p><b>Lock order</b>: `seat_locks` (the `$inc` on each class touched, R-08-07) → `class_sessions` (counters) →
 * `bookings` / `seat_holds`. A swap touches two classes; both seat locks are taken new class first. The in-process
 * lanes (fair locks, one per class hash, taken in lane order) only bound contention inside one JVM so a burst on the
 * last seat queues instead of exhausting its retries; Mongo's write conflicts stay authoritative across replicas.
 * `cancelAllByClub` runs inside the S06 transaction that already holds the class and therefore takes no seat lock:
 * a concurrent booking of that class still conflicts on `class_sessions` and, retried, finds the class CANCELLED.
 * No external call runs inside these transactions: the PAY_TO_BOOK provider checkout (R-08-18) opens after the
 * commit ({@link BookingConfirmationService#openCheckout}).
 */
@Service
public class BookingTransactions {
    static final int ATTEMPTS = 3;
    private final TransactionTemplate transactions;
    private final ReentrantLock[] lanes = java.util.stream.IntStream.range(0, 256).mapToObj(i -> new ReentrantLock(true)).toArray(ReentrantLock[]::new);
    public BookingTransactions(TransactionTemplate transactions) { this.transactions = transactions; }

    public <T> T write(Collection<String> classIds, Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { return work.get(); }
        var taken = new TreeSet<Integer>();
        for (String id : classIds) { if (id != null) { taken.add(Math.floorMod((TenantContext.require() + ":" + id).hashCode(), lanes.length)); } }
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
                if (!transientConflict(failure)) { throw failure; }
                if (attempt >= ATTEMPTS) { throw new ApiException(ErrorCode.STALE_VERSION); }
                try { Thread.sleep(ThreadLocalRandom.current().nextLong(50, 151)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
        }
    }
    public static boolean transientConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.mongodb.MongoException mongo && (mongo.getCode() == 112 || mongo.hasErrorLabel("TransientTransactionError"))) { return true; }
        }
        return false;
    }
}
