package com.agilityhub.core.shared.application;

import com.github.benmanes.caffeine.cache.Cache;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A Caffeine cache whose lookups are safe on virtual threads (the API runs on them). `Cache.get(key, loader)` runs the
 * loader inside a `ConcurrentHashMap` bin lock; on JDK 21 a virtual thread doing I/O there, and every virtual thread
 * waiting for that bin, pins its carrier. Under a burst (E5-T06 k6, 300 simultaneous members) the pinned carriers starve
 * the unmounted threads that hold the Mongo pool's connections, and the whole API stalls. This cache loads outside any
 * lock: concurrent misses may load the same value twice and the last one wins, which is harmless for read caches.
 *
 * <p>Loading outside the lock loses Caffeine's link between a load and a later invalidation, so every invalidation goes
 * through this class and bumps a generation. A load that overlapped any invalidation of this cache (the generation moved
 * between its start and its `put`) removes the value it has just stored: it may have read the state before the change
 * that caused the invalidation. The `put` comes first and the generation is read after it, so an invalidation that lands
 * between the two is still seen. The caller of such a load gets the value it read; the next caller loads again.
 */
public final class CacheLoads<K, V> {
    private final Cache<K, V> cache;
    private final AtomicLong generation = new AtomicLong();
    private CacheLoads(Cache<K, V> cache) { this.cache = cache; }
    public static <K, V> CacheLoads<K, V> of(Cache<K, V> cache) { return new CacheLoads<>(cache); }

    public V get(K key, Function<? super K, ? extends V> loader) {
        V cached = cache.getIfPresent(key);
        if (cached != null) { return cached; }
        long started = generation.get();
        V loaded = loader.apply(key);
        if (loaded == null) { return null; }
        cache.put(key, loaded);
        if (generation.get() != started) { cache.asMap().remove(key, loaded); }
        return loaded;
    }
    public V getIfPresent(K key) { return cache.getIfPresent(key); }
    public void invalidate(K key) { generation.incrementAndGet(); cache.invalidate(key); }
    public void invalidateAll() { generation.incrementAndGet(); cache.invalidateAll(); }
    public void invalidateIf(Predicate<? super K> matches) { generation.incrementAndGet(); cache.asMap().keySet().removeIf(matches); }
}
