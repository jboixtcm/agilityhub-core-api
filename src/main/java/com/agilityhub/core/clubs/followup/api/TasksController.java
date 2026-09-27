package com.agilityhub.core.clubs.followup.api;

import com.agilityhub.core.clubs.followup.application.AttachmentService;
import com.agilityhub.core.clubs.followup.application.FollowupActors;
import com.agilityhub.core.clubs.followup.application.FollowupContractAccess;
import com.agilityhub.core.clubs.followup.application.FollowupTransactions;
import com.agilityhub.core.clubs.followup.application.TaskService;
import com.agilityhub.core.clubs.followup.domain.TaskState;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.AllowsImpersonation;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.domain.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.followup.api.FollowupContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S10 WP-10-C tasks (26, 13 «Veure l'historial», R-10-10), all under TASKS (E6-T03). Every operation runs the tenant,
 * role, module and task guards of {@link FollowupContractAccess} and then {@link TaskService}. Only `GET /tasks` and the
 * completion accept the impersonation token (as the member); the rest answer IMPERSONATION_DENIED (E6ContractConfiguration).
 */
@RestController
@RequiresModule(Module.TASKS)
public class TasksController {
    static final String STAFF = "hasAnyRole('ADMIN','INSTRUCTOR')";
    static final String GUARDS = " Requires TASKS (MODULE_DISABLED). Tenant comes from the JWT.";
    private final FollowupContractAccess access; private final TaskService tasks; private final FollowupActors actors; private final AttachmentService attachments;
    private final FollowupTransactions transactions;
    public TasksController(FollowupContractAccess access, TaskService tasks, FollowupActors actors, AttachmentService attachments, FollowupTransactions transactions) {
        this.access = access; this.tasks = tasks; this.actors = actors; this.attachments = attachments; this.transactions = transactions;
    }
    private FollowupContractAccess.Caller caller(Jwt jwt) { return access.caller(jwt.getClaimAsString("memberId")); }
    private com.agilityhub.core.clubs.followup.persistence.Task.Actor actor(FollowupContractAccess.Caller caller, Jwt jwt) {
        return actors.actor(caller, jwt.getClaimAsString("memberId"), jwt.getClaimAsString("instructorId"));
    }

    @GetMapping("/api/v1/tasks")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, MODULE_DISABLED, DOG_NOT_ACCESSIBLE, FORBIDDEN, NOT_FOUND})
    @Operation(summary = "tasks", description = "Roles: MEMBER for their own dogs (also the impersonation token; not the family group's → 404 DOG_NOT_ACCESSIBLE), INSTRUCTOR, ADMIN (another club's or an unknown dog → 404). Task history of a dog (26, 13), createdAt desc, each with its live attachments and a 5-minute signed url: PENDING and DONE by default (includeDone=true, S10 §6); includeDone=false keeps PENDING; state picks one state and wins over includeDone; includeDeleted adds the deleted tasks, ADMIN only (otherwise 403). page ≥ 0, size 1–200." + GUARDS,
            responses = @ApiResponse(responseCode = "200", description = "TaskList", useReturnTypeSchema = true))
    public TaskList tasks(@RequestParam String dogId, @RequestParam(required = false) TaskState state, @RequestParam(defaultValue = "true") boolean includeDone,
            @RequestParam(defaultValue = "false") boolean includeDeleted, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        var caller = caller(jwt);
        access.dog(caller, dogId);
        if (includeDeleted && !(caller.staff() && isAdmin())) { throw new ApiException(FORBIDDEN); }
        if (page < 0 || size < 1 || size > 200) { throw new ApiException(VALIDATION_ERROR, Map.of("fieldErrors", List.of(Map.of("field", page < 0 ? "page" : "size", "code", "INVALID_VALUE")))); }
        return new TaskList(views(tasks.list(dogId, state, includeDone, includeDeleted, page, size), caller));
    }
    private static boolean isAdmin() {
        return org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(granted -> granted.getAuthority().equals("ROLE_ADMIN"));
    }

    @PostMapping("/api/v1/tasks")
    @PreAuthorize(STAFF)
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, DOG_NOT_ACTIVE, ATTACHMENT_ENTITY_MISMATCH, ATTACHMENT_LIMIT_REACHED, FILE_TOO_LARGE,
            FILE_TYPE_NOT_ALLOWED, IDEMPOTENCY_KEY_REUSED, IMPERSONATION_DENIED})
    @Operation(summary = "createTask", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). «＋ Afegir» (R-10-10): the dog must be ACTIVE (DOG_NOT_ACTIVE); text 1–2000; attachmentIds are the fileKeys of the caller's TASK uploads, registered in the same Mongo transaction as the task, its D14 row and TaskCreated (→ N-20 to the owner): a refused one (ATTACHMENT_LIMIT_REACHED, ATTACHMENT_ENTITY_MISMATCH, …) leaves no task. Same Idempotency-Key → same 201." + GUARDS,
            responses = @ApiResponse(responseCode = "201", description = "Task", useReturnTypeSchema = true))
    public Task createTask(@Valid @RequestBody TaskCreateRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        var caller = caller(jwt);
        access.dog(caller, request.dogId());
        return view(tasks.create(request.dogId(), request.text(), request.attachmentIds(), actor(caller, jwt)), caller);
    }

    @GetMapping("/api/v1/tasks/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, IMPERSONATION_DENIED})
    @Operation(summary = "task", description = "Roles: MEMBER (own dog), INSTRUCTOR, ADMIN; impersonation → IMPERSONATION_DENIED. Another member's or club's task, or a deleted one → 404." + GUARDS,
            responses = @ApiResponse(responseCode = "200", description = "Task", useReturnTypeSchema = true))
    public Task task(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        var caller = caller(jwt);
        return view(access.task(caller, id), caller);
    }

    @PatchMapping("/api/v1/tasks/{id}")
    @PreAuthorize(STAFF)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, STALE_VERSION, IMPERSONATION_DENIED})
    @Operation(summary = "updateTask", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). Edits the text (R-10-10, also of a DONE task) with the version read (STALE_VERSION); TaskUpdated, no notification; refreshes the D14 excerpt, which stays read. The same text changes nothing. A deleted task → 404." + GUARDS,
            responses = @ApiResponse(responseCode = "200", description = "Task", useReturnTypeSchema = true))
    public Task updateTask(@PathVariable String id, @Valid @RequestBody TaskPatchRequest request, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        var caller = caller(jwt); var actor = actor(caller, jwt);
        return view(transactions.run(() -> tasks.updateText(access.task(caller, id), request.text(), request.version(), actor)), caller);
    }

    @DeleteMapping("/api/v1/tasks/{id}")
    @PreAuthorize(STAFF)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, IDEMPOTENCY_KEY_REUSED, IMPERSONATION_DENIED})
    @Operation(summary = "deleteTask", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). Logical deletion (deletedAt, deletedBy; also DONE tasks, BR-12): TaskDeleted, the D14 row hidden, gone from GET /tasks unless includeDeleted (ADMIN). Same Idempotency-Key → the same 204; a deleted task → 404." + GUARDS,
            responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void deleteTask(@PathVariable String id, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        var caller = caller(jwt);
        tasks.delete(access.task(caller, id), actor(caller, jwt));
    }

    @PostMapping("/api/v1/tasks/{id}/completion")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, TASK_ALREADY_DONE})
    @Operation(summary = "completeTask", description = "Roles: MEMBER owner of the dog (also the impersonation token, audited DOG_UPDATED with origin BACKOFFICE; a family-group dog → 404), INSTRUCTOR, ADMIN. PENDING → DONE with doneAt and doneBy {role, displayName, gender}; the D14 row gets completedAt (not activityAt); TaskCompleted → N-21 to every active instructor. Already DONE → TASK_ALREADY_DONE (422), also for the second of two simultaneous calls; deleted → 404. No body." + GUARDS,
            responses = @ApiResponse(responseCode = "200", description = "Task", useReturnTypeSchema = true))
    public Task completeTask(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        var caller = caller(jwt); var actor = actor(caller, jwt);
        return view(transactions.run(() -> tasks.complete(access.task(caller, id), actor)), caller);
    }

    @PostMapping("/api/v1/tasks/{id}/reopening")
    @PreAuthorize(STAFF)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, TASK_NOT_DONE, IMPERSONATION_DENIED})
    @Operation(summary = "reopenTask", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). DONE → PENDING (S10 §13-12), clears doneAt/doneBy and the D14 completedAt; TaskReopened (CATALEG_ESDEVENIMENTS Annex A), no notification; not DONE → TASK_NOT_DONE (422). No body." + GUARDS,
            responses = @ApiResponse(responseCode = "200", description = "Task", useReturnTypeSchema = true))
    public Task reopenTask(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        var caller = caller(jwt); var actor = actor(caller, jwt);
        return view(transactions.run(() -> tasks.reopen(access.task(caller, id), actor)), caller);
    }

    private Task view(com.agilityhub.core.clubs.followup.persistence.Task task, FollowupContractAccess.Caller caller) { return views(List.of(task), caller).getFirst(); }
    /** The §6 `Task` items with their live attachments (one query for the page); a member sees no staff account id. */
    private List<Task> views(List<com.agilityhub.core.clubs.followup.persistence.Task> list, FollowupContractAccess.Caller caller) {
        var files = attachments.byEntity("TASK", list.stream().map(com.agilityhub.core.clubs.followup.persistence.Task::id).toList());
        return list.stream().map(task -> new Task(task.id(), task.dogId(), task.text(), task.state(), task.createdAt(), actorView(task.createdBy(), caller),
                task.doneAt(), actorView(task.doneBy(), caller), task.deletedAt(),
                files.getOrDefault(task.id(), List.of()).stream().map(AttachmentsController::response).toList(), task.version() == null ? 0 : task.version())).toList();
    }
    private static Actor actorView(com.agilityhub.core.clubs.followup.persistence.Task.Actor actor, FollowupContractAccess.Caller caller) {
        if (actor == null) { return null; }
        return new Actor(caller.staff() ? actor.accountId() : null, actor.role(), actor.displayName(), actor.gender());
    }
}
