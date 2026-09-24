package com.agilityhub.core.shared.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** E5-T07: one lane per aggregate key (tenant-prefixed, never per tenant), taken in key order, switchable. */
class LocalLanesTest {
    @Test void sameKeySerialisesDifferentKeysDoNotBlockAndLanesAreReleased() throws Exception {
        var lanes = new LocalLanes(true);
        assertThat(lanes.enabled()).isTrue();
        var inside = new CountDownLatch(1); var release = new CountDownLatch(1); var concurrent = new AtomicInteger(); var maximum = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(3)) {
            var holder = pool.submit(() -> { try (var t = TenantContext.open("club-a")) {
                return lanes.hold(List.of("activity:a"), () -> { inside.countDown(); await(release); return "first"; }); } });
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
            // Another aggregate of the same club, and the same id in another club, never wait for the held lane.
            assertThat(pool.submit(() -> { try (var t = TenantContext.open("club-a")) { return lanes.hold(List.of("activity:b"), () -> "other aggregate"); } })
                    .get(5, TimeUnit.SECONDS)).isEqualTo("other aggregate");
            assertThat(pool.submit(() -> { try (var t = TenantContext.open("club-b")) { return lanes.hold(List.of("activity:a"), () -> "other club"); } })
                    .get(5, TimeUnit.SECONDS)).isEqualTo("other club");
            var waiting = pool.submit(() -> { try (var t = TenantContext.open("club-a")) { return lanes.hold(Arrays.asList(null, "activity:a"), () -> "second"); } });
            assertThatThrownBy(() -> waiting.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(waiting.get(5, TimeUnit.SECONDS)).isEqualTo("second");
        }
        assertThat(lanes.size()).as("no lane survives its last holder").isZero();
        // Overlapping key sets taken in key order never deadlock.
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 64; i++) {
                var keys = i % 2 == 0 ? List.of("class:x", "class:y") : List.of("class:y", "class:x");
                futures.add(pool.submit(() -> lanes.hold(keys, () -> {
                    maximum.accumulateAndGet(concurrent.incrementAndGet(), Math::max); concurrent.decrementAndGet(); return null; })));
            }
            for (var future : futures) { future.get(10, TimeUnit.SECONDS); }
        }
        assertThat(maximum).hasValue(1);
        assertThat(lanes.size()).isZero();
    }

    @Test void aFailingWorkReleasesItsLanesAndDisabledLanesTakeNoLock() {
        var lanes = new LocalLanes(true);
        assertThatThrownBy(() -> lanes.hold(List.of("dog:a", "member:b"), () -> { throw new IllegalStateException("business refusal"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(lanes.size()).isZero();
        var off = new LocalLanes(false);
        assertThat(off.enabled()).isFalse();
        assertThat(off.hold(List.of("dog:a"), () -> { assertThat(off.size()).isZero(); return "unlocked"; })).isEqualTo("unlocked");
    }

    @Test void retriesAreCountedByContextAndCause() {
        var retries = new TransactionRetries(new SimpleMeterRegistry());
        var conflict = new com.mongodb.MongoException(112, "Example write conflict");
        retries.retried("training", new IllegalStateException(conflict));
        retries.retried("training", new org.springframework.dao.DuplicateKeyException("seat"));
        retries.retried("training", new com.mongodb.MongoException(11000, "Example duplicate"));
        retries.retried("bookings", new com.mongodb.MongoException("Example transient"));
        retries.exhausted("bookings");
        assertThat(retries.retries("training")).isEqualTo(3);
        assertThat(retries.retries("training", "write_conflict")).isEqualTo(1);
        assertThat(retries.retries("training", "duplicate_key")).isEqualTo(2);
        assertThat(retries.retries("bookings", "transient")).isEqualTo(1);
        assertThat(retries.exhaustions("bookings")).isEqualTo(1);
        assertThat(retries.exhaustions("training")).isZero();
        assertThat(retries.retries("activities")).isZero();
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("never released"); } }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
}
