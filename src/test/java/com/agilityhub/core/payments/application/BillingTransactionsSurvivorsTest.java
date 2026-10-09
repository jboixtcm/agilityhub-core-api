package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.application.TransactionRetries;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import static org.assertj.core.api.Assertions.*;

/** E11-T06 PIT survivors of {@link BillingTransactions} (S12 R-12-11/29, T-12-10): the attempt budget, the backoff, an interrupt. */
class BillingTransactionsSurvivorsTest {
    static class NoOpManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
    private final TransactionRetries retries = new TransactionRetries(new SimpleMeterRegistry());
    private final BillingTransactions transactions = new BillingTransactions(new NoOpManager(), retries);

    @Test void T_12_10_aPermanentConflictIsTriedExactlySixTimesWithTheSharedBackoffBeforeEachRetry() {
        var pauses = new ArrayList<Long>();
        transactions.backoff(pauses::add);
        var attempts = new AtomicInteger();
        assertThatThrownBy(() -> transactions.run(() -> {
            // Fail fast instead of hanging if the budget stops counting (an endless retry is a defect, not a timeout).
            if (attempts.incrementAndGet() > BillingTransactions.ATTEMPTS) { throw new AssertionError("retried past the attempt budget"); }
            throw new DuplicateKeyException("fictional conflict");
        })).hasMessage("STALE_VERSION");
        assertThat(attempts).hasValue(BillingTransactions.ATTEMPTS);
        assertThat(retries.retries("billing")).isEqualTo(5.0);
        assertThat(pauses).hasSize(5).allSatisfy(millis -> assertThat(millis).isBetween(50L, 150L));
    }

    @Test void T_12_10_anInterruptedBackoffRethrowsTheConflictAndKeepsTheInterruptFlag() {
        var conflict = new DuplicateKeyException("fictional conflict");
        transactions.backoff(millis -> { throw new InterruptedException("fictional interrupt"); });
        var attempts = new AtomicInteger();
        try {
            assertThatThrownBy(() -> transactions.run(() -> { attempts.incrementAndGet(); throw conflict; })).isSameAs(conflict);
            assertThat(Thread.currentThread().isInterrupted()).as("the interrupt is restored").isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(attempts).hasValue(1);
    }
}
