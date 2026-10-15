package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.application.CourseContractGuards;
import com.agilityhub.core.courses.application.PlacementService;
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

/** S16 placements (E9-T03: R-16-05, R-16-06, R-16-13). The build sheet (R-16-10) is still a contract stub. */
@RestController
@RequiresModule(Module.COURSES)
public class PlacementsController {
    static final String WRITE_RULES = " R-16-05: courseId names a course the caller can read (COURSE_NOT_VISIBLE) with a model (else VALIDATION_ERROR "
            + "fieldErrors[{courseId, COURSE_MODEL_INVALID}]) at its current version (courseVersion, else STALE_VERSION); ringId a ring of the club with a "
            + "geometry (RING_WITHOUT_GEOMETRY); activityId an activity of the club and only with ACTIVITIES (else VALIDATION_ERROR fieldErrors[{activityId, "
            + "MODULE_DISABLED}]). resolvedObstacles and warnings are course-core's, stored as sent (the core never recalculates the geometry). With a critical "
            + "warning the placement needs force = true by an ADMIN (an INSTRUCTOR with force → FORBIDDEN), else PLACEMENT_BLOCKED (PlacementBlockedDetails: "
            + "the critical warnings); a forced one audits COURSE_FORCED_PLACEMENT. warningThresholdM defaults to courses.defaultWarningThresholdM; status "
            + "defaults to DRAFT and mode to TRAINING.";
    private final CourseContractGuards guards;
    private final PlacementService placements;
    public PlacementsController(CourseContractGuards guards, PlacementService placements) { this.guards = guards; this.placements = placements; }

    @GetMapping(value = "/api/v1/placements")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, COURSE_NOT_VISIBLE})
    @Operation(summary = "listPlacements", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. The club's placements, filtered by every given "
            + "ringId, courseId and activityId (each must be the club's, a course the caller can read), most recently updated first. stale = the ring's "
            + "geometry changed after the placement was resolved (R-16-06); classSessionIds = the S06 classes that plan it.",
            responses = @ApiResponse(responseCode = "200", description = "S16 List<Placement>", useReturnTypeSchema = true))
    public List<Placement> listPlacements(@RequestParam(required = false) String ringId, @RequestParam(required = false) String courseId, @RequestParam(required = false) String activityId) {
        return placements.list(ringId, courseId, activityId).stream().map(CourseViews::placement).toList();
    }

    @GetMapping(value = "/api/v1/placements/{id}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "getPlacement", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. The placement with its resolvedObstacles in the ring's frame "
            + "(metres, what the AR app reads) and stale (R-16-06: the client recalculates a stale placement and PUTs it).",
            responses = @ApiResponse(responseCode = "200", description = "S16 Placement", useReturnTypeSchema = true))
    public Placement getPlacement(@PathVariable String id) {
        return CourseViews.placement(placements.get(id));
    }

    @PostMapping(value = "/api/v1/placements")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, PLACEMENT_BLOCKED, RING_WITHOUT_GEOMETRY, PLACEMENT_RING_MISMATCH, COURSE_NOT_VISIBLE, STALE_VERSION})
    @Operation(summary = "createPlacement", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES." + WRITE_RULES
            + " Publishes PlacementCreated{placementId, ringId, courseId, warnings[]}.",
            responses = @ApiResponse(responseCode = "201", description = "S16 Placement", useReturnTypeSchema = true))
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Placement createPlacement(@Valid @RequestBody PlacementRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return CourseViews.placement(placements.create(CourseViews.placement(request)));
    }

    @PutMapping(value = "/api/v1/placements/{id}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, PLACEMENT_BLOCKED, RING_WITHOUT_GEOMETRY, PLACEMENT_RING_MISMATCH, STALE_VERSION, COURSE_NOT_VISIBLE})
    @Operation(summary = "putPlacement", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. Replaces the placement at its stored version "
            + "(STALE_VERSION). It keeps its ring (another ringId → PLACEMENT_RING_MISMATCH) and its course (another courseId → VALIDATION_ERROR)." + WRITE_RULES
            + " The stored placement is no longer stale (R-16-06); publishes PlacementUpdated{placementId, stale: false}.",
            responses = @ApiResponse(responseCode = "200", description = "S16 Placement", useReturnTypeSchema = true))
    public Placement putPlacement(@PathVariable String id,
            @Valid @RequestBody PlacementUpdateRequest request) {
        return CourseViews.placement(placements.update(id, CourseViews.placement(request), request.version()));
    }

    @PostMapping(value = "/api/v1/placements/{id}/build-sheet")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "attachBuildSheet", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 Placement", useReturnTypeSchema = true))
    public Placement attachBuildSheet(@PathVariable String id,
            @Valid @RequestBody BuildSheetRequest request) {
        guards.placement(id, false);
        throw new UnsupportedOperationException("S16 contract");
    }

    @GetMapping(value = "/api/v1/placements/{id}/build-sheet", produces = "application/pdf")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "getBuildSheet", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 byte[]", content = @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary"))))
    public byte[] getBuildSheet(@PathVariable String id) {
        guards.placement(id, true);
        throw new UnsupportedOperationException("S16 contract");
    }
}
