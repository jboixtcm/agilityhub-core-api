package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/**
 * E5-T17 (review E5-T15 #4): the S05 ring change, the S06 writers and the S09 training writers share one conflict check
 * (`CatalogService`, `SchedulingTransactions`, `TrainingTransactions`). E5-T21 (review E5-T18 #1): every S05–S09 writer
 * draws its wait from one 50–150 ms range, {@link TransactionRetries#jitter()}: those three, and the S07 and S08 writers
 * (`ActivityTransactions`, R-07-08; `BookingTransactions`, R-08-07). E5-T23 (review E5-T21 #3): the S01 and S04 writers
 * (`IdentityTransactions`, `SignupTransactions`) keep their own growing backoff by design: they retry many more times.
 * Each test is named after the rules it asserts: the conflicts S09 R-09-06 retries, and the backoff of R-07-08 and R-08-07.
 */
class TransactionRetriesTest {
    private static com.mongodb.MongoException mongo(int code, String label) {
        var failure = new com.mongodb.MongoException(code, "fictional failure"); if (label != null) { failure.addLabel(label); }
        return failure;
    }

    @Test void R_09_06_oneConflictCheckRetriesAWriteConflictADuplicateKeyOrATransientError() {
        assertThat(TransactionRetries.conflict(new DuplicateKeyException("E11000 ring_slot_locks"))).isTrue();
        assertThat(TransactionRetries.conflict(new UncategorizedMongoDbException("wrapped", mongo(112, null)))).as("a wrapped write conflict").isTrue();
        assertThat(TransactionRetries.conflict(mongo(11000, null))).isTrue();
        assertThat(TransactionRetries.conflict(mongo(251, "TransientTransactionError"))).isTrue();
        assertThat(TransactionRetries.conflict(mongo(2, null))).isFalse();
        assertThat(TransactionRetries.conflict(new ApiException(ErrorCode.RING_HAS_BOOKINGS))).isFalse();
        assertThat(TransactionRetries.conflict(new IllegalStateException("not Mongo"))).isFalse();
        // `transientFailure` (used by `SignupTransactions`) is unchanged: it does not treat a duplicate key as retryable.
        assertThat(TransactionRetries.transientFailure(mongo(11000, null))).isFalse();
    }

    @Test void R_07_08_R_08_07_theSharedBackoffDrawsFiftyToOneHundredFiftyMilliseconds() {
        var drawn = new HashSet<Long>(); for (int i = 0; i < 2000; i++) { drawn.add(TransactionRetries.jitter()); }
        assertThat(drawn).allSatisfy(wait -> assertThat(wait).isBetween(50L, 150L)).contains(50L, 150L);
    }

    /** A transaction template over a manager that does nothing: the unit runs, its exception rolls nothing back. */
    private static TransactionTemplate transactions() { return new TransactionTemplate(org.mockito.Mockito.mock(PlatformTransactionManager.class)); }

    /**
     * E7-T04 round 3 (S11 T-11-31): the outbox's consumer transaction runs again after a conflict, waiting the shared backoff
     * before each retry, counted under its context; a unit that ends well after two conflicts is no exhaustion.
     */
    @Test void T_11_31_aUnitThatMeetsConflictsRunsAgainAfterTheSharedBackoff() {
        var registry = new SimpleMeterRegistry(); var retries = new TransactionRetries(registry);
        var runs = new AtomicInteger(); var pauses = new ArrayList<Long>();
        retries.inTransaction("outbox", 5, transactions(), status -> {
            if (runs.incrementAndGet() <= 2) { throw new UncategorizedMongoDbException("wrapped", mongo(112, "TransientTransactionError")); }
        }, pauses::add);
        assertThat(runs).hasValue(3);
        assertThat(pauses).hasSize(2).allSatisfy(wait -> assertThat(wait).isBetween(50L, 150L));
        assertThat(retries.retries("outbox", "write_conflict")).isEqualTo(2);
        assertThat(retries.exhaustions("outbox")).isZero();
    }

    /** …the last conflict is rethrown once the attempts run out (counted as exhausted); any other failure at once, never retried. */
    @Test void T_11_31_theLastConflictIsRethrownAndAnyOtherFailureIsNeverRetried() {
        var retries = new TransactionRetries(new SimpleMeterRegistry());
        var runs = new AtomicInteger();
        var conflict = new DuplicateKeyException("E11000 notification_dedup");
        assertThatThrownBy(() -> retries.inTransaction("outbox", 3, transactions(), status -> { runs.incrementAndGet(); throw conflict; }, wait -> { }))
                .isSameAs(conflict);
        assertThat(runs).hasValue(3);
        assertThat(retries.retries("outbox", "duplicate_key")).isEqualTo(2);
        assertThat(retries.exhaustions("outbox")).isEqualTo(1);
        runs.set(0);
        var other = new IllegalStateException("handler failure");
        assertThatThrownBy(() -> retries.inTransaction("outbox", 3, transactions(), status -> { runs.incrementAndGet(); throw other; }, wait -> { })).isSameAs(other);
        assertThat(runs).hasValue(1);
        assertThat(retries.retries("outbox")).isEqualTo(2);
    }

    /** …and inside an outer transaction the unit joins it and runs once: the outer owner retries the whole transaction. */
    @Test void T_11_31_insideAnOuterTransactionTheUnitRunsOnce() {
        var retries = new TransactionRetries(new SimpleMeterRegistry());
        var runs = new AtomicInteger();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> retries.inTransaction("outbox", 5, transactions(), status -> {
                runs.incrementAndGet(); throw new UncategorizedMongoDbException("wrapped", mongo(112, null));
            }, wait -> { })).isInstanceOf(UncategorizedMongoDbException.class);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        assertThat(runs).hasValue(1);
        assertThat(retries.retries("outbox")).isZero();
        assertThat(retries.exhaustions("outbox")).isZero();
    }
}
