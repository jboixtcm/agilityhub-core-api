package com.agilityhub.core.clubs.catalogs.application;

public interface InstructorUsageCounter {
    record Usage(long futureClassSessions, long templateClasses) {
        public java.util.Map<String, Object> details() {
            return java.util.Map.of("futureClassSessions", futureClassSessions, "templateClasses", templateClasses);
        }
    }
    Usage usage(String instructorId);
    boolean hasReferences(String instructorId);
}
