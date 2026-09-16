package com.agilityhub.core.clubs.activities.api;

import com.agilityhub.core.clubs.activities.application.ActivityContractAccess;
import com.agilityhub.core.clubs.activities.domain.*;
import com.agilityhub.core.shared.application.contract.*;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.platform.application.Module;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.shared.application.contract.ApiContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;
import static com.agilityhub.core.clubs.activities.api.ActivityContracts.*;
import static com.agilityhub.core.clubs.activities.api.ActivityRequests.*;

/** Reserved S07 operations. Guards execute before the standard NOT_IMPLEMENTED response. */
@RestController
@RequiresModule(Module.ACTIVITIES)
public class ActivitiesController {
    private final ActivityContractAccess access;
    public ActivitiesController(ActivityContractAccess access) { this.access = access; }
    private String memberId(org.springframework.security.oauth2.jwt.Jwt jwt) {
        var user = com.agilityhub.core.shared.application.CurrentUser.current();
        return user.impersonation() == null ? jwt.getClaimAsString("memberId") : user.impersonation().memberId();
    }

    @GetMapping("/api/v1/activities")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ListContract(filterable = {"state", "type", "date", "ringId", "levelId", "deleted", "registrationOpen"}, sortable = {"date", "title", "state", "createdAt"}, columns = {"title*", "date*", "rings*", "registrations*", "state*", "type", "slug", "registrationTo"}, paged = true, exportable = true)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "activities", description = "Roles: ADMIN, INSTRUCTOR. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Default sort date desc; default filter deleted:eq:false. q searches title. Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "ListPage<ActivityListItem>", useReturnTypeSchema = true))
    public ListPage<ActivityListItem> activities() {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/activities/filter-values")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ListContract(filterable = {"state", "type", "date", "ringId", "levelId", "deleted", "registrationOpen"}, sortable = {"date", "title", "state", "createdAt"}, columns = {"title*", "date*", "rings*", "registrations*", "state*", "type", "slug", "registrationTo"}, paged = true, exportable = true)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_FILTER})
    @Operation(summary = "filterValues", description = "Roles: ADMIN, INSTRUCTOR. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "FilterValues", useReturnTypeSchema = true))
    public FilterValues filterValues(@RequestParam String field) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/activities/export")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ListContract(filterable = {"state", "type", "date", "ringId", "levelId", "deleted", "registrationOpen"}, sortable = {"date", "title", "state", "createdAt"}, columns = {"title*", "date*", "rings*", "registrations*", "state*", "type", "slug", "registrationTo"}, paged = true, exportable = false)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_FILTER, EXPORT_TOO_LARGE, EXPORT_LIMIT, RATE_LIMITED})
    @Operation(summary = "export", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = {@ApiResponse(responseCode = "200", description = "Export file", content = {@Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")), @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", schema = @Schema(type = "string", format = "binary"))}), @ApiResponse(responseCode = "202", description = "Queued export", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExportAccepted.class)))})
    public org.springframework.http.ResponseEntity<byte[]> export(@RequestParam @Schema(allowableValues = {"xlsx", "pdf"}) String format, @RequestParam(required = false) String columns) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/activities")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, LOCALE_NOT_ENABLED})
    @Operation(summary = "createActivity", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "201", description = "Activity", useReturnTypeSchema = true))
    public Activity createActivity(@Valid @RequestBody ActivityCreateRequest request) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/activities/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "activity", description = "Roles: ADMIN, INSTRUCTOR. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. INSTRUCTOR projection omits internalNotes; placementIds only with COURSES. Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "Activity", useReturnTypeSchema = true))
    public Activity activity(@PathVariable String id) {
        access.tenant();
        access.activity(id);
        throw new UnsupportedOperationException();
    }

    @PatchMapping("/api/v1/activities/{id}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_SLOT_GRANULARITY, INVALID_TIME_RANGE, STALE_VERSION, SLUG_LOCKED, DUPLICATE_SLUG, INVALID_STATE, RING_BLOCK_CONFLICT, RING_HAS_BOOKINGS, CAPACITY_BELOW_REGISTRATIONS, OUTSIDE_OPENING_HOURS, ADMIN_TEXT_REQUIRED, LOCALE_NOT_ENABLED})
    @Operation(summary = "patchActivity", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "Activity", useReturnTypeSchema = true))
    public Activity patchActivity(@PathVariable String id, @Valid @RequestBody ActivityPatchRequest request) {
        access.tenant();
        access.activity(id);
        throw new UnsupportedOperationException();
    }

    @PutMapping("/api/v1/activities/{id}/image")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, FILE_TOO_LARGE, FILE_TYPE_NOT_ALLOWED, FILE_NOT_FOUND})
    @Operation(summary = "image", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "ActivityImageResult", useReturnTypeSchema = true))
    public ActivityImageResult image(@PathVariable String id, @Valid @RequestBody ActivityImageRequest request) {
        access.tenant();
        access.activity(id);
        throw new UnsupportedOperationException();
    }

    @DeleteMapping("/api/v1/activities/{id}/image")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "deleteImage", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void deleteImage(@PathVariable String id) {
        access.tenant();
        access.activity(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/activities/{id}/documents")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, FILE_TOO_LARGE, FILE_TYPE_NOT_ALLOWED, FILE_NOT_FOUND, TOO_MANY_DOCUMENTS})
    @Operation(summary = "document", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "201", description = "ActivityDocument", useReturnTypeSchema = true))
    public ActivityDocument document(@PathVariable String id, @Valid @RequestBody ActivityDocumentRequest request) {
        access.tenant();
        access.activity(id);
        throw new UnsupportedOperationException();
    }

    @DeleteMapping("/api/v1/activities/{id}/documents/{docId}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "deleteDocument", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void deleteDocument(@PathVariable String id, @PathVariable String docId) {
        access.tenant();
        access.document(id, docId);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/activities/{id}/ring-conflicts")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "ringConflicts", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "RingConflicts", useReturnTypeSchema = true))
    public RingConflicts ringConflicts(@PathVariable String id) {
        access.tenant();
        access.activity(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/activities/{id}/publication")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_STATE, RING_BLOCK_CONFLICT, RING_HAS_BOOKINGS, ACTIVITY_INCOMPLETE, ACTIVITY_IN_PAST, OUTSIDE_OPENING_HOURS, ADMIN_TEXT_REQUIRED, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "publish", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "Activity", useReturnTypeSchema = true))
    public Activity publish(@PathVariable String id, @Valid @RequestBody PublicationRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey) {
        access.tenant();
        access.activity(id);
        throw new UnsupportedOperationException();
    }

    @DeleteMapping("/api/v1/activities/{id}/publication")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_STATE, ACTIVITY_HAS_REGISTRATIONS})
    @Operation(summary = "unpublish", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "Activity", useReturnTypeSchema = true))
    public Activity unpublish(@PathVariable String id) {
        access.tenant();
        access.activity(id);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/activities/{id}/cancellation-preview")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "cancellationPreview", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "ActivityCancellationPreview", useReturnTypeSchema = true))
    public ActivityCancellationPreview cancellationPreview(@PathVariable String id) {
        access.tenant();
        access.activity(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/activities/{id}/cancellation")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_STATE, ADMIN_TEXT_REQUIRED, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "cancelActivity", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "Activity", useReturnTypeSchema = true))
    public Activity cancelActivity(@PathVariable String id, @Valid @RequestBody ActivityCancellationRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey) {
        access.tenant();
        access.activity(id);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/activities/{id}/registrations")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ListContract(filterable = {"state", "origin", "registeredAt", "memberId"}, sortable = {"registeredAt", "position", "memberLastName"}, paged = true, exportable = true)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "registrations", description = "Roles: ADMIN, INSTRUCTOR. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Export through /activity-registrations/export with filter=activityId:eq:id. Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "ListPage<ActivityRegistrationListItem>", useReturnTypeSchema = true))
    public ListPage<ActivityRegistrationListItem> registrations(@PathVariable String id) {
        access.tenant();
        access.activity(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/activity-registrations")
    @PreAuthorize("hasAnyRole('MEMBER') and (principal.claims['imp'] == true or !hasAnyRole('ADMIN','INSTRUCTOR'))")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, ACTIVITY_NOT_PUBLISHED, REGISTRATION_CLOSED, ACTIVITY_FULL, LEVEL_NOT_ALLOWED, ALREADY_REGISTERED, MEMBER_NOT_ACTIVE, BOOKING_BLOCKED, INACTIVITY_PERIOD, MODULE_DISABLED, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "register", description = "Roles: MEMBER. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "201", description = "ActivityRegistration", useReturnTypeSchema = true))
    public ActivityRegistration register(@Valid @RequestBody ActivityRegistrationRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey) {
        access.tenant();
        access.activity(request.activityId()); access.waitlist(request.joinWaitlist());
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/activity-registrations/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "registration", description = "Roles: ADMIN, INSTRUCTOR, MEMBER. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. MEMBER sees own registration only, including impersonation. Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "ActivityRegistration", useReturnTypeSchema = true))
    public ActivityRegistration registration(@PathVariable String id, @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt, org.springframework.security.core.Authentication authentication) {
        access.tenant();
        access.registration(id, memberId(jwt), authentication.getAuthorities().stream().anyMatch(a -> java.util.Set.of("ROLE_ADMIN", "ROLE_INSTRUCTOR").contains(a.getAuthority())));
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/activity-registrations/{id}/cancellation")
    @PreAuthorize("hasAnyRole('MEMBER') and (principal.claims['imp'] == true or !hasAnyRole('ADMIN','INSTRUCTOR'))")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_STATE, REGISTRATION_NOT_CANCELLABLE})
    @Operation(summary = "cancelRegistration", description = "Roles: MEMBER. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "ActivityRegistration", useReturnTypeSchema = true))
    public ActivityRegistration cancelRegistration(@PathVariable String id, @Valid @RequestBody RegistrationCancellationRequest request, @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt) {
        access.tenant();
        access.registration(id, memberId(jwt), false);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/me/activities")
    @PreAuthorize("hasAnyRole('MEMBER') and (principal.claims['imp'] == true or !hasAnyRole('ADMIN','INSTRUCTOR'))")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, DOG_NOT_ACCESSIBLE})
    @Operation(summary = "mine", description = "Roles: MEMBER. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "MeActivities", useReturnTypeSchema = true))
    public MeActivities mine(@RequestParam(required = false) String dogId) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/me/activities/{activityId}")
    @PreAuthorize("hasAnyRole('MEMBER') and (principal.claims['imp'] == true or !hasAnyRole('ADMIN','INSTRUCTOR'))")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "memberDetail", description = "Roles: MEMBER. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "MemberActivityDetail", useReturnTypeSchema = true))
    public MemberActivityDetail memberDetail(@PathVariable String activityId) {
        access.tenant();
        access.activity(activityId);
        throw new UnsupportedOperationException();
    }
}
