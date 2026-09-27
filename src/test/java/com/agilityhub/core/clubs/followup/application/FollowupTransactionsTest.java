package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.shared.application.TransactionRetries;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

/**
 * T-10-25 (R-10-10): the non-keyed follow-up writes (edit, completion, reopening) run in one transaction retried whole
 * on a write conflict, so the loser of two simultaneous completions re-reads the task and answers TASK_ALREADY_DONE;
 * any other failure is not retried, and exhausted retries answer 409 STALE_VERSION.
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

    @Test void T_10_25_insideAnotherTransactionTheWorkRunsOnceAsIs() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            var attempts = new AtomicInteger();
            assertThatThrownBy(() -> transactions.run(() -> { attempts.incrementAndGet(); throw new DuplicateKeyException("conflict"); })).isInstanceOf(DuplicateKeyException.class);
            assertThat(attempts).hasValue(1);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
}
