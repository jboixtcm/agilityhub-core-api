package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * Base layer of `GET /me/bookable-classes` (S15 R-15-11 step 2 / S08 contract): the ACTIVE classes of the booking weeks
 * W0…W2 with their counters and no per-dog state, TTL 30 s. Owned by S08; the key is `{clubId}:{W0 key}` (the local date of
 * the current week's opening), so a new booking week never serves the previous one. E5-T06's `BookableClassesQuery`
 * reads it through {@link #get()} and adds the per-dog state on top; P1 `week-opening` calls {@link #warm()}.
 */
@Service
public class BookableClassesCache {
    static final Duration TTL = Duration.ofSeconds(30);
    public record Base(String currentWeekKey, Instant from, Instant to, Instant loadedAt, List<ClassSessionBookingAccess.Session> classes) { }
    private final BookingContext context; private final ClassSessionBookingAccess classes; private final Clock clock;
    private final Cache<String, Base> cache;
    public BookableClassesCache(BookingContext context, ClassSessionBookingAccess classes, Clock clock) {
        this.context = context; this.classes = classes; this.clock = clock;
        this.cache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(TTL).ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis())).build();
    }
    public static String key(String clubId, String currentWeekKey) { return clubId + ":" + currentWeekKey; }

    public Base get() {
        var weeks = context.weeks(); var current = weeks.week(clock.instant());
        return com.agilityhub.core.shared.application.CacheLoads.get(cache, key(context.clubId(), current.key()), key -> {
            // W0 … W2: three booking weeks from the current opening.
            var to = weeks.week(weeks.week(current.end()).end()).end();
            return new Base(current.key(), current.start(), to, clock.instant(), classes.activeBetween(current.start(), to));
        });
    }
    /** Drops the club's entries and loads the current one again. */
    public Base warm() { invalidate(context.clubId()); return get(); }
    public void invalidate(String clubId) { cache.asMap().keySet().removeIf(key -> key.startsWith(clubId + ":")); }
}
