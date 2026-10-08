package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.application.CourseContractGuards;
import com.agilityhub.core.courses.domain.CourseParts.*;
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

/** S16 contract. All checks precede the no-write NOT_IMPLEMENTED boundary. */
@RestController
@RequiresModule(Module.COURSES)
public class PlacementsController {
    private final CourseContractGuards guards;
    public PlacementsController(CourseContractGuards guards) { this.guards = guards; }

    @GetMapping(value = "/api/v1/placements")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, COURSE_NOT_VISIBLE})
    @Operation(summary = "listPlacements", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 List<Placement>", useReturnTypeSchema = true))
    public List<Placement> listPlacements(@RequestParam(required = false) String ringId, @RequestParam(required = false) String courseId, @RequestParam(required = false) String activityId) {
        if (ringId != null) { guards.ring(ringId, false); } if (courseId != null) { guards.course(courseId, false); } guards.activity(activityId);
        throw new UnsupportedOperationException("S16 contract");
    }

    @GetMapping(value = "/api/v1/placements/{id}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "getPlacement", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 Placement", useReturnTypeSchema = true))
    public Placement getPlacement(@PathVariable String id) {
        guards.placement(id, false);
        throw new UnsupportedOperationException("S16 contract");
    }

    @PostMapping(value = "/api/v1/placements")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, PLACEMENT_BLOCKED, RING_WITHOUT_GEOMETRY, PLACEMENT_RING_MISMATCH, COURSE_NOT_VISIBLE})
    @Operation(summary = "createPlacement", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "201", description = "S16 Placement", useReturnTypeSchema = true))
    public Placement createPlacement(@Valid @RequestBody PlacementRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        guards.placementReferences(request.courseId(), request.ringId(), request.activityId(), request.force());
        throw new UnsupportedOperationException("S16 contract");
    }

    @PutMapping(value = "/api/v1/placements/{id}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, PLACEMENT_BLOCKED, RING_WITHOUT_GEOMETRY, PLACEMENT_RING_MISMATCH, STALE_VERSION, COURSE_NOT_VISIBLE})
    @Operation(summary = "putPlacement", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 Placement", useReturnTypeSchema = true))
    public Placement putPlacement(@PathVariable String id,
            @Valid @RequestBody PlacementUpdateRequest request) {
        guards.placement(id, false); guards.placementReferences(request.courseId(), request.ringId(), request.activityId(), request.force());
        throw new UnsupportedOperationException("S16 contract");
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
