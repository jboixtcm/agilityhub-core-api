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
 * and one backoff range (`CatalogService`, `SchedulingTransactions`, `TrainingTransactions`).
 */
class TransactionRetriesTest {
    private static com.mongodb.MongoException mongo(int code, String label) {
        var failure = new com.mongodb.MongoException(code, "fictional failure"); if (label != null) { failure.addLabel(label); }
        return failure;
    }

    @Test void E5_T17_oneConflictCheckForTheRetriedWriters() {
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

    @Test void E5_T17_theSharedBackoffDrawsFiftyToOneHundredFiftyMilliseconds() {
        var drawn = new HashSet<Long>(); for (int i = 0; i < 2000; i++) { drawn.add(TransactionRetries.jitter()); }
        assertThat(drawn).allSatisfy(wait -> assertThat(wait).isBetween(50L, 150L)).contains(50L, 150L);
    }
}
