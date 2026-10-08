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
public class CourseRingsController {
    private final CourseContractGuards guards;
    public CourseRingsController(CourseContractGuards guards) { this.guards = guards; }

    @GetMapping(value = "/api/v1/rings/{id}/geometry")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "ringGeometry", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 RingGeometry", useReturnTypeSchema = true))
    public RingGeometry ringGeometry(@PathVariable String id) {
        guards.ring(id, true);
        throw new UnsupportedOperationException("S16 contract");
    }

    @PutMapping(value = "/api/v1/rings/{id}/geometry")
    @PreAuthorize("hasRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, GEOMETRY_INVALID, STALE_VERSION})
    @Operation(summary = "putRingGeometry", description = "Roles: ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 RingGeometry", useReturnTypeSchema = true))
    public RingGeometry putRingGeometry(@PathVariable String id,
            @Valid @RequestBody RingGeometryRequest request) {
        guards.ring(id, false);
        throw new UnsupportedOperationException("S16 contract");
    }

    @DeleteMapping(value = "/api/v1/rings/{id}/geometry")
    @PreAuthorize("hasRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, RING_GEOMETRY_IN_USE, STALE_VERSION})
    @Operation(summary = "deleteRingGeometry", description = "Roles: ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "204", description = "S16 void", content = @Content))
    public void deleteRingGeometry(@PathVariable String id,
            @RequestParam @jakarta.validation.constraints.PositiveOrZero long version) {
        guards.ring(id, true);
        throw new UnsupportedOperationException("S16 contract");
    }

    @GetMapping(value = "/api/v1/rings/{id}/marker-sheet", produces = "application/pdf")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "markerSheet", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 byte[]", content = @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary"))))
    public byte[] markerSheet(@PathVariable String id) {
        guards.ring(id, true);
        throw new UnsupportedOperationException("S16 contract");
    }

    @GetMapping(value = "/api/v1/rings/{id}/setups")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "ringSetupHistory", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 RingSetupPage", useReturnTypeSchema = true))
    public RingSetupPage ringSetupHistory(@PathVariable String id,
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int page, @RequestParam(defaultValue = "50") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(1000) int size) {
        guards.ring(id, false);
        throw new UnsupportedOperationException("S16 contract");
    }

    @PostMapping(value = "/api/v1/rings/{id}/calibrations")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "createCalibration", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "201", description = "S16 CalibrationLog", useReturnTypeSchema = true))
    public CalibrationLog createCalibration(@PathVariable String id,
            @Valid @RequestBody CalibrationRequest request) {
        guards.ring(id, false);
        throw new UnsupportedOperationException("S16 contract");
    }

    @GetMapping(value = "/api/v1/rings/{id}/calibrations")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "listCalibrations", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED. Newest first.",
            responses = @ApiResponse(responseCode = "200", description = "S16 List<CalibrationLog>", useReturnTypeSchema = true))
    public List<CalibrationLog> listCalibrations(@PathVariable String id) {
        guards.ring(id, false);
        throw new UnsupportedOperationException("S16 contract");
    }
}
