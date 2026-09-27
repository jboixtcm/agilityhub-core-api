package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.followup.domain.CensusForeignEvent;
import com.agilityhub.core.clubs.followup.domain.FollowupEvent;
import com.agilityhub.core.clubs.followup.domain.TaskState;
import com.agilityhub.core.clubs.followup.persistence.Task;
import com.agilityhub.core.clubs.followup.persistence.TaskRepository;
import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.FollowupCensusAccess;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * S10 §8 recipients (E6-T03 step 8): N-20 to the task's owner only (APP with an account, EMAIL at the contact address:
 * the account's own when it is the same, the applicant path otherwise), N-21/N-22 to each active instructor with an
 * account once, nothing with TASKS off, for a deleted task, a missing member or an emptied note.
 */
class FollowupNotificationsTest {
    static final Instant NOW = Instant.parse("2026-08-12T16:00:00Z");
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final FollowupCensusAccess census = mock(FollowupCensusAccess.class);
    private final PlanningCatalogAccess catalogs = mock(PlanningCatalogAccess.class);
    private final NotificationAccounts accounts = mock(NotificationAccounts.class);
    private final SystemNotificationService notifications = mock(SystemNotificationService.class);
    private final ClubConfigService configs = mock(ClubConfigService.class);
    private final FollowupNotifications service = new FollowupNotifications(tasks, census, catalogs, accounts, notifications, configs);

    @BeforeEach void club() { modules(Set.of(Module.TASKS)); }
    private void modules(Set<Module> modules) {
        var club = new ClubConfig.ClubView("club-a", "a", "Club A", List.of("ca", "es"), "ca", "Europe/Madrid", "EUR", null, null, "ACTIVE", null);
        when(configs.get("club-a")).thenReturn(new ClubConfig(club, Map.of(), modules, null, Map.of()));
    }
    private static Task task(String id, Instant deletedAt, Task.Actor createdBy) {
        return new Task(id, "club-a", "dog-a", "member-a", "Practiqueu el balancí", TaskState.PENDING, createdBy, null, null, null, NOW, NOW, null, deletedAt, 0, 0L);
    }
    private static FollowupEvent event(FollowupEvent.Kind kind, String taskId, Map<String, Object> payload) {
        return new FollowupEvent(kind, "club-a", taskId, NOW, payload, "account-x", null, DomainEvent.Origin.BACKOFFICE);
    }
    private static CensusForeignEvent note(String dogId) {
        return new CensusForeignEvent("MemberNoteChanged", "club-a", "Dog", dogId, NOW, Map.of("dogId", dogId, "memberId", "member-a"), null, null, DomainEvent.Origin.APP);
    }
    private void member(String accountId, String email) {
        when(census.members(List.of("member-a"))).thenReturn(Map.of("member-a",
                new FollowupCensusAccess.Member("member-a", "Laura", "Laura Example", "FEMALE", accountId, email, "es")));
    }

