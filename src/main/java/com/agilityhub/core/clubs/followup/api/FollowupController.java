package com.agilityhub.core.clubs.followup.api;

import com.agilityhub.core.clubs.followup.application.FollowupActors;
import com.agilityhub.core.clubs.followup.application.FollowupContractAccess;
import com.agilityhub.core.clubs.followup.application.FollowupService;
import com.agilityhub.core.clubs.followup.application.ObservationService;
import com.agilityhub.core.clubs.followup.domain.FollowupKind;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.application.contract.ListContract;
import com.agilityhub.core.shared.application.lists.SparseItems;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.followup.api.FollowupContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S10 WP-10-C staff follow-up under TASKS (E6-T03): the private observations of a dog (R-10-12) and D14 with per-account
 * read marks (R-10-13). INSTRUCTOR/ADMIN only; the impersonation token is IMPERSONATION_DENIED (E6ContractConfiguration).
 */
@RestController
@RequiresModule(Module.TASKS)
@PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
public class FollowupController {
    static final String GUARDS = " Requires TASKS (MODULE_DISABLED). Tenant comes from the JWT.";
    private final FollowupContractAccess access; private final FollowupActors actors; private final ObservationService observations;
    private final FollowupService followup; private final ObjectMapper mapper;
    public FollowupController(FollowupContractAccess access, FollowupActors actors, ObservationService observations, FollowupService followup, ObjectMapper mapper) {
        this.access = access; this.actors = actors; this.observations = observations; this.followup = followup; this.mapper = mapper;
    }
    private static String me() { return CurrentUser.current().accountId(); }

    @PutMapping("/api/v1/dogs/{id}/observations")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, STALE_VERSION, INVALID_STATE, MEMBER_ERASED, IDEMPOTENCY_KEY_REUSED, IMPERSONATION_DENIED})
    @Operation(summary = "saveObservations", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). R-10-12: Dog.remarks (≤ 2000, stored as written; empty clears it) + remarksMeta, with the version of the card's observations block (the observations' own count of changes, which other writes of the dog leave alone; STALE_VERSION); DogUpdated{diff: remarks} and an audit entry (DOG_UPDATED); no notification and no D14 row; never returned by /me/*. The same text changes nothing. The reused dog of a pending readmission (S04 R-04-06) is frozen: 409 INVALID_STATE with details.reason = READMISSION_PENDING; the dog of an erased member is MEMBER_ERASED (S14). Same Idempotency-Key → the same 200." + GUARDS,
            responses = @ApiResponse(responseCode = "200", description = "Observations", useReturnTypeSchema = true))
    public Observations saveObservations(@PathVariable String id, @Valid @RequestBody ObservationsRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        var caller = access.caller(jwt.getClaimAsString("memberId"));
        access.dog(caller, id);
        var saved = observations.save(id, request.text(), request.version(), actors.actor(caller, jwt.getClaimAsString("memberId"), jwt.getClaimAsString("instructorId")));
        return new Observations(saved.text() == null ? "" : saved.text(), saved.updatedAt(), saved.updatedByName(), saved.version());
    }

    @GetMapping("/api/v1/followup")
    @ListContract(filterable = {"kind", "memberId", "dogId", "authorAccountId", "unread"}, sortable = {"activityAt"},
            columns = {"memberName*", "dogName*", "levelCode*", "activityAt*", "authorName*", "textExcerpt*", "createdAt*", "completedAt*"}, paged = true, exportable = false,
            maxSize = FollowupContractAccess.FOLLOWUP_MAX_SIZE,
            fields = {"id", "kind", "taskId", "dogId", "dogName", "levelCode", "memberId", "memberName", "authorName", "authorRole", "authorGender", "textExcerpt", "createdAt",
                    "completedAt", "activityAt", "unread"})
    @ContractErrors({VALIDATION_ERROR, INVALID_FILTER, MODULE_DISABLED, IMPERSONATION_DENIED})
    @Operation(summary = "followup", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). D14 universal list (CONVENCIONS_API §4, R-10-13): the visible FollowupItem rows (a deleted task's row is hidden), unread first (activityAt desc), then the rest (activityAt desc), ordered by the query; unread(item, me) = activityAt > readAllAt ∧ id ∉ readItemIds ∧ author ≠ me. memberName (the dog's current owner), dogName and levelCode (null with levels.enabled = false) are read at request time; authorAccountId, authorName and authorGender are whoever wrote the task or the note, as they were then, never the dog's current owner. Pages hold at most 50 rows (S10 §3): size 20 or 50, and 200 or 1000 is 400 INVALID_FILTER. An undeclared filter or sort is 400 INVALID_FILTER." + GUARDS,
            responses = @ApiResponse(responseCode = "200", description = "FollowupPage",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = FollowupPage.class))))
    public Object followup(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        access.tenant();
        access.followup(params);
        var page = followup.list(params, me());
        var items = page.items().stream().map(item -> new FollowupItem(item.id(), FollowupKind.valueOf(item.kind()), item.taskId(), item.dogId(), item.dogName(),
                item.levelCode(), item.memberId(), item.memberName(), item.authorName(), item.authorRole(), item.authorGender(), item.textExcerpt(), item.createdAt(),
                item.completedAt(), item.activityAt(), item.unread())).toList();
        var whole = new FollowupPage(items, page.page(), page.size(), page.totalItems(), page.totalPages(), page.appliedFilters());
        // With `fields`, the items are cut to the row id and the requested keys (CONVENCIONS_API §4), so the page is no longer typed.
        return params.containsKey("fields") ? SparseItems.apply(mapper, new com.agilityhub.core.shared.application.contract.ApiContracts.ListPage<>(items, page.page(),
                page.size(), page.totalItems(), page.totalPages(), page.appliedFilters()), params, "id") : whole;
    }

    @GetMapping("/api/v1/followup/unread-count")
    @ContractErrors({VALIDATION_ERROR, MODULE_DISABLED, IMPERSONATION_DENIED})
    @Operation(summary = "followupUnreadCount", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). Menu counter «Seguiment alumnes» of the caller (refetched on focus and every 60 s): the caller's unread D14 rows, the same count as GET /dashboard/counters.followUpUnread (S14 R-14-08)." + GUARDS,
            responses = @ApiResponse(responseCode = "200", description = "FollowupUnreadCount", useReturnTypeSchema = true))
    public FollowupUnreadCount followupUnreadCount() {
        access.tenant();
        return new FollowupUnreadCount((int) Math.min(Integer.MAX_VALUE, followup.unreadCount(me())));
    }

    @PostMapping("/api/v1/followup/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, IDEMPOTENCY_KEY_REUSED, IMPERSONATION_DENIED})
    @Operation(summary = "readFollowupItem", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). Adds the row to the caller's readItemIds (pruning the ids readAllAt already covers); another club's row or a hidden one → 404. No body." + GUARDS,
            responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void readFollowupItem(@PathVariable String id, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey) {
        access.tenant();
        access.followupItem(id);
        followup.read(id, me());
    }

    @PostMapping("/api/v1/followup/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({VALIDATION_ERROR, MODULE_DISABLED, IDEMPOTENCY_KEY_REUSED, IMPERSONATION_DENIED})
    @Operation(summary = "readAllFollowup", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). «Marcar-ho tot com a llegit»: readAllAt = now, readItemIds = [] for the caller only, one document in one Mongo transaction whatever the number of rows. No body." + GUARDS,
            responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void readAllFollowup(@RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey) {
        access.tenant();
        followup.readAll(me());
    }
}
