package com.agilityhub.core.clubs.scheduling.application.ports;

import java.util.Locale;
import java.util.Optional;

/**
 * S06 `ClassSession.placementId` / `TemplateClass.placementId` and S16 R-16-13 (T-16-20): the ring of a placement of the current
 * club, so that a class plans only a placement of its own ring, and the course a member's class plans (screen 07, E117 point 3). S16
 * (courses) supplies the adapter at the composition root; the default knows no placement.
 */
public interface CoursePlacementPort {
    /** The ring of the club's placement {@code placementId}, or empty when the club has no such placement. */
    Optional<String> ringOf(String placementId);
    /** R-16-13 «Recorregut previst»: a class's planned placement and the name of its course in the reader's language. */
    record Planned(String placementId, String courseName) { }
    /** The club's placement {@code placementId} with its live course's name in {@code locale}; empty when either is gone. */
    default Optional<Planned> planned(String placementId, Locale locale) { return Optional.empty(); }
}
