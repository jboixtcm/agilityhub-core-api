package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.application.BuildSessionService;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static com.agilityhub.core.courses.api.CourseContracts.*;
import static com.agilityhub.core.courses.api.CourseRequests.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/** S16 build sessions (E9-T04: R-16-11): the session, its live stream, the join code and `BuildSessionExportV1`. */
@RestController
@RequiresModule(Module.COURSES)
public class BuildSessionsController {
    private final BuildSessionService sessions;
    private final BuildSessionStreams streams;
    BuildSessionsController(BuildSessionService sessions, BuildSessionStreams streams) { this.sessions = sessions; this.streams = streams; }

    @PostMapping(value = "/api/v1/build-sessions")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, COURSE_NOT_VISIBLE, STALE_VERSION})
    @Operation(summary = "createBuildSession", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. R-16-11: starts a build session on a placement "
            + "of the club (NOT_FOUND) whose course the caller can read (COURSE_NOT_VISIBLE): status NOT_STARTED, startedAt now, one obstacle per "
            + "resolved obstacle of the placement, all NOT_PLACED; selectedStrategy defaults to EQUIPMENT_TYPE. joinCode (six characters, unique among "
            + "the club's open sessions) is what another phone types and joinToken what its QR carries (POST /build-sessions/join). "
            + "Publishes BuildSessionStarted.",
            responses = @ApiResponse(responseCode = "201", description = "S16 BuildSession", useReturnTypeSchema = true))
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public BuildSession createBuildSession(@Valid @RequestBody BuildSessionRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return CourseViews.session(sessions.start(request.placementId(), request.live(), request.selectedStrategy()));
    }

    @GetMapping(value = "/api/v1/build-sessions/{id}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "getBuildSession", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. R-16-11: a build session of the club, open or "
            + "closed. A live client reads it again whenever its event stream reconnects.",
            responses = @ApiResponse(responseCode = "200", description = "S16 BuildSession", useReturnTypeSchema = true))
    public BuildSession getBuildSession(@PathVariable String id) {
        return CourseViews.session(sessions.get(id));
    }

    @PatchMapping(value = "/api/v1/build-sessions/{id}/obstacles/{obstacleId}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, BUILD_SESSION_FINISHED, STALE_VERSION})
    @Operation(summary = "patchBuildObstacle", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. R-16-11: the obstacle takes the status, with "
            + "byAccountId and at; the last write wins per obstacle (version is accepted and not compared), and the first change makes the session "
            + "IN_PROGRESS; taps of several phones at once all apply (a write conflict is retried, STALE_VERSION only when the retries run out). "
            + "An obstacle the session does not list → NOT_FOUND. A COMPLETED or ABANDONED session, or one open for "
            + "courses.buildSessionMaxHours or longer → BUILD_SESSION_FINISHED. Publishes BuildSessionProgress and an obstacle event on the "
            + "session's stream; the response carries the new session version.",
            responses = @ApiResponse(responseCode = "200", description = "S16 BuildSession", useReturnTypeSchema = true))
    public BuildSession patchBuildObstacle(@PathVariable String id,
            @PathVariable String obstacleId,
            @Valid @RequestBody BuildObstacleRequest request) {
        return CourseViews.session(sessions.mark(id, obstacleId, request.status()));
    }

    @PostMapping(value = "/api/v1/build-sessions/{id}/finish")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, BUILD_SESSION_FINISHED, COURSE_NOT_VISIBLE, STALE_VERSION})
    @Operation(summary = "finishBuildSession", description = "Roles: INSTRUCTOR (while courses.allowInstructorPublish, else FORBIDDEN), ADMIN. Module: "
            + "COURSES. R-16-11: the session becomes COMPLETED (finishedAt) and the ring's setup is registered as POST /ring-setups does: kind from "
            + "the course's discipline (AGILITY, JUMPING, else FUN), the placement as its source, the course's levels. When the ring's active setup "
            + "already shows this placement it is refreshed instead (builtAt, builtByAccountId and a new expiresAt; no event). A closed or outlived "
            + "session → BUILD_SESSION_FINISHED; a course no longer readable → COURSE_NOT_VISIBLE. Publishes BuildSessionFinished and ends the "
            + "session's streams with a finished event.",
            responses = @ApiResponse(responseCode = "200", description = "S16 BuildSession", useReturnTypeSchema = true))
    public BuildSession finishBuildSession(@PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return CourseViews.session(sessions.finish(id));
    }

    @GetMapping(value = "/api/v1/build-sessions/{id}/events", produces = "text/event-stream")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "buildSessionEvents", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. R-16-11 live mode: a text/event-stream of the "
            + "session's changes. Event obstacle {sessionId, obstacleId, status, byAccountId, at, version} for each changed obstacle; event finished "
            + "{sessionId, status, finishedAt, version} when the session closes (COMPLETED or ABANDONED), after which the stream ends (a closed "
            + "session gets it at once); each event id is the session version. A comment line every 20 seconds is the heartbeat. Authentication: "
            + "the bearer header, or the access_token query parameter, which only this operation accepts (a browser EventSource sends no header). "
            + "After a reconnection, read GET /build-sessions/{id} for the state.",
            responses = @ApiResponse(responseCode = "200", description = "S16 build session events", content = @Content(mediaType = "text/event-stream", schema = @Schema(type = "string"))))
    public SseEmitter buildSessionEvents(@PathVariable String id) {
        return streams.subscribe(sessions.get(id));
    }

    @PostMapping(value = "/api/v1/build-sessions/join")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, STALE_VERSION})
    @Operation(summary = "joinBuildSession", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. R-16-11 (S16 §14.2): code is an open session's "
            + "joinCode (case-insensitive; joinedVia CODE) or the joinToken of its QR (joinedVia QR); the caller becomes a participant once (a "
            + "second join returns the session unchanged). No open session of the club with that code, or one open for "
            + "courses.buildSessionMaxHours or longer → NOT_FOUND.",
            responses = @ApiResponse(responseCode = "200", description = "S16 BuildSession", useReturnTypeSchema = true))
    public BuildSession joinBuildSession(@Valid @RequestBody BuildJoinRequest request) {
        return CourseViews.session(sessions.join(request.code()));
    }

    @GetMapping(value = "/api/v1/build-sessions/{id}/export", produces = "application/json")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, COURSE_NOT_VISIBLE, RING_WITHOUT_GEOMETRY, GEOMETRY_INVALID})
    @Operation(summary = "exportBuildSession", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. S16 §14.1 (E77): BuildSessionExportV1, the Unity "
            + "app's contract, kept byte for byte with the planner's spellings (snake_case keys and values, placement_mode in camelCase, "
            + "normalized_json and warnings_json as stored). judge_id and event_id are null, venue_id is the club's venue, is_training comes from the "
            + "placement's mode. The ring needs its geometry (RING_WITHOUT_GEOMETRY) with the six markers A–F the app calibrates with "
            + "(GEOMETRY_INVALID {violations: [{path: markers, rule: TOO_FEW_MARKERS}]}); a course no longer readable → COURSE_NOT_VISIBLE. "
            + "The server checks the result against the schema: a failed check is a server error (500 INTERNAL_ERROR).",
            responses = @ApiResponse(responseCode = "200", description = "S16 BuildSessionExportV1", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BuildSessionExportV1"))))
    public com.fasterxml.jackson.databind.JsonNode exportBuildSession(@PathVariable String id) {
        return sessions.export(id);
    }
}
