package com.agilityhub.core.clubs.scheduling.application;
import com.agilityhub.core.shared.application.TransactionRetries;
import com.agilityhub.core.shared.domain.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SchedulingTransactionsTest {
    private final TransactionRetries retries = new TransactionRetries(new SimpleMeterRegistry());
    private final List<Long> waits = Collections.synchronizedList(new ArrayList<>());
    private SchedulingTransactions transactions(TransactionTemplate template) {
        return new SchedulingTransactions(template, mock(PlanningContext.class), retries, SchedulingTransactions::jitter, waits::add);
    }

    @Test void T_06_23_retriesOnlyAbortedTransactionsAndBoundsPersistentContention() {
        var template=mock(TransactionTemplate.class);var transactions=transactions(template);
        when(template.execute(any())).thenThrow(new com.mongodb.MongoException(112,"Write conflict")).thenReturn("committed");
        assertThat(transactions.write(() -> "unused")).isEqualTo("committed");verify(template,times(2)).execute(any());
        reset(template);when(template.execute(any())).thenThrow(new com.mongodb.MongoException(112,"Write conflict"));
        assertThatThrownBy(() -> transactions.write(() -> "unused")).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(ErrorCode.STALE_VERSION));verify(template,times(6)).execute(any());
        assertThat(retries.exhaustions(SchedulingTransactions.CONTEXT)).isEqualTo(1);
        reset(template);when(template.execute(any())).thenThrow(new IllegalArgumentException("Programming failure"));
        assertThatThrownBy(() -> transactions.write(() -> "unused")).isInstanceOf(IllegalArgumentException.class);verify(template).execute(any());
    }
    /**
     * E5-T07 round 2 (renamed in E5-T15, review E5-T07 #5): each retry waits 50–150 ms (five waits before the sixth and
     * last attempt); an interrupt stops it. The wait is injected, so the test does not sleep.
     */
    @Test void E5_T07_schedulingRetriesWaitAJitteredBackoffAndStopWhenInterrupted() {
        var template=mock(TransactionTemplate.class);
        when(template.execute(any())).thenThrow(new com.mongodb.MongoException(112,"Write conflict"));
        assertThatThrownBy(() -> transactions(template).write(() -> "unused")).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(ErrorCode.STALE_VERSION));
        assertThat(waits).hasSize(5).allSatisfy(wait -> assertThat(wait).isBetween(50L, 150L));
        assertThat(retries.retries(SchedulingTransactions.CONTEXT, "write_conflict")).isEqualTo(5);
        var drawn=new HashSet<Long>(); for(int i=0;i<2000;i++) drawn.add(SchedulingTransactions.jitter());
        assertThat(drawn).allSatisfy(wait -> assertThat(wait).isBetween(50L, 150L)).contains(50L, 150L);
        reset(template);when(template.execute(any())).thenThrow(new com.mongodb.MongoException(112,"Write conflict"));
        var interrupted=new SchedulingTransactions(template,mock(PlanningContext.class),retries,() -> 100L,millis -> { throw new InterruptedException(); });
        try { assertThatThrownBy(() -> interrupted.write(() -> "unused")).isInstanceOf(IllegalStateException.class);verify(template).execute(any()); }
        finally { assertThat(Thread.interrupted()).as("the interrupt flag is restored").isTrue(); }
    }
    /** E5-T15 (review E5-T07 #4): a duplicate key is a conflict, as in S05 and S09 — retried alone, a nested conflict inside a caller's transaction. */
    @Test void E5_T15_schedulingRetriesADuplicateKeyLikeAWriteConflict() {
        var template=mock(TransactionTemplate.class);var transactions=transactions(template);
        when(template.execute(any())).thenThrow(new DuplicateKeyException("E11000 ring_slot_locks"))
                .thenThrow(new com.mongodb.MongoException(11000,"E11000 duplicate key")).thenReturn("committed");
        assertThat(transactions.write(() -> "unused")).isEqualTo("committed");verify(template,times(3)).execute(any());
        assertThat(retries.retries(SchedulingTransactions.CONTEXT, "duplicate_key")).isEqualTo(2);
        assertThat(waits).hasSize(2);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> transactions.write(() -> { throw new DuplicateKeyException("E11000 ring_slot_locks"); },ErrorCode.RING_HAS_BOOKINGS))
                    .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(ErrorCode.RING_HAS_BOOKINGS));
            assertThatThrownBy(() -> transactions.write(() -> { throw new IllegalStateException("not a conflict"); })).isInstanceOf(IllegalStateException.class);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verify(template,times(3)).execute(any());
    }
}
