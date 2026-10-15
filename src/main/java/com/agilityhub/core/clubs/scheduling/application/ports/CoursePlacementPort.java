package com.agilityhub.core.clubs.scheduling.application.ports;

import java.util.Optional;

/**
 * S06 `ClassSession.placementId` / `TemplateClass.placementId` and S16 R-16-13 (T-16-20): the ring of a placement of the current
 * club, so that a class plans only a placement of its own ring. S16 (courses) supplies the adapter at the composition root; the
 * default knows no placement.
 */
public interface CoursePlacementPort {
    /** The ring of the club's placement {@code placementId}, or empty when the club has no such placement. */
    Optional<String> ringOf(String placementId);
}
