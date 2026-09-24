package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.bookings.application.BookingContext;
import com.agilityhub.core.clubs.training.domain.TrainingGrid;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Club configuration, the S09 parameters (`training.*`, `bookings.weekOpensAt`, `bookings.limitUnit`,
 * `levels.enabled`, `club.openingHours`, `club.holidays`, `courses.showSetupToMembers`) and the injected clock.
 * The training week is S08's booking week (R-09-05 reuses {@code BookingWeeks.lastOpening} through {@link BookingContext}).
 */
@Service
public class TrainingContext {
    private final ClubConfigService configs; private final Clock clock; private final BookingContext bookings;
    public TrainingContext(ClubConfigService configs, Clock clock, BookingContext bookings) { this.configs = configs; this.clock = clock; this.bookings = bookings; }
    /** The injected clock through {@link BookingContext#now()}: identical outside the demo seed, which books «as of» an instant. */
    public Instant now() { return bookings.now(); }
    public String clubId() { return TenantContext.require(); }
    public ClubConfig config() { return configs.get(clubId()); }
    public ZoneId zone() { return ZoneId.of(config().club().timeZone()); }
    public LocalDate today() { return now().atZone(zone()).toLocalDate(); }
    public boolean enabled(Module module) { return config().modules().contains(module); }
    public int integer(String key) { return config().get(key, Integer.class); }
    public boolean flag(String key) { return Boolean.TRUE.equals(config().get(key, Boolean.class)); }
    public int slotMinutes() { return integer("training.slotMinutes"); }
    public int windowDays() { return integer("training.bookingWindowDays"); }
    public int maxPerWeek() { return integer("training.maxPerWeek"); }
    public int cancelThreshold() { return integer("training.cancelThresholdMinutes"); }
    /** R-09-02: `Ring.trainingCapacity ?? training.capacityPerRingSlot` (ring-scoped parameter first). */
    public int capacity(String ringId, Integer ringCapacity) {
        if (ringCapacity != null) { return ringCapacity; }
        Integer value = config().get("training.capacityPerRingSlot", ringId, Integer.class); // scopeRef of a ring-scoped parameter = the ring id
        return value == null ? 1 : value;
    }
    /** `bookings.limitUnit = DOG` counts per dog, `MEMBER` per booking member. */
    public boolean dogUnit() { return "DOG".equals(config().get("bookings.limitUnit", String.class)); }
    public String limitUnit() { return dogUnit() ? "DOG" : "MEMBER"; }
    @SuppressWarnings("unchecked")
    public Map<String, Object> weekOpensAt() { return (Map<String, Object>) config().get("bookings.weekOpensAt", Map.class); }
    public BookingContext.WeekSpan week(Instant instant) { return bookings.weekSpan(instant); }
    public Map<DayOfWeek, TrainingGrid.Hours> openingHours() {
        var result = new EnumMap<DayOfWeek, TrainingGrid.Hours>(DayOfWeek.class);
        Map<?, ?> raw = config().get("club.openingHours", Map.class);
        if (raw == null) { return result; }
        raw.forEach((day, value) -> {
            if (value instanceof Map<?, ?> hours && hours.get("open") != null && hours.get("close") != null) {
                result.put(DayOfWeek.valueOf(day.toString()), new TrainingGrid.Hours(LocalTime.parse(hours.get("open").toString()), LocalTime.parse(hours.get("close").toString())));
            }
        });
        return result;
    }
    public Set<LocalDate> holidays() {
        var result = new HashSet<LocalDate>(); List<?> raw = config().get("club.holidays", List.class);
        if (raw != null) { raw.forEach(h -> result.add(LocalDate.parse(h instanceof Map<?, ?> holiday ? holiday.get("date").toString() : h.toString()))); }
        return result;
    }
}
