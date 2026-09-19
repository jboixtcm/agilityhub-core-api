package com.agilityhub.core.clubs.activities.application;

import java.util.function.Supplier;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import com.agilityhub.core.shared.domain.*;

@Service
public class ActivityTransactions {
    private final TransactionTemplate transactions;
    // Bound local contention before opening Mongo snapshots; Mongo locks remain authoritative across replicas.
    private final java.util.concurrent.locks.ReentrantLock[] lanes=java.util.stream.IntStream.range(0,256)
            .mapToObj(i -> new java.util.concurrent.locks.ReentrantLock(true)).toArray(java.util.concurrent.locks.ReentrantLock[]::new);
    public ActivityTransactions(TransactionTemplate transactions) { this.transactions=transactions; }
    public <T> T write(Supplier<T> work) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) return work.get();
        var lane=lanes[Math.floorMod(com.agilityhub.core.shared.application.TenantContext.require().hashCode(),lanes.length)];
        lane.lock();
        try { return retry(work); } finally { lane.unlock(); }
    }
    private <T> T retry(Supplier<T> work) {
        for(int attempt=0;;attempt++) {
            try { return transactions.execute(tx -> work.get()); }
            catch(RuntimeException error) {
                if(!transientConflict(error)) throw error;
                if(attempt>=3) throw new ApiException(ErrorCode.STALE_VERSION);
                try { Thread.sleep(ThreadLocalRandom.current().nextLong(50,151)); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
        }
    }
    public static boolean transientConflict(Throwable error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) if(cause instanceof com.mongodb.MongoException mongo && (mongo.getCode()==112 || mongo.hasErrorLabel("TransientTransactionError"))) return true;
        return false;
    }
}
