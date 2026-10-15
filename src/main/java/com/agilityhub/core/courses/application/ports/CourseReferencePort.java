package com.agilityhub.core.courses.application.ports;

import java.util.List;

/** Read-only tenant reference checks; other club contexts never become a dependency of courses. */
public interface CourseReferencePort {
    boolean activityExists(String id);
    boolean dogExists(String id);
    /** S16 §3 `Placement.classSessionIds[]` (read only): the club's classes (S06 `ClassSession.placementId`) that plan the placement. */
    List<String> classSessionIds(String placementId);
}
