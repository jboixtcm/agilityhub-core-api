package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.dashboard.application.DashboardQuery;
import com.agilityhub.core.clubs.followup.domain.FollowupEvent;
import com.agilityhub.core.clubs.followup.domain.FollowupRules;
import com.agilityhub.core.clubs.followup.domain.TaskState;
import com.agilityhub.core.clubs.followup.persistence.FollowupItemRepository;
import com.agilityhub.core.clubs.followup.persistence.Task;
import com.agilityhub.core.clubs.followup.persistence.TaskRepository;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.FollowupCensusAccess;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S10 R-10-10 and §5, the task lifecycle (E6-T03). Every change writes the task, its D14 row and the outbox event in one
 * Mongo transaction: `TaskCreated` (→ N-20 to the owner), `TaskUpdated`, `TaskDeleted`, `TaskCompleted` (→ N-21 to every
 * active instructor) and `TaskReopened` (CATALEG_ESDEVENIMENTS Annex A). The callers' guards ({@link FollowupContractAccess})
 * have checked the tenant, the role, the module and the task's visibility; the states are checked here.
 */
@Service
public class TaskService {
    public static final int MAX_TEXT = 2000;
    private final TaskRepository tasks; private final FollowupItemRepository items; private final AttachmentService attachments;
    private final FollowupCensusAccess census; private final FollowupEvents events; private final AuditWriter audit; private final DashboardQuery dashboard;
    private final Clock clock;
    public TaskService(TaskRepository tasks, FollowupItemRepository items, AttachmentService attachments, FollowupCensusAccess census, FollowupEvents events,
            AuditWriter audit, DashboardQuery dashboard, Clock clock) {
        this.tasks = tasks; this.items = items; this.attachments = attachments; this.census = census; this.events = events; this.audit = audit;
        this.dashboard = dashboard; this.clock = clock;
    }

    /**
     * «＋ Afegir»: the dog must be ACTIVE (422 DOG_NOT_ACTIVE) and the text 1–2000 characters; `memberId` is the dog's owner.
     * The TASK uploads named by `attachmentIds` (their file keys) are registered in the same transaction, so a refused one
     * (limit, another purpose, another uploader) leaves no task at all.
     */
    @Transactional
    public Task create(String dogId, String text, List<String> attachmentIds, Task.Actor by) {
        text(text);
        var dog = census.dog(dogId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!"ACTIVE".equals(dog.status())) { throw new ApiException(ErrorCode.DOG_NOT_ACTIVE); }
        var now = clock.instant();
        var task = tasks.insert(new Task(UUID.randomUUID().toString(), TenantContext.require(), dogId, dog.memberId(), text, TaskState.PENDING, by, null, null, null,
                now, now, null, null, 0, 0L));
        for (String key : new LinkedHashSet<>(attachmentIds == null ? List.<String>of() : attachmentIds)) { attachments.add("TASK", task.id(), key, null); }
        items.task(task.id(), dogId, dog.memberId(), by.accountId(), by.role(), by.displayName(), by.gender(), FollowupRules.excerpt(text), now);
        events.created(task, by);
        dashboard.invalidateCountersAfterCommit(task.clubId());
        return tasks.findById(task.id()).orElseThrow();
    }

    /** `PATCH`: a new text with the version the caller read (409 STALE_VERSION); refreshes the D14 excerpt, no notification. */
    @Transactional
    public Task updateText(Task task, String text, long version, Task.Actor by) {
        text(text);
        if (task.version() == null || task.version() != version) { throw new ApiException(ErrorCode.STALE_VERSION); }
        if (task.text().equals(text)) { return task; }
        var now = clock.instant();
        var updated = tasks.updateText(task.id(), version, text, by, now).orElseThrow(() -> gone(task.id(), ErrorCode.STALE_VERSION));
        items.excerpt(task.id(), FollowupRules.excerpt(text), now);
        events.task(FollowupEvent.Kind.TaskUpdated, updated, by);
        return updated;
    }

