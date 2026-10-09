package com.agilityhub.core.identity.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link IdentityTransactions#run} (Mongo write conflicts retry the whole unit of work): a duplicate-key
 * conflict (two concurrent `createIfAbsent` upserts) is retried, each retry first backs off, the back-off window grows without
 * collapsing, the 41st failed attempt gives up, and a non-retryable failure is never retried. The transaction manager is a mock.
 * Where the test only counts attempts, the work grants its own thread a park permit before failing, so the back-off returns at
 * once without changing the retry logic.
 */
class IdentityTransactionsSurvivorsTest {
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final IdentityTransactions transactions = new IdentityTransactions(manager);

    @BeforeEach void setUp() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        // An interrupt flag leaked by an earlier test on this thread would end `run` at its first conflict
        // (IdentityTransactions: `isInterrupted()`) and make every back-off return at once.
        Thread.interrupted();
        LockSupport.parkNanos(1); // consume any permit left by an earlier test on this thread
    }

    @AfterEach void tearDown() { LockSupport.parkNanos(1); }

    @Test void E11_T06_aDuplicateKeyConflictIsRetriedAndTheSecondRetryStillBacksOffWithinAValidWindow() {
        var attempts = new AtomicInteger();

        String result = transactions.run(() -> {
            if (attempts.incrementAndGet() <= 2) {
                LockSupport.unpark(Thread.currentThread());
                throw new DuplicateKeyException("E11000 duplicate key error collection: accounts index: account_email");
            }
            return "committed";
        });

        assertThat(result).isEqualTo("committed");
        assertThat(attempts).hasValue(3);
    }

    /**
     * Only a lower bound on the monotonic clock (`System.nanoTime`, not the wall clock): three back-offs of at least 25 ms each
     * (IdentityTransactions.java:21, `nextLong(25, …)` milliseconds) must take at least 25 ms in total. A slow or loaded host only
     * lengthens the run, so it cannot make this test fail; only three spurious early returns of `parkNanos` in a row could. No
     * permit is granted here, so each back-off parks for its full random time.
     */
    @Test void E11_T06_eachRetryWaitsBeforeRebuildingTheTransaction() {
        var attempts = new AtomicInteger();
        long started = System.nanoTime();

        transactions.run(() -> {
            if (attempts.incrementAndGet() <= 3) { throw new DuplicateKeyException("E11000 duplicate key error collection: accounts"); }
            return "committed";
        });

        assertThat(attempts).hasValue(4);
        assertThat(System.nanoTime() - started).isGreaterThanOrEqualTo(25_000_000L);
    }

    @Test void E11_T06_aConflictThatNeverClearsGivesUpAfterFortyRetries() {
        var attempts = new AtomicInteger();

        assertThatThrownBy(() -> transactions.run(() -> {
            attempts.incrementAndGet();
            LockSupport.unpark(Thread.currentThread());
            throw new DuplicateKeyException("E11000 duplicate key error collection: accounts");
        })).isInstanceOf(DuplicateKeyException.class);

        assertThat(attempts).hasValue(41);
    }

    /**
     * A failure that is neither a transient transaction error nor a duplicate key, such as MembershipService's `NO_MEMBERSHIP`
     * thrown inside its unit of work (MembershipService.java:45-47), leaves at once: the work runs exactly once. Had it been
     * retried, the second attempt here commits (a concurrent writer created the membership meanwhile), so a retry shows as a
     * missing exception rather than a hang; the work grants a park permit first, so a retry's back-off returns at once.
     */
    @Test void E11_T06_aBusinessRuleFailureIsThrownAtOnceWithoutARetry() {
        var attempts = new AtomicInteger();

        assertThatThrownBy(() -> transactions.run(() -> {
            if (attempts.incrementAndGet() == 1) {
                LockSupport.unpark(Thread.currentThread());
                throw new ApiException(ErrorCode.NO_MEMBERSHIP);
            }
            return "committed";
        })).isInstanceOf(ApiException.class).hasMessage("NO_MEMBERSHIP");

        assertThat(attempts).hasValue(1);
    }
}
