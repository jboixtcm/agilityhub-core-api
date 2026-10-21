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

    /**
     * E5-T07 / R-08-07 (E11-T16 step 4, audit 21-10 S4): the booking writers retry what `TransactionRetries.conflict` retries for
     * S05, S06, S09 and `JobRunner.write`, a duplicate key too — the first `seat_locks` upsert of a class after the seven-day
     * `seat_lock_ttl` can raise one — counted as `duplicate_key`; a duplicate key in the chain of another failure too.
     */
    @Test void T_08_29_R_08_07_aDuplicateKeyOnTheFirstAttemptIsRetriedAndCounted() {
        when(template.execute(any())).thenAnswer(inv -> {
            int call = calls.incrementAndGet();
            if (call == 1) { throw new org.springframework.dao.DuplicateKeyException("E11000 duplicate key error collection: seat_locks"); }
            if (call == 2) { throw new IllegalStateException(new MongoException(11000, "E11000 duplicate key error collection: seat_locks")); }
            return "booking-1";
        });
        var transactions = new BookingTransactions(template, new LocalLanes(false), retries);

        try (var tenant = TenantContext.open("club-a")) {
            assertThat(transactions.write(List.of("class-a"), () -> "work")).isEqualTo("booking-1");
        }

        assertThat(calls.get()).isEqualTo(3);
        assertThat(retries.retries(BookingTransactions.CONTEXT, "duplicate_key")).isEqualTo(2);
        assertThat(retries.exhaustions(BookingTransactions.CONTEXT)).isZero();
        assertThat(BookingTransactions.transientConflict(new org.springframework.dao.DuplicateKeyException("E11000"))).isTrue();
    }

    private static MongoException transientError() {
        var error = new MongoException("Transaction aborted");
        error.addLabel("TransientTransactionError");
        return error;
    }
}