    @Test void T_10_15_n20GoesToTheOwnerByAppAndByEmailAtTheContactAddress() {
        when(tasks.findById("t1")).thenReturn(Optional.of(task("t1", null, new Task.Actor("account-i", com.agilityhub.core.clubs.followup.domain.AuthorRole.INSTRUCTOR, "Estel"))));
        when(census.dog("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Dog("dog-a", "Duna", "ACTIVE", "member-a", "C")));
        // An account whose email is the contact one: APP + the account's own email, in the account's language.
        member("account-m", "laura@example.test");
        when(accounts.find("account-m")).thenReturn(Optional.of(new NotificationAccounts.Recipient("account-m", "LAURA@example.test", "ca", null)));
        service.taskCreated("event-1", event(FollowupEvent.Kind.TaskCreated, "t1", Map.of("taskId", "t1", "memberId", "member-a")));
        verify(notifications).appOnce(eq("event-1:member-a:app"), eq("N-20"), eq("account-m"), argThat(v -> v.get("dog_name").equals("Duna")
                && v.get("instructor_name").equals("Estel") && v.get("task_excerpt").equals("Practiqueu el balancí") && v.get("action").equals("OPEN_TASKS")
                && v.get("entityId").equals("dog-a") && v.get("club_name").equals("Club A")));
        verify(notifications).sendOnceLocalized(eq("event-1:member-a:email"), eq("N-20"), eq("account-m"), eq("ca"), anyMap());
        // Another contact email: the applicant path, in the account's language.
        member("account-m", "other@example.test");
        service.taskCreated("event-2", event(FollowupEvent.Kind.TaskCreated, "t1", Map.of("taskId", "t1", "memberId", "member-a")));
        verify(notifications).sendApplicantOnce(eq("event-2:member-a:email"), eq("N-20"), eq("other@example.test"), eq("ca"), anyMap());
        // No account: email only, in the signup's language; no dog and no creator: empty names.
        when(tasks.findById("t2")).thenReturn(Optional.of(task("t2", null, null)));
        when(census.dog("dog-a")).thenReturn(Optional.empty());
        member(null, "laura@example.test");
        service.taskCreated("event-3", event(FollowupEvent.Kind.TaskCreated, "t2", Map.of("taskId", "t2")));
        verify(notifications).sendApplicantOnce(eq("event-3:member-a:email"), eq("N-20"), eq("laura@example.test"), eq("es"),
                argThat(v -> v.get("dog_name").equals("") && v.get("instructor_name").equals("")));
        // An account and no email: APP only.
        member("account-m", null);
        service.taskCreated("event-4", event(FollowupEvent.Kind.TaskCreated, "t2", Map.of("taskId", "t2")));
        verify(notifications).appOnce(eq("event-4:member-a:app"), eq("N-20"), eq("account-m"), anyMap());
        verify(notifications, never()).sendOnceLocalized(eq("event-4:member-a:email"), any(), any(), any(), any());
        verify(notifications, never()).sendApplicantOnce(eq("event-4:member-a:email"), any(), any(), any(), anyMap());
        // An account that no longer exists and no email: nothing.
        when(accounts.find("account-gone")).thenReturn(Optional.empty());
        member("account-gone", null);
        service.taskCreated("event-5", event(FollowupEvent.Kind.TaskCreated, "t2", Map.of("taskId", "t2")));
        verify(notifications, never()).appOnce(startsWith("event-5"), any(), any(), any());
    }

    @Test void T_10_33_nothingIsQueuedWithTasksOffForADeletedTaskOrWithoutAMember() {
        when(tasks.findById("gone")).thenReturn(Optional.empty());
        when(tasks.findById("deleted")).thenReturn(Optional.of(task("deleted", NOW, null)));
        when(tasks.findById("t1")).thenReturn(Optional.of(task("t1", null, null)));
        when(census.members(anyCollection())).thenReturn(Map.of());
        service.taskCreated("e1", event(FollowupEvent.Kind.TaskCreated, "gone", Map.of()));
        service.taskCreated("e2", event(FollowupEvent.Kind.TaskCreated, "deleted", Map.of()));
        service.taskCreated("e3", event(FollowupEvent.Kind.TaskCreated, "t1", Map.of("memberId", "member-a")));
        service.taskCompleted("e4", event(FollowupEvent.Kind.TaskCompleted, "gone", Map.of()));
        when(census.instructorNote("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Note("   ", NOW, "account-m")));
        when(census.dog("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Dog("dog-a", "Duna", "ACTIVE", "member-a", "C")));
        service.memberNote("e5", note("dog-a"));
        when(census.instructorNote("dog-b")).thenReturn(Optional.empty());
        service.memberNote("e6", note("dog-b"));
        when(census.instructorNote("dog-c")).thenReturn(Optional.of(new FollowupCensusAccess.Note(null, NOW, null)));
        when(census.dog("dog-c")).thenReturn(Optional.of(new FollowupCensusAccess.Dog("dog-c", "Nit", "ACTIVE", "member-a", null)));
        service.memberNote("e7", note("dog-c"));
        when(census.instructorNote("dog-d")).thenReturn(Optional.of(new FollowupCensusAccess.Note("Text", NOW, null)));
        when(census.dog("dog-d")).thenReturn(Optional.empty());
        service.memberNote("e8", note("dog-d"));
        modules(Set.of(Module.WAITLIST));
        when(tasks.findById("t1")).thenReturn(Optional.of(task("t1", null, null)));
        service.taskCreated("e9", event(FollowupEvent.Kind.TaskCreated, "t1", Map.of()));
        service.taskCompleted("e10", event(FollowupEvent.Kind.TaskCompleted, "t1", Map.of()));
        when(census.instructorNote("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Note("Text", NOW, null)));
        service.memberNote("e11", note("dog-a"));
        verifyNoInteractions(notifications);
    }

    @Test void T_10_15_T_10_18_n21AndN22GoToEachActiveInstructorWithAnAccountOnce() {
        when(catalogs.instructorRefs()).thenReturn(List.of(new PlanningCatalogAccess.InstructorRef("i1", "Estel", "m-estel", true),
                new PlanningCatalogAccess.InstructorRef("i2", "Estel bis", "m-estel", true), new PlanningCatalogAccess.InstructorRef("i3", "Marc", "m-marc", true),
                new PlanningCatalogAccess.InstructorRef("i4", "Vell", "m-old", false), new PlanningCatalogAccess.InstructorRef("i5", "Sense", null, true),
                new PlanningCatalogAccess.InstructorRef("i6", "Núria", "m-nuria", true), new PlanningCatalogAccess.InstructorRef("i7", "Sense compte", "m-none", true)));
        when(census.members(List.of("m-estel", "m-marc", "m-nuria", "m-none"))).thenReturn(Map.of(
                "m-estel", new FollowupCensusAccess.Member("m-estel", "Estel", "Estel Example", "FEMALE", "a-estel", null, "ca"),
                "m-marc", new FollowupCensusAccess.Member("m-marc", "Marc", "Marc Example", "MALE", "a-marc", null, "es"),
                "m-nuria", new FollowupCensusAccess.Member("m-nuria", "Núria", "Núria Example", "FEMALE", "a-gone", null, "en"),
                "m-none", new FollowupCensusAccess.Member("m-none", "Sense", "Sense Compte", null, null, null, "ca")));
        when(accounts.find("a-estel")).thenReturn(Optional.of(new NotificationAccounts.Recipient("a-estel", "estel@example.test", "ca", null)));
        when(accounts.find("a-marc")).thenReturn(Optional.of(new NotificationAccounts.Recipient("a-marc", "marc@example.test", "es", null)));
        when(accounts.find("a-gone")).thenReturn(Optional.empty());
        when(tasks.findById("t1")).thenReturn(Optional.of(task("t1", null, null)));
        when(census.dog("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Dog("dog-a", "Duna", "ACTIVE", "member-a", "C")));
        when(census.members(List.of("member-a"))).thenReturn(Map.of("member-a",
                new FollowupCensusAccess.Member("member-a", "Laura", "Laura Example", null, "account-m", null, "ca")));
        service.taskCompleted("done-1", event(FollowupEvent.Kind.TaskCompleted, "t1", Map.of("taskId", "t1")));
        for (String account : List.of("a-estel", "a-marc")) {
            verify(notifications).appOnce(eq("done-1:" + account + ":app"), eq("N-21"), eq(account), argThat(v -> v.get("member_name").equals("Laura Example")
                    && v.get("gender").equals("OTHER") && v.get("task_excerpt").equals("Practiqueu el balancí") && v.get("action").equals("OPEN_DOG")));
        }
        when(census.instructorNote("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Note("A veure si treballem el doble", NOW, "account-m")));
        when(census.members(List.of("member-a"))).thenReturn(Map.of());
        service.memberNote("note-1", note("dog-a"));
        verify(notifications).appOnce(eq("note-1:a-estel:app"), eq("N-22"), eq("a-estel"), argThat(v -> v.get("member_name").equals("") && !v.containsKey("task_excerpt")));
        verify(notifications, times(4)).appOnce(anyString(), anyString(), anyString(), anyMap());
    }
}
