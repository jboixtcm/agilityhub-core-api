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
public class RingSetupsController {
    private final CourseContractGuards guards;
    public RingSetupsController(CourseContractGuards guards) { this.guards = guards; }

    @PostMapping(value = "/api/v1/ring-setups")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, RING_WITHOUT_GEOMETRY, PLACEMENT_RING_MISMATCH, SETUP_SOURCE_REQUIRED, COURSE_NOT_VISIBLE})
    @Operation(summary = "createRingSetup", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "201", description = "S16 RingSetupCreated", useReturnTypeSchema = true))
    public RingSetupCreated createRingSetup(@Valid @RequestBody RingSetupRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        guards.canPublish(); guards.ring(request.ringId(), false); if (request.placementId() != null) { guards.placement(request.placementId(), false); } if (request.courseId() != null) { guards.course(request.courseId(), false); } guards.levels(request.levelIds());
        throw new UnsupportedOperationException("S16 contract");
    }

    @GetMapping(value = "/api/v1/ring-setups/{id}")
    @PreAuthorize("hasAnyRole('MEMBER', 'INSTRUCTOR', 'ADMIN')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "getRingSetup", description = "Roles: MEMBER, INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 Object", content = @Content(schema = @Schema(oneOf = {RingSetup.class, MemberRingSetup.class}))))
    public Object getRingSetup(@PathVariable String id) {
        guards.setup(id);
        throw new UnsupportedOperationException("S16 contract");
    }

    @PostMapping(value = "/api/v1/ring-setups/{id}/dismantle")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, INVALID_STATE})
    @Operation(summary = "dismantleRingSetup", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 RingSetup", useReturnTypeSchema = true))
    public RingSetup dismantleRingSetup(@PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        guards.setup(id);
        throw new UnsupportedOperationException("S16 contract");
    }

    @PostMapping(value = "/api/v1/ring-setups/{id}/renewal")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, INVALID_STATE})
    @Operation(summary = "renewRingSetup", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 RingSetup", useReturnTypeSchema = true))
    public RingSetup renewRingSetup(@PathVariable String id,
            @Valid @RequestBody RenewalRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        guards.setup(id);
        throw new UnsupportedOperationException("S16 contract");
    }

    @GetMapping(value = "/api/v1/me/ring-setups")
    @PreAuthorize("hasAnyRole('MEMBER', 'INSTRUCTOR')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "myRingSetups", description = "Roles: MEMBER, INSTRUCTOR. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 List<MeRingSetup>", useReturnTypeSchema = true))
    public List<MeRingSetup> myRingSetups(@RequestParam(required = false) List<String> ringIds) {
        guards.memberVisibility(); guards.rings(ringIds);
        throw new UnsupportedOperationException("S16 contract");
    }
}
