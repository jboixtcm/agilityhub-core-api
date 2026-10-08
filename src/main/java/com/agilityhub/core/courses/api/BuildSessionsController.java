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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.courses.api.CourseContracts.*;
import static com.agilityhub.core.courses.api.CourseRequests.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/** S16 contract. All checks precede the no-write NOT_IMPLEMENTED boundary. */
@RestController
@RequiresModule(Module.COURSES)
public class BuildSessionsController {
    private final CourseContractGuards guards;
    public BuildSessionsController(CourseContractGuards guards) { this.guards = guards; }

    @PostMapping(value = "/api/v1/build-sessions")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "createBuildSession", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "201", description = "S16 BuildSession", useReturnTypeSchema = true))
    public BuildSession createBuildSession(@Valid @RequestBody BuildSessionRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        guards.placement(request.placementId(), false);
        throw new UnsupportedOperationException("S16 contract");
    }

    @GetMapping(value = "/api/v1/build-sessions/{id}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "getBuildSession", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 BuildSession", useReturnTypeSchema = true))
    public BuildSession getBuildSession(@PathVariable String id) {
        guards.session(id);
        throw new UnsupportedOperationException("S16 contract");
    }

    @PatchMapping(value = "/api/v1/build-sessions/{id}/obstacles/{obstacleId}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, BUILD_SESSION_FINISHED, STALE_VERSION})
    @Operation(summary = "patchBuildObstacle", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 BuildSession", useReturnTypeSchema = true))
    public BuildSession patchBuildObstacle(@PathVariable String id,
            @PathVariable String obstacleId,
            @Valid @RequestBody BuildObstacleRequest request) {
        guards.obstacle(id, obstacleId);
        throw new UnsupportedOperationException("S16 contract");
    }

    @PostMapping(value = "/api/v1/build-sessions/{id}/finish")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, BUILD_SESSION_FINISHED})
    @Operation(summary = "finishBuildSession", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 BuildSession", useReturnTypeSchema = true))
    public BuildSession finishBuildSession(@PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        guards.session(id);
        throw new UnsupportedOperationException("S16 contract");
    }

    @GetMapping(value = "/api/v1/build-sessions/{id}/events", produces = "text/event-stream")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "buildSessionEvents", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED. Events: obstacle, finished; heartbeat every 20 seconds. Bearer authentication.",
            responses = @ApiResponse(responseCode = "200", description = "S16 org.springframework.web.servlet.mvc.method.annotation.SseEmitter", content = @Content(mediaType = "text/event-stream", schema = @Schema(type = "string"))))
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter buildSessionEvents(@PathVariable String id) {
        guards.session(id);
        throw new UnsupportedOperationException("S16 contract");
    }

    @PostMapping(value = "/api/v1/build-sessions/join")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "joinBuildSession", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 BuildSession", useReturnTypeSchema = true))
    public BuildSession joinBuildSession(@Valid @RequestBody BuildJoinRequest request) {
        guards.join(request.code());
        throw new UnsupportedOperationException("S16 contract");
    }

    @GetMapping(value = "/api/v1/build-sessions/{id}/export", produces = "application/json")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "exportBuildSession", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 JsonNode", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BuildSessionExportV1"))))
    public com.fasterxml.jackson.databind.JsonNode exportBuildSession(@PathVariable String id) {
        guards.session(id);
        throw new UnsupportedOperationException("S16 contract");
    }
}
