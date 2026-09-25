package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.domain.CatalogKind;
import com.agilityhub.core.clubs.catalogs.persistence.CatalogRepository;
import com.agilityhub.core.shared.application.TransactionRetries;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
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

    @SuppressWarnings("unchecked")
    private CatalogService service() {
        ObjectProvider<CatalogService> self = mock(ObjectProvider.class); when(self.getObject()).thenReturn(attempt);
        return new CatalogService(mock(CatalogRepository.class), mock(CatalogRepository.class), mock(CatalogRepository.class), null, null, null, null, null, null,
                null, self, retries);
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
    }

    @Test void E5_T15_aRingChangeThatKeepsConflictingEndsInStaleVersionAfterItsBudget() {
        var transientError = new com.mongodb.MongoException(251, "NoSuchTransaction"); transientError.addLabel("TransientTransactionError");
        when(attempt.change(any(), anyString(), anyMap())).thenThrow(new UncategorizedMongoDbException("aborted", transientError));
        assertThatThrownBy(() -> service().update(CatalogKind.RING, "ring", Map.of("version", 0)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.STALE_VERSION));
        verify(attempt, times(CatalogService.RING_CHANGE_ATTEMPTS)).change(any(), anyString(), anyMap());
        assertThat(retries.exhaustions(CatalogService.CONTEXT)).isEqualTo(1);
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
        assertThat(CatalogService.conflict(new IllegalStateException("not Mongo"))).isFalse();
    }
}
