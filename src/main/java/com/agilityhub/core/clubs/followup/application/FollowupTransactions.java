package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.shared.application.TransactionRetries;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The follow-up writes without an `Idempotency-Key` (the text edit, the completion and the reopening) run in one Mongo
 * transaction retried whole on a write conflict: two simultaneous completions give one 200 and one TASK_ALREADY_DONE
 * (T-10-25). A keyed route already runs inside the idempotency filter's transaction and is not retried here.
 */
@Component
public class FollowupTransactions {
    static final String CONTEXT = "followup";
    static final int ATTEMPTS = 10;
    private final TransactionTemplate transactions; private final TransactionRetries retries;
    private TransactionRetries.Backoff backoff = Thread::sleep;
    public FollowupTransactions(PlatformTransactionManager manager, TransactionRetries retries) { this.transactions = new TransactionTemplate(manager); this.retries = retries; }
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
}
