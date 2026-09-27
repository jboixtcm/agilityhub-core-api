package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.followup.domain.FollowupRules;
import com.agilityhub.core.clubs.followup.persistence.TaskRepository;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFacts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationSubject;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.shared.application.FollowupCensusAccess;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * S10 notices explained to the S11 engine (E7-T02; replaces `FollowupNotifications`, rules unchanged; the TASKS module guard
 * is the catalog's): N-20 `TaskCreated` → the dog's owner (never the family group), action OPEN_TASKS; N-21 `TaskCompleted`
 * (whoever completed it) and N-22 `MemberNoteChanged` (a note with text: an emptied note has nothing to read) → every
 * active instructor of the club, action OPEN_DOG, with the owner's `member_name` and `gender` («alumne/alumna», S10 §10).
 * Nothing for a deleted task. N-20 goes to the event's `memberId` only while the census still holds them as the dog's
 * owner, with the event's `textExcerpt` (the text of the creation), never the task's current text: a delivery after a
 * transfer sends nothing, the task follows the dog and its new owner finds it on 13 (R-10-10, E64). The census is read
 * through `FollowupCensusAccess`.
 */
@Service
public class FollowupNotificationFacts implements NotificationFactsPort {
    private static final Set<String> TYPES = Set.of("TaskCreated", "TaskCompleted", "MemberNoteChanged");
    private final TaskRepository tasks; private final FollowupCensusAccess census;

    public FollowupNotificationFacts(TaskRepository tasks, FollowupCensusAccess census) { this.tasks = tasks; this.census = census; }

    @Override public Set<String> eventTypes() { return TYPES; }

    @Override public Optional<NotificationFacts> facts(NotificationTrigger trigger, String code) {
        return switch (trigger.type()) {
            case "TaskCreated" -> tasks.findById(trigger.aggregateId()).filter(task -> task.deletedAt() == null).flatMap(task -> {
                String memberId = Objects.requireNonNullElse(trigger.text("memberId"), task.memberId()), excerpt = trigger.text("textExcerpt");
                var dog = census.dog(task.dogId()).orElse(null);
                if (dog == null || !Objects.equals(memberId, dog.memberId()) || excerpt == null) { return Optional.empty(); }
                return Optional.of(NotificationFacts.builder().subject(NotificationSubject.task(task.id()).withDog(task.dogId()))
                        .member(new NotificationFacts.MemberSubject(memberId, task.dogId(), Map.of("dog_name", dog.name()),
                                NotificationSubject.task(task.id()).withDog(task.dogId())))
                        .value("instructor_name", task.createdBy() == null ? "" : task.createdBy().displayName())
                        .value("task_excerpt", excerpt).value("entityId", task.dogId()).build());
            });
            case "TaskCompleted" -> tasks.findById(trigger.aggregateId())
                    .map(task -> owner(task.dogId(), Objects.requireNonNullElse(trigger.text("memberId"), task.memberId()))
                            .value("task_excerpt", FollowupRules.excerpt(task.text())).subject(NotificationSubject.task(task.id())).build());
            case "MemberNoteChanged" -> {
                String dogId = trigger.text("dogId");
                var note = dogId == null ? null : census.instructorNote(dogId).orElse(null);
                var dog = dogId == null ? null : census.dog(dogId).orElse(null);
                if (note == null || dog == null || note.text() == null || note.text().isBlank()) { yield Optional.empty(); }
                yield Optional.of(owner(dogId, dog.memberId()).build());
            }
            default -> Optional.empty();
        };
    }

    /** `member_name` (full name), `dog_name` and the owner's `gender` for the instructors' copy; the subject is the dog. */
    private NotificationFacts.Builder owner(String dogId, String memberId) {
        var dog = census.dog(dogId).orElse(null);
        var member = memberId == null ? null : census.members(List.of(memberId)).get(memberId);
        return NotificationFacts.builder().noMembers().subject(NotificationSubject.dog(dogId).with(NotificationSubject.member(memberId)))
                .value("dog_name", dog == null ? "" : dog.name()).value("member_name", member == null ? "" : member.fullName())
                .value("gender", member == null || member.gender() == null ? "OTHER" : member.gender()).value("entityId", dogId);
    }
}
