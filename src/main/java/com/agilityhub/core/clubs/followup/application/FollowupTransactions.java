package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.shared.application.IdempotentOperation;
import com.agilityhub.core.shared.application.TransactionRetries;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Each write of the follow-up routes (tasks, attachment registrations and removals, read marks, observations) runs in one Mongo transaction retried
 * whole on a write conflict: two simultaneous completions give
 * one 200 and one TASK_ALREADY_DONE (T-10-25), two edits of one version one 200 and one STALE_VERSION. The text edit is not
 * keyed (its `version` is its idempotency, S10 §6). The keyed writes ({@link #keyed}) are left to this transaction by the
 * idempotency filter (`IdempotencyFilter.FOLLOWUP`, E6-T03 rounds 4 and 5, INC-47), with their idempotency row inside it.
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

    /**
     * A keyed write (E6-T03 round 5): the `Idempotency-Key`'s row is locked first and stores `status` and the answer's bytes
     * last, in the attempt that commits. Two requests with different keys that meet on one document: the loser's attempt
     * runs again, re-reads, and answers the spec's code for the conflict (or succeeds), never a 500. Without a key, the
     * row calls do nothing.
     */
    public <T> T keyed(int status, Supplier<T> work, Function<T, byte[]> answer) {
        return run(() -> {
            IdempotentOperation.lock();
            T result = work.get();
            IdempotentOperation.complete(status, answer.apply(result));
            return result;
        });
    }
    /** A keyed write answered `204` (no body). */
    public void keyedNoContent(Runnable work) { keyed(204, () -> { work.run(); return null; }, none -> new byte[0]); }
}
