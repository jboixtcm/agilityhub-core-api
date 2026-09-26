package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import static org.assertj.core.api.Assertions.*;

/**
 * E5-T17 (review E5-T15 #4): the S05 ring change, the S06 writers and the S09 training writers share one conflict check
 * (`CatalogService`, `SchedulingTransactions`, `TrainingTransactions`). E5-T21 (review E5-T18 #1): every retried writer
 * draws its wait from one 50–150 ms range, {@link TransactionRetries#jitter()}: those three, and the S07 and S08 writers
 * (`ActivityTransactions`, R-07-08; `BookingTransactions`, R-08-07). Each test is named after the rules it asserts: the
 * conflicts S09 R-09-06 retries, and the backoff of R-07-08 and R-08-07.
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
}
