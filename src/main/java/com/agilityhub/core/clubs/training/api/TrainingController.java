package com.agilityhub.core.clubs.training.api;

import com.agilityhub.core.clubs.training.application.TrainingContractAccess;
import com.agilityhub.core.clubs.training.domain.TrainingBookingState;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.contract.AllowsImpersonation;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.application.contract.ListContract;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.training.api.TrainingContracts.*;
import static com.agilityhub.core.clubs.training.api.TrainingRequests.*;
import static com.agilityhub.core.shared.application.contract.ApiContracts.ExportAccepted;
import static com.agilityhub.core.shared.application.contract.ApiContracts.ListPage;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S09 WP-09-A: free-training slots and bookings, all behind FREE_TRAINING. `/ring-blocks*` stays in
 * SchedulingController (E4-T01/E4-T03). Guards run, then 501 NOT_IMPLEMENTED until E5-T04.
 */
@RestController
@RequiresModule(Module.FREE_TRAINING)
public class TrainingController {
    static final String MEMBER = "hasRole('MEMBER') and (principal.claims['imp'] == true or !hasAnyRole('ADMIN','INSTRUCTOR'))";
    private final TrainingContractAccess access;
    public TrainingController(TrainingContractAccess access) { this.access = access; }

    private static String memberId(Jwt jwt) {
        var user = CurrentUser.current();
        return user != null && user.impersonation() != null ? user.impersonation().memberId() : jwt.getClaimAsString("memberId");
    }
    private static boolean staff() {
        var user = CurrentUser.current();
        return (user == null || user.impersonation() == null) && SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN") || authority.getAuthority().equals("ROLE_INSTRUCTOR"));
    }

