package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.followup.domain.TaskState;
import com.agilityhub.core.clubs.followup.persistence.AttachmentRepository;
import com.agilityhub.core.clubs.followup.persistence.TaskRepository;
import com.agilityhub.core.shared.application.FollowupCensusAccess;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * What the attendance sheet (21/D12 `pendingTasksCount`) and the student card (22/D13, its three TASKS blocks) read from
 * follow-up (S10 §6, R-10-02, R-10-12). `clubs.bookings` declares the port it reads through; the composition root adapts
 * this query to it, because `clubs.followup` → `clubs.bookings` would close a context cycle (bookings → census → followup).
 * Staff only: `observations` never reaches a member-facing response.
 */
@Service
public class FollowupCardQuery {
    public record CardAttachment(String id, String name, String mimeType, String url) { }
    public record Note(String text, Instant updatedAt, List<CardAttachment> attachments) { }
    public record Latest(String id, String text, Instant createdAt, String createdByName) { }
    public record Tasks(int pendingCount, int doneCount, Latest latest) { }
    public record Observations(String text, Instant updatedAt, String updatedByName, List<CardAttachment> attachments, long version) { }
    private final TaskRepository tasks; private final AttachmentRepository attachments; private final AttachmentService files; private final FollowupCensusAccess census;
    public FollowupCardQuery(TaskRepository tasks, AttachmentRepository attachments, AttachmentService files, FollowupCensusAccess census) {
        this.tasks = tasks; this.attachments = attachments; this.files = files; this.census = census;
    }

    /** Live PENDING tasks per dog, one aggregation (index `{clubId, dogId, deletedAt, state, createdAt}`); dogs without any are 0. */
    public Map<String, Integer> pendingTasks(Collection<String> dogIds) {
        var counts = tasks.pendingCounts(dogIds); var result = new java.util.HashMap<String, Integer>();
        dogIds.forEach(id -> result.put(id, counts.getOrDefault(id, 0)));
        return result;
    }
    /** «Notes als instructors (de l'alumne)»: the member's note with its INSTRUCTOR_NOTE attachments (text null without a note). */
    public Optional<Note> instructorNote(String dogId) {
        return census.instructorNote(dogId).map(note -> new Note(note.text(), note.updatedAt(), attachments("INSTRUCTOR_NOTE", dogId)));
    }
    /** «Tasques»: live pending and done counts and the latest live task. */
    public Tasks tasks(String dogId) {
        var counts = tasks.counts(dogId);
        var latest = tasks.latest(dogId).map(task -> new Latest(task.id(), task.text(), task.createdAt(), task.createdBy() == null ? null : task.createdBy().displayName()));
        return new Tasks(counts.get(TaskState.PENDING), counts.get(TaskState.DONE), latest.orElse(null));
    }
    /** «Observacions (privades)»: `Dog.remarks`, its meta and DOG_OBSERVATIONS attachments, and the version `PUT …/observations` sends back. */
    public Optional<Observations> observations(String dogId) {
        return census.remarks(dogId).map(remarks -> new Observations(remarks.text(), remarks.updatedAt(), remarks.updatedByName(),
                attachments("DOG_OBSERVATIONS", dogId), remarks.version()));
    }
    private List<CardAttachment> attachments(String type, String dogId) {
        return attachments.forEntity(type, dogId).stream().map(a -> new CardAttachment(a.id(), a.name(), a.mimeType(), files.url(a.fileKey(), a.name()))).toList();
    }
}