    /** `DELETE` (also a DONE task): `deletedAt` + `deletedBy`, the D14 row hidden (BR-12, nothing is removed). */
    @Transactional
    public void delete(Task task, Task.Actor by) {
        var now = clock.instant();
        var deleted = tasks.delete(task.id(), by, now).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        items.hide(task.id(), now);
        events.task(FollowupEvent.Kind.TaskDeleted, deleted, by);
        dashboard.invalidateCountersAfterCommit(task.clubId());
    }

    /**
     * PENDING → DONE by the owner (13, also impersonated: audited under DOG_UPDATED, origin BACKOFFICE) or by staff (26):
     * `doneAt`, `doneBy` and the D14 `completedAt`, never its `activityAt`. Already DONE → 422 TASK_ALREADY_DONE; a
     * concurrent completion finds it DONE on its retry, so there is one `TaskCompleted` and one N-21 (T-10-25). The event
     * and the audit entry name the dog's owner as the census holds it now, not `Task.memberId` (R-10-10, E64).
     */
    @Transactional
    public Task complete(Task task, Task.Actor by) {
        if (task.state() == TaskState.DONE) { throw new ApiException(ErrorCode.TASK_ALREADY_DONE); }
        var now = clock.instant();
        var done = tasks.complete(task.id(), by, now).orElseThrow(() -> gone(task.id(), ErrorCode.TASK_ALREADY_DONE));
        String owner = census.dog(task.dogId()).map(FollowupCensusAccess.Dog::memberId).orElse(task.memberId());
        items.completed(task.id(), now, now);
        events.completed(done, owner, by);
        var user = CurrentUser.current();
        if (user != null && user.impersonation() != null) {
            audit.write(new AuditCommand(AuditAction.DOG_UPDATED, "Task", task.id(), owner, auditView(task), auditView(done), null));
        }
        return done;
    }

    /** DONE → PENDING by INSTRUCTOR/ADMIN (§13-12): clears `doneAt`, `doneBy` and `completedAt`; not DONE → 422 TASK_NOT_DONE. */
    @Transactional
    public Task reopen(Task task, Task.Actor by) {
        if (task.state() != TaskState.DONE) { throw new ApiException(ErrorCode.TASK_NOT_DONE); }
        var now = clock.instant();
        var reopened = tasks.reopen(task.id(), by, now).orElseThrow(() -> gone(task.id(), ErrorCode.TASK_NOT_DONE));
        items.completed(task.id(), null, now);
        events.task(FollowupEvent.Kind.TaskReopened, reopened, by);
        return reopened;
    }

    /** `GET /tasks`: a dog's tasks, newest first; `state` wins over `includeDone` (default true, S10 §6). */
    public List<Task> list(String dogId, TaskState state, boolean includeDone, boolean includeDeleted, int page, int size) {
        var states = state != null ? EnumSet.of(state) : includeDone ? EnumSet.allOf(TaskState.class) : EnumSet.of(TaskState.PENDING);
        return tasks.forDog(dogId, states, includeDeleted, page, size);
    }

    /** The concurrent writer won: the task was deleted meanwhile (404), or its state/version moved on ({@code conflict}). */
    private ApiException gone(String id, ErrorCode conflict) {
        return new ApiException(tasks.findById(id).filter(current -> current.deletedAt() == null).isPresent() ? conflict : ErrorCode.NOT_FOUND);
    }
    private static void text(String text) {
        if (text == null || text.isBlank() || text.length() > MAX_TEXT) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("fieldErrors", List.of(Map.of("field", "text", "code", "INVALID_VALUE"))));
        }
    }
    private static Map<String, Object> auditView(Task task) {
        var view = new LinkedHashMap<String, Object>();
        view.put("dogId", task.dogId()); view.put("state", task.state()); view.put("doneAt", task.doneAt());
        view.put("doneBy", task.doneBy() == null ? null : Map.of("role", task.doneBy().role().name(), "displayName", String.valueOf(task.doneBy().displayName())));
        return view;
    }
}
