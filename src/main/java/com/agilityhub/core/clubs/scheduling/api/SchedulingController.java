package com.agilityhub.core.clubs.scheduling.api;

import com.agilityhub.core.clubs.scheduling.application.SchedulingContractAccess;
import com.agilityhub.core.clubs.scheduling.domain.*;
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
import static com.agilityhub.core.clubs.scheduling.api.SchedulingContracts.*;
import static com.agilityhub.core.clubs.scheduling.api.SchedulingRequests.*;

/** Reserved S06 operations. Guards execute before the standard NOT_IMPLEMENTED response. */
@RestController
public class SchedulingController {
    private final SchedulingContractAccess access;
    public SchedulingController(SchedulingContractAccess access) { this.access = access; }

    @GetMapping("/api/v1/week-templates")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "templates", description = "Roles: ADMIN, INSTRUCTOR. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "WeekTemplates", useReturnTypeSchema = true))
    public WeekTemplates templates(@RequestParam(required = false) TemplateKind kind, @RequestParam(required = false) Boolean active) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/week-templates")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, DUPLICATE_NAME})
    @Operation(summary = "createTemplate", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "201", description = "WeekTemplate", useReturnTypeSchema = true))
    public WeekTemplate createTemplate(@Valid @RequestBody WeekTemplateCreateRequest request) {
        access.tenant();
        if (request.copyFromId() != null) { access.template(request.copyFromId()); }
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/week-templates/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "template", description = "Roles: ADMIN, INSTRUCTOR. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "WeekTemplate", useReturnTypeSchema = true))
    public WeekTemplate template(@PathVariable String id) {
        access.tenant();
        access.template(id);
        throw new UnsupportedOperationException();
    }

    @PatchMapping("/api/v1/week-templates/{id}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, STALE_VERSION, DUPLICATE_NAME})
    @Operation(summary = "patchTemplate", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "WeekTemplate", useReturnTypeSchema = true))
    public WeekTemplate patchTemplate(@PathVariable String id, @Valid @RequestBody WeekTemplatePatchRequest request) {
        access.tenant();
        access.template(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/week-templates/{id}/bands")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, BAND_OVERLAP, INVALID_TIME_RANGE, INVALID_SLOT_GRANULARITY, OUTSIDE_OPENING_HOURS})
    @Operation(summary = "createBand", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "201", description = "WeekTemplate", useReturnTypeSchema = true))
    public WeekTemplate createBand(@PathVariable String id, @Valid @RequestBody TimeBandCreateRequest request) {
        access.tenant();
        access.template(id);
        throw new UnsupportedOperationException();
    }

    @PatchMapping("/api/v1/week-templates/{id}/bands/{bandId}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, STALE_VERSION, BAND_OVERLAP, INVALID_TIME_RANGE, INVALID_SLOT_GRANULARITY, OUTSIDE_OPENING_HOURS})
    @Operation(summary = "patchBand", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "WeekTemplate", useReturnTypeSchema = true))
    public WeekTemplate patchBand(@PathVariable String id, @PathVariable String bandId, @Valid @RequestBody TimeBandPatchRequest request) {
        access.tenant();
        access.band(id, bandId);
        throw new UnsupportedOperationException();
    }

    @DeleteMapping("/api/v1/week-templates/{id}/bands/{bandId}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, BAND_NOT_EMPTY})
    @Operation(summary = "deleteBand", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void deleteBand(@PathVariable String id, @PathVariable String bandId) {
        access.tenant();
        access.band(id, bandId);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/week-templates/{id}/classes")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, TOO_MANY_INSTRUCTORS, LEVEL_REQUIRED, DESCRIPTION_REQUIRED})
    @Operation(summary = "createTemplateClass", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "201", description = "WeekTemplate", useReturnTypeSchema = true))
    public WeekTemplate createTemplateClass(@PathVariable String id, @Valid @RequestBody TemplateClassCreateRequest request) {
        access.tenant();
        access.band(id, request.bandId());
        throw new UnsupportedOperationException();
    }

    @PatchMapping("/api/v1/week-templates/{id}/classes/{classId}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, STALE_VERSION, TOO_MANY_INSTRUCTORS, LEVEL_REQUIRED, DESCRIPTION_REQUIRED})
    @Operation(summary = "patchTemplateClass", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "WeekTemplate", useReturnTypeSchema = true))
    public WeekTemplate patchTemplateClass(@PathVariable String id, @PathVariable String classId, @Valid @RequestBody TemplateClassPatchRequest request) {
        access.tenant();
        access.templateClass(id, classId);
        throw new UnsupportedOperationException();
    }

    @DeleteMapping("/api/v1/week-templates/{id}/classes/{classId}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "deleteTemplateClass", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void deleteTemplateClass(@PathVariable String id, @PathVariable String classId) {
        access.tenant();
        access.templateClass(id, classId);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/coverage")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, LEVELS_DISABLED})
    @Operation(summary = "coverage", description = "Roles: ADMIN, INSTRUCTOR. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Exactly one of templateId (optional saturdayTemplateId) or weekId is required. Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "Coverage", useReturnTypeSchema = true))
    public Coverage coverage(@RequestParam(required = false) String templateId, @RequestParam(required = false) String saturdayTemplateId, @RequestParam(required = false) String weekId) {
        access.tenant();
        access.coverage(templateId, saturdayTemplateId, weekId);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/weeks")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ListContract(filterable = {"startDate", "state"}, sortable = {"startDate"}, paged = true)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "weeks", description = "Roles: ADMIN, INSTRUCTOR. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "ListPage<WeekListItem>", useReturnTypeSchema = true))
    public ListPage<WeekListItem> weeks() {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/weeks/generation-candidates")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "generationCandidates", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "GenerationCandidates", useReturnTypeSchema = true))
    public GenerationCandidates generationCandidates() {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/weeks")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "200", description = "Existing week", useReturnTypeSchema = true)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "createWeek", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. 200 for an existing week; 201 for a new week. startDate must be Monday. Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "201", description = "Week", useReturnTypeSchema = true))
    public Week createWeek(@Valid @RequestBody WeekCreateRequest request) {
        access.tenant();
        if (request.startDate().getDayOfWeek() != java.time.DayOfWeek.MONDAY) { throw new com.agilityhub.core.shared.domain.ApiException(VALIDATION_ERROR); }
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/weeks/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "week", description = "Roles: ADMIN, INSTRUCTOR. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "Week", useReturnTypeSchema = true))
    public Week week(@PathVariable String id) {
        access.tenant();
        access.week(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/weeks/{id}/generation")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, WEEK_ALREADY_GENERATED, TEMPLATE_INCONSISTENT, TEMPLATE_KIND_MISMATCH, WEEK_IN_PAST, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "generate", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "GenerationResult", useReturnTypeSchema = true))
    public GenerationResult generate(@PathVariable String id, @Valid @RequestBody GenerationRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey) {
        access.tenant();
        access.week(id); access.template(request.weekdayTemplateId()); if (request.saturdayTemplateId() != null) { access.template(request.saturdayTemplateId()); }
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/weeks/{id}/validation")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, WEEK_INCONSISTENT, NOTHING_TO_VALIDATE})
    @Operation(summary = "validateWeek", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "ValidationResult", useReturnTypeSchema = true))
    public ValidationResult validateWeek(@PathVariable String id, @Valid @RequestBody EmptyRequest request) {
        access.tenant();
        access.week(id);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/weeks/{id}/calendar")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "calendar", description = "Roles: ADMIN, INSTRUCTOR. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "WeekCalendar", useReturnTypeSchema = true))
    public WeekCalendar calendar(@PathVariable String id, @RequestParam(defaultValue = "ACTIVE") CalendarFilter filter) {
        access.tenant();
        access.week(id);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/class-sessions")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ListContract(filterable = {"date", "state", "ringId", "instructorId", "levelId", "weekId"}, sortable = {"startsAt", "date"}, paged = true)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "classes", description = "Roles: ADMIN, INSTRUCTOR. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. MEMBER cannot list classes; use day-grid or class detail. Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "ListPage<ClassSession>", useReturnTypeSchema = true))
    public ListPage<ClassSession> classes() {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/class-sessions")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_TIME_RANGE, INVALID_SLOT_GRANULARITY, OUTSIDE_OPENING_HOURS, TOO_MANY_INSTRUCTORS, LEVEL_REQUIRED, DESCRIPTION_REQUIRED, RING_HAS_BOOKINGS, RING_BLOCKED})
    @Operation(summary = "createClass", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "201", description = "ClassSession", useReturnTypeSchema = true))
    public ClassSession createClass(@Valid @RequestBody ClassSessionCreateRequest request) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/class-sessions/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @ApiResponse(responseCode = "200", description = "ADMIN/INSTRUCTOR or MEMBER projection", content = @Content(schema = @Schema(anyOf = {ClassSession.class, ClassSessionMemberView.class})))
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "classSession", description = "Roles: ADMIN, INSTRUCTOR, MEMBER. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. MEMBER including impersonation sees only ACTIVE/FINISHED; no notes or instructor counts. Instructor visibility follows R-06-12. Tenant comes from the JWT.")
    public ClassSession classSession(@PathVariable String id) {
        access.tenant();
        access.classSession(id);
        throw new UnsupportedOperationException();
    }

    @PatchMapping("/api/v1/class-sessions/{id}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, STALE_VERSION, INVALID_STATE, CAPACITY_BELOW_BOOKINGS, INVALID_TIME_RANGE, INVALID_SLOT_GRANULARITY, OUTSIDE_OPENING_HOURS, TOO_MANY_INSTRUCTORS, LEVEL_REQUIRED, DESCRIPTION_REQUIRED, RING_HAS_BOOKINGS, RING_BLOCKED})
    @Operation(summary = "patchClass", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. The date field is forbidden (400 VALIDATION_ERROR). Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "ClassSession", useReturnTypeSchema = true))
    public ClassSession patchClass(@PathVariable String id, @Valid @RequestBody ClassSessionPatchRequest request) {
        access.tenant();
        access.classSession(id);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/class-sessions/{id}/cancellation-preview")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "classCancellationPreview", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "CancellationPreview", useReturnTypeSchema = true))
    public CancellationPreview classCancellationPreview(@PathVariable String id) {
        access.tenant();
        access.classSession(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/class-sessions/{id}/cancellation")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_STATE, ADMIN_TEXT_REQUIRED, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "cancelClass", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "ClassSession", useReturnTypeSchema = true))
    public ClassSession cancelClass(@PathVariable String id, @Valid @RequestBody ClassCancellationRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey) {
        access.tenant();
        access.classSession(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/class-sessions/{id}/risk-exemption")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_STATE})
    @Operation(summary = "exemptClass", description = "Roles: ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "ClassSession", useReturnTypeSchema = true))
    public ClassSession exemptClass(@PathVariable String id, @Valid @RequestBody RiskExemptionRequest request) {
        access.tenant();
        access.classSession(id);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/ring-blocks")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @ListContract(filterable = {"ringId", "kind", "reason", "state", "from", "to"}, sortable = {"from"}, paged = true)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "blocks", description = "Roles: ADMIN, INSTRUCTOR, MEMBER. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. MEMBER receives RingBlockMemberView without note or createdByName. Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "ListPage<RingBlock>", useReturnTypeSchema = true))
    public ListPage<RingBlock> blocks() {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/ring-blocks/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @ApiResponse(responseCode = "200", description = "Staff or redacted MEMBER projection", content = @Content(schema = @Schema(anyOf = {RingBlock.class, RingBlockMemberView.class})))
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "block", description = "Roles: ADMIN, INSTRUCTOR, MEMBER. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.")
    public RingBlock block(@PathVariable String id) {
        access.tenant();
        access.block(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/ring-blocks")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_TIME_RANGE, INVALID_SLOT_GRANULARITY, OUTSIDE_OPENING_HOURS, RING_BLOCK_CONFLICT, RING_HAS_BOOKINGS, MODULE_DISABLED, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "createBlock", description = "Roles: ADMIN, INSTRUCTOR. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. RESERVATION requires FREE_TRAINING; cancelBookings requires ADMIN. Conditional write rules enforced in E4-T03. Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "201", description = "RingBlock", useReturnTypeSchema = true))
    public RingBlock createBlock(@Valid @RequestBody RingBlockCreateRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @PatchMapping("/api/v1/ring-blocks/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, STALE_VERSION, INVALID_STATE, RING_BLOCK_CONFLICT, RING_BLOCK_MANAGED_BY_ACTIVITY, RING_HAS_BOOKINGS, INVALID_TIME_RANGE, INVALID_SLOT_GRANULARITY, OUTSIDE_OPENING_HOURS, MODULE_DISABLED})
    @Operation(summary = "patchBlock", description = "Roles: ADMIN, INSTRUCTOR. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "RingBlock", useReturnTypeSchema = true))
    public RingBlock patchBlock(@PathVariable String id, @Valid @RequestBody RingBlockPatchRequest request) {
        access.tenant();
        access.block(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/ring-blocks/{id}/cancellation")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_STATE, RING_BLOCK_MANAGED_BY_ACTIVITY})
    @Operation(summary = "cancelBlock", description = "Roles: ADMIN, INSTRUCTOR. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "RingBlock", useReturnTypeSchema = true))
    public RingBlock cancelBlock(@PathVariable String id, @Valid @RequestBody EmptyRequest request) {
        access.tenant();
        access.block(id);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/day-grid")
    @PreAuthorize("isAuthenticated() and (#view != 'instructor' or hasAnyRole('ADMIN','INSTRUCTOR'))")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "dayGrid", description = "Roles: ADMIN, INSTRUCTOR, MEMBER, AGILITYHUB_ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Every authenticated role may use member view; instructor view requires INSTRUCTOR/ADMIN. Impersonation permits only member view. Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "DayGrid", useReturnTypeSchema = true))
    public DayGrid dayGrid(@RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate date, @RequestParam(defaultValue = "member") @Schema(allowableValues = {"member", "instructor"}) String view) {
        access.tenant();
        if (!java.util.Set.of("member", "instructor").contains(view)) { throw new com.agilityhub.core.shared.domain.ApiException(VALIDATION_ERROR); }
        throw new UnsupportedOperationException();
    }
}
