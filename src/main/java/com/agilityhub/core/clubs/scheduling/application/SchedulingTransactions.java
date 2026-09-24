package com.agilityhub.core.clubs.scheduling.application;

import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.agilityhub.core.shared.domain.*;

/**
 * Retry aborted Mongo transactions (at most 6 attempts, 50–150 ms randomised backoff like the S07/S08/S09 contexts),
 * never a committed business operation or external side effect. The backoff lets a concurrent writer of the same
 * documents (for example a training booking of the ring-slot a block covers, R-09-13) commit before the next attempt.
 */
@Service
public class SchedulingTransactions {
    private final TransactionTemplate transactions;
    private final PlanningContext context;
    public SchedulingTransactions(TransactionTemplate transactions, PlanningContext context) { this.transactions = transactions; this.context = context; }
    public <T> T write(Supplier<T> action) { return write(action,ErrorCode.STALE_VERSION); }
    public <T> T write(Supplier<T> action,ErrorCode nestedConflict) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            try { context.lockReferences(); return action.get(); }
            catch (RuntimeException failure) { if(conflict(failure)) throw new ApiException(nestedConflict); throw failure; }
        }
        for (int attempt = 0; ; attempt++) {
            try { return transactions.execute(tx -> { context.lockReferences(); return action.get(); }); }
            catch (RuntimeException failure) {
                if (!conflict(failure)) { throw failure; }
                if (attempt >= 5) { throw new ApiException(ErrorCode.STALE_VERSION); }
                try { Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(50, 151)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
        }
    }
    private boolean conflict(RuntimeException failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) if(cause instanceof com.mongodb.MongoException mongo && (mongo.getCode()==112 || mongo.hasErrorLabel("TransientTransactionError"))) return true;
        return false;
    }
}
