package com.agilityhub.core.platform.application;

import com.agilityhub.core.shared.application.TransactionRetries;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** S17 R-17-05 / R-17-09: the console's lock conflicts rerun the whole unit and never surface as a 500. */
class ConsoleTransactionsTest {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final TransactionRetries retries = new TransactionRetries(registry);
    final TransactionTemplate template = mock(TransactionTemplate.class);
    final ConsoleTransactions transactions = new ConsoleTransactions(template, retries);

    @SuppressWarnings("unchecked")
    void runs() { when(template.execute(any())).thenAnswer(call -> ((TransactionCallback<Object>) call.getArgument(0)).doInTransaction(new SimpleTransactionStatus())); }

    @Test void T_17_08_R_17_09_aConflictRerunsTheWholeUnitUntilItCommits() {
        runs(); var attempts = new AtomicInteger();
        String result = transactions.write(() -> {
            if (attempts.incrementAndGet() < 3) { throw new com.mongodb.MongoException(112, "WriteConflict"); }
            return "committed";
        });
        assertThat(result).isEqualTo("committed");
        assertThat(attempts).hasValue(3);
        assertThat(retries.retries(ConsoleTransactions.CONTEXT, "write_conflict")).isEqualTo(2);
    }
    @Test void T_17_05_R_17_05_exhaustedConflictsAnswerStaleVersionAndBusinessErrorsAreNotRetried() {
        runs(); var attempts = new AtomicInteger();
        assertThatThrownBy(() -> transactions.write(() -> { attempts.incrementAndGet(); throw new com.mongodb.MongoException(112, "WriteConflict"); }))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(ErrorCode.STALE_VERSION));
        assertThat(attempts).hasValue(20);
        assertThat(retries.exhaustions(ConsoleTransactions.CONTEXT)).isEqualTo(1);
        attempts.set(0);
        assertThatThrownBy(() -> transactions.write(() -> { attempts.incrementAndGet(); throw new ApiException(ErrorCode.PROVIDER_IN_USE); }))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(ErrorCode.PROVIDER_IN_USE));
        assertThat(attempts).hasValue(1);
    }
    @Test void T_17_08_R_17_09_insideAnOuterTransactionTheUnitJoinsIt() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThat(transactions.write(() -> "joined")).isEqualTo("joined"); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verifyNoInteractions(template);
    }
}
