package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.domain.CatalogKind;
import com.agilityhub.core.clubs.catalogs.persistence.CatalogRepository;
import com.agilityhub.core.shared.application.TransactionRetries;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * E5-T15 (review E5-T07 #1): a ring change (S05 R-05-08 → S09 R-09-13) is retried whole on a Mongo conflict, at most
 * {@link CatalogService#RING_CHANGE_ATTEMPTS} attempts (E26), each attempt through the proxy (its own transaction and audit).
 */
class CatalogServiceRetryTest {
    private final TransactionRetries retries = new TransactionRetries(new SimpleMeterRegistry());
    private final CatalogService attempt = mock(CatalogService.class);
    /** E5-T17 (review E5-T15 #4): the backoff is injected, so these tests record the waits instead of sleeping. */
    private final List<Long> waits = Collections.synchronizedList(new ArrayList<>());

    @SuppressWarnings("unchecked")
    private CatalogService service() {
        ObjectProvider<CatalogService> self = mock(ObjectProvider.class); when(self.getObject()).thenReturn(attempt);
        return new CatalogService(mock(CatalogRepository.class), mock(CatalogRepository.class), mock(CatalogRepository.class), null, null, null, null, null, null,
                null, self, retries, TransactionRetries::jitter, waits::add);
    }
    private static RuntimeException writeConflict() {
        return new UncategorizedMongoDbException("Write conflict", new com.mongodb.MongoException(112, "WriteConflict"));
    }

    @Test void E5_T15_aRingChangeMetByAConflictIsRetriedAndThenCommits() {
        when(attempt.change(any(), anyString(), anyMap())).thenThrow(writeConflict()).thenThrow(new DuplicateKeyException("E11000 ring_slot_locks"))
                .thenReturn(Map.of("id", "ring"));
        assertThat(service().update(CatalogKind.RING, "ring", Map.of("version", 0, "active", false))).containsEntry("id", "ring");
        verify(attempt, times(3)).change(any(), anyString(), anyMap());
        assertThat(retries.retries(CatalogService.CONTEXT, "write_conflict")).isEqualTo(1);
        assertThat(retries.retries(CatalogService.CONTEXT, "duplicate_key")).isEqualTo(1);
        assertThat(waits).hasSize(2).allSatisfy(wait -> assertThat(wait).isBetween(50L, 150L));
    }

    @Test void E5_T15_aRingChangeThatKeepsConflictingEndsInStaleVersionAfterItsBudget() {
        var transientError = new com.mongodb.MongoException(251, "NoSuchTransaction"); transientError.addLabel("TransientTransactionError");
        when(attempt.change(any(), anyString(), anyMap())).thenThrow(new UncategorizedMongoDbException("aborted", transientError));
        assertThatThrownBy(() -> service().update(CatalogKind.RING, "ring", Map.of("version", 0)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.STALE_VERSION));
        verify(attempt, times(CatalogService.RING_CHANGE_ATTEMPTS)).change(any(), anyString(), anyMap());
        assertThat(retries.exhaustions(CatalogService.CONTEXT)).isEqualTo(1);
        assertThat(waits).as("a wait before each retry, none after the last attempt").hasSize(CatalogService.RING_CHANGE_ATTEMPTS - 1);
    }

    /**
     * E5-T17 (review E5-T15 #4): an interrupt during the wait stops the retries and keeps the interrupt flag. E5-T18 (review
     * E5-T17 #1): named after the rule of the retried ring change (R-05-08, which follows S09 R-09-13).
     */
    @SuppressWarnings("unchecked")
    @Test void R_05_08_aRingChangeInterruptedDuringItsBackoffStopsRetrying() {
        when(attempt.change(any(), anyString(), anyMap())).thenThrow(writeConflict());
        ObjectProvider<CatalogService> self = mock(ObjectProvider.class); when(self.getObject()).thenReturn(attempt);
        var interrupted = new CatalogService(mock(CatalogRepository.class), mock(CatalogRepository.class), mock(CatalogRepository.class), null, null, null, null, null,
                null, null, self, retries, () -> 100L, millis -> { throw new InterruptedException(); });
        try {
            assertThatThrownBy(() -> interrupted.update(CatalogKind.RING, "ring", Map.of("version", 0))).isInstanceOf(IllegalStateException.class);
            verify(attempt, times(1)).change(any(), anyString(), anyMap());
        } finally { assertThat(Thread.interrupted()).as("the interrupt flag is restored").isTrue(); }
    }

    @Test void E5_T15_businessAnswersAndOtherKindsAreNotRetried() {
        when(attempt.change(any(), anyString(), anyMap())).thenThrow(new ApiException(ErrorCode.RING_HAS_BOOKINGS));
        assertThatThrownBy(() -> service().update(CatalogKind.RING, "ring", Map.of("version", 0)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.RING_HAS_BOOKINGS));
        verify(attempt, times(1)).change(any(), anyString(), anyMap());
        // A level (no ring slots) keeps the single attempt: a write conflict is STALE_VERSION at once.
        reset(attempt); when(attempt.change(any(), anyString(), anyMap())).thenThrow(writeConflict());
        assertThatThrownBy(() -> service().update(CatalogKind.LEVEL, "level", Map.of("version", 0)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.STALE_VERSION));
        verify(attempt, times(1)).change(any(), anyString(), anyMap());
        // Inside an outer transaction (club:apply) the ring change joins it: no retry, STALE_VERSION for the outer owner.
        reset(attempt); when(attempt.change(any(), anyString(), anyMap())).thenThrow(writeConflict());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service().update(CatalogKind.RING, "ring", Map.of("version", 0)))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.STALE_VERSION));
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verify(attempt, times(1)).change(any(), anyString(), anyMap());
        assertThat(retries.retries(CatalogService.CONTEXT)).isZero();
        assertThat(waits).isEmpty();
        assertThat(CatalogService.conflict(new IllegalStateException("not Mongo"))).isFalse();
    }
}
