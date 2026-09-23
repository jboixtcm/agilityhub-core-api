package com.agilityhub.core.clubs.bookings.domain;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.*;
import java.util.*;

/**
 * S08 R-08-04/05/06/17 — the one ordered list of booking checks, shared by `/seat-holds`, `/bookings`,
 * `/waitlist-entries` and `claim` so they cannot drift apart. The first failing check wins:
 * dog accessible and ACTIVE (404) → owner and booker ACTIVE → booking block of either → leaving (end of the
 * local leave day on, A20c / S15 §13-6) → inactivity period → level → class ACTIVE and not started → week open → pack.
 */
public final class BookingEligibility {
    public record Person(String status, boolean blocked, String blockReason, LocalDate leaveDate) { }
    public record Period(LocalDate from, LocalDate to) { }
    public record Pack(int available, LocalDate expiresOn) { }
    /**
     * @param inactivity the owner's APPROVED/ACTIVE period covering the class's local date, if INACTIVITY is on
     * @param pack       the owner's pack for the dog when PACKS applies to the plan, else empty
     */
    public record Input(boolean dogAccessible, boolean dogActive, Person owner, Person booker, Optional<Period> inactivity,
            boolean levelsEnabled, String dogLevelId, List<String> classLevelIds, boolean classActive, Instant classStartsAt,
            ZoneId zone, Instant now, RelativeWeek week, Instant opensAt, Optional<Pack> pack) { }
    private BookingEligibility() { }

    public static void check(Input in) {
        if (!in.dogAccessible() || !in.dogActive()) { throw new ApiException(ErrorCode.DOG_NOT_ACCESSIBLE); }
        if (!"ACTIVE".equals(in.owner().status()) || !"ACTIVE".equals(in.booker().status())) { throw new ApiException(ErrorCode.MEMBER_NOT_ACTIVE); }
        for (var person : List.of(in.owner(), in.booker())) {
            if (person.blocked()) {
                var details = new LinkedHashMap<String, Object>(); details.put("reason", person.blockReason() == null ? "" : person.blockReason());
                throw new ApiException(ErrorCode.BOOKING_BLOCKED, details);
            }
        }
        if (leaving(in.owner().leaveDate(), in.classStartsAt(), in.zone())) {
            throw new ApiException(ErrorCode.CLASS_NOT_BOOKABLE, Map.of("reason", "LEAVING"));
        }
        if (in.inactivity().isPresent()) {
            var period = in.inactivity().get(); var details = new LinkedHashMap<String, Object>();
            details.put("from", period.from().toString()); if (period.to() != null) { details.put("to", period.to().toString()); }
            throw new ApiException(ErrorCode.INACTIVITY_PERIOD, details);
        }
        if (!levelAllowed(in.levelsEnabled(), in.dogLevelId(), in.classLevelIds())) { throw new ApiException(ErrorCode.LEVEL_NOT_ALLOWED); }
        if (!in.classActive() || !in.now().isBefore(in.classStartsAt())) { throw new ApiException(ErrorCode.CLASS_NOT_BOOKABLE); }
        if (in.week() == RelativeWeek.LATER) { throw new ApiException(ErrorCode.NOT_YET_OPEN, Map.of("opensAt", in.opensAt().toString())); }
        if (in.pack().isPresent() && packEmpty(in.pack().get(), in.classStartsAt().atZone(in.zone()).toLocalDate())) {
            throw new ApiException(ErrorCode.PACK_EMPTY);
        }
    }
    /** A future leave date blocks the classes starting from the end of that local day on (§13-8 + S15 §13-6). */
    public static boolean leaving(LocalDate leaveDate, Instant classStartsAt, ZoneId zone) {
        return leaveDate != null && !classStartsAt.isBefore(leaveDate.plusDays(1).atStartOfDay(zone).toInstant());
    }
    /** `levels.enabled = false` → every class; otherwise the dog's level must be listed, an empty list admits all (T-08-10). */
    public static boolean levelAllowed(boolean levelsEnabled, String dogLevelId, List<String> classLevelIds) {
        return !levelsEnabled || classLevelIds == null || classLevelIds.isEmpty() || dogLevelId != null && classLevelIds.contains(dogLevelId);
    }
    /** R-08-17: no sessions left, or the pack expires before the class's local date. */
    public static boolean packEmpty(Pack pack, LocalDate classDate) {
        return pack.available() <= 0 || pack.expiresOn() != null && pack.expiresOn().isBefore(classDate);
    }
    /** Whether {@code date} falls inside the period (both ends inclusive; an open end has no upper bound). */
    public static boolean covers(Period period, LocalDate date) {
        return !date.isBefore(period.from()) && (period.to() == null || !date.isAfter(period.to()));
    }
}
