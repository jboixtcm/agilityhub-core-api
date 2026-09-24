package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.bookings.application.*;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.shared.application.IdempotentOperation;
import com.agilityhub.core.shared.application.lists.ListEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * S08: class bookings, seat holds and waiting list. WP-08-B (E5-T02) serves holds, confirmation, detail, lists,
 * `.ics` and cancellation; WP-08-C (E5-T03) the waiting list (join, detail, leave, claim, class list); WP-08-D (E5-T06)
 * the aggregates `/me/home` (03) and `/me/bookable-classes` (04).
 */
@RestController
public class BookingsController {
    static final String MEMBER = "hasRole('MEMBER') and (principal.claims['imp'] == true or !hasAnyRole('ADMIN','INSTRUCTOR'))";
    private final BookingContractAccess access; private final BookingActors actors; private final SeatHoldService holds;
    private final BookingConfirmationService confirmations; private final BookingCancellationService cancellations; private final BookingQueryService queries;
    private final BookingViews views; private final BookingTransactions transactions; private final ListEngine lists; private final ObjectMapper mapper;
    private final WaitlistService waitlist; private final MemberHomeQuery home; private final BookableClassesQuery bookable;
    public BookingsController(BookingContractAccess access, BookingActors actors, SeatHoldService holds, BookingConfirmationService confirmations,
            BookingCancellationService cancellations, BookingQueryService queries, BookingViews views, BookingTransactions transactions,
            ListEngine lists, ObjectMapper mapper, WaitlistService waitlist, MemberHomeQuery home, BookableClassesQuery bookable) {
        this.access = access; this.actors = actors; this.holds = holds; this.confirmations = confirmations; this.cancellations = cancellations;
        this.queries = queries; this.views = views; this.transactions = transactions; this.lists = lists; this.mapper = mapper; this.waitlist = waitlist;
        this.home = home; this.bookable = bookable;
    }
    private <T> T view(Object value, Class<T> type) { return mapper.convertValue(value, type); }
    private static boolean instructorOnly() {
        var user = CurrentUser.current();
        return (user == null || user.impersonation() == null) && SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_INSTRUCTOR"));
    }

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
    @Operation(summary = "memberHome", description = "Roles: MEMBER (also the impersonation token). S08 screen 03. dogId absent = «Tots». Chips = own dogs plus the family group's (FAMILY_GROUP); limits = R-08-02 counters of W0/W1 summed over the filtered dogs; reservations = future rows (endsAt > now) of CLASS, CLASS_WAITLIST (WAITLIST), TRAINING (FREE_TRAINING) and ACTIVITY (ACTIVITIES) by startsAt; instructorName per R-08-20. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "MeHome", useReturnTypeSchema = true))
    public MeHome memberHome(@RequestParam(required = false) String dogId, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        return view(home.home(memberId(jwt), dogId), MeHome.class);
    }

    @GetMapping("/api/v1/me/bookable-classes")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, DOG_NOT_ACCESSIBLE})
    @Operation(summary = "bookableClasses", description = "Roles: MEMBER (also the impersonation token). S08 screen 04, horizon until the end of W2; dogId absent = proposed dog (Member.lastDogForClass while accessible, else the first own dog); no accessible dog → 404 DOG_NOT_ACCESSIBLE. Classes the dog has booked or waits for are left out; row states per R-08-03, decided by the server. The class list and counters come from a 30 s base cache; the per-dog state is live. SINGLE_CLASS off: no price. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "BookableClasses", useReturnTypeSchema = true))
    public BookableClasses bookableClasses(@RequestParam(required = false) String dogId, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        return view(bookable.bookable(memberId(jwt), dogId), BookableClasses.class);
    }

    @PostMapping("/api/v1/seat-holds")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, DOG_NOT_ACCESSIBLE, MODULE_DISABLED, CLASS_FULL, CLASS_NOT_BOOKABLE, NOT_YET_OPEN, BOOKING_LIMIT_REACHED,
            BOOKING_BLOCKED, INACTIVITY_PERIOD, MEMBER_NOT_ACTIVE, LEVEL_NOT_ALLOWED, ALREADY_BOOKED, PACK_EMPTY, SEAT_TAKEN,
            WAITLIST_NOT_NOTIFIED, WAITLIST_OFFER_EXPIRED})
    @Operation(summary = "holdSeat", description = "Roles: MEMBER (also the impersonation token). R-08-07: one Mongo transaction serialised by seat_locks; re-entering refreshes the same hold. waitlistEntryId requires WAITLIST. details: CLASS_FULL{heldOnly}, NOT_YET_OPEN{opensAt}, BOOKING_LIMIT_REACHED{unit, week, limit, current, swappable[], notSelectable[], nextBookableAt}, INACTIVITY_PERIOD{from, to}. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "201", description = "SeatHoldResponse", useReturnTypeSchema = true))
    public SeatHoldResponse holdSeat(@Valid @RequestBody SeatHoldRequest request, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.waitlistModule(request.waitlistEntryId() != null);
        var held = holds.hold(actors.member(memberId(jwt)), request.classSessionId(), request.dogId(), request.waitlistEntryId());
        return view(views.hold(held), SeatHoldResponse.class);
    }

    @DeleteMapping("/api/v1/seat-holds/{id}")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({VALIDATION_ERROR})
    @Operation(summary = "releaseSeat", description = "Roles: MEMBER owner of the hold (also the impersonation token). 204 also when the hold is already gone (expired or consumed). Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void releaseSeat(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        holds.release(actors.member(memberId(jwt)), id);
    }

    @PostMapping("/api/v1/bookings")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, SEAT_HOLD_EXPIRED, SWAP_NOT_ALLOWED, CLASS_FULL, CLASS_NOT_BOOKABLE, NOT_YET_OPEN, BOOKING_LIMIT_REACHED,
            BOOKING_BLOCKED, INACTIVITY_PERIOD, MEMBER_NOT_ACTIVE, LEVEL_NOT_ALLOWED, ALREADY_BOOKED, PACK_EMPTY, SEAT_TAKEN, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "confirmBooking", description = "Roles: MEMBER (also the impersonation token: origin BACKOFFICE). R-08-08 confirmation (or R-08-09 atomic swap with swapBookingId); Idempotency-Key = seatHoldId, a repeated key returns the same response, also a 409. PAY_TO_BOOK (SINGLE_CLASS) → PAYMENT_PENDING + checkoutUrl. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "201", description = "Booking", useReturnTypeSchema = true))
    public Booking confirmBooking(@Valid @RequestBody BookingRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        var actor = actors.member(memberId(jwt));
        return confirmed(confirmations.classes(request.seatHoldId(), request.swapBookingId()),
                () -> confirmations.confirm(actor, request.seatHoldId(), request.swapBookingId()));
    }
    /**
     * Confirmation and claim: the booking and the idempotent 201 commit together; a PAY_TO_BOOK booking (R-08-18)
     * commits first, then its provider checkout opens outside the retried transaction and the 201 carrying the
     * `checkoutUrl` is stored in a second, short transaction.
     */
    private Booking confirmed(java.util.Collection<String> classes, java.util.function.Supplier<BookingConfirmationService.Confirmed> work) {
        var committed = transactions.write(classes, () -> {
            IdempotentOperation.lock();
            var confirmed = work.get();
            return confirmed.checkout() == null ? new Committed(stored(confirmed), null) : new Committed(null, confirmed);
        });
        if (committed.pendingCheckout() == null) { return committed.response(); }
        var opened = confirmations.openCheckout(committed.pendingCheckout());
        return transactions.write(java.util.List.of(), () -> { IdempotentOperation.lock(); return stored(opened); });
    }
    private record Committed(Booking response, BookingConfirmationService.Confirmed pendingCheckout) { }
    private Booking stored(BookingConfirmationService.Confirmed confirmed) {
        var result = view(views.booking(confirmed.booking(), false, confirmed.checkoutUrl()), Booking.class);
        try { IdempotentOperation.complete(201, mapper.writeValueAsBytes(result)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
        return result;
    }

    @GetMapping("/api/v1/me/bookings")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, DOG_NOT_ACCESSIBLE})
    @Operation(summary = "memberBookings", description = "Roles: MEMBER (also the impersonation token). Own and family-group bookings for 03/07; the history is /me/history (S10). Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "MemberBookings", useReturnTypeSchema = true))
    public MemberBookings memberBookings(@RequestParam(required = false) String dogId, @RequestParam(required = false) BookingState state,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        return new MemberBookings(queries.mine(memberId(jwt), dogId, state, from, to).stream().map(b -> view(b, Booking.class)).toList());
    }

    @GetMapping("/api/v1/bookings/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND})
    @Operation(summary = "booking", description = "Roles: MEMBER (own or family group, also the impersonation token), INSTRUCTOR, ADMIN. Detail 07 with cancellation{at, byDisplayName, byRole, late, minutesBefore, message} and displayState. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "Booking", useReturnTypeSchema = true))
    public Booking booking(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        return view(queries.detail(id, memberId(jwt), staff()), Booking.class);
    }

    @GetMapping("/api/v1/bookings/{id}/calendar.ics")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND})
    @Operation(summary = "bookingCalendar", description = "Roles: anyone holding the signed token of calendarLinks.ics (no JWT; the club comes from the host). Token valid until classEndsAt; any mismatch is 404.",
            responses = @ApiResponse(responseCode = "200", description = "iCalendar file", content = @Content(mediaType = "text/calendar", schema = @Schema(type = "string"))))
    public String bookingCalendar(@PathVariable String id, @RequestParam(required = false) String token,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) jakarta.servlet.http.HttpServletResponse response) {
        access.tenant();
        var body = queries.calendar(id, token);
        response.setContentType("text/calendar;charset=UTF-8"); response.setHeader("Content-Disposition", "attachment; filename=\"booking.ics\"");
        return body;
    }

    @PostMapping("/api/v1/bookings/{id}/cancellation")
    @PreAuthorize("(hasRole('INSTRUCTOR') and principal.claims['imp'] != true) or (" + MEMBER + ")")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, BOOKING_NOT_CANCELLABLE})
    @Operation(summary = "cancelBooking", description = "Roles: MEMBER (own or family group, also the impersonation token), INSTRUCTOR when bookings.instructorLastMinuteNotice (origin INSTRUCTOR, otherwise 403). ADMIN without impersonation → 403. R-08-10: late = now > classStartsAt - bookings.lateCancelThresholdMinutes. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "Booking", useReturnTypeSchema = true))
    public Booking cancelBooking(@PathVariable String id, @Valid @RequestBody(required = false) BookingCancellationRequest request, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        boolean instructor = instructorOnly();
        queries.visible(id, memberId(jwt), instructor);
        var actor = instructor ? actors.instructor() : actors.member(memberId(jwt));
        var cancelled = cancellations.cancel(id, actor, request == null ? null : request.message());
        return view(views.booking(cancelled, instructor, null), Booking.class);
    }

    @GetMapping("/api/v1/bookings")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ListContract(filterable = {"state", "dogId", "memberId", "classSessionId", "bookingWeekKey", "origin", "classStartsAt"}, sortable = {"classStartsAt", "bookedAt"},
            columns = {"classStartsAt*", "dogName*", "memberName*", "state*", "origin*", "bookedAt", "bookingWeekKey", "late"}, paged = true, exportable = false)
    @ContractErrors({VALIDATION_ERROR, INVALID_FILTER, IMPERSONATION_DENIED})
    @Operation(summary = "bookings", description = "Roles: ADMIN, INSTRUCTOR (MEMBER → 403; impersonation → IMPERSONATION_DENIED). Universal list (CONVENCIONS_API §4) for D10/D12. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "ListPage<BookingListItem>", useReturnTypeSchema = true))
    public ListPage<BookingListItem> bookings(@io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestParam org.springframework.util.MultiValueMap<String, String> params) {
        access.tenant();
        var page = queries.list(lists, params);
        return new ListPage<>(page.items().stream().map(item -> view(item, BookingListItem.class)).toList(), page.page(), page.size(), page.totalItems(),
                page.totalPages(), page.appliedFilters());
    }

    @PostMapping("/api/v1/waitlist-entries")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @RequiresModule(Module.WAITLIST)
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, DOG_NOT_ACCESSIBLE, MODULE_DISABLED, CLASS_NOT_FULL, ALREADY_BOOKED, ALREADY_ON_WAITLIST, WAITLIST_LIMIT,
            BOOKING_LIMIT_REACHED, BOOKING_BLOCKED, INACTIVITY_PERIOD, PACK_EMPTY, LEVEL_NOT_ALLOWED, MEMBER_NOT_ACTIVE})
    @Operation(summary = "joinWaitlist", description = "Roles: MEMBER (also the impersonation token). R-08-12: the class must be full by bookings alone (CLASS_NOT_FULL otherwise), the booking eligibility chain runs, then waitlist.maxPerClass, waitlist.maxPerDogPerWeek / maxPerDogPerWeekIfAttended (details WAITLIST_LIMIT{scope: CLASS|DOG_WEEK}) and the seat must be acceptable (BOOKING_LIMIT_REACHED). Requires WAITLIST. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "201", description = "WaitlistEntry", useReturnTypeSchema = true))
    public WaitlistEntry joinWaitlist(@Valid @RequestBody WaitlistEntryRequest request, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        var entry = waitlist.join(actors.member(memberId(jwt)), request.classSessionId(), request.dogId());
        return view(views.waitlistEntry(entry, false), WaitlistEntry.class);
    }

    @GetMapping("/api/v1/waitlist-entries/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @AllowsImpersonation
    @RequiresModule(Module.WAITLIST)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "waitlistEntry", description = "Roles: MEMBER (own or family group, also the impersonation token), INSTRUCTOR, ADMIN. Screen 07 «/espera/:id»: class card, position (FIFO order), confirmBy (FIFO only), state. Requires WAITLIST. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "WaitlistEntry", useReturnTypeSchema = true))
    public WaitlistEntry waitlistEntry(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        boolean staff = staff();
        return view(views.waitlistEntry(waitlist.visible(id, memberId(jwt), staff), staff), WaitlistEntry.class);
    }

    @PostMapping("/api/v1/waitlist-entries/{id}/cancellation")
    @PreAuthorize("(hasRole('ADMIN') and principal.claims['imp'] != true) or (" + MEMBER + ")")
    @AllowsImpersonation
    @RequiresModule(Module.WAITLIST)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, WAITLIST_ENTRY_NOT_LIVE})
    @Operation(summary = "leaveWaitlist", description = "Roles: MEMBER (own or family group, also the impersonation token), ADMIN (D4/D12). R-08-16: ACTIVE or NOTIFIED only (WAITLIST_ENTRY_NOT_LIVE otherwise) → CANCELLED with cancelReason MEMBER, or ADMIN when an administrator acts (directly or impersonating); WaitlistLeft. Requires WAITLIST. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "WaitlistEntry", useReturnTypeSchema = true))
    public WaitlistEntry leaveWaitlist(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        boolean admin = staff();
        waitlist.visible(id, memberId(jwt), admin);
        var left = waitlist.leave(admin ? actors.admin() : actors.member(memberId(jwt)), id);
        return view(views.waitlistEntry(left, admin), WaitlistEntry.class);
    }

    @PostMapping("/api/v1/waitlist-entries/{id}/claim")
    @PreAuthorize(MEMBER)
    @AllowsImpersonation
    @RequiresModule(Module.WAITLIST)
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, SEAT_TAKEN, SEAT_HOLD_EXPIRED, WAITLIST_NOT_NOTIFIED, WAITLIST_OFFER_EXPIRED,
            BOOKING_LIMIT_REACHED, SWAP_NOT_ALLOWED, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "claimSeat", description = "Roles: MEMBER (own or family group, also the impersonation token). R-08-15: the entry must be NOTIFIED (FIFO: confirmBy > now) and the seat hold taken with its waitlistEntryId; then the same transaction as R-08-08 (checks, swap with swapBookingId, BR-01) plus entry → CONSOLIDATED and WaitlistConsolidated; in ALL_AT_ONCE the last seat sends the other NOTIFIED entries back to ACTIVE. Waiting-list limits do not apply. Idempotency-Key: a repeated key returns the same response, also a 409/422. Requires WAITLIST. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "201", description = "Booking", useReturnTypeSchema = true))
    public Booking claimSeat(@PathVariable String id, @Valid @RequestBody ClaimRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        waitlist.visible(id, memberId(jwt), false);
        var actor = actors.member(memberId(jwt));
        return confirmed(waitlist.classes(id, request.swapBookingId()), () -> waitlist.claim(actor, id, request.seatHoldId(), request.swapBookingId()));
    }

    @GetMapping("/api/v1/class-sessions/{id}/bookings")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "classBookings", description = "Roles: INSTRUCTOR, ADMIN (impersonation → IMPERSONATION_DENIED). Read for 21/D4/D12: every booking of the class, any state. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "ClassBookings", useReturnTypeSchema = true))
    public ClassBookings classBookings(@PathVariable String id) {
        access.tenant();
        access.classSession(id);
        return new ClassBookings(queries.forClass(id).stream().map(item -> view(item, BookingListItem.class)).toList());
    }

    @GetMapping("/api/v1/class-sessions/{id}/waitlist-entries")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @RequiresModule(Module.WAITLIST)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, IMPERSONATION_DENIED})
    @Operation(summary = "classWaitlist", description = "Roles: INSTRUCTOR, ADMIN (impersonation → IMPERSONATION_DENIED). Read for 21/D4/D12 and the S06 cancellation preview: every entry of the class, any state, in position order. Requires WAITLIST. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "ClassWaitlist", useReturnTypeSchema = true))
    public ClassWaitlist classWaitlist(@PathVariable String id) {
        access.tenant();
        access.classSession(id);
        return new ClassWaitlist(waitlist.forClass(id).stream().map(e -> view(views.waitlistEntry(e, true), WaitlistEntry.class)).toList());
    }
}
