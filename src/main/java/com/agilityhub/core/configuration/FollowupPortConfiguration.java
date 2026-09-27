package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.bookings.application.ports.DogFollowupPort;
import com.agilityhub.core.clubs.followup.application.FollowupCardQuery;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * E6-T03: the real {@link DogFollowupPort} of the attendance sheet and the student card, adapted from S10 follow-up's
 * {@link FollowupCardQuery} at the composition root. `clubs.followup` cannot implement the port itself: `clubs.bookings`
 * reads the census, and the census depends on `clubs.followup` for the attachments, so followup → bookings would be a
 * context cycle (`ArchitectureTest.E0_T01_contextsHaveNoCycles`). Only mapping here; the queries live in followup.
 * With TASKS off the callers never ask it (S10 §9), which leaves the fields absent.
 */
@Configuration(proxyBeanMethods = false)
public class FollowupPortConfiguration {
    @Bean DogFollowupPort dogFollowupPort(FollowupCardQuery cards) {
        return new DogFollowupPort() {
            @Override public Map<String, Integer> pendingTasks(Collection<String> dogIds) { return cards.pendingTasks(dogIds); }
            @Override public Optional<Note> instructorNote(String dogId) {
                return cards.instructorNote(dogId).map(note -> new Note(note.text(), note.updatedAt(), attachments(note.attachments())));
            }
            @Override public Tasks tasks(String dogId) {
                var tasks = cards.tasks(dogId); var latest = tasks.latest();
                return new Tasks(tasks.pendingCount(), tasks.doneCount(),
                        latest == null ? null : new LatestTask(latest.id(), latest.text(), latest.createdAt(), latest.createdByName()));
            }
            @Override public Optional<Observations> observations(String dogId) {
                return cards.observations(dogId).map(o -> new Observations(o.text(), o.updatedAt(), o.updatedByName(), attachments(o.attachments()), o.version()));
            }
        };
    }
    private static List<DogFollowupPort.NoteAttachment> attachments(List<FollowupCardQuery.CardAttachment> list) {
        return list.stream().map(a -> new DogFollowupPort.NoteAttachment(a.id(), a.name(), a.mimeType(), a.url())).toList();
    }
}
