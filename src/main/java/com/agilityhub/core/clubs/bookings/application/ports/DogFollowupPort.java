package com.agilityhub.core.clubs.bookings.application.ports;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the attendance sheet (21/D12) and the student card (22/D13) read from S10's follow-up side (`clubs.followup`),
 * which depends on bookings and never the other way round. E6-T03 implements it; the default has no tasks, notes or
 * observations. Only called with the TASKS module on (S10 §9).
 */
public interface DogFollowupPort {
    record NoteAttachment(String id, String name, String mimeType, String url) { }
    record Note(String text, Instant updatedAt, List<NoteAttachment> attachments) { }
    record LatestTask(String id, String text, Instant createdAt, String createdByName) { }
    record Tasks(int pendingCount, int doneCount, LatestTask latest) { }
    record Observations(String text, Instant updatedAt, String updatedByName, List<NoteAttachment> attachments, long version) { }

    /** False for the default until E6-T03 implements the port: callers then leave the TASKS fields out, as with TASKS off. */
    default boolean available() { return true; }
    /** `pendingTasksCount` per dog of the sheet rows (R-10-02). */
    Map<String, Integer> pendingTasks(Collection<String> dogIds);
    /** «Notes als instructors (de l'alumne)»: `Dog.instructorNote` with its INSTRUCTOR_NOTE attachments. */
    Optional<Note> instructorNote(String dogId);
    Tasks tasks(String dogId);
    /** «Observacions (privades)»: `Dog.remarks` with its DOG_OBSERVATIONS attachments; never on `/me/*` (R-10-12). */
    Optional<Observations> observations(String dogId);
}
