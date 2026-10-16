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
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    BuildSessionsController(BuildSessionService sessions, BuildSessionStreams streams, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.sessions = sessions; this.streams = streams; this.mapper = mapper;
    }

    @PostMapping(value = "/api/v1/build-sessions")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, COURSE_NOT_VISIBLE, STALE_VERSION})
    @Operation(summary = "createBuildSession", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. R-16-11: starts a build session on a placement "
            + "of the club (NOT_FOUND) whose course the caller can read (COURSE_NOT_VISIBLE): status NOT_STARTED, startedAt now, one obstacle per "
            + "resolved obstacle of the placement, all NOT_PLACED; selectedStrategy defaults to EQUIPMENT_TYPE. joinCode (six characters, unique among "
            + "the club's open sessions) is what another phone types and joinToken what its QR carries (POST /build-sessions/join); a code "
            + "drawn by another session at the same moment is drawn again. Publishes BuildSessionStarted.",
            responses = @ApiResponse(responseCode = "201", description = "S16 BuildSession", useReturnTypeSchema = true))
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public BuildSession createBuildSession(@Valid @RequestBody BuildSessionRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return CourseViews.session(sessions.start(request.placementId(), request.live(), request.selectedStrategy(), this::answer));
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
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, BUILD_SESSION_FINISHED, COURSE_NOT_VISIBLE,
            RING_WITHOUT_GEOMETRY, STALE_VERSION})
    @Operation(summary = "finishBuildSession", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. R-16-11: the session becomes COMPLETED "
            + "(finishedAt), whoever of them finishes it. The ring's setup follows courses.allowInstructorPublish (R-16-07): an ADMIN, or an "
            + "INSTRUCTOR while it is on, registers it as POST /ring-setups does (kind from the course's discipline: AGILITY, JUMPING, else FUN; "
            + "the placement as its source; the course's levels), and setupId names it; when the ring's active setup already shows this "
            + "placement it is refreshed instead, as a renewal (builtAt, builtByAccountId, the same expectedUntil and a new expiresAt; no "
            + "event). An INSTRUCTOR's finish with the switch off registers no setup: setupId is null and an ADMIN registers it with POST "
            + "/ring-setups. A closed or outlived session → BUILD_SESSION_FINISHED; a course no longer readable → COURSE_NOT_VISIBLE; a ring "
            + "without geometry → RING_WITHOUT_GEOMETRY. Taps or another finish racing it are retried (of two finishes at once, one closes the "
            + "session and the other gets BUILD_SESSION_FINISHED), STALE_VERSION only when the retries run out. Publishes "
            + "BuildSessionFinished and ends the session's streams with a finished event.",
            responses = @ApiResponse(responseCode = "200", description = "S16 BuildSession", useReturnTypeSchema = true))
    public BuildSession finishBuildSession(@PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return CourseViews.session(sessions.finish(id, this::answer));
    }

    @GetMapping(value = "/api/v1/build-sessions/{id}/events", produces = "text/event-stream")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "buildSessionEvents", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. R-16-11 live mode: a text/event-stream of the "
            + "session's changes. Event obstacle {sessionId, obstacleId, status, byAccountId, at, version} for each changed obstacle; event finished "
            + "{sessionId, status, finishedAt, version} when the session closes (COMPLETED or ABANDONED), after which the stream ends (a closed "
            + "session gets it at once); a session open for courses.buildSessionMaxHours or longer gets finished {status: ABANDONED, finishedAt: "
            + "null} within a second, before S15 P9 records it. Each event id is the session version. A comment line every 20 seconds is the "
            + "heartbeat. A stream lasts at most 30 minutes and never longer than the access token it opened with (its exp); a client that does "
            + "not read its events is closed. Authentication: the bearer header, or the access_token query parameter, which only this "
            + "operation accepts (a browser EventSource sends no header). After a reconnection, read GET /build-sessions/{id} for the state.",
            responses = @ApiResponse(responseCode = "200", description = "S16 build session events", content = @Content(mediaType = "text/event-stream", schema = @Schema(type = "string"))))
    public SseEmitter buildSessionEvents(@PathVariable String id) {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        java.time.Instant expiresAt = authentication instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt
                ? jwt.getToken().getExpiresAt() : null;
        return streams.subscribe(sessions.get(id), expiresAt);
    }

    @PostMapping(value = "/api/v1/build-sessions/join")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, STALE_VERSION})
    @Operation(summary = "joinBuildSession", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. R-16-11 (S16 §14.2): code is an open session's "
            + "joinCode (case-insensitive; joinedVia CODE) or the joinToken of its QR (joinedVia QR); the caller becomes a participant once (a "
            + "second join returns the session unchanged); phones joining at once all join. No open session of the club with that code, or "
            + "one open for courses.buildSessionMaxHours or longer → NOT_FOUND.",
            responses = @ApiResponse(responseCode = "200", description = "S16 BuildSession", useReturnTypeSchema = true))
    public BuildSession joinBuildSession(@Valid @RequestBody BuildJoinRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return CourseViews.session(sessions.join(request.code(), this::answer));
    }

    @GetMapping(value = "/api/v1/build-sessions/{id}/export", produces = "application/json")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, COURSE_NOT_VISIBLE, RING_WITHOUT_GEOMETRY, GEOMETRY_INVALID})
    @Operation(summary = "exportBuildSession", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. S16 §14.1 (E77): BuildSessionExportV1, the Unity "
            + "app's contract, kept byte for byte with the planner's spellings (snake_case keys and values, placement_mode in camelCase, "
            + "normalized_json and warnings_json as stored). judge_id and event_id are null, venue_id is the club's venue, is_training comes from the "
            + "placement's mode. The ring needs its geometry (RING_WITHOUT_GEOMETRY) with exactly six active markers, the A–F the app "
            + "calibrates with, and only those are exported (GEOMETRY_INVALID {violations: [{path: markers, rule}]}: TOO_FEW_MARKERS for "
            + "fewer, TOO_MANY_MARKERS for more); a course no longer readable → COURSE_NOT_VISIBLE. "
            + "The server checks the result against the schema: a failed check is a server error (500 INTERNAL_ERROR).",
            responses = @ApiResponse(responseCode = "200", description = "S16 BuildSessionExportV1", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BuildSessionExportV1"))))
    public com.fasterxml.jackson.databind.JsonNode exportBuildSession(@PathVariable String id) {
        return sessions.export(id);
    }

    /** The bytes of a keyed write's answer (`IdempotencyFilter.BUILD_SESSIONS`), stored with its key in the transaction that commits. */
    private byte[] answer(com.agilityhub.core.courses.persistence.BuildSession session) {
        try { return mapper.writeValueAsBytes(CourseViews.session(session)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException(failure); }
    }
}
