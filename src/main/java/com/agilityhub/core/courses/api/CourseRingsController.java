package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.application.RingGeometryService;
import com.agilityhub.core.courses.application.RingSetupService;
import com.agilityhub.core.courses.domain.CourseParts.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.courses.api.CourseContracts.*;
import static com.agilityhub.core.courses.api.CourseRequests.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S16 ring geometry and calibrations (E9-T02: R-16-04, §14.2) and the setup history (E9-T03: R-16-08). The QR marker sheet is the
 * client's PDF (A8b, E115): the api has no route for it.
 */
@RestController
@RequiresModule(Module.COURSES)
public class CourseRingsController {
    private final RingGeometryService geometries;
    private final RingSetupService setups;
    public CourseRingsController(RingGeometryService geometries, RingSetupService setups) { this.geometries = geometries; this.setups = setups; }

    @GetMapping(value = "/api/v1/rings/{id}/geometry")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "ringGeometry", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. A ring without geometry → NOT_FOUND.",
            responses = @ApiResponse(responseCode = "200", description = "S16 RingGeometry", useReturnTypeSchema = true))
    public RingGeometry ringGeometry(@PathVariable String id) {
        return geometries.get(id);
    }

    @PutMapping(value = "/api/v1/rings/{id}/geometry")
    @PreAuthorize("hasRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, GEOMETRY_INVALID, STALE_VERSION})
    @Operation(summary = "putRingGeometry", description = "Roles: ADMIN. Module: COURSES. R-16-04: lengthM and widthM within [10, 60]; doors on the perimeter "
            + "(0 ≤ startM < endM ≤ the side: lengthM for NORTH/SOUTH, widthM for EAST/WEST; x runs along lengthM from the bottom-left corner); no-go polygons "
            + "of at least 3 points, simple and inside the ring; at least 3 active, non-collinear markers; unique ids, marker labels, markerUids and aprilTagIds; "
            + "non-negative clearances and margins; positive marker sizes. A failed check → GEOMETRY_INVALID (422, GeometryInvalidDetails {violations[] {path, rule}}). "
            + "version = the geometry's stored version (0 without one), else STALE_VERSION. Saving increments the version, marks every placement of the ring stale "
            + "(R-16-06), publishes RingGeometryChanged{ringId, version} and audits RING_GEOMETRY_CHANGED; the club's first geometry creates the club's venue.",
            responses = @ApiResponse(responseCode = "200", description = "S16 RingGeometry", useReturnTypeSchema = true))
    public RingGeometry putRingGeometry(@PathVariable String id,
            @Valid @RequestBody RingGeometryRequest request) {
        return geometries.put(id, CourseViews.geometry(request), request.version());
    }

    @DeleteMapping(value = "/api/v1/rings/{id}/geometry")
    @PreAuthorize("hasRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, RING_GEOMETRY_IN_USE, STALE_VERSION})
    @Operation(summary = "deleteRingGeometry", description = "Roles: ADMIN. Module: COURSES. R-16-04: removes the geometry at its stored version (STALE_VERSION); "
            + "RING_GEOMETRY_IN_USE while a placement that is not archived, or an active setup with a placement, uses it. Audits RING_GEOMETRY_CHANGED.",
            responses = @ApiResponse(responseCode = "204", description = "S16 void", content = @Content))
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void deleteRingGeometry(@PathVariable String id,
            @RequestParam @jakarta.validation.constraints.PositiveOrZero long version) {
        geometries.delete(id, version);
    }

    @GetMapping(value = "/api/v1/rings/{id}/setups")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "ringSetupHistory", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. R-16-08: every setup of the ring (ACTIVE, EXPIRED and "
            + "DISMANTLED), newest builtAt first; page from 0.",
            responses = @ApiResponse(responseCode = "200", description = "S16 RingSetupPage", useReturnTypeSchema = true))
    public RingSetupPage ringSetupHistory(@PathVariable String id,
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int page, @RequestParam(defaultValue = "50") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(1000) int size) {
        return CourseViews.history(setups.history(id, page, size));
    }

    @PostMapping(value = "/api/v1/rings/{id}/calibrations")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "createCalibration", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. S16 §14.2: stores one calibration of the ring (per-marker data in rawMetadata).",
            responses = @ApiResponse(responseCode = "201", description = "S16 CalibrationLog", useReturnTypeSchema = true))
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public CalibrationLog createCalibration(@PathVariable String id,
            @Valid @RequestBody CalibrationRequest request) {
        return CourseViews.calibration(geometries.calibrate(id, CourseViews.calibration(request)));
    }

    @GetMapping(value = "/api/v1/rings/{id}/calibrations")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "listCalibrations", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. The ring's calibrations, newest first.",
            responses = @ApiResponse(responseCode = "200", description = "S16 List<CalibrationLog>", useReturnTypeSchema = true))
    public List<CalibrationLog> listCalibrations(@PathVariable String id) {
        return geometries.calibrations(id).stream().map(CourseViews::calibration).toList();
    }
}
