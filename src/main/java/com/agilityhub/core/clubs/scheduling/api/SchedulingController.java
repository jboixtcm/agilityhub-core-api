package com.agilityhub.core.clubs.scheduling.api;

import com.agilityhub.core.clubs.scheduling.application.*;
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

/** S06 planning, calendar and ring occupancy. */
@RestController
public class SchedulingController {
    private final WeekValidationUseCase validation; private final CalendarQuery calendarQuery; private final ClassSessionService sessions;
    private final ClassCancellationUseCase cancellations; private final RingBlockService ringBlocks; private final DayGridQuery grid;
    private final SessionProjection projections; private final SchedulingLists schedulingLists; private final SchedulingEvents events;
    private final SchedulingContractAccess access;
    private final TemplateQuery templates;
    private final TemplateService templateWrites;
    private final CoverageQuery coverage;
    private final WeekGenerationUseCase planning;
    private final com.agilityhub.core.shared.application.lists.ListEngine lists;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    public SchedulingController(SchedulingContractAccess access, TemplateQuery templates, TemplateService templateWrites,
            CoverageQuery coverage, WeekGenerationUseCase planning, com.agilityhub.core.shared.application.lists.ListEngine lists,
            com.fasterxml.jackson.databind.ObjectMapper mapper, WeekValidationUseCase validation, CalendarQuery calendarQuery, ClassSessionService sessions,
            ClassCancellationUseCase cancellations, RingBlockService ringBlocks, DayGridQuery grid, SessionProjection projections, SchedulingLists schedulingLists, SchedulingEvents events) {
        this.validation=validation; this.calendarQuery=calendarQuery; this.sessions=sessions; this.cancellations=cancellations; this.ringBlocks=ringBlocks;
        this.grid=grid; this.projections=projections; this.schedulingLists=schedulingLists; this.events=events;
        this.access = access; this.templates = templates; this.templateWrites = templateWrites; this.coverage = coverage;
        this.planning = planning; this.lists = lists; this.mapper = mapper;
    }
    private <T> T view(Object source, Class<T> type) { return mapper.convertValue(source, type); }


    @GetMapping("/api/v1/week-templates")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "templates", description = "Roles: ADMIN, INSTRUCTOR. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "WeekTemplates", useReturnTypeSchema = true))
    public WeekTemplates templates(@RequestParam(required = false) TemplateKind kind, @RequestParam(required = false) Boolean active) {
        access.tenant();
        return view(templates.list(kind, active), WeekTemplates.class);
    }

