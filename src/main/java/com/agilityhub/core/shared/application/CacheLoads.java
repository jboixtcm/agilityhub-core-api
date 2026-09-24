package com.agilityhub.core.shared.application;

import com.github.benmanes.caffeine.cache.Cache;
import java.util.function.Function;

/**
 * Caffeine lookups that are safe on virtual threads (the API runs on them). `Cache.get(key, loader)` runs the loader
 * inside a `ConcurrentHashMap` bin lock; on JDK 21 a virtual thread doing I/O there, and every virtual thread waiting
 * for that bin, pins its carrier. Under a burst (E5-T06 k6, 300 simultaneous members) the pinned carriers starve the
 * unmounted threads that hold the Mongo pool's connections, and the whole API stalls. This helper loads outside any
 * lock: concurrent misses may load the same value twice and the last one wins, which is harmless for these read caches.
 */
public final class CacheLoads {
    private CacheLoads() { }
    public static <K, V> V get(Cache<K, V> cache, K key, Function<? super K, ? extends V> loader) {
        V cached = cache.getIfPresent(key);
        if (cached != null) { return cached; }
        V loaded = loader.apply(key);
        if (loaded != null) { cache.put(key, loaded); }
        return loaded;
    }
}
