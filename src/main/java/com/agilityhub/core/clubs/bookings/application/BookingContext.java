package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.BookingWeeks;
import com.agilityhub.core.clubs.bookings.domain.LimitUnit;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.*;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Club configuration, parameters (the `bookings.*`, `waitlist.*` and `classes.minDogs` keys) and the injected clock. */
@Service
public class BookingContext {
    private final ClubConfigService configs; private final Clock clock;
    private final ThreadLocal<Instant> asOf = new ThreadLocal<>();
    public BookingContext(ClubConfigService configs, Clock clock) { this.configs = configs; this.clock = clock; }
    /** Business time: the injected clock, or the instant a demo-seed step books «as of» (see {@link #asOf}). */
    public Instant now() { var fixed = asOf.get(); return fixed != null ? fixed : clock.instant(); }
    /**
     * Demo seed only (E4-T05 timeline, E5-T06 scenario): the seeded classes are not yet bookable on the run date, so the
     * seed books, cancels and trains «as of» a scenario instant (S09 reads the same instant through `TrainingContext`).
     * Every rule runs unchanged; only the evaluation instant moves. Only `Demo*` seed classes may call it (ArchUnit
     * `BOOKING_TIME_OVERRIDE`, E5-T06 round 2).
     */
    public <T> T asOf(Instant instant, java.util.function.Supplier<T> work) {
        var previous = asOf.get(); asOf.set(instant);
        try { return work.get(); } finally { if (previous == null) { asOf.remove(); } else { asOf.set(previous); } }
    }
    public String clubId() { return TenantContext.require(); }
    public ClubConfig config() { return configs.get(clubId()); }
    public ZoneId zone() { return ZoneId.of(config().club().timeZone()); }
    public boolean enabled(Module module) { return config().modules().contains(module); }
    public int integer(String key) { return config().get(key, Integer.class); }
    public boolean flag(String key) { return Boolean.TRUE.equals(config().get(key, Boolean.class)); }
    public BookingWeeks weeks() { return new BookingWeeks(BookingWeeks.Opening.of(config().get("bookings.weekOpensAt", Map.class)), zone()); }
    /** The last local `bookings.weekOpensAt` occurrence at or before `at` (S15 P1 `opensAt`). */
    public Instant lastOpening(Instant at) { return weeks().lastOpening(at); }
    public LimitUnit unit() { return LimitUnit.valueOf(config().get("bookings.limitUnit", String.class)); }
    /** `[O, next O)` around an instant, `O` = the last local `bookings.weekOpensAt` ≤ it (S09 R-09-05 reuses R-08-01's weeks). */
    public record WeekSpan(Instant start, Instant end) { }
    public WeekSpan weekSpan(Instant instant) { var week = weeks().week(instant); return new WeekSpan(week.start(), week.end()); }
    public com.agilityhub.core.clubs.bookings.domain.WaitlistMode waitlistMode() {
        return com.agilityhub.core.clubs.bookings.domain.WaitlistMode.valueOf(config().get("waitlist.mode", String.class));
    }
    public Duration lateThreshold() { return Duration.ofMinutes(integer("bookings.lateCancelThresholdMinutes")); }
    public LocalDate today() { return now().atZone(zone()).toLocalDate(); }
}
