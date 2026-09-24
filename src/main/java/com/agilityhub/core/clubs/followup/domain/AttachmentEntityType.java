package com.agilityhub.core.clubs.followup.domain;

/**
 * S10 §3 polymorphic owners of an `Attachment`: TASK (taskId), INSTRUCTOR_NOTE (dogId, the member's note) and
 * DOG_OBSERVATIONS (dogId, never readable by the member, R-10-11). All three need TASKS.
 */
public enum AttachmentEntityType {
    TASK, INSTRUCTOR_NOTE, DOG_OBSERVATIONS;
    /** Written only by INSTRUCTOR/ADMIN (R-10-11). */
    public boolean staffOnly() { return this != INSTRUCTOR_NOTE; }
    /** The dog's owner (also impersonated) may read it. */
    public boolean memberReadable() { return this != DOG_OBSERVATIONS; }
}
