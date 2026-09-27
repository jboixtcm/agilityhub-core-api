package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.shared.application.DogOwnerAccess;
import com.agilityhub.core.clubs.followup.domain.AttachmentEntityType;
import com.agilityhub.core.clubs.followup.domain.TaskState;
import com.agilityhub.core.clubs.followup.persistence.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.ModuleGuard;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** E6-T01 guards of the S10 follow-up stubs: roles per entity (R-10-11), own dogs only (§13-13), tenant 404 (T-10-22). */
class FollowupContractAccessTest {
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final AttachmentRepository attachments = mock(AttachmentRepository.class);
    private final FollowupItemRepository items = mock(FollowupItemRepository.class);
    private final DogOwnerAccess census = mock(DogOwnerAccess.class);
    private final ModuleGuard modules = mock(ModuleGuard.class);
    private final FollowupContractAccess access = new FollowupContractAccess(tasks, attachments, items, census, modules);
    private static final Instant NOW = Instant.parse("2026-08-12T16:00:00Z");
    private final FollowupContractAccess.Caller staff = new FollowupContractAccess.Caller(null, true, false);
    private final FollowupContractAccess.Caller owner = new FollowupContractAccess.Caller("member-a", false, false);
    private final FollowupContractAccess.Caller impersonated = new FollowupContractAccess.Caller("member-a", false, true);
    private final FollowupContractAccess.Caller stranger = new FollowupContractAccess.Caller("member-b", false, false);

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private void dogs() {
        when(census.ownerOf("dog-a")).thenReturn(Optional.of("member-a"));
        when(census.ownerOf("dog-x")).thenReturn(Optional.empty());
    }
    private static Task task(String id, Instant deletedAt) {
        return new Task(id, "club-a", "dog-a", "member-a", "Practiqueu el balancí", TaskState.PENDING, null, null, null, null, NOW, NOW, null, deletedAt, 0, 1L);
    }
    private static Attachment attachment(String id, String type, String entityId, Instant removedAt) {
        return new Attachment(id, "club-a", type, entityId, id, "foto.jpg", "image/jpeg", 4, "account-a", NOW, NOW, removedAt, null, 0);
    }