    @PostMapping("/api/v1/week-templates")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, DUPLICATE_NAME, TEMPLATE_KIND_MISMATCH})
    @Operation(summary = "createTemplate", description = "Roles: ADMIN. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "201", description = "WeekTemplate", useReturnTypeSchema = true))
    public WeekTemplate createTemplate(@Valid @RequestBody WeekTemplateCreateRequest request) {
        access.tenant();
        return view(templateWrites.create(request.name(), request.kind(), request.copyFromId()), WeekTemplate.class);
    }

    @GetMapping("/api/v1/week-templates/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "template", description = "Roles: ADMIN, INSTRUCTOR. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "WeekTemplate", useReturnTypeSchema = true))
    public WeekTemplate template(@PathVariable String id) {
        access.tenant();
        return view(templates.get(id), WeekTemplate.class);
    }

    @PatchMapping("/api/v1/week-templates/{id}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, STALE_VERSION, DUPLICATE_NAME})
    @Operation(summary = "patchTemplate", description = "Roles: ADMIN. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "WeekTemplate", useReturnTypeSchema = true))
    public WeekTemplate patchTemplate(@PathVariable String id, @Valid @RequestBody WeekTemplatePatchRequest request) {
        access.tenant();
        return view(templateWrites.patch(id, request.version, request.patch()), WeekTemplate.class);
    }

    @PostMapping("/api/v1/week-templates/{id}/bands")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, BAND_OVERLAP, INVALID_TIME_RANGE, INVALID_SLOT_GRANULARITY, OUTSIDE_OPENING_HOURS})
    @Operation(summary = "createBand", description = "Roles: ADMIN. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "201", description = "WeekTemplate", useReturnTypeSchema = true))
    public WeekTemplate createBand(@PathVariable String id, @Valid @RequestBody TimeBandCreateRequest request) {
        access.tenant();
        return view(templateWrites.addBand(id, request.startTime(), request.endTime()), WeekTemplate.class);
    }

    @PatchMapping("/api/v1/week-templates/{id}/bands/{bandId}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, STALE_VERSION, BAND_OVERLAP, INVALID_TIME_RANGE, INVALID_SLOT_GRANULARITY, OUTSIDE_OPENING_HOURS})
    @Operation(summary = "patchBand", description = "Roles: ADMIN. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "WeekTemplate", useReturnTypeSchema = true))
    public WeekTemplate patchBand(@PathVariable String id, @PathVariable String bandId, @Valid @RequestBody TimeBandPatchRequest request) {
        access.tenant();
        return view(templateWrites.patchBand(id, bandId, request.version(), request.startTime(), request.endTime()), WeekTemplate.class);
    }

    @DeleteMapping("/api/v1/week-templates/{id}/bands/{bandId}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, BAND_NOT_EMPTY})
    @Operation(summary = "deleteBand", description = "Roles: ADMIN. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void deleteBand(@PathVariable String id, @PathVariable String bandId) {
        access.tenant();
        templateWrites.deleteBand(id, bandId);
    }

    @PostMapping("/api/v1/week-templates/{id}/classes")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, TOO_MANY_INSTRUCTORS, LEVEL_REQUIRED, DESCRIPTION_REQUIRED})
    @Operation(summary = "createTemplateClass", description = "Roles: ADMIN. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "201", description = "WeekTemplate", useReturnTypeSchema = true))
    public WeekTemplate createTemplateClass(@PathVariable String id, @Valid @RequestBody TemplateClassCreateRequest request) {
        access.tenant();
        return view(templateWrites.addClass(id, request.bandId(), request.dayOfWeek(), request.instructorIds(), request.ringId(), request.levelIds(), request.capacity(), request.description()), WeekTemplate.class);
    }

    @PatchMapping("/api/v1/week-templates/{id}/classes/{classId}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, STALE_VERSION, TOO_MANY_INSTRUCTORS, LEVEL_REQUIRED, DESCRIPTION_REQUIRED})
    @Operation(summary = "patchTemplateClass", description = "Roles: ADMIN. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "WeekTemplate", useReturnTypeSchema = true))
    public WeekTemplate patchTemplateClass(@PathVariable String id, @PathVariable String classId, @Valid @RequestBody TemplateClassPatchRequest request) {
        access.tenant();
        return view(templateWrites.patchClass(id, classId, request.version, request.patch()), WeekTemplate.class);
    }

    @DeleteMapping("/api/v1/week-templates/{id}/classes/{classId}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "deleteTemplateClass", description = "Roles: ADMIN. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void deleteTemplateClass(@PathVariable String id, @PathVariable String classId) {
        access.tenant();
        templateWrites.deleteClass(id, classId);
    }

    @GetMapping("/api/v1/coverage")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, LEVELS_DISABLED})
    @Operation(summary = "coverage", description = "Roles: ADMIN, INSTRUCTOR. Tenant and role guards apply. Exactly one of templateId (optional saturdayTemplateId) or weekId is required. Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "Coverage", useReturnTypeSchema = true))
    public Coverage coverage(@RequestParam(required = false) String templateId, @RequestParam(required = false) String saturdayTemplateId, @RequestParam(required = false) String weekId) {
        access.tenant();
        return view(coverage.get(templateId, saturdayTemplateId, weekId), Coverage.class);
    }

    @GetMapping("/api/v1/weeks")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ListContract(filterable = {"id", "startDate", "state"}, sortable = {"startDate"}, paged = true,
            fields = {"id", "isoYear", "isoWeek", "startDate", "endDate", "state", "generatedAt", "validatedAt", "weekdayTemplateName", "saturdayTemplateName", "classCounts"})
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "weeks", description = "Roles: ADMIN, INSTRUCTOR. Tenant and role guards apply. With fields an item has id and the requested keys only (CONVENCIONS_API §4). Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "ListPage<WeekListItem>", useReturnTypeSchema = true))
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ListPage<WeekListItem> weeks(@io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestParam org.springframework.util.MultiValueMap<String, String> params) {
        access.tenant();
        // CONVENCIONS_API §4: the engine rows are sparse with `fields`, as WeekListItem publishes (only `id` required).
        return (ListPage) lists.list("weeks", params);
    }

    @GetMapping("/api/v1/weeks/generation-candidates")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "generationCandidates", description = "Roles: ADMIN. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "GenerationCandidates", useReturnTypeSchema = true))
    public GenerationCandidates generationCandidates() {
        access.tenant();
        return view(planning.candidates(), GenerationCandidates.class);
    }

    @PostMapping("/api/v1/weeks")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ApiResponse(responseCode = "200", description = "Existing week", useReturnTypeSchema = true)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "createWeek", description = "Roles: ADMIN. Tenant and role guards apply. 200 for an existing week; 201 for a new week. startDate must be Monday. Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "201", description = "Week", useReturnTypeSchema = true))
    public Week createWeek(@Valid @RequestBody WeekCreateRequest request, jakarta.servlet.http.HttpServletResponse response) {
        access.tenant();
        var result = planning.create(request.startDate());
        response.setStatus(result.created() ? 201 : 200);
        return view(result.week(), Week.class);
    }

    @GetMapping("/api/v1/weeks/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "week", description = "Roles: ADMIN, INSTRUCTOR. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "Week", useReturnTypeSchema = true))
    public Week week(@PathVariable String id) {
        access.tenant();
        return view(planning.get(id), Week.class);
    }

    @PostMapping("/api/v1/weeks/{id}/generation")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, WEEK_ALREADY_GENERATED, TEMPLATE_INCONSISTENT, TEMPLATE_KIND_MISMATCH, WEEK_IN_PAST, INVALID_STATE, STALE_VERSION, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "generate", description = "Roles: ADMIN. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "GenerationResult", useReturnTypeSchema = true))
    public GenerationResult generate(@PathVariable String id, @Valid @RequestBody GenerationRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey) {
        access.tenant();
        return view(planning.generate(id, request.weekdayTemplateId(), request.saturdayTemplateId()), GenerationResult.class);
    }

    @PostMapping("/api/v1/weeks/{id}/validation")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, WEEK_INCONSISTENT, NOTHING_TO_VALIDATE})
    @Operation(summary = "validateWeek", description = "Roles: ADMIN. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "ValidationResult", useReturnTypeSchema = true))
    public ValidationResult validateWeek(@PathVariable String id, @Valid @RequestBody EmptyRequest request) {
        access.tenant();
        access.week(id);
        return view(validation.validate(id), ValidationResult.class);
    }

    @GetMapping("/api/v1/weeks/{id}/calendar")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "calendar", description = "Roles: ADMIN, INSTRUCTOR. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "WeekCalendar", useReturnTypeSchema = true))
    public WeekCalendar calendar(@PathVariable String id, @RequestParam(defaultValue = "ACTIVE") CalendarFilter filter) {
        access.tenant();
        access.week(id);
        return view(calendarQuery.get(id, filter.name()), WeekCalendar.class);
    }

    @GetMapping("/api/v1/class-sessions")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ListContract(filterable = {"id", "date", "state", "ringId", "instructorId", "levelId", "weekId"}, sortable = {"startsAt", "date"}, paged = true,
            fields = {"id", "weekId", "date", "startTime", "endTime", "startsAt", "endsAt", "ringId", "levelIds", "instructorIds", "capacity", "capacityMode", "description",
                    "displayDescription", "state", "counters", "atRisk", "riskExempt", "cancellation", "origin", "version", "inconsistencyIds", "placementId", "notes"})
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "classes", description = "Roles: ADMIN, INSTRUCTOR. Tenant and role guards apply. MEMBER cannot list classes; use day-grid or class detail. With fields an item has id and the requested keys only (CONVENCIONS_API §4). Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "ListPage<ClassSessionListItem>", useReturnTypeSchema = true))
    public ListPage<ClassSessionListItem> classes(@io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestParam org.springframework.util.MultiValueMap<String,String> params) {
        access.tenant();
        return (ListPage) schedulingLists.list(lists, "class-sessions", params);
    }

    @PostMapping("/api/v1/class-sessions")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_TIME_RANGE, INVALID_SLOT_GRANULARITY, OUTSIDE_OPENING_HOURS, TOO_MANY_INSTRUCTORS, LEVEL_REQUIRED, DESCRIPTION_REQUIRED, RING_HAS_BOOKINGS, RING_BLOCKED})
    @Operation(summary = "createClass", description = "Roles: ADMIN. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "201", description = "ClassSession", useReturnTypeSchema = true))
    public ClassSession createClass(@Valid @RequestBody ClassSessionCreateRequest request) {
        access.tenant();
        return view(projections.session(sessions.create(request.date(),request.startTime(),request.endTime(),request.ringId(),request.levelIds(),request.instructorIds(),request.capacity(),request.description(),Boolean.TRUE.equals(request.cancelBookings())),false,java.util.List.of()),ClassSession.class);
    }

    @GetMapping("/api/v1/class-sessions/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @ApiResponse(responseCode = "200", description = "ADMIN/INSTRUCTOR or MEMBER projection", content = @Content(schema = @Schema(anyOf = {ClassSession.class, ClassSessionMemberView.class})))
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "classSession", description = "Roles: ADMIN, INSTRUCTOR, MEMBER. Tenant and role guards apply. MEMBER including impersonation sees only ACTIVE/FINISHED; no notes or instructor counts. Instructor visibility follows R-06-12. ADMIN/INSTRUCTOR also get instructorNames[] and ring (null without a ring). Tenant comes from the JWT.")
    public Object classSession(@PathVariable String id) {
        access.tenant();
        access.classSession(id);
        return projections.detail(sessions.require(id));
    }

    @PatchMapping("/api/v1/class-sessions/{id}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, STALE_VERSION, INVALID_STATE, CAPACITY_BELOW_BOOKINGS, INVALID_TIME_RANGE, INVALID_SLOT_GRANULARITY, OUTSIDE_OPENING_HOURS, TOO_MANY_INSTRUCTORS, LEVEL_REQUIRED, DESCRIPTION_REQUIRED, RING_HAS_BOOKINGS, RING_BLOCKED})
    @Operation(summary = "patchClass", description = "Roles: ADMIN. Tenant and role guards apply. The date field is forbidden (400 VALIDATION_ERROR). Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "ClassSession", useReturnTypeSchema = true))
    public ClassSession patchClass(@PathVariable String id, @Valid @RequestBody ClassSessionPatchRequest request) {
        access.tenant();
        access.classSession(id);
        return view(projections.session(sessions.patch(id,request.version,request.patch(),Boolean.TRUE.equals(request.cancelBookings)),false,java.util.List.of()),ClassSession.class);
    }

    @GetMapping("/api/v1/class-sessions/{id}/cancellation-preview")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "classCancellationPreview", description = "Roles: ADMIN. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "CancellationPreview", useReturnTypeSchema = true))
    public CancellationPreview classCancellationPreview(@PathVariable String id) {
        access.tenant();
        access.classSession(id);
        return view(cancellations.preview(id),CancellationPreview.class);
    }

    @PostMapping("/api/v1/class-sessions/{id}/cancellation")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_STATE, ADMIN_TEXT_REQUIRED, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "cancelClass", description = "Roles: ADMIN. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "ClassSession", useReturnTypeSchema = true))
    public ClassSession cancelClass(@PathVariable String id, @Valid @RequestBody ClassCancellationRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey) {
        access.tenant();
        access.classSession(id);
        return view(projections.session(cancellations.cancel(id,ClassCancellationReason.valueOf(request.reason().name()),request.adminText(),events.actor()),false,java.util.List.of()),ClassSession.class);
    }

    @PostMapping("/api/v1/class-sessions/{id}/risk-exemption")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_STATE})
    @Operation(summary = "exemptClass", description = "Roles: ADMIN. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "ClassSession", useReturnTypeSchema = true))
    public ClassSession exemptClass(@PathVariable String id, @Valid @RequestBody RiskExemptionRequest request) {
        access.tenant();
        access.classSession(id);
        return view(projections.session(sessions.exemption(id,request.exempt()),false,java.util.List.of()),ClassSession.class);
    }

    @GetMapping("/api/v1/ring-blocks")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @ListContract(filterable = {"id", "ringId", "kind", "reason", "state", "from", "to"}, sortable = {"from"}, paged = true,
            fields = {"id", "ringId", "from", "to", "date", "fromLocal", "toLocal", "kind", "reason", "activityId", "activityTitle", "state", "version", "note", "createdByName"})
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "blocks", description = "Roles: ADMIN, INSTRUCTOR, MEMBER. Tenant and role guards apply. MEMBER rows leave out note and createdByName (as RingBlockMemberView), and asking for them in fields is INVALID_FILTER. With fields an item has id and the requested keys only (CONVENCIONS_API §4). Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "ListPage<RingBlockListItem>", useReturnTypeSchema = true))
    public ListPage<RingBlockListItem> blocks(@io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestParam org.springframework.util.MultiValueMap<String,String> params) {
        access.tenant();
        return (ListPage) schedulingLists.list(lists, "ring-blocks", params);
    }

    @GetMapping("/api/v1/ring-blocks/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @ApiResponse(responseCode = "200", description = "Staff or redacted MEMBER projection", content = @Content(schema = @Schema(anyOf = {RingBlock.class, RingBlockMemberView.class})))
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "block", description = "Roles: ADMIN, INSTRUCTOR, MEMBER. Tenant and role guards apply.  Tenant comes from the JWT.")
    public Object block(@PathVariable String id) {
        access.tenant();
        access.block(id);
        return projections.block(ringBlocks.require(id),projections.member());
    }

    @PostMapping("/api/v1/ring-blocks")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_TIME_RANGE, INVALID_SLOT_GRANULARITY, OUTSIDE_OPENING_HOURS, RING_BLOCK_CONFLICT, RING_HAS_BOOKINGS, MODULE_DISABLED, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "createBlock", description = "Roles: ADMIN, INSTRUCTOR. Tenant and role guards apply. RESERVATION requires FREE_TRAINING; cancelBookings requires ADMIN. Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "201", description = "RingBlock", useReturnTypeSchema = true))
    public RingBlock createBlock(@Valid @RequestBody RingBlockCreateRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey) {
        access.tenant();
        return view(projections.block(ringBlocks.create(request.ringId(),request.from(),request.to(),request.kind(),request.reason(),request.note(),Boolean.TRUE.equals(request.cancelBookings())),false),RingBlock.class);
    }

    @PatchMapping("/api/v1/ring-blocks/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, STALE_VERSION, INVALID_STATE, RING_BLOCK_CONFLICT, RING_BLOCK_MANAGED_BY_ACTIVITY, RING_HAS_BOOKINGS, INVALID_TIME_RANGE, INVALID_SLOT_GRANULARITY, OUTSIDE_OPENING_HOURS, MODULE_DISABLED})
    @Operation(summary = "patchBlock", description = "Roles: ADMIN, INSTRUCTOR. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "RingBlock", useReturnTypeSchema = true))
    public RingBlock patchBlock(@PathVariable String id, @Valid @RequestBody RingBlockPatchRequest request) {
        access.tenant();
        access.block(id);
        return view(projections.block(ringBlocks.patch(id,request.version,request.patch(),Boolean.TRUE.equals(request.cancelBookings)),false),RingBlock.class);
    }

    @PostMapping("/api/v1/ring-blocks/{id}/cancellation")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_STATE, RING_BLOCK_MANAGED_BY_ACTIVITY})
    @Operation(summary = "cancelBlock", description = "Roles: ADMIN, INSTRUCTOR. Tenant and role guards apply.  Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "RingBlock", useReturnTypeSchema = true))
    public RingBlock cancelBlock(@PathVariable String id, @Valid @RequestBody EmptyRequest request) {
        access.tenant();
        access.block(id);
        return view(projections.block(ringBlocks.cancel(id),false),RingBlock.class);
    }

    @GetMapping("/api/v1/day-grid")
    @PreAuthorize("isAuthenticated() and (#view != 'instructor' or hasAnyRole('ADMIN','INSTRUCTOR'))")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "dayGrid", description = "Roles: ADMIN, INSTRUCTOR, MEMBER, AGILITYHUB_ADMIN. Tenant and role guards apply. Every authenticated role may use member view; instructor view requires INSTRUCTOR/ADMIN. Impersonation permits only member view. Tenant comes from the JWT.", responses = @ApiResponse(responseCode = "200", description = "DayGrid", useReturnTypeSchema = true))
    public DayGrid dayGrid(@RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate date, @RequestParam(defaultValue = "member") @Schema(allowableValues = {"member", "instructor"}) String view) {
        access.tenant();
        // CONVENCIONS_API §5-§6 (E5-T20): the error names its field, like every other 400 VALIDATION_ERROR.
        if (!java.util.Set.of("member", "instructor").contains(view)) {
            throw new com.agilityhub.core.shared.domain.ApiException(VALIDATION_ERROR, java.util.Map.of("field", "view",
                    "fieldErrors", java.util.List.of(java.util.Map.of("field", "view", "code", "INVALID_VALUE"))));
        }
        return view(grid.get(date,view.equals("instructor")),DayGrid.class);
    }
}
