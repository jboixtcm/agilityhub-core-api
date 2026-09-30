package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.shared.application.IdempotentOperation;
import com.agilityhub.core.shared.application.TransactionRetries;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

/**
 * T-10-25 (R-10-10): the follow-up writes run in one transaction retried whole on a write conflict, so the loser of two
 * simultaneous completions re-reads the task and answers TASK_ALREADY_DONE; any other failure is not retried, and
 * exhausted retries answer 409 STALE_VERSION. A keyed write keeps its idempotency row in that transaction (round 5).
 */
class FollowupTransactionsTest {
    /** A transaction manager without a database: begin, commit and rollback do nothing. */
    static class NoOpManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
    private final TransactionRetries retries = new TransactionRetries(new SimpleMeterRegistry());
    private final FollowupTransactions transactions = new FollowupTransactions(new NoOpManager(), retries);
    { transactions.backoff(millis -> { }); }

    @Test void T_10_25_aConflictIsRetriedWholeAndOtherFailuresAreNot() {
        var attempts = new AtomicInteger();
        assertThat(transactions.run(() -> { if (attempts.incrementAndGet() < 3) { throw new DuplicateKeyException("fictional conflict"); } return "done"; })).isEqualTo("done");
        assertThat(attempts).hasValue(3); assertThat(retries.retries("followup")).isEqualTo(2);
        var once = new AtomicInteger();
        assertThatThrownBy(() -> transactions.run(() -> { once.incrementAndGet(); throw new IllegalStateException("fictional failure"); })).hasMessage("fictional failure");
        assertThat(once).hasValue(1);
        assertThatThrownBy(() -> transactions.run(() -> { throw new DuplicateKeyException("always"); })).hasMessage("STALE_VERSION");
        assertThat(retries.exhaustions("followup")).isEqualTo(1);
    }

    /**
     * E6-T03 round 5 (INC-47): a keyed write locks its key's row first and stores the answer of the attempt that commits, in
     * that attempt; a conflict runs the lock and the work again. Without a key (no operation open) the work runs alone.
     */
    @Test void T_10_25_aKeyedWriteLocksItsKeyAndStoresTheAnswerOfTheAttemptThatCommits() {
        var calls = new ArrayList<String>();
        try (var scope = IdempotentOperation.open(() -> calls.add("lock"), (status, body) -> calls.add(status + " [" + new String(body) + "]"))) {
            var attempts = new AtomicInteger();
            assertThat(transactions.keyed(200, () -> {
                calls.add("work " + attempts.incrementAndGet());
                if (attempts.get() < 2) { throw new DuplicateKeyException("fictional conflict"); }
                return "done";
            }, String::getBytes)).isEqualTo("done");
            transactions.keyedNoContent(() -> calls.add("read"));
        }
        assertThat(calls).containsExactly("lock", "work 1", "lock", "work 2", "200 [done]", "lock", "read", "204 []");
        calls.clear();
        assertThat(transactions.keyed(201, () -> { calls.add("alone"); return "created"; }, String::getBytes)).isEqualTo("created");
        assertThat(calls).containsExactly("alone");
    }

    @Test void T_10_25_insideAnotherTransactionTheWorkRunsOnceAsIs() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            var attempts = new AtomicInteger();
            assertThatThrownBy(() -> transactions.run(() -> { attempts.incrementAndGet(); throw new DuplicateKeyException("conflict"); })).isInstanceOf(DuplicateKeyException.class);
            assertThat(attempts).hasValue(1);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
}
