package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.common.application.ClubGridCaches;
import com.agilityhub.core.clubs.scheduling.application.RingScheduleAccess;
import com.agilityhub.core.clubs.training.domain.TrainingGrid;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * S09 R-09-03 static layer: per `{clubId, date}` the day's slots plus the classes and ring blocks overlapping it,
 * TTL 60 s on the injected clock, invalidated by the `training` consumers (a day, or the whole club). Bookings are
 * never cached, and nothing that decides a booking reads this cache: the unique index is the source of truth.
 */
@Component
public class TrainingGridCache implements ClubGridCaches {
    static final Duration TTL = Duration.ofSeconds(60);
    public record Day(LocalDate date, boolean closed, List<TrainingGrid.Slot> slots, List<RingScheduleAccess.ClassInterval> classes,
            List<RingScheduleAccess.BlockInterval> blocks) { }
    private record Entry(Day day, Instant expiresAt) { }
    private final Map<String, Entry> days = new ConcurrentHashMap<>();
    private final Clock clock;
    public TrainingGridCache(Clock clock) { this.clock = clock; }

    public Day day(String clubId, LocalDate date, Supplier<Day> loader) {
        String key = clubId + "|" + date; var now = clock.instant(); var cached = days.get(key);
        if (cached != null && now.isBefore(cached.expiresAt())) { return cached.day(); }
        var fresh = loader.get(); days.put(key, new Entry(fresh, now.plus(TTL))); return fresh;
    }
    public void invalidate(String clubId, Collection<LocalDate> dates) { dates.forEach(date -> days.remove(clubId + "|" + date)); }
    /** Also S15 P1 (R-15-11 step 1), through {@link ClubGridCaches}, with or without FREE_TRAINING. */
    @Override public void invalidateClub(String clubId) { days.keySet().removeIf(key -> key.startsWith(clubId + "|")); }
    int size() { return days.size(); }
}
