package com.agilityhub.core.shared.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-process fair locks keyed per aggregate (`activity:<id>`, `class:<id>`, `dog:<id>`, `member:<id>`, `slot:…`),
 * never per tenant, taken in key order around a whole retried Mongo transaction. One lock exists per key
 * while somebody holds or waits for it, so unrelated aggregates never block each other.
 *
 * <p>R1 runs a single API instance (ADR-003). The local lanes only bound contention inside one process: a burst on one
 * aggregate queues instead of exhausting its write-conflict retries. With more than one instance they protect
 * nothing and the Mongo mechanisms are the guarantee: the `$inc` sequences with `WriteConflict` retries (R-07-08,
 * R-08-07), the partial unique index `training_active_seat` and the `trainingSeq` / ring-slot sequences (R-09-06,
 * R-09-13). Switched by the infrastructure property `core.concurrency.local-lanes` (default `true`).
 */
@Component
public class LocalLanes {
    private static final class Lane { final ReentrantLock lock = new ReentrantLock(true); int users; }
    private final boolean enabled;
    private final ConcurrentHashMap<String, Lane> lanes = new ConcurrentHashMap<>();

    public LocalLanes(@Value("${core.concurrency.local-lanes:true}") boolean enabled) { this.enabled = enabled; }

    public boolean enabled() { return enabled; }

    /** Runs {@code work} holding the lanes of {@code keys} (null keys ignored; the open tenant, if any, prefixes each key). */
    public <T> T hold(Collection<String> keys, Supplier<T> work) {
        if (!enabled) { return work.get(); }
        String tenant = TenantContext.current();
        var ordered = new TreeSet<String>();
        for (String key : keys) { if (key != null) { ordered.add(tenant == null ? key : tenant + ":" + key); } }
        var held = new ArrayList<String>();
        try {
            for (String key : ordered) { acquire(key).lock(); held.add(key); }
            return work.get();
        } finally { for (int i = held.size() - 1; i >= 0; i--) { release(held.get(i)); } }
    }

    /** Lanes currently registered (held or awaited); for tests. */
    int size() { return lanes.size(); }

    private ReentrantLock acquire(String key) {
        return lanes.compute(key, (k, lane) -> {
            var result = lane == null ? new Lane() : lane;
            result.users++;
            return result;
        }).lock;
    }

    private void release(String key) {
        lanes.computeIfPresent(key, (k, lane) -> {
            lane.lock.unlock();
            return --lane.users == 0 ? null : lane;
        });
    }
}
