package com.agilityhub.core.payments.application;

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
 * S12 R-12-11/29 (E8-T02, `SchedulingTransactions`' pattern): a billing write runs in one transaction run again whole after a
 * write conflict (two runs or a run and a manual invoice on the club's receipt counter, two actions on one invoice), any other
 * failure is not retried, exhausted retries answer `409 STALE_VERSION`; a keyed write locks its key first and stores the
 * answer of the attempt that commits.
 */
class BillingTransactionsTest {
    static class NoOpManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
    private final TransactionRetries retries = new TransactionRetries(new SimpleMeterRegistry());
    private final BillingTransactions transactions = new BillingTransactions(new NoOpManager(), retries);
    { transactions.backoff(millis -> { }); }

    @Test void T_12_10_aConflictIsRetriedWholeOtherFailuresAreNotAndExhaustedRetriesAreStaleVersion() {
        var attempts = new AtomicInteger();
        assertThat(transactions.run(() -> { if (attempts.incrementAndGet() < 3) { throw new DuplicateKeyException("fictional conflict"); } return "run"; })).isEqualTo("run");
        assertThat(attempts).hasValue(3); assertThat(retries.retries("billing")).isEqualTo(2);
        var once = new AtomicInteger();
        assertThatThrownBy(() -> transactions.run(() -> { once.incrementAndGet(); throw new IllegalStateException("fictional failure"); })).hasMessage("fictional failure");
        assertThat(once).hasValue(1);
        // E11-T06: an endless retry fails here with an assertion instead of hanging.
        var always = new AtomicInteger();
        assertThatThrownBy(() -> transactions.run(() -> {
            if (always.incrementAndGet() > BillingTransactions.ATTEMPTS) { throw new AssertionError("retried past the attempt budget"); }
            throw new DuplicateKeyException("always");
        })).hasMessage("STALE_VERSION");
        assertThat(retries.exhaustions("billing")).isEqualTo(1);
    }

    @Test void T_12_10_aKeyedWriteLocksItsKeyAndStoresTheAnswerOfTheAttemptThatCommits() {
        var calls = new ArrayList<String>();
        try (var scope = IdempotentOperation.open(() -> calls.add("lock"), (status, body) -> calls.add(status + " [" + new String(body) + "]"))) {
            var attempts = new AtomicInteger();
            assertThat(transactions.keyed(201, () -> {
                calls.add("work " + attempts.incrementAndGet());
                if (attempts.get() < 2) { throw new DuplicateKeyException("fictional conflict"); }
                return "run";
            }, String::getBytes)).isEqualTo("run");
        }
        assertThat(calls).containsExactly("lock", "work 1", "lock", "work 2", "201 [run]");
    }

    @Test void insideAnotherTransactionTheWorkRunsOnceAsIs() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            var attempts = new AtomicInteger();
            assertThatThrownBy(() -> transactions.run(() -> { attempts.incrementAndGet(); throw new DuplicateKeyException("conflict"); })).isInstanceOf(DuplicateKeyException.class);
            assertThat(attempts).hasValue(1);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
}
