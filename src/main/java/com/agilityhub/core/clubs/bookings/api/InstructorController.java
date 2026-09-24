package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.bookings.application.AttendanceContractAccess;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.contract.AllowsImpersonation;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.application.contract.ListContract;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.bookings.api.InstructorContracts.*;
import static com.agilityhub.core.shared.application.contract.ApiContracts.ListPage;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S10 WP-10-A: instructor aggregates (20, D12, 22/D13), the attendance sheet (21, D12) and the member history (25).
 * Every operation runs the tenant, role and resource guards and then answers 501 NOT_IMPLEMENTED until E6-T02.
 * Staff routes reject the impersonation token (IMPERSONATION_DENIED, E5ContractConfiguration covers this package);
 * `/me/history` accepts it as the member.
 */
@RestController
public class InstructorController {
    static final String STAFF = "hasAnyRole('ADMIN','INSTRUCTOR')";
    static final String STUB = " Contract only; returns 501 NOT_IMPLEMENTED after tenant, role, module and resource guards. Tenant comes from the JWT.";
    private final AttendanceContractAccess access;
    public InstructorController(AttendanceContractAccess access) { this.access = access; }

    private static String memberId(Jwt jwt) {
        var user = CurrentUser.current();
        return user != null && user.impersonation() != null ? user.impersonation().memberId() : jwt.getClaimAsString("memberId");
    }