    @GetMapping("/api/v1/training-slots")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, MODULE_DISABLED, DOG_NOT_ACCESSIBLE})
    @Operation(summary = "trainingSlots", description = "Roles: MEMBER (also the impersonation token; interval cut to the booking window, R-09-04), INSTRUCTOR, ADMIN (at most 31 days; cells add occupants[], block and classSession). Requires FREE_TRAINING. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "TrainingSlots", useReturnTypeSchema = true))
    public TrainingSlots trainingSlots(@RequestParam LocalDate from, @RequestParam LocalDate to, @RequestParam(required = false) String dogId,
            @RequestParam(required = false) String ringId) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/me/training-summary")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, MODULE_DISABLED, DOG_NOT_ACCESSIBLE})
    @Operation(summary = "trainingSummary", description = "Roles: MEMBER (also the impersonation token). Eligible dogs (R-09-01), default dog (R-09-09) and the counter of the training week of date (R-09-05; default today). Requires FREE_TRAINING. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "TrainingSummary", useReturnTypeSchema = true))
    public TrainingSummary trainingSummary(@RequestParam(required = false) String dogId, @RequestParam(required = false) LocalDate date) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/me/training-bookings")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, MODULE_DISABLED})
    @Operation(summary = "memberTrainingBookings", description = "Roles: MEMBER (also the impersonation token). Own and family-group bookings with cancellableUntil. Requires FREE_TRAINING. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "MemberTrainingBookings", useReturnTypeSchema = true))
    public MemberTrainingBookings memberTrainingBookings(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) TrainingBookingState state) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/training-bookings")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, DOG_NOT_ACCESSIBLE, SLOT_TAKEN, TRAINING_LIMIT_REACHED, DOG_ALREADY_BOOKED, SLOT_NOT_ON_GRID,
            DOG_NOT_ALLOWED, RING_NOT_RESERVABLE, SLOT_OUT_OF_WINDOW, CLUB_CLOSED, BOOKING_BLOCKED, INACTIVITY_PERIOD, MEMBER_NOT_ACTIVE,
            OVERRIDE_NOT_ALLOWED, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "bookTraining", description = "Roles: MEMBER (also the impersonation token: origin BACKOFFICE, override{limit, reason} allowed). R-09-06 transaction; the partial unique index {clubId, ringId, startsAt, seatIndex} on ACTIVE is the final guard. details: TRAINING_LIMIT_REACHED{limit, used, weekStart, weekEnd, cancellableBookings[]}, SLOT_TAKEN{ringId, startsAt, reason, freeRings[]}, SLOT_OUT_OF_WINDOW{from, to}. Requires FREE_TRAINING. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "201", description = "TrainingBooking", useReturnTypeSchema = true))
    public TrainingBooking bookTraining(@Valid @RequestBody TrainingBookingRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/training-bookings/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "trainingBooking", description = "Roles: MEMBER (own, also the impersonation token), INSTRUCTOR, ADMIN. Requires FREE_TRAINING. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "TrainingBooking", useReturnTypeSchema = true))
    public TrainingBooking trainingBooking(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.booking(id, memberId(jwt), staff());
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/training-bookings/{id}/cancellation")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, TRAINING_CANCEL_TOO_LATE, INVALID_STATE, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "cancelTraining", description = "Roles: MEMBER (own, also the impersonation token). R-09-10: until startsAt - training.cancelThresholdMinutes; later only with the impersonation token and a reason (ADMIN_LATE). details TRAINING_CANCEL_TOO_LATE{thresholdMinutes, minutesBefore}. An optional Idempotency-Key replays the same 200. Requires FREE_TRAINING. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "TrainingBooking", useReturnTypeSchema = true))
    public TrainingBooking cancelTraining(@PathVariable String id, @Valid @RequestBody(required = false) TrainingCancellationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) @Schema(format = "uuid") java.util.UUID idempotencyKey, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.booking(id, memberId(jwt), false);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/training-bookings")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ListContract(filterable = {"date", "ringId", "memberId", "dogId", "state", "origin"}, sortable = {"startsAt"},
            columns = {"date*", "startsAtLocal*", "ringName*", "memberName*", "dogName*", "state*", "origin", "createdAt"}, paged = true, exportable = true)
    @ContractErrors({VALIDATION_ERROR, INVALID_FILTER, MODULE_DISABLED, IMPERSONATION_DENIED})
    @Operation(summary = "trainingBookings", description = "Roles: ADMIN, INSTRUCTOR (read); MEMBER → 403; impersonation → IMPERSONATION_DENIED. Ring usage register, universal list (CONVENCIONS_API §4), listKey training-bookings. Requires FREE_TRAINING. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "ListPage<TrainingBookingListItem>", useReturnTypeSchema = true))
    public ListPage<TrainingBookingListItem> trainingBookings() {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/training-bookings/export")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ListContract(filterable = {"date", "ringId", "memberId", "dogId", "state", "origin"}, sortable = {"startsAt"},
            columns = {"date*", "startsAtLocal*", "ringName*", "memberName*", "dogName*", "state*", "origin", "createdAt"}, paged = true, exportable = false)
    @ContractErrors({VALIDATION_ERROR, INVALID_FILTER, MODULE_DISABLED, IMPERSONATION_DENIED, EXPORT_TOO_LARGE, EXPORT_LIMIT, RATE_LIMITED})
    @Operation(summary = "exportTrainingBookings", description = "Roles: ADMIN, INSTRUCTOR. Same q/filter/sort and selected columns as GET /training-bookings (listKey training-bookings); 200 file or 202 ExportAccepted. Requires FREE_TRAINING. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = {@ApiResponse(responseCode = "200", description = "Export file", content = {@Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", schema = @Schema(type = "string", format = "binary"))}),
                    @ApiResponse(responseCode = "202", description = "Queued export", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExportAccepted.class)))})
    public org.springframework.http.ResponseEntity<byte[]> exportTrainingBookings(@RequestParam @Schema(allowableValues = {"xlsx", "pdf"}) String format,
            @RequestParam(required = false) String columns) {
        access.tenant();
        throw new UnsupportedOperationException();
    }
}
