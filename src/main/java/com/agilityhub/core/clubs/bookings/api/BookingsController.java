package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.bookings.application.BookingContractAccess;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.bookings.api.BookingContracts.*;
import static com.agilityhub.core.clubs.bookings.api.BookingRequests.*;
import static com.agilityhub.core.shared.application.contract.ApiContracts.ListPage;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S08 WP-08-A: class bookings, seat holds and waiting list. Every operation runs the tenant, role, ownership
 * and module guards and then answers 501 NOT_IMPLEMENTED until E5-T02 (bookings) and E5-T03 (waiting list).
 */
@RestController
public class BookingsController {
    static final String MEMBER = "hasRole('MEMBER') and (principal.claims['imp'] == true or !hasAnyRole('ADMIN','INSTRUCTOR'))";
    private final BookingContractAccess access;
    public BookingsController(BookingContractAccess access) { this.access = access; }

    private static String memberId(Jwt jwt) {
        var user = CurrentUser.current();
        return user != null && user.impersonation() != null ? user.impersonation().memberId() : jwt.getClaimAsString("memberId");
    }
    private static boolean staff() {
        var user = CurrentUser.current();
        return (user == null || user.impersonation() == null) && SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN") || authority.getAuthority().equals("ROLE_INSTRUCTOR"));
    }

