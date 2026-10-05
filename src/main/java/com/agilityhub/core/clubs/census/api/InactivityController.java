package com.agilityhub.core.clubs.census.api;

import com.agilityhub.core.clubs.census.application.LifecycleContractAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.AllowsImpersonation;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.application.contract.ListContract;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.census.api.LifecycleContracts.*;
import static com.agilityhub.core.clubs.census.api.LifecycleRequests.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S13 §6 inactivity periods (screens 14 and 12, D10's «Inactivitat», «Inactivitats i baixes»), module `INACTIVITY` on every
 * route (off → 404). Member routes: MEMBER, also the impersonation token (`origin = BACKOFFICE`, R-13-18), on their own
 * periods only (another member's, also of their family group → 404). Admin routes: ADMIN without impersonation
 * (`IMPERSONATION_DENIED`); INSTRUCTOR → 403 (MATRIU «Inactivitat / baixa»). Every operation runs its guards and then
 * answers 501 NOT_IMPLEMENTED until E8-T05. Error statuses are CATALEG_ERRORS' (§1 and rule 0), whatever S13 §6 writes.
 */
@RestController
@RequiresModule(Module.INACTIVITY)
public class InactivityController {
    static final String MEMBER = "hasRole('MEMBER')";
    static final String ADMIN = "hasRole('ADMIN') and principal.claims['imp'] != true";
    static final String MEMBER_ROLES = "Roles: MEMBER, also the impersonation token (ADMIN- or INSTRUCTOR-only tokens → 403). INACTIVITY off → 404 MODULE_DISABLED. ";
    static final String ADMIN_ROLES = "Roles: ADMIN (MEMBER, INSTRUCTOR → 403; impersonation → 403 IMPERSONATION_DENIED). INACTIVITY off → 404 MODULE_DISABLED. ";
    static final String STUB = " Contract only; returns 501 NOT_IMPLEMENTED after the tenant, role, module and resource guards (E8-T01). Tenant comes from the JWT.";
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.clubs.census.application.InactivityPeriodService service;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.clubs.census.application.LifecycleViews views;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.clubs.census.application.LifecycleTransactions transactions;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.shared.application.lists.ListEngine lists;
    @org.springframework.beans.factory.annotation.Autowired private com.fasterxml.jackson.databind.ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.clubs.census.application.CensusQuery queries;
    private <T> T mapped(Object value, Class<T> type) { return mapper.convertValue(value, type); }
    private final LifecycleContractAccess access;
    public InactivityController(LifecycleContractAccess access) { this.access = access; }

    @GetMapping("/api/v1/me/inactivity-periods")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ContractErrors({NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "myInactivityContext", description = MEMBER_ROLES + "Screen 14's context (S13 §6 JSON): the first month the caller may "
            + "still change E(today) (R-13-01, inactivity.requestDeadlineDay in the club's time zone), the fee with BILLING, and the caller's own "
            + "periods with what each still lets them change.",
            responses = @ApiResponse(responseCode = "200", description = "MeInactivityContext", useReturnTypeSchema = true))
    public MeInactivityContext myInactivityContext() {
        access.me();
        return mapped(views.inactivityContext(access.callerMember()), MeInactivityContext.class);
    }

    @GetMapping("/api/v1/me/inactivity-periods/preview")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, INACTIVITY_INVALID_RANGE})
    @Operation(summary = "previewInactivity", description = MEMBER_ROLES + "Screen 14's yellow note and fee card (R-13-06/08), recomputed at each "
            + "change of months: the caller's live bookings inside the months (a counter of a module that is off is absent) and the fee per month. "
            + "toMonth before fromMonth → 422 INACTIVITY_INVALID_RANGE; a malformed month → 400 VALIDATION_ERROR.",
            responses = @ApiResponse(responseCode = "200", description = "InactivityPreview", useReturnTypeSchema = true))
    public InactivityPreview previewInactivity(@RequestParam @Schema(pattern = LifecycleContracts.MONTH) String fromMonth,
            @RequestParam(required = false) @Schema(pattern = LifecycleContracts.MONTH) String toMonth) {
        access.me();
        access.month("fromMonth", fromMonth, true);
        access.month("toMonth", toMonth, false);
        return mapped(views.preview(access.callerMember(), fromMonth, toMonth), InactivityPreview.class);
    }

    @PostMapping("/api/v1/me/inactivity-periods")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MEMBER_ERASED, MODULE_DISABLED, MEMBER_NOT_ACTIVE, LEAVE_ALREADY_SCHEDULED, INACTIVITY_OVERLAP,
            IDEMPOTENCY_KEY_REUSED, INACTIVITY_DEADLINE_PASSED, INACTIVITY_INVALID_RANGE, INACTIVITY_NOT_APPLICABLE})
    @Operation(summary = "requestInactivity", description = MEMBER_ROLES + "R-13-02 [ENVIA LA SOL·LICITUD]: a REQUESTED period of the caller "
            + "(origin APP, or BACKOFFICE under impersonation, audited with both ids); InactivityRequested → N-18a. Only an ACTIVE member (422 "
            + "MEMBER_NOT_ACTIVE) without a planned leave (409 LEAVE_ALREADY_SCHEDULED) on a MONTHLY plan (a PACK or SINGLE_CLASS plan → 422 "
            + "INACTIVITY_NOT_APPLICABLE); fromMonth ≥ E(today) (422 INACTIVITY_DEADLINE_PASSED {earliestMonth}) and ≤ E(today) + "
            + "inactivity.maxStartMonthsAhead, toMonth null or ≥ fromMonth (422 INACTIVITY_INVALID_RANGE); no overlap nor adjacency with a live "
            + "period (409 INACTIVITY_OVERLAP {periodId, hint: EXTEND}). The same Idempotency-Key answers the same period.",
            responses = @ApiResponse(responseCode = "201", description = "InactivityPeriod", useReturnTypeSchema = true))
    public InactivityPeriod requestInactivity(@Valid @RequestBody InactivityRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.mutableMe();
        return transactions.run(() -> mapped(views.period(service.request(access.callerMember(), request.fromMonth(), request.toMonth(), request.comments(), false, false)), InactivityPeriod.class));
    }

    @PatchMapping("/api/v1/me/inactivity-periods/{id}")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, INACTIVITY_INVALID_STATE, STALE_VERSION, INACTIVITY_DEADLINE_PASSED, INACTIVITY_INVALID_RANGE})
    @Operation(summary = "changeMyInactivity", description = MEMBER_ROLES + "R-13-04 [MODIFICA]: REQUESTED everything, APPROVED the months and "
            + "comments, ACTIVE only toMonth (lengthen, shorten, set or open the end), within R-13-03's day-25 rule (422 INACTIVITY_DEADLINE_PASSED "
            + "{earliestMonth}); applied without a new approval, history +1, InactivityChanged → N-18d; bookings in added months are cancelled "
            + "(R-13-06). FINISHED/DENIED/CANCELLED → 409 INACTIVITY_INVALID_STATE; an old version → 409 STALE_VERSION (the version comes with each "
            + "period of GET /me/inactivity-periods). Another member's → 404. Field presence: an omitted field stays, toMonth: null opens the "
            + "period, fromMonth: null or an unknown field → 400 VALIDATION_ERROR.",
            responses = @ApiResponse(responseCode = "200", description = "InactivityPeriod", useReturnTypeSchema = true))
    public InactivityPeriod changeMyInactivity(@PathVariable String id, @Valid @RequestBody InactivityPatchRequest request) {
        access.ownPeriod(id);
        access.mutableMe(); return transactions.run(() -> mapped(views.period(service.change(id, request.patch(), request.version, false, false)), InactivityPeriod.class));
    }

    @PostMapping("/api/v1/me/inactivity-periods/{id}/cancellation")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ContractErrors({NOT_FOUND, MODULE_DISABLED, INACTIVITY_INVALID_STATE, INACTIVITY_DEADLINE_PASSED})
    @Operation(summary = "withdrawMyInactivity", description = MEMBER_ROLES + "R-13-04 [RETIRA LA SOL·LICITUD]: REQUESTED always, APPROVED only "
            + "while fromMonth ≥ E(today) (422 INACTIVITY_DEADLINE_PASSED) → CANCELLED{MEMBER, WITHDRAWN}, InactivityCancelled; an ACTIVE one is "
            + "shortened instead (409 INACTIVITY_INVALID_STATE). Idempotent by effect. Another member's → 404.",
            responses = @ApiResponse(responseCode = "200", description = "InactivityPeriod", useReturnTypeSchema = true))
    public InactivityPeriod withdrawMyInactivity(@PathVariable String id) {
        access.ownPeriod(id);
        access.mutableMe(); return transactions.run(() -> mapped(views.period(service.cancel(id, false)), InactivityPeriod.class));
    }

    @GetMapping("/api/v1/inactivity-periods")
    @PreAuthorize(ADMIN)
    @ListContract(filterable = {"memberId", "state", "fromMonth", "toMonth", "origin", "requestedAt"}, sortable = {"fromMonth", "requestedAt", "memberLastName"},
            columns = {"member*", "fromMonth*", "toMonth*", "state*", "origin", "requestedAt", "comments"}, paged = true,
            fields = {"id", "member", "fromMonth", "toMonth", "state", "origin", "requestedAt", "comments", "feeSnapshot", "decision"})
    @ContractErrors({INVALID_FILTER, MODULE_DISABLED})
    @Operation(summary = "inactivityPeriods", description = ADMIN_ROLES + "«Inactivitats i baixes» › «Inactivitats» (universal list, CONVENCIONS_API "
            + "§4): by default state:in:REQUESTED,APPROVED,ACTIVE ordered by fromMonth; q searches the member's name. An undeclared filter, sort or "
            + "fields key → 400 INVALID_FILTER.",
            responses = @ApiResponse(responseCode = "200", description = "InactivityPeriodPage",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = InactivityPeriodPage.class))))
    public Object inactivityPeriods(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        access.tenant();
        access.periodList(params);
        return lists.list("inactivity-periods", params);
    }

    @GetMapping("/api/v1/inactivity-periods/{id}")
    @PreAuthorize(ADMIN)
    @ContractErrors({NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "inactivityPeriod", description = ADMIN_ROLES + "D10's drawer: the period with its history and cancelled bookings. "
            + "Another club's → 404.",
            responses = @ApiResponse(responseCode = "200", description = "InactivityPeriod", useReturnTypeSchema = true))
    public InactivityPeriod inactivityPeriod(@PathVariable String id) {
        access.period(id);
        return mapped(views.period(service.get(id)), InactivityPeriod.class);
    }

    @PostMapping("/api/v1/inactivity-periods")
    @PreAuthorize(ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, MEMBER_ERASED, MEMBER_NOT_ACTIVE, LEAVE_ALREADY_SCHEDULED, INACTIVITY_OVERLAP,
            INACTIVITY_DEADLINE_PASSED, INACTIVITY_INVALID_RANGE, INACTIVITY_NOT_APPLICABLE})
    @Operation(summary = "createInactivity", description = ADMIN_ROLES + "R-13-05 D10 «Nou període d'inactivitat»: created and approved at once "
            + "(origin BACKOFFICE, feeSnapshot frozen, bookings inside cancelled, ACTIVE at once from the 1st of fromMonth); overrideDeadline skips "
            + "the day-25 rule (decision.deadlineOverridden, audited). InactivityResolved{APPROVED} → N-18b, INACTIVITY_RESOLVED audit. The same "
            + "checks as the member's request; another club's member → 404; an erased one → 409 MEMBER_ERASED.",
            responses = @ApiResponse(responseCode = "201", description = "InactivityPeriod", useReturnTypeSchema = true))
    public InactivityPeriod createInactivity(@Valid @RequestBody AdminInactivityRequest request) {
        access.mutableMember(request.memberId());
        return transactions.run(() -> mapped(views.period(service.request(request.memberId(), request.fromMonth(), request.toMonth(), request.comments(), true, Boolean.TRUE.equals(request.overrideDeadline()))), InactivityPeriod.class));
    }

    @PostMapping("/api/v1/inactivity-periods/{id}/decision")
    @PreAuthorize(ADMIN)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, INACTIVITY_INVALID_STATE})
    @Operation(summary = "decideInactivity", description = ADMIN_ROLES + "R-13-05 [Aprova] / [Denega]: only a REQUESTED period (409 "
            + "INACTIVITY_INVALID_STATE). APPROVED freezes feeSnapshot, cancels the bookings inside (R-13-06, cancelledBookings) and is ACTIVE at once "
            + "from the 1st of fromMonth (InactivityStarted in the same transaction); DENIED changes nothing else. InactivityResolved → N-18b with "
            + "the note, INACTIVITY_RESOLVED audit. Idempotent by effect.",
            responses = @ApiResponse(responseCode = "200", description = "InactivityPeriod", useReturnTypeSchema = true))
    public InactivityPeriod decideInactivity(@PathVariable String id, @Valid @RequestBody DecisionRequest request) {
        access.period(id);
        return transactions.run(() -> mapped(views.period(service.decide(id, request.decision(), request.note())), InactivityPeriod.class));
    }

    @PatchMapping("/api/v1/inactivity-periods/{id}")
    @PreAuthorize(ADMIN)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, INACTIVITY_INVALID_STATE, STALE_VERSION, INACTIVITY_DEADLINE_PASSED, INACTIVITY_INVALID_RANGE})
    @Operation(summary = "changeInactivity", description = ADMIN_ROLES + "R-13-05 [Modifica els mesos]: as the member's change (R-13-04), with "
            + "overrideDeadline; months already billed are not recomputed (an ADJUSTMENT invoice in S12). An old version → 409 STALE_VERSION. "
            + "Field presence: an omitted field stays, toMonth: null opens the period, fromMonth: null or an unknown field → 400 VALIDATION_ERROR.",
            responses = @ApiResponse(responseCode = "200", description = "InactivityPeriod", useReturnTypeSchema = true))
    public InactivityPeriod changeInactivity(@PathVariable String id, @Valid @RequestBody AdminInactivityPatchRequest request) {
        access.period(id);
        return transactions.run(() -> mapped(views.period(service.change(id, request.patch(), request.version, true, Boolean.TRUE.equals(request.overrideDeadline))), InactivityPeriod.class));
    }

    @PostMapping("/api/v1/inactivity-periods/{id}/termination")
    @PreAuthorize(ADMIN)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, INACTIVITY_INVALID_STATE, INACTIVITY_INVALID_RANGE})
    @Operation(summary = "terminateInactivity", description = ADMIN_ROLES + "R-13-05 [Finalitza el període]: an ACTIVE period ends at toMonth ≥ "
            + "fromMonth (422 INACTIVITY_INVALID_RANGE): before the current month → FINISHED now (finishReason ADMIN, InactivityEnded → N-18c); "
            + "otherwise toMonth is set and the scheduler closes it. Not ACTIVE → 409 INACTIVITY_INVALID_STATE. Idempotent by effect.",
            responses = @ApiResponse(responseCode = "200", description = "InactivityPeriod", useReturnTypeSchema = true))
    public InactivityPeriod terminateInactivity(@PathVariable String id, @Valid @RequestBody TerminationRequest request) {
        access.period(id);
        return transactions.run(() -> mapped(views.period(service.terminate(id, request.toMonth())), InactivityPeriod.class));
    }

    @PostMapping("/api/v1/inactivity-periods/{id}/cancellation")
    @PreAuthorize(ADMIN)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, INACTIVITY_INVALID_STATE})
    @Operation(summary = "cancelInactivity", description = ADMIN_ROLES + "[Anul·la]: an APPROVED period not started yet → CANCELLED{ADMIN}, "
            + "InactivityCancelled; the cancelled bookings are not restored. Otherwise → 409 INACTIVITY_INVALID_STATE. Idempotent by effect.",
            responses = @ApiResponse(responseCode = "200", description = "InactivityPeriod", useReturnTypeSchema = true))
    public InactivityPeriod cancelInactivity(@PathVariable String id, @Valid @RequestBody(required = false) AdminCancellationRequest request) {
        access.period(id);
        return transactions.run(() -> mapped(views.period(service.cancel(id, true)), InactivityPeriod.class));
    }
}
