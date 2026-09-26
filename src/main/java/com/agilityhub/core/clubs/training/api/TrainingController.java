package com.agilityhub.core.clubs.training.api;

import com.agilityhub.core.clubs.common.application.ListExportService;
import com.agilityhub.core.clubs.training.application.*;
import com.agilityhub.core.clubs.training.domain.TrainingBookingState;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.IdempotentOperation;
import com.agilityhub.core.shared.application.contract.AllowsImpersonation;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.application.contract.ListContract;
import com.agilityhub.core.shared.application.lists.ListEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * S09 WP-09-B: free-training slots and bookings, all behind FREE_TRAINING. `/ring-blocks*` stays in
 * SchedulingController (E4-T03), fed by the real `TrainingConflictPort` / `TrainingOccupancyPort` of this context.
 */
@RestController
@RequiresModule(Module.FREE_TRAINING)
public class TrainingController {
    static final String MEMBER = "hasRole('MEMBER') and (principal.claims['imp'] == true or !hasAnyRole('ADMIN','INSTRUCTOR'))";
    private final TrainingContractAccess access; private final TrainingSlotService slots; private final TrainingQueryService queries;
    private final TrainingBookingService service; private final TrainingTransactions transactions; private final ListEngine lists;
    private final ListExportService exports; private final ObjectMapper mapper;
    public TrainingController(TrainingContractAccess access, TrainingSlotService slots, TrainingQueryService queries, TrainingBookingService service,
            TrainingTransactions transactions, ListEngine lists, ListExportService exports, ObjectMapper mapper) {
        this.access = access; this.slots = slots; this.queries = queries; this.service = service; this.transactions = transactions; this.lists = lists;
        this.exports = exports; this.mapper = mapper;
    }
    private <T> T view(Object value, Class<T> type) { return mapper.convertValue(value, type); }

    private static String memberId(Jwt jwt) {
        var user = CurrentUser.current();
        return user != null && user.impersonation() != null ? user.impersonation().memberId() : jwt.getClaimAsString("memberId");
    }
    private static boolean staff() {
        var user = CurrentUser.current();
        return (user == null || user.impersonation() == null) && SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN") || authority.getAuthority().equals("ROLE_INSTRUCTOR"));
    }
    /** Runs a keyed mutation in one retried transaction that also stores the Idempotency-Key response (R-09-06). */
    private <T> T idempotent(java.util.List<String> lanes, int status, java.util.function.Supplier<T> work) {
        return transactions.write(lanes, () -> {
            IdempotentOperation.lock();
            var result = work.get();
            try { IdempotentOperation.complete(status, mapper.writeValueAsBytes(result)); }
            catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
            return result;
        });
    }