    @GetMapping("/api/v1/me/home")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, DOG_NOT_ACCESSIBLE})
    @Operation(summary = "memberHome", description = "Roles: MEMBER (also the impersonation token). S08 screen 03. dogId absent = «Tots». Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "MeHome", useReturnTypeSchema = true))
    public MeHome memberHome(@RequestParam(required = false) String dogId) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/me/bookable-classes")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, DOG_NOT_ACCESSIBLE})
    @Operation(summary = "bookableClasses", description = "Roles: MEMBER (also the impersonation token). S08 screen 04, horizon until the end of W2; dogId absent = proposed dog (Member.lastDogForClass). Row states per R-08-03. SINGLE_CLASS off: no price. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "BookableClasses", useReturnTypeSchema = true))
    public BookableClasses bookableClasses(@RequestParam(required = false) String dogId) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/seat-holds")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, DOG_NOT_ACCESSIBLE, MODULE_DISABLED, CLASS_FULL, CLASS_NOT_BOOKABLE, NOT_YET_OPEN, BOOKING_LIMIT_REACHED,
            BOOKING_BLOCKED, INACTIVITY_PERIOD, MEMBER_NOT_ACTIVE, LEVEL_NOT_ALLOWED, ALREADY_BOOKED, PACK_EMPTY, SEAT_TAKEN,
            WAITLIST_NOT_NOTIFIED, WAITLIST_OFFER_EXPIRED})
    @Operation(summary = "holdSeat", description = "Roles: MEMBER (also the impersonation token). R-08-07: one Mongo transaction serialised by seat_locks; re-entering refreshes the same hold. waitlistEntryId requires WAITLIST. details: CLASS_FULL{heldOnly}, NOT_YET_OPEN{opensAt}, BOOKING_LIMIT_REACHED{unit, week, limit, current, swappable[], notSelectable[], nextBookableAt}, INACTIVITY_PERIOD{from, to}. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "201", description = "SeatHoldResponse", useReturnTypeSchema = true))
    public SeatHoldResponse holdSeat(@Valid @RequestBody SeatHoldRequest request) {
        access.tenant();
        access.waitlistModule(request.waitlistEntryId() != null);
        throw new UnsupportedOperationException();
    }

    @DeleteMapping("/api/v1/seat-holds/{id}")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({VALIDATION_ERROR})
    @Operation(summary = "releaseSeat", description = "Roles: MEMBER owner of the hold (also the impersonation token). 204 also when the hold is already gone (expired or consumed). Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void releaseSeat(@PathVariable String id) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/bookings")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, SEAT_HOLD_EXPIRED, SWAP_NOT_ALLOWED, CLASS_FULL, CLASS_NOT_BOOKABLE, NOT_YET_OPEN, BOOKING_LIMIT_REACHED,
            BOOKING_BLOCKED, INACTIVITY_PERIOD, MEMBER_NOT_ACTIVE, LEVEL_NOT_ALLOWED, ALREADY_BOOKED, PACK_EMPTY, SEAT_TAKEN, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "confirmBooking", description = "Roles: MEMBER (also the impersonation token: origin BACKOFFICE). R-08-08 confirmation (or R-08-09 atomic swap with swapBookingId); Idempotency-Key = seatHoldId, a repeated key returns the same response, also a 409. PAY_TO_BOOK (SINGLE_CLASS) → PAYMENT_PENDING + checkoutUrl. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "201", description = "Booking", useReturnTypeSchema = true))
    public Booking confirmBooking(@Valid @RequestBody BookingRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/me/bookings")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, DOG_NOT_ACCESSIBLE})
    @Operation(summary = "memberBookings", description = "Roles: MEMBER (also the impersonation token). Own and family-group bookings for 03/07; the history is /me/history (S10). Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "MemberBookings", useReturnTypeSchema = true))
    public MemberBookings memberBookings(@RequestParam(required = false) String dogId, @RequestParam(required = false) BookingState state,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/bookings/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND})
    @Operation(summary = "booking", description = "Roles: MEMBER (own or family group, also the impersonation token), INSTRUCTOR, ADMIN. Detail 07 with cancellation{at, byDisplayName, byRole, late, minutesBefore, message} and displayState. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "Booking", useReturnTypeSchema = true))
    public Booking booking(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.booking(id, memberId(jwt), staff());
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/bookings/{id}/calendar.ics")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND})
    @Operation(summary = "bookingCalendar", description = "Roles: anyone holding the signed token of calendarLinks.ics (no JWT; the club comes from the host). Token valid until classEndsAt. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.",
            responses = @ApiResponse(responseCode = "200", description = "iCalendar file", content = @Content(mediaType = "text/calendar", schema = @Schema(type = "string"))))
    public String bookingCalendar(@PathVariable String id, @RequestParam(required = false) String token) {
        access.tenant();
        access.calendar(id, token);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/bookings/{id}/cancellation")
    @PreAuthorize("(hasRole('INSTRUCTOR') and principal.claims['imp'] != true) or (" + MEMBER + ")")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, BOOKING_NOT_CANCELLABLE})
    @Operation(summary = "cancelBooking", description = "Roles: MEMBER (own or family group, also the impersonation token), INSTRUCTOR when bookings.instructorLastMinuteNotice (origin INSTRUCTOR, otherwise 403). ADMIN without impersonation → 403. R-08-10: late = now > classStartsAt - bookings.lateCancelThresholdMinutes. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "Booking", useReturnTypeSchema = true))
    public Booking cancelBooking(@PathVariable String id, @Valid @RequestBody(required = false) BookingCancellationRequest request, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.booking(id, memberId(jwt), staff());
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/bookings")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ListContract(filterable = {"state", "dogId", "memberId", "classSessionId", "bookingWeekKey", "origin", "classStartsAt"}, sortable = {"classStartsAt", "bookedAt"},
            columns = {"classStartsAt*", "dogName*", "memberName*", "state*", "origin*", "bookedAt", "bookingWeekKey", "late"}, paged = true, exportable = false)
    @ContractErrors({VALIDATION_ERROR, INVALID_FILTER, IMPERSONATION_DENIED})
    @Operation(summary = "bookings", description = "Roles: ADMIN, INSTRUCTOR (MEMBER → 403; impersonation → IMPERSONATION_DENIED). Universal list (CONVENCIONS_API §4) for D10/D12. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "ListPage<BookingListItem>", useReturnTypeSchema = true))
    public ListPage<BookingListItem> bookings() {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/waitlist-entries")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @RequiresModule(Module.WAITLIST)
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, DOG_NOT_ACCESSIBLE, MODULE_DISABLED, CLASS_NOT_FULL, ALREADY_BOOKED, ALREADY_ON_WAITLIST, WAITLIST_LIMIT,
            BOOKING_LIMIT_REACHED, BOOKING_BLOCKED, INACTIVITY_PERIOD, PACK_EMPTY, LEVEL_NOT_ALLOWED, MEMBER_NOT_ACTIVE})
    @Operation(summary = "joinWaitlist", description = "Roles: MEMBER (also the impersonation token). R-08-12; details WAITLIST_LIMIT{scope: CLASS|DOG_WEEK}. Requires WAITLIST. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "201", description = "WaitlistEntry", useReturnTypeSchema = true))
    public WaitlistEntry joinWaitlist(@Valid @RequestBody WaitlistEntryRequest request) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/waitlist-entries/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @AllowsImpersonation
    @RequiresModule(Module.WAITLIST)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "waitlistEntry", description = "Roles: MEMBER (own, also the impersonation token), INSTRUCTOR, ADMIN. Requires WAITLIST. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "WaitlistEntry", useReturnTypeSchema = true))
    public WaitlistEntry waitlistEntry(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.waitlistEntry(id, memberId(jwt), staff());
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/waitlist-entries/{id}/cancellation")
    @PreAuthorize("(hasRole('ADMIN') and principal.claims['imp'] != true) or (" + MEMBER + ")")
    @AllowsImpersonation
    @RequiresModule(Module.WAITLIST)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, WAITLIST_ENTRY_NOT_LIVE})
    @Operation(summary = "leaveWaitlist", description = "Roles: MEMBER (own, also the impersonation token), ADMIN (cancelReason ADMIN). R-08-16. Requires WAITLIST. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "WaitlistEntry", useReturnTypeSchema = true))
    public WaitlistEntry leaveWaitlist(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.waitlistEntry(id, memberId(jwt), staff());
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/waitlist-entries/{id}/claim")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @RequiresModule(Module.WAITLIST)
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, SEAT_TAKEN, SEAT_HOLD_EXPIRED, WAITLIST_NOT_NOTIFIED, WAITLIST_OFFER_EXPIRED,
            BOOKING_LIMIT_REACHED, SWAP_NOT_ALLOWED, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "claimSeat", description = "Roles: MEMBER (own, also the impersonation token). R-08-15: same transaction as R-08-08 plus entry → CONSOLIDATED; waiting-list limits do not apply. Requires WAITLIST. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "201", description = "Booking", useReturnTypeSchema = true))
    public Booking claimSeat(@PathVariable String id, @Valid @RequestBody ClaimRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.waitlistEntry(id, memberId(jwt), false);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/class-sessions/{id}/bookings")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "classBookings", description = "Roles: INSTRUCTOR, ADMIN (impersonation → IMPERSONATION_DENIED). Read for 21/D4/D12. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "ClassBookings", useReturnTypeSchema = true))
    public ClassBookings classBookings(@PathVariable String id) {
        access.tenant();
        access.classSession(id);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/class-sessions/{id}/waitlist-entries")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @RequiresModule(Module.WAITLIST)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, IMPERSONATION_DENIED})
    @Operation(summary = "classWaitlist", description = "Roles: INSTRUCTOR, ADMIN (impersonation → IMPERSONATION_DENIED). Requires WAITLIST. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "ClassWaitlist", useReturnTypeSchema = true))
    public ClassWaitlist classWaitlist(@PathVariable String id) {
        access.tenant();
        access.classSession(id);
        throw new UnsupportedOperationException();
    }
}
