package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.shared.application.LocalLanes;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.TransactionRetries;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.mongodb.MongoException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link BookingTransactions} (S08 §6, R-08-07; T-08-29): one local lane per class, at most three
 * attempts with every retry counted (bounded: a fourth attempt fails the test instead of looping), and an interrupted
 * backoff stops retrying with the thread's interrupt kept.
 */
class BookingTransactionsSurvivorsTest {
    final TransactionTemplate template = mock(TransactionTemplate.class);
    final TransactionRetries retries = new TransactionRetries(new SimpleMeterRegistry());
    final AtomicInteger calls = new AtomicInteger();

    @Test void T_08_29_eachClassTouchedHoldsItsOwnLane() {
        var lanes = mock(LocalLanes.class);
        when(lanes.hold(any(), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        // TransactionTemplate#execute runs the callback and returns its result.
        when(template.execute(any())).thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        var transactions = new BookingTransactions(template, lanes, retries);

        try (var tenant = TenantContext.open("club-a")) {
            // A swap touches two classes, the new one first.
            assertThat(transactions.write(List.of("class-b", "class-a"), () -> "booking-1")).isEqualTo("booking-1");
        }

        verify(lanes).hold(eq(List.of("class:class-b", "class:class-a")), any());
    }

    @Test void T_08_29_aConflictIsRetriedAtMostThreeTimesAndEveryRetryIsCounted() {
        when(template.execute(any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() > BookingTransactions.ATTEMPTS) { throw new AssertionError("attempt " + calls.get() + " past the budget"); }
            throw transientError();
        });
        var transactions = new BookingTransactions(template, new LocalLanes(false), retries);

        try (var tenant = TenantContext.open("club-a")) {
            assertThatThrownBy(() -> transactions.write(List.of("class-a"), () -> "work"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.STALE_VERSION));
        }

        assertThat(calls.get()).isEqualTo(BookingTransactions.ATTEMPTS);
        assertThat(retries.retries(BookingTransactions.CONTEXT, "transient")).isEqualTo(BookingTransactions.ATTEMPTS - 1);
        assertThat(retries.exhaustions(BookingTransactions.CONTEXT)).isEqualTo(1);
    }

    @Test void T_08_29_anInterruptedBackoffStopsRetryingAndKeepsTheInterrupt() {
        when(template.execute(any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) { throw transientError(); }
            return "done";
        });
        var transactions = new BookingTransactions(template, new LocalLanes(false), retries);

        try (var tenant = TenantContext.open("club-a")) {
            // The instance is shutting down while a booking waits to retry: the sleep is interrupted at once.
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> transactions.write(List.of("class-a"), () -> "work"))
                    .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).as("the interrupt is restored").isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(calls.get()).isEqualTo(1);
    }

    private static MongoException transientError() {
        var error = new MongoException("Transaction aborted");
        error.addLabel("TransientTransactionError");
        return error;
    }
}
