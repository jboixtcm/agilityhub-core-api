package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.followup.domain.TaskState;
import com.agilityhub.core.clubs.followup.persistence.Task;
import com.agilityhub.core.clubs.followup.persistence.TaskRepository;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.FollowupCensusAccess;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * S10 §8 recipients (E6-T03 step 8, through the S11 engine since E7-T02): N-20 is about the task's owner only (never the
 * family group), N-21/N-22 about the dog and its owner for every active instructor (the engine's INSTRUCTORS default, no
 * class), nothing for a deleted task, an emptied note or a missing dog, and never without TASKS (the catalog's guard). The
 * channels and addresses are the engine's (R-11-03: T-11-02, T-11-03).
 */
class FollowupNotificationsTest {
    static final Instant NOW = Instant.parse("2026-08-12T16:00:00Z");
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final FollowupCensusAccess census = mock(FollowupCensusAccess.class);
    private final FollowupNotificationFacts facts = new FollowupNotificationFacts(tasks, census);

    private static Task task(String id, Instant deletedAt, Task.Actor createdBy) {
        return new Task(id, "club-a", "dog-a", "member-a", "Practiqueu el balancí", TaskState.PENDING, createdBy, null, null, null, NOW, NOW, null, deletedAt, 0, 0L);
    }
    private static NotificationTrigger event(String type, String aggregateId, Map<String, Object> payload) {
        return new NotificationTrigger("event-1", type, "club-a", "Task", aggregateId, NOW, payload, "account-x", null, DomainEvent.Origin.BACKOFFICE);
    }

    @Test void T_10_15_n20GoesToTheOwnerByAppAndByEmailAtTheContactAddress() {
        when(tasks.findById("t1")).thenReturn(Optional.of(task("t1", null, new Task.Actor("account-i", com.agilityhub.core.clubs.followup.domain.AuthorRole.INSTRUCTOR, "Estel"))));
        when(census.dog("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Dog("dog-a", "Duna", "ACTIVE", "member-a", "C")));
        var n20 = facts.facts(event("TaskCreated", "t1", Map.of("taskId", "t1", "memberId", "member-a")), "N-20").orElseThrow();
        assertThat(n20.members()).singleElement().satisfies(subject -> {
            assertThat(subject.memberId()).isEqualTo("member-a"); assertThat(subject.dogId()).isEqualTo("dog-a");
            assertThat(subject.values()).containsEntry("dog_name", "Duna");
            assertThat(subject.subject().taskId()).isEqualTo("t1");
        });
        assertThat(n20.values()).containsEntry("instructor_name", "Estel").containsEntry("task_excerpt", "Practiqueu el balancí").containsEntry("entityId", "dog-a");
        // The event's member wins over the task's (the owner at creation); no dog and no creator: empty names.
        when(tasks.findById("t2")).thenReturn(Optional.of(task("t2", null, null)));
        when(census.dog("dog-a")).thenReturn(Optional.empty());
        var bare = facts.facts(event("TaskCreated", "t2", Map.of("taskId", "t2")), "N-20").orElseThrow();
        assertThat(bare.members()).singleElement().satisfies(subject -> assertThat(subject.values()).containsEntry("dog_name", ""));
        assertThat(bare.values()).containsEntry("instructor_name", "");
    }

    @Test void T_10_33_nothingIsQueuedWithTasksOffForADeletedTaskOrWithoutAMember() {
        when(tasks.findById("gone")).thenReturn(Optional.empty());
        when(tasks.findById("deleted")).thenReturn(Optional.of(task("deleted", NOW, null)));
        assertThat(facts.facts(event("TaskCreated", "gone", Map.of()), "N-20")).isEmpty();
        assertThat(facts.facts(event("TaskCreated", "deleted", Map.of()), "N-20")).isEmpty();
        assertThat(facts.facts(event("TaskCompleted", "gone", Map.of()), "N-21")).isEmpty();
        when(census.instructorNote("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Note("   ", NOW, "account-m")));
        when(census.dog("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Dog("dog-a", "Duna", "ACTIVE", "member-a", "C")));
        assertThat(facts.facts(event("MemberNoteChanged", "dog-a", Map.of("dogId", "dog-a")), "N-22")).as("blank note").isEmpty();
        when(census.instructorNote("dog-b")).thenReturn(Optional.empty());
        assertThat(facts.facts(event("MemberNoteChanged", "dog-b", Map.of("dogId", "dog-b")), "N-22")).as("no note").isEmpty();
        when(census.instructorNote("dog-c")).thenReturn(Optional.of(new FollowupCensusAccess.Note(null, NOW, null)));
        when(census.dog("dog-c")).thenReturn(Optional.of(new FollowupCensusAccess.Dog("dog-c", "Nit", "ACTIVE", "member-a", null)));
        assertThat(facts.facts(event("MemberNoteChanged", "dog-c", Map.of("dogId", "dog-c")), "N-22")).as("null text").isEmpty();
        when(census.instructorNote("dog-d")).thenReturn(Optional.of(new FollowupCensusAccess.Note("Text", NOW, null)));
        when(census.dog("dog-d")).thenReturn(Optional.empty());
        assertThat(facts.facts(event("MemberNoteChanged", "dog-d", Map.of("dogId", "dog-d")), "N-22")).as("no dog").isEmpty();
        // TASKS off: the engine never asks (the catalog's module guard of the three codes, R-11-17).
        for (String code : List.of("N-20", "N-21", "N-22")) {
            assertThat(NotificationCatalog.byCode(code).orElseThrow().moduleGuards()).as(code).containsExactly(Module.TASKS);
        }
        assertThat(facts.facts(event("TaskUpdated", "t1", Map.of()), "N-20")).isEmpty();
    }

    @Test void T_10_15_T_10_18_n21AndN22GoToEachActiveInstructorWithAnAccountOnce() {
        when(tasks.findById("t1")).thenReturn(Optional.of(task("t1", null, null)));
        when(census.dog("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Dog("dog-a", "Duna", "ACTIVE", "member-a", "C")));
        when(census.members(List.of("member-a"))).thenReturn(Map.of("member-a",
                new FollowupCensusAccess.Member("member-a", "Laura", "Laura Example", null, "account-m", null, "ca")));
        var n21 = facts.facts(event("TaskCompleted", "t1", Map.of("taskId", "t1")), "N-21").orElseThrow();
        // No MEMBER notice and no class: the engine's INSTRUCTORS default is every active instructor (R-11-02).
        assertThat(n21.members()).isEmpty();
        assertThat(n21.instructors()).isNull();
        assertThat(n21.values()).containsEntry("member_name", "Laura Example").containsEntry("gender", "OTHER").containsEntry("dog_name", "Duna")
                .containsEntry("task_excerpt", "Practiqueu el balancí");
        assertThat(n21.subject().dogId()).isEqualTo("dog-a");
        assertThat(n21.subject().taskId()).isEqualTo("t1");
        when(census.instructorNote("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Note("A veure si treballem el doble", NOW, "account-m")));
        when(census.members(anyCollection())).thenReturn(Map.of());
        var n22 = facts.facts(event("MemberNoteChanged", "dog-a", Map.of("dogId", "dog-a")), "N-22").orElseThrow();
        assertThat(n22.values()).containsEntry("member_name", "").doesNotContainKey("task_excerpt");
        assertThat(facts.eventTypes()).containsExactlyInAnyOrder("TaskCreated", "TaskCompleted", "MemberNoteChanged");
    }
}
