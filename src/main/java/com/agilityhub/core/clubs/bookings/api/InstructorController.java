package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.bookings.application.*;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.IdempotentOperation;
import com.agilityhub.core.shared.application.LocaleContext;
import com.agilityhub.core.shared.application.contract.AllowsImpersonation;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.application.contract.ListContract;
import com.agilityhub.core.shared.application.lists.ListEngine;
import com.agilityhub.core.shared.application.lists.SparseItems;
import com.agilityhub.core.shared.domain.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.bookings.api.InstructorContracts.*;
import static com.agilityhub.core.shared.application.contract.ApiContracts.ListPage;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S10 WP-10-B (E6-T02): instructor aggregates (20, D12, 22/D13), the attendance sheet (21, D12), the universal
 * `/attendances` list and the member history (25). Staff routes reject the impersonation token (IMPERSONATION_DENIED,
 * E5ContractConfiguration covers this package); `/me/history` accepts it as the member. Tenant comes from the JWT.
 */
@RestController
public class InstructorController {
    static final String STAFF = "hasAnyRole('ADMIN','INSTRUCTOR')";
    private final AttendanceContractAccess access; private final AttendanceCallers callers; private final AttendanceSheetQuery sheets;
    private final AttendanceSheetService saves; private final InstructorDayQuery days; private final WeekAgendaQuery weeks; private final WeekAgendaPdf pdf;
    private final InstructorCardQuery cards; private final HistoryQuery history; private final AttendanceListQuery attendances; private final ListEngine lists;
    private final BookingTransactions transactions; private final BookingContext context; private final ObjectMapper mapper;
    public InstructorController(AttendanceContractAccess access, AttendanceCallers callers, AttendanceSheetQuery sheets, AttendanceSheetService saves,
            InstructorDayQuery days, WeekAgendaQuery weeks, WeekAgendaPdf pdf, InstructorCardQuery cards, HistoryQuery history, AttendanceListQuery attendances,
            ListEngine lists, BookingTransactions transactions, BookingContext context, ObjectMapper mapper) {
        this.access = access; this.callers = callers; this.sheets = sheets; this.saves = saves; this.days = days; this.weeks = weeks; this.pdf = pdf;
        this.cards = cards; this.history = history; this.attendances = attendances; this.lists = lists; this.transactions = transactions;
        this.context = context; this.mapper = mapper;
    }

    private static String memberId(Jwt jwt) {
        var user = CurrentUser.current();
        return user != null && user.impersonation() != null ? user.impersonation().memberId() : jwt.getClaimAsString("memberId");
    }
    private AttendanceCaller caller(Jwt jwt) {
        boolean admin = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return callers.resolve(jwt.getSubject(), jwt.getClaimAsString("memberId"), jwt.getClaimAsString("instructorId"), admin);
    }
    private <T> T view(Object value, Class<T> type) { return mapper.convertValue(value, type); }

