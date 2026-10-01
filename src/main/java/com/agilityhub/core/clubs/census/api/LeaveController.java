package com.agilityhub.core.clubs.census.api;

import com.agilityhub.core.clubs.census.application.LifecycleContractAccess;
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
 * S13 §6 leave requests, planned leave and reactivation (screen 15, D10's «Baixa (amb data)», «Inactivitats i baixes»); no
 * module (R-13-19: the leave is always there). Member routes: MEMBER, also the impersonation token (`origin = BACKOFFICE`),
 * on their own requests only. Admin routes: ADMIN without impersonation; INSTRUCTOR → 403. Every operation runs its guards
 * and then answers 501 NOT_IMPLEMENTED until E8-T05. Error statuses are CATALEG_ERRORS' (§1 and rule 0), whatever S13 §6
 * writes (LEAVE_DATE_INVALID, LEAVE_REASON_UNKNOWN, MEMBER_NOT_LEFT and NO_PLANNED_LEAVE are 422).
 */
@RestController
public class LeaveController {
    static final String MEMBER_ROLES = "Roles: MEMBER, also the impersonation token (ADMIN- or INSTRUCTOR-only tokens → 403). No module. ";
    static final String ADMIN_ROLES = "Roles: ADMIN (MEMBER, INSTRUCTOR → 403; impersonation → 403 IMPERSONATION_DENIED). No module. ";
    static final String STUB = InactivityController.STUB;
    private final LifecycleContractAccess access;
    public LeaveController(LifecycleContractAccess access) { this.access = access; }

    @GetMapping("/api/v1/me/leave-requests")
    @PreAuthorize(InactivityController.MEMBER)
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND})
    @Operation(summary = "myLeaveContext", description = MEMBER_ROLES + "Screen 15's context (S13 §6): the inactivity offer (INACTIVITY on and a "
            + "plan that may request it), the fee with BILLING, today in the club's time zone, leave.fullMonthIfLater, leave.npsEnabled, the MEMBER "
            + "reasons of leave.reasons in the reader's locale, the caller's planned leave and requests." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "MeLeaveContext", useReturnTypeSchema = true))
    public MeLeaveContext myLeaveContext() {
        access.me();
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/me/leave-requests")
    @PreAuthorize(InactivityController.MEMBER)
    @AllowsImpersonation
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MEMBER_ERASED, READ_ONLY, MEMBER_NOT_ACTIVE, LEAVE_ALREADY_REQUESTED, LEAVE_ALREADY_SCHEDULED,
            IDEMPOTENCY_KEY_REUSED, LEAVE_DATE_INVALID, LEAVE_REASON_UNKNOWN})
    @Operation(summary = "requestLeave", description = MEMBER_ROLES + "R-13-09 [ENVIA LA SOL·LICITUD]: a PENDING request of the caller (origin APP, "
            + "or BACKOFFICE under impersonation, audited); LeaveRequested → N-14 with the localized reason. Only an ACTIVE member (422 "
            + "MEMBER_NOT_ACTIVE), one PENDING request (409 LEAVE_ALREADY_REQUESTED) and no planned leave (409 LEAVE_ALREADY_SCHEDULED); "
            + "requestedDate today or later (422 LEAVE_DATE_INVALID); reasonKey of leave.reasons (422 LEAVE_REASON_UNKNOWN, rule 0: S13 writes "
            + "400); nps only with leave.npsEnabled (403 READ_ONLY, §1: T-13-15 writes 400). The same Idempotency-Key answers the same request." + STUB,
            responses = @ApiResponse(responseCode = "201", description = "LeaveRequest", useReturnTypeSchema = true))
    public LeaveRequest requestLeave(@Valid @RequestBody LeaveCreateRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.mutableMe();
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/me/leave-requests/{id}/cancellation")
    @PreAuthorize(InactivityController.MEMBER)
    @AllowsImpersonation
    @ContractErrors({NOT_FOUND, LEAVE_INVALID_STATE})
    @Operation(summary = "withdrawMyLeave", description = MEMBER_ROLES + "R-13-09 [RETIRA LA SOL·LICITUD]: a PENDING request → CANCELLED{MEMBER, "
            + "WITHDRAWN}, LeaveCancelled, LEAVE_CANCELLED audit; otherwise 409 LEAVE_INVALID_STATE. Idempotent by effect. Another member's request, "
            + "also of the caller's family group → 404." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "LeaveRequest", useReturnTypeSchema = true))
    public LeaveRequest withdrawMyLeave(@PathVariable String id) {
        access.ownRequest(id);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/leave-requests")
    @PreAuthorize(InactivityController.ADMIN)
    @ListContract(filterable = {"memberId", "state", "source", "requestedDate", "effectiveDate", "reasonKey", "nps"},
            sortable = {"requestedAt", "requestedDate", "effectiveDate"},
            columns = {"member*", "requestedDate*", "reasonKey*", "state*", "source", "requestedAt", "effectiveDate", "nps", "comment"}, paged = true,
            fields = {"id", "member", "source", "state", "requestedAt", "requestedDate", "effectiveDate", "reasonKey", "nps", "comment"})
    @ContractErrors({INVALID_FILTER})
    @Operation(summary = "leaveRequests", description = ADMIN_ROLES + "«Inactivitats i baixes» › «Baixes» (universal list, CONVENCIONS_API §4): "
            + "by default state:eq:PENDING ordered by requestedAt; q searches the member's name. An undeclared filter, sort or fields key → 400 "
            + "INVALID_FILTER." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "LeaveRequestPage",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = LeaveRequestPage.class))))
    public Object leaveRequests(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        access.tenant();
        access.requestList(params);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/leave-requests/{id}")
    @PreAuthorize(InactivityController.ADMIN)
    @ContractErrors({NOT_FOUND})
    @Operation(summary = "leaveRequest", description = ADMIN_ROLES + "D10's drawer: the request with its decision and cancelled bookings. Another "
            + "club's → 404 (T-13-24)." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "LeaveRequest", useReturnTypeSchema = true))
    public LeaveRequest leaveRequest(@PathVariable String id) {
        access.request(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/leave-requests/{id}/decision")
    @PreAuthorize(InactivityController.ADMIN)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, LEAVE_INVALID_STATE, LEAVE_DATE_INVALID})
    @Operation(summary = "decideLeave", description = ADMIN_ROLES + "R-13-10 [Aprova] / [Denega] of a PENDING request (409 LEAVE_INVALID_STATE). "
            + "APPROVED: effectiveDate (default requestedDate, today or later: 422 LEAVE_DATE_INVALID) becomes Member.leaveDate; bookings after it are "
            + "cancelled (R-13-12), live inactivity periods closed; the member stays active up to leaveDate included. LeaveResolved → N-28, "
            + "LEAVE_RESOLVED audit; the answer's member carries the leaveDate. Idempotent by effect." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "LeaveRequest", useReturnTypeSchema = true))
    public LeaveRequest decideLeave(@PathVariable String id, @Valid @RequestBody LeaveDecisionRequest request) {
        access.request(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/members/{id}/leave")
    @PreAuthorize(InactivityController.ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MEMBER_ERASED, MEMBER_NOT_ACTIVE, LEAVE_ALREADY_SCHEDULED, LEAVE_DATE_INVALID, LEAVE_REASON_UNKNOWN})
    @Operation(summary = "scheduleLeave", description = ADMIN_ROLES + "R-13-10 direct leave from D10: an APPROVED LeaveRequest{source ADMIN, "
            + "origin BACKOFFICE} with the same effects as an approval; a PENDING one becomes CANCELLED{ADMIN}. Only an ACTIVE member (422 "
            + "MEMBER_NOT_ACTIVE) without a planned leave (409 LEAVE_ALREADY_SCHEDULED). Another club's member → 404; an erased one → 409 MEMBER_ERASED." + STUB,
            responses = @ApiResponse(responseCode = "201", description = "LeaveRequest", useReturnTypeSchema = true))
    public LeaveRequest scheduleLeave(@PathVariable String id, @Valid @RequestBody DirectLeaveRequest request) {
        access.mutableMember(id);
        throw new UnsupportedOperationException();
    }

    @DeleteMapping("/api/v1/members/{id}/planned-leave")
    @PreAuthorize(InactivityController.ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({NOT_FOUND, MEMBER_ERASED, NO_PLANNED_LEAVE, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "cancelPlannedLeave", description = ADMIN_ROLES + "R-13-15 [Anul·la la baixa prevista]: an ACTIVE member with a leaveDate "
            + "loses it, its LeaveRequest becomes CANCELLED{ADMIN}, LeaveCancelled → N-28 (variant), LEAVE_CANCELLED audit; billing returns to "
            + "normal; the cancelled bookings are not restored. Without a planned leave → 422 NO_PLANNED_LEAVE (rule 0, S13 writes 409). No body." + STUB,
            responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void cancelPlannedLeave(@PathVariable String id, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.mutableMember(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/members/{id}/reactivation")
    @PreAuthorize(InactivityController.ADMIN)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MEMBER_ERASED, MEMBER_NOT_LEFT})
    @Operation(summary = "reactivateMember", description = ADMIN_ROLES + "R-13-16 [Reactiva l'abonat]: a LEFT member becomes ACTIVE with the same "
            + "number (leaveHistory +1, leaveDate/leftAt/leftReason cleared, Membership ACTIVE, dogs stay INACTIVE, no entry fee); "
            + "MemberStatusChanged{LEFT→ACTIVE}, audited. With BILLING planId, priceId and nextInvoiceDate are required (400 VALIDATION_ERROR). "
            + "Not LEFT → 422 MEMBER_NOT_LEFT (rule 0, S13 writes 409)." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "Member", useReturnTypeSchema = true))
    public CensusResponses.Member reactivateMember(@PathVariable String id, @Valid @RequestBody ReactivationRequest request) {
        access.mutableMember(id);
        throw new UnsupportedOperationException();
    }
}
