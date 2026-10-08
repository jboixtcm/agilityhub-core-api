package com.agilityhub.core.shared.persistence;

import com.agilityhub.core.shared.application.TenantWriteFence;
import com.agilityhub.core.shared.application.TransactionRetries;
import com.mongodb.MongoException;
import com.mongodb.client.result.UpdateResult;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantWriteTrackingTest {
    private static final String CONTEXT = "tenant-write-tracking";
    @Mock PlatformTransactionManager manager;
    @Mock ObjectProvider<TenantWriteFence> provider;
    @Mock TenantWriteFence fence;
    @Mock TransactionRetries retries;
    @Mock ProceedingJoinPoint call;
    @Mock MongoTemplate mongo;
    @Mock Signature signature;
    @InjectMocks TenantWriteTracking tracking;

    @BeforeEach void prepare() {
        when(call.getTarget()).thenReturn(mongo);
        when(call.getArgs()).thenReturn(new Object[] {
                Query.query(Criteria.where("clubId").is("fictional-club")), new Update().inc("value", 1), "rows"});
        when(call.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("updateFirst");
        when(provider.getObject()).thenReturn(fence);
        when(manager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus(true));
    }

    @Test void T_18_16_round2_point1_standaloneConflictRetriesTheWholeTransaction() throws Throwable {
        var conflict = new MongoException(112, "WriteConflict");
        var result = UpdateResult.acknowledged(1, 1L, null);
        when(call.proceed()).thenThrow(conflict).thenReturn(result);
        assertThat(tracking.write(call)).isSameAs(result);
        verify(manager, times(2)).getTransaction(any());
        verify(manager).rollback(any());
        verify(manager).commit(any());
        verify(retries).retried(CONTEXT, conflict);
        verify(fence).written("fictional-club");
    }

    @Test void T_18_16_round2_point1_anOuterTransactionOwnsItsConflictRetry() throws Throwable {
        var conflict = new MongoException(112, "WriteConflict");
        when(call.proceed()).thenThrow(conflict);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> tracking.write(call)).isSameAs(conflict); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verify(call).proceed();
        verifyNoInteractions(retries);
    }

    @Test void T_18_16_round2_point1_unknownCommitIsNeverReplayedEvenWithATransientLabel() throws Throwable {
        var uncertain = new MongoException(112, "Uncertain commit");
        uncertain.addLabel("UnknownTransactionCommitResult");
        uncertain.addLabel("TransientTransactionError");
        when(call.proceed()).thenReturn(UpdateResult.acknowledged(1, 1L, null));
        doThrow(uncertain).when(manager).commit(any());
        assertThatThrownBy(() -> tracking.write(call)).isSameAs(uncertain);
        verify(call).proceed();
        verifyNoInteractions(retries);
    }

    @Test void T_18_16_round2_point1_nonTransientFailureIsNotRetried() throws Throwable {
        var failure = new IllegalStateException("Rejected mutation");
        when(call.proceed()).thenThrow(failure);
        assertThatThrownBy(() -> tracking.write(call)).isSameAs(failure);
        verify(call).proceed();
        verifyNoInteractions(retries);
    }

    @Test void T_18_16_round2_point1_persistentConflictIsBoundedAndCounted() throws Throwable {
        var conflict = new MongoException(112, "WriteConflict");
        when(call.proceed()).thenThrow(conflict);
        assertThatThrownBy(() -> tracking.write(call)).isSameAs(conflict);
        verify(call, times(20)).proceed();
        verify(retries, times(19)).retried(CONTEXT, conflict);
        verify(retries).exhausted(CONTEXT);
    }

    @Test void T_18_16_round2_point1_interruptionStopsRetriesAndPreservesTheFlag() throws Throwable {
        var conflict = new MongoException(112, "WriteConflict");
        when(call.proceed()).thenThrow(conflict);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> tracking.write(call)).isSameAs(conflict);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        verify(call).proceed();
        verify(retries).retried(CONTEXT, conflict);
    }
}
