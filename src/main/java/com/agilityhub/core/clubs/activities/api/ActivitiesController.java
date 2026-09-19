package com.agilityhub.core.clubs.activities.api;

import com.agilityhub.core.clubs.activities.application.*;
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

/** S07 activity maintenance and member registration endpoints. */
@RestController
@RequiresModule(Module.ACTIVITIES)
public class ActivitiesController {
    private final ActivityApiService service;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final com.agilityhub.core.clubs.common.application.ListExportService exports;
    private final jakarta.validation.Validator validator;
    private final ActivityTransactions transactions;
    public ActivitiesController(ActivityApiService service, com.fasterxml.jackson.databind.ObjectMapper mapper,
            com.agilityhub.core.clubs.common.application.ListExportService exports, jakarta.validation.Validator validator, ActivityTransactions transactions) {
        this.service=service; this.mapper=mapper; this.exports=exports; this.validator=validator; this.transactions=transactions;
    }
    private <T> T view(Object value, Class<T> type) { return mapper.convertValue(value,type); }
    private <T> T mutation(java.util.function.Supplier<?> work,Class<T> type,int status) {
        return transactions.write(() -> {
            com.agilityhub.core.shared.application.IdempotentOperation.lock();
            T result=view(work.get(),type);
            try { com.agilityhub.core.shared.application.IdempotentOperation.complete(status,mapper.writeValueAsBytes(result)); }
            catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
            return result;
        });
    }
    private boolean staff() { return com.agilityhub.core.clubs.scheduling.application.RingBlockService.role("ADMIN") || com.agilityhub.core.clubs.scheduling.application.RingBlockService.role("INSTRUCTOR"); }
    private com.agilityhub.core.clubs.scheduling.application.RingBlockService.Options options(Boolean bookings,Boolean classes,String text) {
        return new com.agilityhub.core.clubs.scheduling.application.RingBlockService.Options(Boolean.TRUE.equals(bookings),Boolean.TRUE.equals(classes),text);
    }

    @GetMapping("/api/v1/activities")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ListContract(filterable = {"state", "type", "date", "ringId", "levelId", "deleted", "registrationOpen"}, sortable = {"date", "title", "state", "createdAt"}, columns = {"title*", "date*", "rings*", "registrations*", "state*", "type", "slug", "registrationTo"}, paged = true, exportable = true)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "activities", description = "Roles: ADMIN, INSTRUCTOR. Default sort date desc; default filter deleted:eq:false. q searches title. Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "ListPage<ActivityListItem>", useReturnTypeSchema = true))
    public ListPage<ActivityListItem> activities(@io.swagger.v3.oas.annotations.Parameter(hidden=true) @RequestParam org.springframework.util.MultiValueMap<String,String> params) {
        return mapper.convertValue(service.list(params),new com.fasterxml.jackson.core.type.TypeReference<ListPage<ActivityListItem>>() {});
    }

