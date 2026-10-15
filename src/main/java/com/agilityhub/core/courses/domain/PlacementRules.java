package com.agilityhub.core.courses.domain;

import com.agilityhub.core.courses.domain.CourseParts.PlacementWarning;
import java.util.List;

/**
 * S16 R-16-05's policy over course-core's warnings (§14.4, E77). The core never recalculates the geometry: it stores the warnings
 * the client sends (`warningsJson`) and applies the policy: a placement with a `critical` warning is stored only with
 * `force = true` by an ADMIN (`422 PLACEMENT_BLOCKED {warnings}` otherwise, and `COURSE_FORCED_PLACEMENT` when forced);
 * `warning` and `info` are stored as they are.
 */
public final class PlacementRules {
    private PlacementRules() { }

    public enum Outcome { STORE, BLOCKED, FORCED }

    /** The `critical` warnings, in the client's order. */
    public static List<PlacementWarning> critical(List<PlacementWarning> warnings) {
        return warnings.stream().filter(w -> w.severity() == WarningSeverity.CRITICAL).toList();
    }

    /** The policy's answer; an INSTRUCTOR never reaches it with `force` (`403`, T-16-07). */
    public static Outcome outcome(List<PlacementWarning> warnings, boolean force) {
        if (critical(warnings).isEmpty()) { return Outcome.STORE; }
        return force ? Outcome.FORCED : Outcome.BLOCKED;
    }
}
