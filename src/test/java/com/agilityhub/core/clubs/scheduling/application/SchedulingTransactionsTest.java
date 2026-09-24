package com.agilityhub.core.clubs.scheduling.application;
import com.agilityhub.core.shared.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SchedulingTransactionsTest {
    @Test void T_06_23_retriesOnlyAbortedTransactionsAndBoundsPersistentContention() {
        var template=mock(TransactionTemplate.class);var context=mock(PlanningContext.class);var transactions=new SchedulingTransactions(template,context);
        when(template.execute(any())).thenThrow(new com.mongodb.MongoException(112,"Write conflict")).thenReturn("committed");
        assertThat(transactions.write(() -> "unused")).isEqualTo("committed");verify(template,times(2)).execute(any());
        reset(template);when(template.execute(any())).thenThrow(new com.mongodb.MongoException(112,"Write conflict"));
        assertThatThrownBy(() -> transactions.write(() -> "unused")).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(ErrorCode.STALE_VERSION));verify(template,times(6)).execute(any());
        reset(template);when(template.execute(any())).thenThrow(new IllegalArgumentException("Programming failure"));
        assertThatThrownBy(() -> transactions.write(() -> "unused")).isInstanceOf(IllegalArgumentException.class);verify(template).execute(any());
    }
    /** E5-T07 round 2: each retry waits 50–150 ms (five waits before the sixth and last attempt); an interrupt stops it. */
    @Test void T_06_23_retriesWaitAJitteredBackoffAndStopWhenInterrupted() {
        var template=mock(TransactionTemplate.class);var transactions=new SchedulingTransactions(template,mock(PlanningContext.class));
        when(template.execute(any())).thenThrow(new com.mongodb.MongoException(112,"Write conflict"));
        long started=System.nanoTime();
        assertThatThrownBy(() -> transactions.write(() -> "unused")).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(ErrorCode.STALE_VERSION));
        assertThat((System.nanoTime()-started)/1_000_000).isGreaterThanOrEqualTo(250);
        reset(template);when(template.execute(any())).thenThrow(new com.mongodb.MongoException(112,"Write conflict"));
        Thread.currentThread().interrupt();
        try { assertThatThrownBy(() -> transactions.write(() -> "unused")).isInstanceOf(IllegalStateException.class);verify(template).execute(any()); }
        finally { assertThat(Thread.interrupted()).as("the interrupt flag is restored").isTrue(); }
    }
}
