package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.identity.application.IdentityTransactions;
import com.agilityhub.core.shared.domain.*;
import com.mongodb.MongoException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OfferTransactionsTest {
    @Test void T_05_20_transientConflictsRetryCompleteWorkAndBusinessErrorsDoNotRetry() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        var transactions = new IdentityTransactions(manager); var attempts = new AtomicInteger();
        var conflict = new MongoException(112, "Concurrent offer write"); conflict.addLabel("TransientTransactionError");
        assertThat(transactions.run(() -> {
            if (attempts.incrementAndGet() < 3) { throw conflict; }
            return "created";
        })).isEqualTo("created");
        assertThat(attempts.get()).isEqualTo(3); verify(manager, times(2)).rollback(any()); verify(manager).commit(any());
        assertThatThrownBy(() -> transactions.run(() -> { throw new ApiException(ErrorCode.PRICE_OVERLAP); })).isInstanceOf(ApiException.class);
        verify(manager, times(3)).rollback(any());
    }
    @Test void T_05_20_interruptedTransactionsDoNotRetryAndPreserveCancellation() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var transactions = new IdentityTransactions(manager);
        var conflict = new MongoException(112, "Concurrent offer write"); conflict.addLabel("TransientTransactionError");
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> transactions.run(() -> { throw conflict; })).isSameAs(conflict);
            assertThat(Thread.currentThread().isInterrupted()).isTrue(); verify(manager).rollback(any());
        } finally { Thread.interrupted(); }
    }
}
