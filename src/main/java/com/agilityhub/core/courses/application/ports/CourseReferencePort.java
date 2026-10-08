package com.agilityhub.core.courses.application.ports;

/** Read-only tenant reference checks; other club contexts never become a dependency of courses. */
public interface CourseReferencePort {
    boolean activityExists(String id);
    boolean dogExists(String id);
}
