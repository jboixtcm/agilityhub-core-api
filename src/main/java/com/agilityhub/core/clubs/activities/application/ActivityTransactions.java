package com.agilityhub.core.clubs.activities.application;

import java.util.Collection;
import java.util.function.Supplier;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import com.agilityhub.core.shared.application.LocalLanes;
import com.agilityhub.core.shared.application.TransactionRetries;
import com.agilityhub.core.shared.domain.*;

/**
 * R-07-08: one Mongo transaction per write; the `$inc registrationSeq` on the Activity ({@code ActivityRepository.lock})
 * makes concurrent writers conflict, and a `WriteConflict` / `TransientTransactionError` is retried at most 3 times with
 * a 50–150 ms randomised backoff, then `409 STALE_VERSION`. The {@link LocalLanes} (one per activity) only bound
 * contention inside one API instance.
 */
@Service
public class ActivityTransactions {
    static final String CONTEXT = "activities";
    private final TransactionTemplate transactions; private final LocalLanes lanes; private final TransactionRetries retries;
    public ActivityTransactions(TransactionTemplate transactions, LocalLanes lanes, TransactionRetries retries) {
        this.transactions=transactions; this.lanes=lanes; this.retries=retries;
    }
    /** @param activityIds the activities the write locks (their lanes); empty for writes that touch no existing activity */
    public <T> T write(Collection<String> activityIds, Supplier<T> work) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) return work.get();
        return lanes.hold(activityIds.stream().filter(java.util.Objects::nonNull).map(id -> "activity:"+id).toList(), () -> retry(work));
    }
    private <T> T retry(Supplier<T> work) {
        for(int attempt=0;;attempt++) {
            try { return transactions.execute(tx -> work.get()); }
            catch(RuntimeException error) {
                if(!transientConflict(error)) throw error;
                if(attempt>=3) { retries.exhausted(CONTEXT); throw new ApiException(ErrorCode.STALE_VERSION); }
                retries.retried(CONTEXT,error);
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