    @GetMapping("/api/v1/training-slots")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, MODULE_DISABLED, DOG_NOT_ACCESSIBLE})
    @Operation(summary = "trainingSlots", description = "Roles: MEMBER (also the impersonation token; interval cut to the booking window, R-09-04), INSTRUCTOR, ADMIN (at most 31 days; cells add occupants[], block and classSession). R-09-03 computed grid: holidays and days without opening hours are closed; cells BLOCKED/CLASS > BLOCKED/RING_BLOCK > BOOKED/TRAINING (OWN_TRAINING for dogId) > FREE. Requires FREE_TRAINING. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "TrainingSlots", useReturnTypeSchema = true))
    public TrainingSlots trainingSlots(@RequestParam LocalDate from, @RequestParam LocalDate to, @RequestParam(required = false) String dogId,
            @RequestParam(required = false) String ringId, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        boolean staff = staff();
        return view(slots.grid(from, to, dogId, ringId, new TrainingSlotService.Viewer(staff ? null : memberId(jwt), staff)), TrainingSlots.class);
    }

    @GetMapping("/api/v1/me/training-summary")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, MODULE_DISABLED, DOG_NOT_ACCESSIBLE})
    @Operation(summary = "trainingSummary", description = "Roles: MEMBER (also the impersonation token). Eligible dogs (R-09-01), default dog (R-09-09) and the counter of the training week of date (R-09-05; default today, counted by session date). Requires FREE_TRAINING. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "TrainingSummary", useReturnTypeSchema = true))
    public TrainingSummary trainingSummary(@RequestParam(required = false) String dogId, @RequestParam(required = false) LocalDate date, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        return view(queries.summary(memberId(jwt), dogId, date), TrainingSummary.class);
    }

    @GetMapping("/api/v1/me/training-bookings")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, MODULE_DISABLED})
    @Operation(summary = "memberTrainingBookings", description = "Roles: MEMBER (also the impersonation token). Own and family-group bookings with cancellableUntil. Requires FREE_TRAINING. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "MemberTrainingBookings", useReturnTypeSchema = true))
    public MemberTrainingBookings memberTrainingBookings(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) TrainingBookingState state, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        return new MemberTrainingBookings(queries.mine(memberId(jwt), from, to, state).stream().map(b -> view(b, TrainingBooking.class)).toList());
    }

    @PostMapping("/api/v1/training-bookings")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, DOG_NOT_ACCESSIBLE, SLOT_TAKEN, TRAINING_LIMIT_REACHED, DOG_ALREADY_BOOKED, SLOT_NOT_ON_GRID,
            DOG_NOT_ALLOWED, RING_NOT_RESERVABLE, SLOT_OUT_OF_WINDOW, CLUB_CLOSED, BOOKING_BLOCKED, INACTIVITY_PERIOD, MEMBER_NOT_ACTIVE,
            OVERRIDE_NOT_ALLOWED, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "bookTraining", description = "Roles: MEMBER (also the impersonation token: origin BACKOFFICE, override{limit, reason} allowed). R-09-06 transaction; the partial unique index {clubId, ringId, startsAt, seatIndex} on ACTIVE is the final guard; without ringId («Qualsevol») the first FREE ring in catalog order. details: TRAINING_LIMIT_REACHED{limit, used, weekStart, weekEnd, cancellableBookings[]}, SLOT_TAKEN{ringId, startsAt, reason, freeRings[]}, SLOT_OUT_OF_WINDOW{from, to}. A repeated Idempotency-Key returns the same response, also a 409/422. Requires FREE_TRAINING. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "201", description = "TrainingBooking", useReturnTypeSchema = true))
    public TrainingBooking bookTraining(@Valid @RequestBody TrainingBookingRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        var actor = TrainingActor.member(memberId(jwt));
        var override = request.override() == null ? null : new TrainingBookingService.Override(Boolean.TRUE.equals(request.override().limit()), request.override().reason());
        return idempotent(service.lanes(request.dogId(), actor.memberId(), request.startsAt()), 201, () -> view(queries.view(
                service.book(actor, request.dogId(), request.startsAt(), request.ringId(), override, idempotencyKey.toString())), TrainingBooking.class));
    }

    @GetMapping("/api/v1/training-bookings/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "trainingBooking", description = "Roles: MEMBER (own or family group, also the impersonation token), INSTRUCTOR, ADMIN; another member's booking is 404. Requires FREE_TRAINING. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "TrainingBooking", useReturnTypeSchema = true))
    public TrainingBooking trainingBooking(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        boolean staff = staff();
        return view(queries.view(queries.visible(id, staff ? null : memberId(jwt), staff)), TrainingBooking.class);
    }

    @PostMapping("/api/v1/training-bookings/{id}/cancellation")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, TRAINING_CANCEL_TOO_LATE, INVALID_STATE, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "cancelTraining", description = "Roles: MEMBER (own or family group, also the impersonation token). R-09-10: until startsAt - training.cancelThresholdMinutes; later only with the impersonation token and a reason (ADMIN_LATE, audited). A booking block never prevents it; a second call is INVALID_STATE. details TRAINING_CANCEL_TOO_LATE{thresholdMinutes, minutesBefore}. An optional Idempotency-Key replays the same 200. Requires FREE_TRAINING. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "TrainingBooking", useReturnTypeSchema = true))
    public TrainingBooking cancelTraining(@PathVariable String id, @Valid @RequestBody(required = false) TrainingCancellationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) @Schema(format = "uuid") java.util.UUID idempotencyKey, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        var booking = queries.visible(id, memberId(jwt), false);
        var actor = TrainingActor.member(memberId(jwt));
        return idempotent(java.util.List.of("dog:" + booking.dogId(), "member:" + booking.memberId()), 200,
                () -> view(queries.view(service.cancel(id, actor, request == null ? null : request.reason())), TrainingBooking.class));
    }

    @GetMapping("/api/v1/training-bookings")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ListContract(filterable = {"date", "ringId", "memberId", "dogId", "state", "origin"}, sortable = {"startsAt"},
            columns = {"date*", "startsAtLocal*", "ringName*", "memberName*", "dogName*", "state*", "origin", "createdAt"}, paged = true, exportable = true,
            fields = {"id", "date", "startsAt", "startsAtLocal", "ringId", "ringName", "memberId", "memberName", "dogId", "dogName", "state", "origin", "createdAt"})
    @ContractErrors({VALIDATION_ERROR, INVALID_FILTER, MODULE_DISABLED, IMPERSONATION_DENIED})
    @Operation(summary = "trainingBookings", description = "Roles: ADMIN, INSTRUCTOR (read); MEMBER → 403; impersonation → IMPERSONATION_DENIED. Ring usage register, universal list (CONVENCIONS_API §4), listKey training-bookings; an undeclared filter is INVALID_FILTER. Without fields every item property is sent; with fields an item has id and the requested keys only. Requires FREE_TRAINING. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "ListPage<TrainingBookingListItem>", useReturnTypeSchema = true))
    public ListPage<TrainingBookingListItem> trainingBookings(
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestParam org.springframework.util.MultiValueMap<String, String> params) {
        access.tenant();
        var page = queries.list(lists, params);
        // E5-T22 (CONVENCIONS_API §4): the record would send an unrequested key as null; `fields` leaves it out instead.
        return com.agilityhub.core.shared.application.lists.SparseItems.apply(mapper, new ListPage<>(page.items().stream().map(item -> view(item, TrainingBookingListItem.class)).toList(),
                page.page(), page.size(), page.totalItems(), page.totalPages(), page.appliedFilters()), params, "id");
    }

    @GetMapping("/api/v1/training-bookings/export")
    @PreAuthorize("hasRole('ADMIN')")
    @ListContract(filterable = {"date", "ringId", "memberId", "dogId", "state", "origin"}, sortable = {"startsAt"},
            columns = {"date*", "startsAtLocal*", "ringName*", "memberName*", "dogName*", "state*", "origin", "createdAt"}, paged = true, exportable = false)
    @ContractErrors({VALIDATION_ERROR, INVALID_FILTER, MODULE_DISABLED, IMPERSONATION_DENIED, EXPORT_TOO_LARGE, EXPORT_LIMIT, RATE_LIMITED})
    @Operation(summary = "exportTrainingBookings", description = "Roles: ADMIN (S14 R-14-12: list exports are ADMIN only; INSTRUCTOR → 403). Same q/filter/sort and selected columns as GET /training-bookings (listKey training-bookings); 200 file or 202 ExportAccepted. Requires FREE_TRAINING. Tenant comes from the JWT.",
            responses = {@ApiResponse(responseCode = "200", description = "Export file", content = {@Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", schema = @Schema(type = "string", format = "binary"))}),
                    @ApiResponse(responseCode = "202", description = "Queued export", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExportAccepted.class)))})
    public org.springframework.http.ResponseEntity<?> exportTrainingBookings(@RequestParam @Schema(allowableValues = {"xlsx", "pdf"}) String format,
            @RequestParam(required = false) String columns,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestParam org.springframework.util.MultiValueMap<String, String> params) {
        access.tenant();
        var result = exports.export("training-bookings", format, columns, params);
        if (result.file() == null) {
            String url = "/api/v1/exports/" + result.jobId();
            return org.springframework.http.ResponseEntity.accepted().location(java.net.URI.create(url)).body(new ExportAccepted(result.jobId(), url));
        }
        return org.springframework.http.ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"" + result.fileName() + "\"")
                .header("Cache-Control", "no-store")
                .contentType(org.springframework.http.MediaType.parseMediaType(format.equals("pdf") ? "application/pdf" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(result.file());
    }
}
