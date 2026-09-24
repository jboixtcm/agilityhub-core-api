package com.agilityhub.core.shared.application;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** E5-T06 round 2: the lock-free loads keep Caffeine's rule that an invalidation during a load wins over the load. */
class CacheLoadsTest {
    final CacheLoads<String, String> cache = CacheLoads.of(Caffeine.newBuilder().maximumSize(10).build());
    final AtomicInteger loads = new AtomicInteger();

    @Test void loadsOnceAndServesTheCachedValue() {
        assertThat(cache.get("a", key -> key + loads.incrementAndGet())).isEqualTo("a1");
        assertThat(cache.get("a", key -> key + loads.incrementAndGet())).isEqualTo("a1");
        assertThat(cache.getIfPresent("a")).isEqualTo("a1");
        assertThat(loads).hasValue(1);
    }
    @Test void aNullLoadIsReturnedAndNotCached() {
        assertThat(cache.get("a", key -> null)).isNull();
        assertThat(cache.getIfPresent("a")).isNull();
    }
    @Test void aKeyInvalidationDuringTheLoadDropsTheLoadedValue() {
        assertThat(cache.get("a", key -> { cache.invalidate("a"); return "stale"; })).isEqualTo("stale");
        assertThat(cache.getIfPresent("a")).isNull();
        assertThat(cache.get("a", key -> "fresh")).isEqualTo("fresh");
        assertThat(cache.getIfPresent("a")).isEqualTo("fresh");
    }
    @Test void anInvalidateAllOrAPredicateInvalidationDuringTheLoadDropsTheLoadedValue() {
        cache.get("a", key -> { cache.invalidateAll(); return "stale"; });
        assertThat(cache.getIfPresent("a")).isNull();
        cache.get("club-a:1", key -> { cache.invalidateIf(k -> k.startsWith("club-a:")); return "stale"; });
        assertThat(cache.getIfPresent("club-a:1")).isNull();
    }
    @Test void anInvalidationOfAnotherKeyDuringTheLoadAlsoDropsIt() {
        // One generation per cache: conservative, the next caller simply loads again.
        cache.get("a", key -> { cache.invalidate("b"); return "value"; });
        assertThat(cache.getIfPresent("a")).isNull();
    }
    @Test void anOverlappingOlderLoadNeverLeavesItsValueBehind() {
        // A newer load (started after the invalidation) stores its value; the older one then overwrites it and, having
        // seen the invalidation, removes its own value: the cache is left empty (a miss), never with the older value.
        assertThat(cache.get("a", key -> { cache.invalidate("a"); assertThat(cache.get("a", inner -> "newer")).isEqualTo("newer"); return "older"; }))
                .isEqualTo("older");
        assertThat(cache.getIfPresent("a")).isNull();
    }
    @Test void invalidationsOutsideALoadRemoveTheValues() {
        cache.get("a", key -> "1"); cache.get("b", key -> "2"); cache.get("c", key -> "3");
        cache.invalidate("a"); assertThat(cache.getIfPresent("a")).isNull(); assertThat(cache.getIfPresent("b")).isEqualTo("2");
        cache.invalidateIf(k -> k.equals("b")); assertThat(cache.getIfPresent("b")).isNull(); assertThat(cache.getIfPresent("c")).isEqualTo("3");
        cache.invalidateAll(); assertThat(cache.getIfPresent("c")).isNull();
    }
}