    @GetMapping("/api/v1/activities/filter-values")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ListContract(filterable = {"state", "type", "date", "ringId", "levelId", "deleted", "registrationOpen"}, sortable = {"date", "title", "state", "createdAt"}, columns = {"title*", "date*", "rings*", "registrations*", "state*", "type", "slug", "registrationTo"}, paged = true, exportable = true)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_FILTER})
    @Operation(summary = "filterValues", description = "Roles: ADMIN, INSTRUCTOR.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "FilterValues", useReturnTypeSchema = true))
    public FilterValues filterValues(@RequestParam String field, @io.swagger.v3.oas.annotations.Parameter(hidden=true) @RequestParam org.springframework.util.MultiValueMap<String,String> params) {
        return service.facets(field,params);
    }

    @GetMapping("/api/v1/activities/export")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ListContract(filterable = {"state", "type", "date", "ringId", "levelId", "deleted", "registrationOpen"}, sortable = {"date", "title", "state", "createdAt"}, columns = {"title*", "date*", "rings*", "registrations*", "state*", "type", "slug", "registrationTo"}, paged = true, exportable = false)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_FILTER, EXPORT_TOO_LARGE, EXPORT_LIMIT, RATE_LIMITED})
    @Operation(summary = "export", description = "Roles: ADMIN.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = {@ApiResponse(responseCode = "200", description = "Export file", content = {@Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")), @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", schema = @Schema(type = "string", format = "binary"))}), @ApiResponse(responseCode = "202", description = "Queued export", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExportAccepted.class)))})
    public org.springframework.http.ResponseEntity<?> export(@RequestParam @Schema(allowableValues = {"xlsx", "pdf"}) String format, @RequestParam(required = false) String columns, @io.swagger.v3.oas.annotations.Parameter(hidden=true) @RequestParam org.springframework.util.MultiValueMap<String,String> params) {
        var result=exports.export("activities",format,columns,ActivityLists.params(params));
        if(result.file()==null) return org.springframework.http.ResponseEntity.accepted().body(new ExportAccepted(result.jobId(),"/api/v1/exports/"+result.jobId()));
        return org.springframework.http.ResponseEntity.ok().header("Content-Type",com.agilityhub.core.clubs.common.application.ExportPolicy.contentType(format))
                .header("Content-Disposition",org.springframework.http.ContentDisposition.attachment().filename(result.fileName()).build().toString()).body(result.file());
    }

    @PostMapping("/api/v1/activities")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, LOCALE_NOT_ENABLED})
    @Operation(summary = "createActivity", description = "Roles: ADMIN.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "201", description = "Activity", useReturnTypeSchema = true))
    public Activity createActivity(@Valid @RequestBody ActivityCreateRequest request) {
        return mutation(() -> service.create(request.title(),request.type()),Activity.class,201);
    }

    @GetMapping("/api/v1/activities/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "activity", description = "Roles: ADMIN, INSTRUCTOR. INSTRUCTOR projection omits internalNotes; placementIds only with COURSES. Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "Activity", useReturnTypeSchema = true))
    public Activity activity(@PathVariable String id) {
        return view(service.activity(id,com.agilityhub.core.clubs.scheduling.application.RingBlockService.role("ADMIN")),Activity.class);
    }

    @PatchMapping("/api/v1/activities/{id}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_SLOT_GRANULARITY, INVALID_TIME_RANGE, STALE_VERSION, SLUG_LOCKED, DUPLICATE_SLUG, INVALID_STATE, RING_BLOCK_CONFLICT, RING_HAS_BOOKINGS, CAPACITY_BELOW_REGISTRATIONS, OUTSIDE_OPENING_HOURS, ADMIN_TEXT_REQUIRED, LOCALE_NOT_ENABLED})
    @Operation(summary = "patchActivity", description = "Roles: ADMIN.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "Activity", useReturnTypeSchema = true))
    public Activity patchActivity(@PathVariable String id, @RequestBody @Schema(implementation=ActivityPatchRequest.class) com.fasterxml.jackson.databind.JsonNode body) {
        if(body==null || !body.isObject()) throw new com.agilityhub.core.shared.domain.ApiException(VALIDATION_ERROR);
        ActivityPatchRequest request;
        try { request=mapper.convertValue(body,ActivityPatchRequest.class); }
        catch(IllegalArgumentException invalid) { throw new com.agilityhub.core.shared.domain.ApiException(VALIDATION_ERROR); }
        var violations=validator.validate(request); if(!violations.isEmpty()) throw new jakarta.validation.ConstraintViolationException(violations);
        java.util.Map<String,Object> changes=mapper.convertValue(body,new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String,Object>>() {});
        for(String key:java.util.List.of("version","cancelBookings","cancelClasses","adminText")) changes.remove(key);
        return view(service.patch(id,request.version(),changes,options(request.cancelBookings(),request.cancelClasses(),request.adminText())),Activity.class);
    }

    @PutMapping("/api/v1/activities/{id}/image")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, FILE_TOO_LARGE, FILE_TYPE_NOT_ALLOWED, FILE_NOT_FOUND})
    @Operation(summary = "image", description = "Roles: ADMIN.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "ActivityImageResult", useReturnTypeSchema = true))
    public ActivityImageResult image(@PathVariable String id, @Valid @RequestBody ActivityImageRequest request) {
        return view(service.image(id,request.fileKey(),request.name()),ActivityImageResult.class);
    }

    @DeleteMapping("/api/v1/activities/{id}/image")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "deleteImage", description = "Roles: ADMIN.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void deleteImage(@PathVariable String id) {
        service.deleteFile(id,null);
    }

    @PostMapping("/api/v1/activities/{id}/documents")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, FILE_TOO_LARGE, FILE_TYPE_NOT_ALLOWED, FILE_NOT_FOUND, TOO_MANY_DOCUMENTS})
    @Operation(summary = "document", description = "Roles: ADMIN.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "201", description = "ActivityDocument", useReturnTypeSchema = true))
    public ActivityDocument document(@PathVariable String id, @Valid @RequestBody ActivityDocumentRequest request) {
        return mutation(() -> service.document(id,request.fileKey(),request.name()),ActivityDocument.class,201);
    }

    @DeleteMapping("/api/v1/activities/{id}/documents/{docId}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "deleteDocument", description = "Roles: ADMIN.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void deleteDocument(@PathVariable String id, @PathVariable String docId) {
        service.deleteFile(id,docId);
    }

    @GetMapping("/api/v1/activities/{id}/ring-conflicts")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "ringConflicts", description = "Roles: ADMIN.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "RingConflicts", useReturnTypeSchema = true))
    public RingConflicts ringConflicts(@PathVariable String id) {
        return view(service.conflicts(id),RingConflicts.class);
    }

    @PostMapping("/api/v1/activities/{id}/publication")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_STATE, RING_BLOCK_CONFLICT, RING_HAS_BOOKINGS, ACTIVITY_INCOMPLETE, ACTIVITY_IN_PAST, OUTSIDE_OPENING_HOURS, ADMIN_TEXT_REQUIRED, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "publish", description = "Roles: ADMIN.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "Activity", useReturnTypeSchema = true))
    public Activity publish(@PathVariable String id, @Valid @RequestBody PublicationRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey) {
        return mutation(() -> service.publish(id,Boolean.TRUE.equals(request.notifyEmail()),options(request.cancelBookings(),request.cancelClasses(),request.adminText())),Activity.class,200);
    }

    @DeleteMapping("/api/v1/activities/{id}/publication")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_STATE, ACTIVITY_HAS_REGISTRATIONS})
    @Operation(summary = "unpublish", description = "Roles: ADMIN.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "Activity", useReturnTypeSchema = true))
    public Activity unpublish(@PathVariable String id) {
        return view(service.unpublish(id),Activity.class);
    }

    @GetMapping("/api/v1/activities/{id}/cancellation-preview")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "cancellationPreview", description = "Roles: ADMIN.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "ActivityCancellationPreview", useReturnTypeSchema = true))
    public ActivityCancellationPreview cancellationPreview(@PathVariable String id) {
        return view(service.preview(id),ActivityCancellationPreview.class);
    }

    @PostMapping("/api/v1/activities/{id}/cancellation")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_STATE, ADMIN_TEXT_REQUIRED, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "cancelActivity", description = "Roles: ADMIN.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "Activity", useReturnTypeSchema = true))
    public Activity cancelActivity(@PathVariable String id, @Valid @RequestBody ActivityCancellationRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey) {
        return mutation(() -> service.cancel(id,request.reason(),request.adminText()),Activity.class,200);
    }

    @GetMapping("/api/v1/activities/{id}/registrations")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ListContract(filterable = {"state", "origin", "registeredAt", "memberId"}, sortable = {"registeredAt", "position", "memberLastName"}, paged = true, exportable = true)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "registrations", description = "Roles: ADMIN, INSTRUCTOR. Export through /activity-registrations/export with filter=activityId:eq:id. Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "ListPage<ActivityRegistrationListItem>", useReturnTypeSchema = true))
    public ListPage<ActivityRegistrationListItem> registrations(@PathVariable String id, @io.swagger.v3.oas.annotations.Parameter(hidden=true) @RequestParam org.springframework.util.MultiValueMap<String,String> params) {
        return mapper.convertValue(service.registrations(id,params),new com.fasterxml.jackson.core.type.TypeReference<ListPage<ActivityRegistrationListItem>>() {});
    }

    @PostMapping("/api/v1/activity-registrations")
    @PreAuthorize("hasAnyRole('MEMBER') and (principal.claims['imp'] == true or !hasAnyRole('ADMIN','INSTRUCTOR'))")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, ACTIVITY_NOT_PUBLISHED, REGISTRATION_CLOSED, ACTIVITY_FULL, LEVEL_NOT_ALLOWED, ALREADY_REGISTERED, MEMBER_NOT_ACTIVE, BOOKING_BLOCKED, INACTIVITY_PERIOD, MODULE_DISABLED, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "register", description = "Roles: MEMBER.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "201", description = "ActivityRegistration", useReturnTypeSchema = true))
    public ActivityRegistration register(@Valid @RequestBody ActivityRegistrationRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey) {
        return mutation(() -> service.register(request.activityId(),Boolean.TRUE.equals(request.joinWaitlist())),ActivityRegistration.class,201);
    }

    @GetMapping("/api/v1/activity-registrations/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "registration", description = "Roles: ADMIN, INSTRUCTOR, MEMBER. MEMBER sees own registration only, including impersonation. Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "ActivityRegistration", useReturnTypeSchema = true))
    public ActivityRegistration registration(@PathVariable String id, @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt, org.springframework.security.core.Authentication authentication) {
        return view(service.registration(id,staff() && com.agilityhub.core.shared.application.CurrentUser.current().impersonation()==null),ActivityRegistration.class);
    }

    @PostMapping("/api/v1/activity-registrations/{id}/cancellation")
    @PreAuthorize("hasAnyRole('MEMBER') and (principal.claims['imp'] == true or !hasAnyRole('ADMIN','INSTRUCTOR'))")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_STATE, REGISTRATION_NOT_CANCELLABLE})
    @Operation(summary = "cancelRegistration", description = "Roles: MEMBER.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "ActivityRegistration", useReturnTypeSchema = true))
    public ActivityRegistration cancelRegistration(@PathVariable String id, @Valid @RequestBody RegistrationCancellationRequest request, @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt) {
        return mutation(() -> service.cancelRegistration(id,request.reason()),ActivityRegistration.class,200);
    }

    @GetMapping("/api/v1/me/activities")
    @PreAuthorize("hasAnyRole('MEMBER') and (principal.claims['imp'] == true or !hasAnyRole('ADMIN','INSTRUCTOR'))")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, DOG_NOT_ACCESSIBLE})
    @Operation(summary = "mine", description = "Roles: MEMBER.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "MeActivities", useReturnTypeSchema = true))
    public MeActivities mine(@RequestParam(required = false) String dogId) {
        return view(service.mine(dogId),MeActivities.class);
    }

    @GetMapping("/api/v1/me/activities/{activityId}")
    @PreAuthorize("hasAnyRole('MEMBER') and (principal.claims['imp'] == true or !hasAnyRole('ADMIN','INSTRUCTOR'))")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "memberDetail", description = "Roles: MEMBER.  Tenant comes from the JWT. Requires ACTIVITIES.", responses = @ApiResponse(responseCode = "200", description = "MemberActivityDetail", useReturnTypeSchema = true))
    public MemberActivityDetail memberDetail(@PathVariable String activityId) {
        return view(service.detail(activityId),MemberActivityDetail.class);
    }
}