    @GetMapping("/api/v1/instructor/day")
    @PreAuthorize(STAFF)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "instructorDay", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). Screen 20 (R-10-01): global vision; date absent = today (club-local); instructorId absent = the caller's profile (an ADMIN without one gets the first by shortName); an instructor of another club → 404. Every ring block of the day is listed. WAITLIST off: no waiting." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "InstructorDay", useReturnTypeSchema = true))
    public InstructorDay instructorDay(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String instructorId) {
        access.tenant();
        access.filters(instructorId, null);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/instructor/week")
    @PreAuthorize(STAFF)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "instructorWeek", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). D12 (R-10-15): ISO week of date in the club's zone, Monday–Saturday (Sunday only with items); instructorId filters classes only (`me` = the caller; also the shared classes with classes.maxInstructorsPerClass > 1), ringId filters everything; trainings and blocks are always shown. FREE_TRAINING off: no TRAINING cells; WAITLIST off: no waiting." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "InstructorWeek", useReturnTypeSchema = true))
    public InstructorWeek instructorWeek(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String instructorId, @RequestParam(required = false) String ringId) {
        access.tenant();
        access.filters(instructorId, ringId);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/instructor/week/export")
    @PreAuthorize(STAFF)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "exportInstructorWeek", description = "Roles: INSTRUCTOR, ADMIN (MEMBER → 403; impersonation → IMPERSONATION_DENIED). D12 [PDF]: the same query as GET /instructor/week rendered landscape with the club logo, synchronous." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "Week agenda PDF", content = @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary"))))
    public ResponseEntity<byte[]> exportInstructorWeek(@RequestParam @Schema(allowableValues = "pdf") String format,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String instructorId, @RequestParam(required = false) String ringId) {
        access.tenant();
        if (!"pdf".equals(format)) { throw new com.agilityhub.core.shared.domain.ApiException(VALIDATION_ERROR, java.util.Map.of("field", "format")); }
        access.filters(instructorId, ringId);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/class-sessions/{id}/attendance")
    @PreAuthorize(STAFF)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "attendanceSheet", description = "Roles: INSTRUCTOR, ADMIN, any class (global vision; MEMBER → 403; impersonation → IMPERSONATION_DENIED). Screens 21/D12 (R-10-02, R-10-03): rows = live bookings plus NOTIFIED by bookedAt; sheet.canMarkPresence/canMarkNotice/editableUntil paint the circles. TASKS off: no pendingTasksCount; WAITLIST off: no waiting/waitlist." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "AttendanceSheet", useReturnTypeSchema = true))
    public AttendanceSheet attendanceSheet(@PathVariable String id) {
        access.tenant();
        access.classSession(id);
        throw new UnsupportedOperationException();
    }

    @PutMapping("/api/v1/class-sessions/{id}/attendance")
    @PreAuthorize(STAFF)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, STALE_VERSION, INVALID_STATE, ATTENDANCE_NOT_OPEN, ATTENDANCE_WINDOW_CLOSED, ATTENDANCE_NOTIFIED_FINAL,
            ATTENDANCE_BOOKING_NOT_ACTIVE, INSTRUCTOR_NOTICE_DISABLED, IDEMPOTENCY_KEY_REUSED, IMPERSONATION_DENIED})
    @Operation(summary = "saveAttendance", description = "Roles: INSTRUCTOR inside the window, ADMIN always (audited ATTENDANCE_OVERRIDDEN); MEMBER → 403; impersonation → IMPERSONATION_DENIED. R-10-04: one Mongo transaction serialised by seat_locks with the S08 cancellation of NOTIFIED (R-10-05); version ≠ attendanceSummary.version → 409 STALE_VERSION{current: AttendanceSheet}; any error changes nothing; same Idempotency-Key → same response. details: ATTENDANCE_BOOKING_NOT_ACTIVE{bookingId}, ATTENDANCE_WINDOW_CLOSED{editableUntil}. An unknown state is 400." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "AttendanceSheet with applied[]", useReturnTypeSchema = true))
    public AttendanceSheet saveAttendance(@PathVariable String id, @Valid @RequestBody AttendanceSaveRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey) {
        access.tenant();
        access.classSession(id);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/attendances")
    @PreAuthorize(STAFF)
    @ListContract(filterable = {"dogId", "memberId", "classSessionId", "classDate", "state"}, sortable = {"classStartsAt", "classDate"},
            columns = {"classStartsAt*", "classDate", "dogName*", "memberName*", "state*", "markedAt", "markedByName"}, paged = true, exportable = false)
    @ContractErrors({VALIDATION_ERROR, INVALID_FILTER, IMPERSONATION_DENIED})
    @Operation(summary = "attendances", description = "Roles: ADMIN, INSTRUCTOR (MEMBER → 403; impersonation → IMPERSONATION_DENIED). Universal list (CONVENCIONS_API §4) of the S10 attendances (D10, exports, phase 2); an undeclared filter or sort is 400 INVALID_FILTER." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "ListPage<AttendanceListItem>", useReturnTypeSchema = true))
    public ListPage<AttendanceListItem> attendances(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        access.tenant();
        access.attendances(params);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/dogs/{id}/instructor-card")
    @PreAuthorize(STAFF)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED})
    @Operation(summary = "instructorCard", description = "Roles: INSTRUCTOR, ADMIN, any dog of the club (MEMBER → 403; impersonation → IMPERSONATION_DENIED). Screens 22/D13 (R-10-08, R-10-09): 30-day metrics, last 5 classes, level age. TASKS off: no instructorNote/tasks/observations; FREE_TRAINING off: no trainingsCount/trainingsPerWeek; levels.enabled = false: no level. Another club's dog → 404." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "InstructorCard", useReturnTypeSchema = true))
    public InstructorCard instructorCard(@PathVariable String id) {
        access.tenant();
        access.dog(id);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/me/history")
    @PreAuthorize(BookingsController.MEMBER)
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, DOG_NOT_ACCESSIBLE})
    @Operation(summary = "memberHistory", description = "Roles: MEMBER (also the impersonation token). Screen 25 (R-10-14): own and family-group dogs, history.monthsVisible months, startsAt desc, no live bookings (they are on 03). dogId absent = «Tots»; a dog that is not accessible → 404 DOG_NOT_ACCESSIBLE. type TRAINING needs FREE_TRAINING, ACTIVITY needs ACTIVITIES." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "MemberHistory", useReturnTypeSchema = true))
    public MemberHistory memberHistory(@RequestParam(required = false) String dogId, @RequestParam(required = false) HistoryType type,
            @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.accessibleDog(memberId(jwt), dogId);
        throw new UnsupportedOperationException();
    }
}
