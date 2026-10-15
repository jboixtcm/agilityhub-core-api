package com.agilityhub.core.courses.domain;

import com.agilityhub.core.courses.domain.CourseTypes.SetupKind;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * S16 R-16-07/08: what a ring setup shows and until when. Pure rules; the application reads the club's zone and parameters.
 * <p>
 * Expiry is by club-local day (R-16-08's example: registered on Monday 5-10 with `courses.setupAutoExpireDays = 7`, it is
 * `EXPIRED` by P5 at 06:00 on 12-10): the automatic end is the start of the day `days` after the registration (or renewal) day,
 * and `expectedUntil` («Fins quan?») is the last day the setup is shown, so it ends at the start of the next day. `expiresAt` is
 * the earlier of the two. A start of day that falls in a DST gap moves forward (`atStartOfDay(zone)`).
 */
public final class RingSetupRules {
    private RingSetupRules() { }

    /** A source field the setup names. */
    public enum Source { PLACEMENT, COURSE, IMAGE }

    /** R-16-07: `expiresAt = min(expectedUntil, from + days)`, both as the start of a club-local day. */
    public static Instant expiresAt(Instant from, LocalDate expectedUntil, int autoExpireDays, ZoneId zone) {
        var automatic = from.atZone(zone).toLocalDate().plusDays(autoExpireDays).atStartOfDay(zone).toInstant();
        if (expectedUntil == null) { return automatic; }
        var expected = expectedUntil.plusDays(1).atStartOfDay(zone).toInstant();
        return expected.isBefore(automatic) ? expected : automatic;
    }

    /** `expectedUntil` is a day still to come or today (club-local); an earlier day would register a setup already ended. */
    public static boolean expectedUntilValid(LocalDate expectedUntil, LocalDate today) { return expectedUntil == null || !expectedUntil.isBefore(today); }

    /** The sources a setup names, in §3's order. */
    public static List<Source> sources(String placementId, String courseId, String imageFileKey) {
        var sources = new ArrayList<Source>();
        if (placementId != null) { sources.add(Source.PLACEMENT); }
        if (courseId != null) { sources.add(Source.COURSE); }
        if (imageFileKey != null) { sources.add(Source.IMAGE); }
        return sources;
    }

    /**
     * §3: «almenys un llevat de `EMPTY`». An `EMPTY` setup says the ring is empty, so it names none (the sources it names are
     * returned to be refused); any other kind needs one (`SETUP_SOURCE_REQUIRED` when the result says so).
     */
    public record SourceCheck(boolean missing, List<Source> unexpected) { }
    public static SourceCheck check(SetupKind kind, List<Source> sources) {
        if (kind == SetupKind.EMPTY) { return new SourceCheck(false, List.copyOf(sources)); }
        return new SourceCheck(sources.isEmpty(), List.of());
    }

    /** R-16-09 `hasViewer`: a placed or library course can be drawn in the 2D viewer; a photo or nothing cannot. */
    public static boolean hasViewer(String placementId, boolean courseWithModel) { return placementId != null || courseWithModel; }
}
