package com.agilityhub.core.clubs.common.application.ports;

import java.time.Instant;
import java.util.List;

/**
 * S16 R-16-11 «Una sessió oberta > 12 h es tanca sola (S15 P9 neteja)»: how P9 reaches the club's build sessions. S16 (courses)
 * supplies the adapter at the composition root; `clubs.common` never imports `courses`.
 */
public interface OutlivedBuildSessionsPort {
    /** The ids of the club's open sessions started `courses.buildSessionMaxHours` or more before {@code now}, oldest first. */
    List<String> outlived(Instant now);
    /** The session, still open and outlived at {@code now}, becomes `ABANDONED`; false when it is no longer in scope. */
    boolean abandon(String sessionId, Instant now);
}
