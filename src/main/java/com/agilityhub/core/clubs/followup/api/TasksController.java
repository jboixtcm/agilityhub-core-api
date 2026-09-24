package com.agilityhub.core.clubs.followup.api;

import com.agilityhub.core.clubs.followup.application.FollowupContractAccess;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.followup.api.FollowupContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S10 WP-10-A tasks (26, 13 «Veure l'historial», R-10-10), all under TASKS. Every operation runs the tenant, role,
 * module and resource guards and then answers 501 NOT_IMPLEMENTED until E6-T03. Only `GET /tasks` and the completion
 * accept the impersonation token (as the member); the rest answer IMPERSONATION_DENIED (E6ContractConfiguration).
 */
@RestController
@RequiresModule(Module.TASKS)
public class TasksController {
    static final String STAFF = "hasAnyRole('ADMIN','INSTRUCTOR')";
    static final String STUB = " Requires TASKS. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role, module and resource guards. Tenant comes from the JWT.";
    private final FollowupContractAccess access;
    public TasksController(FollowupContractAccess access) { this.access = access; }

    @GetMapping("/api/v1/tasks")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, MODULE_DISABLED, DOG_NOT_ACCESSIBLE, FORBIDDEN})
    @Operation(summary = "tasks", description = "Roles: MEMBER for their own dogs (also the impersonation token; not the family group's → 404 DOG_NOT_ACCESSIBLE), INSTRUCTOR, ADMIN. Task history of a dog (26, 13): PENDING by default, includeDone=true adds DONE; includeDeleted only for ADMIN (otherwise 403)." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "TaskList", useReturnTypeSchema = true))
    public TaskList tasks(@RequestParam String dogId, @RequestParam(required = false) TaskState state, @RequestParam(defaultValue = "false") boolean includeDone,
            @RequestParam(defaultValue = "false") boolean includeDeleted, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        var caller = access.caller(jwt.getClaimAsString("memberId"));
        access.dog(caller, dogId);
        if (includeDeleted && !(caller.staff() && isAdmin())) { throw new ApiException(FORBIDDEN); }
        throw new UnsupportedOperationException();
    }
    private static boolean isAdmin() {
        return org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(granted -> granted.getAuthority().equals("ROLE_ADMIN"));
    }

    @PostMapping("/api/v1/tasks")
    @PreAuthorize(STAFF)
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, DOG_NOT_ACTIVE, ATTACHMENT_ENTITY_MISMATCH, ATTACHMENT_LIMIT_REACHED, IDEMPOTENCY_KEY_REUSED,
            IMPERSONATION_DENIED})
    @Operation(summary = "createTask", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). «＋ Afegir» (R-10-10): the dog must be ACTIVE (DOG_NOT_ACTIVE); text 1–2000; attachments of purpose TASK registered in the same Mongo transaction; TaskCreated → N-20 to the owner and a D14 row. Same Idempotency-Key → same response." + STUB,
            responses = @ApiResponse(responseCode = "201", description = "Task", useReturnTypeSchema = true))
    public Task createTask(@Valid @RequestBody TaskCreateRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.dog(access.caller(jwt.getClaimAsString("memberId")), request.dogId());
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/tasks/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, IMPERSONATION_DENIED})
    @Operation(summary = "task", description = "Roles: MEMBER (own dog), INSTRUCTOR, ADMIN; impersonation → IMPERSONATION_DENIED. Another member's or club's task → 404." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "Task", useReturnTypeSchema = true))
    public Task task(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.task(access.caller(jwt.getClaimAsString("memberId")), id);
        throw new UnsupportedOperationException();
    }

    @PatchMapping("/api/v1/tasks/{id}")
    @PreAuthorize(STAFF)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, STALE_VERSION, IMPERSONATION_DENIED})
    @Operation(summary = "updateTask", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). Edits the text (R-10-10) with version (STALE_VERSION); TaskUpdated, no notification; refreshes the D14 excerpt." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "Task", useReturnTypeSchema = true))
    public Task updateTask(@PathVariable String id, @Valid @RequestBody TaskPatchRequest request, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.task(access.caller(jwt.getClaimAsString("memberId")), id);
        throw new UnsupportedOperationException();
    }

    @DeleteMapping("/api/v1/tasks/{id}")
    @PreAuthorize(STAFF)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, IDEMPOTENCY_KEY_REUSED, IMPERSONATION_DENIED})
    @Operation(summary = "deleteTask", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). Logical deletion (deletedAt, also DONE tasks); TaskDeleted hides the D14 row." + STUB,
            responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void deleteTask(@PathVariable String id, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.task(access.caller(jwt.getClaimAsString("memberId")), id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/tasks/{id}/completion")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, TASK_ALREADY_DONE})
    @Operation(summary = "completeTask", description = "Roles: MEMBER owner of the dog (also the impersonation token, audited; a family-group dog → 404), INSTRUCTOR, ADMIN. PENDING → DONE with doneBy; TaskCompleted → N-21 to every active instructor; already DONE → TASK_ALREADY_DONE (422); deleted → 404. No body." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "Task", useReturnTypeSchema = true))
    public Task completeTask(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.task(access.caller(jwt.getClaimAsString("memberId")), id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/tasks/{id}/reopening")
    @PreAuthorize(STAFF)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, TASK_NOT_DONE, IMPERSONATION_DENIED})
    @Operation(summary = "reopenTask", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). DONE → PENDING (S10 §13-12), clears doneAt/doneBy and the D14 completedAt; TaskReopened; not DONE → TASK_NOT_DONE (422). No body." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "Task", useReturnTypeSchema = true))
    public Task reopenTask(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.task(access.caller(jwt.getClaimAsString("memberId")), id);
        throw new UnsupportedOperationException();
    }
}
