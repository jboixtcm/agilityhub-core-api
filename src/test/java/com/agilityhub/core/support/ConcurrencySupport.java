package com.agilityhub.core.support;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.TransactionRetries;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E5-T07 helpers for the Mongo-path concurrency tests (local lanes off): parallel bursts released together, and a
 * Mongo transaction held open in a background thread so a request under test meets its write deterministically.
 */
public final class ConcurrencySupport {
    private static final long WAIT_SECONDS = 30;
    private ConcurrencySupport() { }

    /** Runs {@code threads} tasks released by one latch and returns their results in task order. */
    public static <T> List<T> parallel(int threads, IntFunction<Callable<T>> work) throws Exception {
        var pool = Executors.newFixedThreadPool(threads); var start = new CountDownLatch(1); var futures = new ArrayList<Future<T>>();
        try {
            for (int i = 0; i < threads; i++) { var task = work.apply(i); futures.add(pool.submit(() -> { start.await(); return task.call(); })); }
            start.countDown(); var results = new ArrayList<T>();
            for (var future : futures) { results.add(future.get(120, TimeUnit.SECONDS)); }
            return results;
        } finally { pool.shutdownNow(); }
    }

    /** Waits until {@code context} has retried more than {@code before} times (the request under test met a conflict). */
    public static double awaitRetry(TransactionRetries retries, String context, double before) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (retries.retries(context) <= before) {
            if (System.nanoTime() > deadline) { throw new AssertionError("no " + context + " retry within " + WAIT_SECONDS + " s"); }
            Thread.sleep(2);
        }
        return retries.retries(context);
    }

    /** A Mongo transaction of {@code clubId} that has run {@code work} and stays open until {@link #commit()}. */
    public static final class HeldTransaction implements AutoCloseable {
        private final ExecutorService thread = Executors.newSingleThreadExecutor();
        private final CountDownLatch written = new CountDownLatch(1), release = new CountDownLatch(1);
        private final Future<?> outcome;

        public HeldTransaction(TransactionTemplate transactions, String clubId, Runnable work) throws Exception {
            outcome = thread.submit(() -> {
                try (var tenant = TenantContext.open(clubId)) {
                    transactions.executeWithoutResult(status -> {
                        work.run(); written.countDown();
                        try { if (!release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { throw new IllegalStateException("held transaction never released"); } }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                    });
                }
                return null;
            });
            while (!written.await(10, TimeUnit.MILLISECONDS)) {
                if (outcome.isDone()) { outcome.get(); throw new AssertionError("held transaction ended before writing"); }
            }
        }
        /** Commits the held transaction and waits for it. */
        public void commit() throws Exception { release.countDown(); outcome.get(WAIT_SECONDS, TimeUnit.SECONDS); }
        @Override public void close() { release.countDown(); thread.shutdown(); }
    }
}