    @Test void T_10_22_callerIsTheMemberTheImpersonatedMemberOrUnimpersonatedStaff() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("x", null, "ROLE_INSTRUCTOR"));
        assertThat(access.caller("member-a")).isEqualTo(new FollowupContractAccess.Caller("member-a", true, false));
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("x", null, "ROLE_MEMBER"));
        assertThat(access.caller("member-a").staff()).isFalse();
        try (var user = CurrentUser.open(new CurrentUser("admin-a", "Admin", new CurrentUser.Impersonation("admin-a", "Admin", "member-c"), DomainEvent.Origin.BACKOFFICE))) {
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("x", null, "ROLE_MEMBER", "ROLE_ADMIN"));
            assertThat(access.caller(null)).isEqualTo(new FollowupContractAccess.Caller("member-c", false, true));
        }
        SecurityContextHolder.clearContext();
        assertThat(access.caller("member-a").staff()).isFalse();
        access.staffWriter(staff);
        assertThatThrownBy(() -> access.staffWriter(owner)).hasMessage("FORBIDDEN");
        assertThatThrownBy(() -> access.staffWriter(impersonated)).hasMessage("IMPERSONATION_DENIED");
        try (var tenant = TenantContext.open("club-a")) { access.tenant(); access.tasksModule(); verify(modules).require("club-a", Module.TASKS); }
    }

    @Test void T_10_15_T_10_22_membersReachOnlyTheirOwnDogsAndTasks() {
        dogs();
        access.dog(staff, "dog-a"); access.dog(owner, "dog-a"); access.dog(impersonated, "dog-a");
        assertThatThrownBy(() -> access.dog(staff, "dog-x")).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> access.dog(stranger, "dog-a")).hasMessage("DOG_NOT_ACCESSIBLE");
        assertThatThrownBy(() -> access.dog(owner, "dog-x")).hasMessage("DOG_NOT_ACCESSIBLE");
        assertThatThrownBy(() -> access.dog(new FollowupContractAccess.Caller(null, false, false), "dog-a")).hasMessage("DOG_NOT_ACCESSIBLE");
        when(tasks.findById("task-a")).thenReturn(Optional.of(task("task-a", null)));
        when(tasks.findById("task-d")).thenReturn(Optional.of(task("task-d", NOW)));
        assertThat(access.task(staff, "task-a").id()).isEqualTo("task-a"); assertThat(access.task(owner, "task-a").id()).isEqualTo("task-a");
        for (var caller : List.of(stranger, new FollowupContractAccess.Caller(null, false, false))) {
            assertThatThrownBy(() -> access.task(caller, "task-a")).hasMessage("NOT_FOUND");
        }
        assertThatThrownBy(() -> access.task(owner, "task-d")).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> access.task(staff, "task-x")).hasMessage("NOT_FOUND");
        when(items.findById("item-a")).thenReturn(Optional.of(new FollowupItem("item-a", "club-a", com.agilityhub.core.clubs.followup.domain.FollowupKind.TASK, "task-a",
                "dog-a", "member-a", "account-a", com.agilityhub.core.clubs.followup.domain.AuthorRole.INSTRUCTOR, "Estel", null, "Practiqueu", NOW, null, NOW, false, NOW)));
        when(items.findById("item-h")).thenReturn(Optional.of(new FollowupItem("item-h", "club-a", com.agilityhub.core.clubs.followup.domain.FollowupKind.TASK, "task-d",
                "dog-a", "member-a", "account-a", com.agilityhub.core.clubs.followup.domain.AuthorRole.INSTRUCTOR, "Estel", null, "Pujar", NOW, null, NOW, true, NOW)));
        access.followupItem("item-a");
        assertThatThrownBy(() -> access.followupItem("item-x")).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> access.followupItem("item-h")).as("a deleted task's hidden row").hasMessage("NOT_FOUND");
    }

    /**
     * E6-T03 step 11 (E6-T01 round-2 review #1, R-10-10): a deleted task is 404 for every caller, staff included — its GET,
     * edit, deletion, completion, reopening and attachments all go through this guard; only ADMIN's `includeDeleted` list
     * shows it. Before the fix, staff reached it.
     */
    @Test void T_10_15_aDeletedTaskIsNotFoundForStaffToo() {
        dogs();
        when(tasks.findById("task-d")).thenReturn(Optional.of(task("task-d", NOW)));
        for (var caller : List.of(staff, owner, impersonated)) { assertThatThrownBy(() -> access.task(caller, "task-d")).hasMessage("NOT_FOUND"); }
        assertThatThrownBy(() -> access.readableEntity(staff, AttachmentEntityType.TASK, "task-d")).hasMessage("NOT_FOUND");
        try (var tenant = TenantContext.open("club-a")) {
            assertThatThrownBy(() -> access.writableEntity(staff, AttachmentEntityType.TASK, "task-d")).hasMessage("NOT_FOUND");
        }
        when(attachments.findById("a-dead")).thenReturn(Optional.of(attachment("a-dead", "TASK", "task-d", null)));
        assertThatThrownBy(() -> access.removableAttachment(staff, "a-dead")).hasMessage("NOT_FOUND");
    }

    /**
     * E6-T03 round 2 (review #1, R-10-10 amended 27-09): the owner of a task is the dog's current owner as the census holds
     * it, not `Task.memberId`, which the `DogTransferred` consumer refreshes later. Here the census already says member-b
     * while the task still says member-a: member-a no longer reaches the task, its attachments or (through the same guard)
     * its completion; member-b does. Before the fix it was the other way round.
     */
    @Test void R_10_10_theOwnerOfATaskIsTheDogsCurrentOwnerNotTaskMemberId() {
        when(census.ownerOf("dog-a")).thenReturn(Optional.of("member-b"));
        when(tasks.findById("task-a")).thenReturn(Optional.of(task("task-a", null)));
        for (var previous : List.of(owner, impersonated)) {
            assertThatThrownBy(() -> access.task(previous, "task-a")).hasMessage("NOT_FOUND");
            assertThatThrownBy(() -> access.readableEntity(previous, AttachmentEntityType.TASK, "task-a")).hasMessage("NOT_FOUND");
        }
        assertThat(access.task(stranger, "task-a").id()).isEqualTo("task-a");
        access.readableEntity(stranger, AttachmentEntityType.TASK, "task-a");
        assertThat(access.task(staff, "task-a").id()).isEqualTo("task-a");
        // A dog the census does not know in this club: no member reaches its tasks.
        when(census.ownerOf("dog-a")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> access.task(stranger, "task-a")).hasMessage("NOT_FOUND");
    }

    @Test void T_10_16_attachmentRolesFollowTheirEntity() {
        dogs();
        when(tasks.findById("task-a")).thenReturn(Optional.of(task("task-a", null)));
        access.readableEntity(owner, AttachmentEntityType.TASK, "task-a");
        access.readableEntity(impersonated, AttachmentEntityType.INSTRUCTOR_NOTE, "dog-a");
        access.readableEntity(staff, AttachmentEntityType.DOG_OBSERVATIONS, "dog-a");
        assertThatThrownBy(() -> access.readableEntity(owner, AttachmentEntityType.DOG_OBSERVATIONS, "dog-a")).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> access.readableEntity(stranger, AttachmentEntityType.INSTRUCTOR_NOTE, "dog-a")).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> access.readableEntity(new FollowupContractAccess.Caller(null, false, false), AttachmentEntityType.INSTRUCTOR_NOTE, "dog-a")).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> access.readableEntity(staff, AttachmentEntityType.INSTRUCTOR_NOTE, "dog-x")).hasMessage("NOT_FOUND");
        try (var tenant = TenantContext.open("club-a")) {
            access.writableEntity(staff, AttachmentEntityType.DOG_OBSERVATIONS, "dog-a");
            assertThatThrownBy(() -> access.writableEntity(owner, AttachmentEntityType.TASK, "task-a")).hasMessage("FORBIDDEN");
            doThrow(new ApiException(ErrorCode.MODULE_DISABLED)).when(modules).require("club-a", Module.TASKS);
            assertThatThrownBy(() -> access.writableEntity(staff, AttachmentEntityType.TASK, "task-a")).hasMessage("MODULE_DISABLED");
        }
        when(attachments.findById("a-task")).thenReturn(Optional.of(attachment("a-task", "TASK", "task-a", null)));
        when(attachments.findById("a-note")).thenReturn(Optional.of(attachment("a-note", "INSTRUCTOR_NOTE", "dog-a", null)));
        when(attachments.findById("a-gone")).thenReturn(Optional.of(attachment("a-gone", "TASK", "task-a", NOW)));
        when(attachments.findById("a-odd")).thenReturn(Optional.of(attachment("a-odd", "DOG_DOCUMENT", "dog-a", null)));
        access.removableAttachment(staff, "a-task");
        access.removableAttachment(owner, "a-note"); access.removableAttachment(impersonated, "a-note");
        assertThatThrownBy(() -> access.removableAttachment(owner, "a-task")).hasMessage("FORBIDDEN");
        assertThatThrownBy(() -> access.removableAttachment(impersonated, "a-task")).hasMessage("IMPERSONATION_DENIED");
        assertThatThrownBy(() -> access.removableAttachment(staff, "a-note")).hasMessage("FORBIDDEN");
        assertThatThrownBy(() -> access.removableAttachment(stranger, "a-note")).hasMessage("NOT_FOUND");
        for (String id : List.of("a-gone", "a-odd", "a-missing")) { assertThatThrownBy(() -> access.removableAttachment(staff, id)).as(id).hasMessage("NOT_FOUND"); }
    }

    @Test void T_10_21_followupAcceptsOnlyTheDeclaredFiltersAndSort() {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("filter", "kind:eq:TASK"); params.add("filter", "unread:eq:true"); params.add("sort", "activityAt,desc");
        access.followup(params);
        var invalid = new LinkedMultiValueMap<String, String>(); invalid.add("filter", "textExcerpt:contains:balancí");
        assertThatThrownBy(() -> access.followup(invalid)).hasMessage("INVALID_FILTER");
        assertThat(FollowupContractAccess.FOLLOWUP.filters()).containsOnlyKeys("kind", "memberId", "dogId", "authorAccountId", "unread");
        assertThat(AttachmentEntityType.TASK.staffOnly()).isTrue(); assertThat(AttachmentEntityType.INSTRUCTOR_NOTE.staffOnly()).isFalse();
        assertThat(AttachmentEntityType.DOG_OBSERVATIONS.memberReadable()).isFalse(); assertThat(AttachmentEntityType.TASK.memberReadable()).isTrue();
    }
}