    @GetMapping("/api/v1/instructor/day")
    @PreAuthorize(STAFF)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "instructorDay", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). Screen 20 (R-10-01): global vision; date absent = today (club-local); instructorId absent = the caller's profile (`me` too; an ADMIN without one gets the first active instructor by shortName); an instructor of another club → 404. Seven day chips from today; the selected instructor's ACTIVE, FINISHED and CANCELLED classes with attendance.status; every ring block of the day. WAITLIST off: no waiting. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "InstructorDay", useReturnTypeSchema = true))
    public InstructorDay instructorDay(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String instructorId, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.filters(instructorId, null);
        return view(days.day(date, instructorId, caller(jwt)), InstructorDay.class);
    }

    @GetMapping("/api/v1/instructor/week")
    @PreAuthorize(STAFF)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "instructorWeek", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). D12 (R-10-15): ISO week of date in the club's zone, Monday–Saturday (Sunday only with items); classes of the S06 calendar form B (never DRAFT) with attendanceStatus; instructorId filters classes only (`me` = the caller; also the shared classes with classes.maxInstructorsPerClass > 1), ringId filters everything; trainings (half height: training.slotMinutes) and blocks are always shown. FREE_TRAINING off: no TRAINING cells; WAITLIST off: no waiting. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "InstructorWeek", useReturnTypeSchema = true))
    public InstructorWeek instructorWeek(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String instructorId, @RequestParam(required = false) String ringId, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.filters(instructorId, ringId);
        return view(weeks.week(date, instructorId, ringId, caller(jwt)), InstructorWeek.class);
    }

    @GetMapping("/api/v1/instructor/week/export")
    @PreAuthorize(STAFF)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "exportInstructorWeek", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). D12 [PDF]: the same query as GET /instructor/week rendered as a landscape A4 grid in the club's theme colour, labels in the caller's locale, synchronous (not an export job: no S3, no rate limit); Content-Disposition `{club.slug}_agenda_{yyyyMMdd}.pdf` (the week's Monday). Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "Week agenda PDF", content = @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary"))))
    public ResponseEntity<byte[]> exportInstructorWeek(@RequestParam @Schema(allowableValues = "pdf") String format,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String instructorId, @RequestParam(required = false) String ringId, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        if (!"pdf".equals(format)) { throw new ApiException(VALIDATION_ERROR, Map.of("field", "format")); }
        access.filters(instructorId, ringId);
        var week = weeks.week(date, instructorId, ringId, caller(jwt));
        var club = context.config().club();
        byte[] body = pdf.render(week, club.name(), context.config().primaryColor(), LocaleContext.current());
        @SuppressWarnings("unchecked") var monday = (LocalDate) ((Map<String, Object>) week.get("week")).get("startDate");
        String name = club.slug() + "_agenda_" + monday.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + ".pdf";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString()).body(body);
    }

    @GetMapping("/api/v1/class-sessions/{id}/attendance")
    @PreAuthorize(STAFF)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "attendanceSheet", description = "Roles: INSTRUCTOR, ADMIN, any class (global vision; MEMBER → 403; impersonation → IMPERSONATION_DENIED). Screens 21/D12 (R-10-02, R-10-03): rows = live bookings (ACTIVE, PAYMENT_PENDING) plus the NOTIFIED ones, by bookedAt; sheet.canMarkPresence/canMarkNotice/editableUntil paint the circles (a CANCELLED class: both false). A DRAFT class or another club's → 404. TASKS off: no pendingTasksCount; WAITLIST off: no waiting/waitlist. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "AttendanceSheet", useReturnTypeSchema = true))
    public AttendanceSheet attendanceSheet(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.classSession(id);
        return view(sheets.sheet(id, caller(jwt)), AttendanceSheet.class);
    }

    @PutMapping("/api/v1/class-sessions/{id}/attendance")
    @PreAuthorize(STAFF)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, STALE_VERSION, INVALID_STATE, ATTENDANCE_NOT_OPEN, ATTENDANCE_WINDOW_CLOSED, ATTENDANCE_NOTIFIED_FINAL,
            ATTENDANCE_BOOKING_NOT_ACTIVE, INSTRUCTOR_NOTICE_DISABLED, IDEMPOTENCY_KEY_REUSED, IMPERSONATION_DENIED})
    @Operation(summary = "saveAttendance", description = "Roles: INSTRUCTOR inside the window, ADMIN always (audited ATTENDANCE_OVERRIDDEN); MEMBER → 403; impersonation → IMPERSONATION_DENIED. R-10-04: one Mongo transaction serialised by seat_locks with the S08 cancellation of NOTIFIED (R-10-05); version ≠ attendanceSummary.version → 409 STALE_VERSION{current: AttendanceSheet}; a DRAFT or CANCELLED class → 409 INVALID_STATE; an item equal to the current state is a no-op; any error changes nothing; same Idempotency-Key → same response. details: ATTENDANCE_BOOKING_NOT_ACTIVE{bookingId}, ATTENDANCE_WINDOW_CLOSED{editableUntil}. An unknown state or a repeated bookingId is 400. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "AttendanceSheet with applied[]", useReturnTypeSchema = true))
    public AttendanceSheet saveAttendance(@PathVariable String id, @Valid @RequestBody AttendanceSaveRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.classSession(id);
        var caller = caller(jwt);
        var items = request.items().stream().map(i -> new AttendanceSheetService.Item(i.bookingId(), i.state())).toList();
        // The save, its S08 effects and the stored 200 of the key commit together (R-10-04: same key → same response).
        return transactions.write(List.of(id), () -> {
            IdempotentOperation.lock();
            var result = view(saves.save(id, request.version(), items, caller), AttendanceSheet.class);
            try { IdempotentOperation.complete(200, mapper.writeValueAsBytes(result)); }
            catch (JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
            return result;
        });
    }

    @GetMapping("/api/v1/attendances")
    @PreAuthorize(STAFF)
    @ListContract(filterable = {"dogId", "memberId", "classSessionId", "classDate", "state"}, sortable = {"classStartsAt", "classDate"},
            columns = {"classStartsAt*", "classDate", "dogName*", "memberName*", "state*", "markedAt", "markedByName"}, paged = true, exportable = false,
            fields = {"id", "bookingId", "classSessionId", "classDate", "classStartsAt", "dogId", "dogName", "memberId", "memberName", "state", "markedAt", "markedByName"})
    @ContractErrors({VALIDATION_ERROR, INVALID_FILTER, IMPERSONATION_DENIED})
    @Operation(summary = "attendances", description = "Roles: ADMIN, INSTRUCTOR (MEMBER → 403; impersonation → IMPERSONATION_DENIED). Universal list (CONVENCIONS_API §4) of the stored S10 attendances (D10, phase 2; a booking never marked has no row); an undeclared filter or sort is 400 INVALID_FILTER. No export. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "ListPage<AttendanceListItem>", useReturnTypeSchema = true))
    public ListPage<AttendanceListItem> attendances(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        access.tenant();
        access.attendances(params);
        var page = attendances.list(lists, params);
        return SparseItems.apply(mapper, new ListPage<>(page.items().stream().map(item -> view(item, AttendanceListItem.class)).toList(), page.page(), page.size(),
                page.totalItems(), page.totalPages(), page.appliedFilters()), params, "id");
    }

    @GetMapping("/api/v1/dogs/{id}/instructor-card")
    @PreAuthorize(STAFF)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "instructorCard", description = "Roles: INSTRUCTOR, ADMIN, any dog of the club (MEMBER → 403; impersonation → IMPERSONATION_DENIED). Screens 22/D13 (R-10-08, R-10-09): 30-day metrics (cancelledLate in the denominator, assumption §13-1), last 5 classes, level with assignedAt. TASKS off: no instructorNote/tasks/observations; FREE_TRAINING off: no trainingsCount/trainingsPerWeek; levels.enabled = false: no level. Another club's dog → 404. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "InstructorCard", useReturnTypeSchema = true))
    public InstructorCard instructorCard(@PathVariable String id) {
        access.tenant();
        access.dog(id);
        return view(cards.card(id), InstructorCard.class);
    }

    @GetMapping("/api/v1/me/history")
    @PreAuthorize(BookingsController.MEMBER)
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, DOG_NOT_ACCESSIBLE})
    @Operation(summary = "memberHistory", description = "Roles: MEMBER (also the impersonation token). Screen 25 (R-10-14): own and family-group dogs, history.monthsVisible months, startsAt desc, at most 500, no live bookings (they are on 03; cancelled ones appear, future ones too). dogId absent = «Tots»; a dog that is not accessible → 404 DOG_NOT_ACCESSIBLE. TRAINING items need FREE_TRAINING, ACTIVITY items ACTIVITIES (listed with «Tots» only). Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "MemberHistory", useReturnTypeSchema = true))
    public MemberHistory memberHistory(@RequestParam(required = false) String dogId, @RequestParam(required = false) HistoryType type,
            @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        String member = memberId(jwt);
        access.accessibleDog(member, dogId);
        return view(history.history(member, dogId, type == null ? null : HistoryQuery.Type.valueOf(type.name())), MemberHistory.class);
    }
}
