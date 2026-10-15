package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.application.CourseAccess;
import com.agilityhub.core.courses.application.RingSetupService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.courses.api.CourseContracts.*;
import static com.agilityhub.core.courses.api.CourseRequests.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/** S16 ring setups, «recorregut muntat» (E9-T03: R-16-07, R-16-08, R-16-09). */
@RestController
@RequiresModule(Module.COURSES)
public class RingSetupsController {
    private final RingSetupService setups;
    public RingSetupsController(RingSetupService setups) { this.setups = setups; }

    @PostMapping(value = "/api/v1/ring-setups")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, RING_WITHOUT_GEOMETRY, PLACEMENT_RING_MISMATCH, SETUP_SOURCE_REQUIRED, COURSE_NOT_VISIBLE, STALE_VERSION})
    @Operation(summary = "createRingSetup", description = "Roles: INSTRUCTOR (while courses.allowInstructorPublish, else FORBIDDEN), ADMIN. Module: COURSES. "
            + "R-16-07: registers what is set up on the ring. A kind other than EMPTY needs a source — placementId, courseId or imageFileKey (SETUP_SOURCE_REQUIRED); "
            + "EMPTY (the ring is empty) names none (VALIDATION_ERROR). placementId: a placement of the same ring (PLACEMENT_RING_MISMATCH) on a ring with a geometry "
            + "(RING_WITHOUT_GEOMETRY); with a courseId too, it must be the placement's course (VALIDATION_ERROR). courseId: a course the caller can read. "
            + "imageFileKey: this club's COURSE_IMAGE upload (else NOT_FOUND). levelIds: this club's levels (NOT_FOUND), none while levels.enabled = false "
            + "(VALIDATION_ERROR fieldErrors[{levelIds, LEVELS_DISABLED}]). expectedUntil: the last club-local day the setup is shown, today or later "
            + "(VALIDATION_ERROR). expiresAt = the earlier of the start of the day after expectedUntil and the start of the day courses.setupAutoExpireDays "
            + "after today, in the club's time zone. The ring's active setup, if any, becomes DISMANTLED (replacedSetupId) in the same transaction and "
            + "Ring.activeSetupId names the new one; a concurrent registration on the ring → STALE_VERSION. Publishes RingSetupChanged{ACTIVE} (N-31 when "
            + "messaging.notifyNewRingSetup and kind ≠ EMPTY) and audits RING_SETUP_REGISTERED.",
            responses = @ApiResponse(responseCode = "201", description = "S16 RingSetupCreated", useReturnTypeSchema = true))
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public RingSetupCreated createRingSetup(@Valid @RequestBody RingSetupRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var created = setups.register(CourseViews.setup(request));
        return new RingSetupCreated(CourseViews.setup(created.setup()), created.replacedSetupId());
    }

    @GetMapping(value = "/api/v1/ring-setups/{id}")
    @PreAuthorize("hasAnyRole('MEMBER', 'INSTRUCTOR', 'ADMIN')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "getRingSetup", description = "Roles: MEMBER, INSTRUCTOR, ADMIN. Module: COURSES. R-16-09: INSTRUCTOR and ADMIN read any setup of the "
            + "club (view STAFF); a MEMBER (impersonation included) reads only an ACTIVE one while courses.showSetupToMembers (else NOT_FOUND), without "
            + "builtByAccountId, dismantledByAccountId or notes (view MEMBER). imageUrl is a short-lived signed URL of imageFileKey.",
            responses = @ApiResponse(responseCode = "200", description = "S16 setup projected by view: STAFF or MEMBER",
                    content = @Content(schema = @Schema(oneOf = {RingSetup.class, MemberRingSetup.class}))))
    public Object getRingSetup(@PathVariable String id) {
        var setup = setups.get(id);
        return CourseAccess.staff() ? CourseViews.setup(setup) : CourseViews.memberSetup(setup);
    }

    @PostMapping(value = "/api/v1/ring-setups/{id}/dismantle")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, INVALID_STATE})
    @Operation(summary = "dismantleRingSetup", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. R-16-08: an ACTIVE setup becomes DISMANTLED "
            + "(dismantledAt, dismantledByAccountId), Ring.activeSetupId = null, and RingSetupChanged{DISMANTLED} is published; any other status → INVALID_STATE.",
            responses = @ApiResponse(responseCode = "200", description = "S16 RingSetup", useReturnTypeSchema = true))
    public RingSetup dismantleRingSetup(@PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return CourseViews.setup(setups.dismantle(id));
    }

    @PostMapping(value = "/api/v1/ring-setups/{id}/renewal")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, INVALID_STATE})
    @Operation(summary = "renewRingSetup", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. R-16-08: an ACTIVE setup (else INVALID_STATE) gets a new "
            + "expiresAt counted from today, as registering does, with the given expectedUntil (null = none; an earlier day than today → VALIDATION_ERROR). "
            + "It stays ACTIVE: no event.",
            responses = @ApiResponse(responseCode = "200", description = "S16 RingSetup", useReturnTypeSchema = true))
    public RingSetup renewRingSetup(@PathVariable String id,
            @Valid @RequestBody RenewalRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return CourseViews.setup(setups.renew(id, request.expectedUntil()));
    }

    @GetMapping(value = "/api/v1/me/ring-setups")
    @PreAuthorize("hasAnyRole('MEMBER', 'INSTRUCTOR')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "myRingSetups", description = "Roles: MEMBER, INSTRUCTOR. Module: COURSES. R-16-09: one item per ring — the given ringIds (each a "
            + "ring of the club, else NOT_FOUND), in that order, else the club's active rings in catalog order — with its ACTIVE setup's summary, or setup = "
            + "null («Sense recorregut registrat»). levelLabel: the club levels' codes joined by an en dash, else the agilityhubLevel; thumbnailUrl: the "
            + "setup's photo, else the shown course's thumbnail (signed); hasViewer: a placement or a course with a model to draw. No builtBy, notes nor "
            + "inventory. When courses.showSetupToMembers is false, the MEMBER list remains accessible with setup = null for every ring; the INSTRUCTOR "
            + "projection is unchanged.",
            responses = @ApiResponse(responseCode = "200", description = "S16 List<MeRingSetup>", useReturnTypeSchema = true))
    public List<MeRingSetup> myRingSetups(@RequestParam(required = false) List<String> ringIds) {
        return setups.mine(ringIds).stream().map(CourseViews::mine).toList();
    }
}
