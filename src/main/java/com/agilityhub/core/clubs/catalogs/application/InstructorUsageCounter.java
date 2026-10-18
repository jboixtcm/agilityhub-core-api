package com.agilityhub.core.clubs.catalogs.application;

public interface InstructorUsageCounter {
    record Usage(long futureClassSessions, long templateClasses) {
        public java.util.Map<String, Object> details() {
            return java.util.Map.of("futureClassSessions", futureClassSessions, "templateClasses", templateClasses);
        }
    }
    Usage usage(String instructorId);
    /** E11-T10 (INC-38): {@link #usage(String)} of every id, keyed by id, in a fixed number of reads. */
    java.util.Map<String, Usage> usage(java.util.Collection<String> instructorIds);
    boolean hasReferences(String instructorId);
}
