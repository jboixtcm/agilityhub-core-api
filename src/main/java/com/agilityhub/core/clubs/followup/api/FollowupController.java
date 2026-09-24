package com.agilityhub.core.clubs.followup.api;

import com.agilityhub.core.clubs.followup.application.FollowupContractAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.application.contract.ListContract;
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
 * S10 WP-10-A staff follow-up under TASKS: the private observations of a dog (R-10-12) and D14 with per-account read
 * marks (R-10-13). INSTRUCTOR/ADMIN only; the impersonation token is IMPERSONATION_DENIED (E6ContractConfiguration).
 * Every operation runs its guards and then answers 501 NOT_IMPLEMENTED until E6-T03.
 */
@RestController
@RequiresModule(Module.TASKS)
@PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
public class FollowupController {
    static final String STUB = " Requires TASKS. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role, module and resource guards. Tenant comes from the JWT.";
    private final FollowupContractAccess access;
    public FollowupController(FollowupContractAccess access) { this.access = access; }

    @PutMapping("/api/v1/dogs/{id}/observations")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, STALE_VERSION, MEMBER_ERASED, IDEMPOTENCY_KEY_REUSED, IMPERSONATION_DENIED})
    @Operation(summary = "saveObservations", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). R-10-12: Dog.remarks (≤ 2000) + remarksMeta with version (STALE_VERSION); DogUpdated{diff: remarks} and an audit entry (DOG_UPDATED); no notification and no D14 row; never returned by /me/*. The dog of an erased member is MEMBER_ERASED (S14)." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "Observations", useReturnTypeSchema = true))
    public Observations saveObservations(@PathVariable String id, @Valid @RequestBody ObservationsRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.dog(access.caller(jwt.getClaimAsString("memberId")), id);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/followup")
    @ListContract(filterable = {"kind", "memberId", "dogId", "authorAccountId", "unread"}, sortable = {"activityAt"},
            columns = {"memberName*", "dogName*", "levelCode*", "activityAt*", "authorName*", "textExcerpt*", "createdAt*", "completedAt*"}, paged = true, exportable = false)
    @ContractErrors({VALIDATION_ERROR, INVALID_FILTER, MODULE_DISABLED, IMPERSONATION_DENIED})
    @Operation(summary = "followup", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). D14 universal list (CONVENCIONS_API §4, R-10-13): visible FollowupItem rows, unread first; unread(item, me) = activityAt > readAllAt ∧ id ∉ readItemIds ∧ author ≠ me. An undeclared filter or sort is 400 INVALID_FILTER." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "FollowupPage", useReturnTypeSchema = true))
    public FollowupPage followup(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        access.tenant();
        access.followup(params);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/followup/unread-count")
    @ContractErrors({VALIDATION_ERROR, MODULE_DISABLED, IMPERSONATION_DENIED})
    @Operation(summary = "followupUnreadCount", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). Menu counter «Seguiment alumnes» of the caller (refetched on focus and every 60 s)." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "FollowupUnreadCount", useReturnTypeSchema = true))
    public FollowupUnreadCount followupUnreadCount() {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/followup/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, IDEMPOTENCY_KEY_REUSED, IMPERSONATION_DENIED})
    @Operation(summary = "readFollowupItem", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). Adds the item to the caller's readItemIds. No body." + STUB,
            responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void readFollowupItem(@PathVariable String id, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey) {
        access.tenant();
        access.followupItem(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/followup/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({VALIDATION_ERROR, MODULE_DISABLED, IDEMPOTENCY_KEY_REUSED, IMPERSONATION_DENIED})
    @Operation(summary = "readAllFollowup", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). «Marcar-ho tot com a llegit»: readAllAt = now, readItemIds = [] in one Mongo transaction. No body." + STUB,
            responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void readAllFollowup(@RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey) {
        access.tenant();
        throw new UnsupportedOperationException();
    }
}
