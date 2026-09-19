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
}
