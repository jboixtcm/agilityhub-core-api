package com.agilityhub.core.courses.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.courses.domain.BuildSessionRules;
import com.agilityhub.core.courses.domain.RingGeometryRules;
import com.agilityhub.core.courses.domain.CourseEvent;
import com.agilityhub.core.courses.domain.CourseParts.*;
import com.agilityhub.core.courses.domain.CourseTypes.*;
import com.agilityhub.core.courses.persistence.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.ClubClock;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.IdempotentOperation;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.TransactionRetries;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S16 R-16-11, the build session: started on a placement with every obstacle `NOT_PLACED` (§3's `PENDING`), a join code and a QR
 * token for the other phones, obstacles marked last-write-wins, and `finish`, which registers (or refreshes) the ring's setup.
 * Every change commits with its outbox event (`BuildSessionStarted`, `BuildSessionProgress`, `BuildSessionFinished`) and then
 * signals {@link Changed} so the live streams (`GET /build-sessions/{id}/events`) send it. An open session that has outlived
 * `courses.buildSessionMaxHours` is over: its writes answer `422 BUILD_SESSION_FINISHED` and S15 P9 records it `ABANDONED`.
 * <p>
 * Every write is one Mongo transaction retried whole on a write conflict (at most {@link #ATTEMPTS} times, the shared jitter):
 * phones tapping one session at once meet on its document, and «last-write-wins per obstacle» means each tap applies, never a
 * `409` or a `500`. Exhausted retries are `409 STALE_VERSION` (E73). Inside a caller's transaction the work joins it. The keyed
 * writes (start, join, `finish`; E9-T06, `IdempotencyFilter.BUILD_SESSIONS`) lock their `Idempotency-Key` row first and store
 * their answer last, in the attempt that commits.
 */
@Service
public class BuildSessionService {
    /** After commit: the session {@code sessionId} of {@code clubId} changed (an obstacle, its end, a participant). */
    public record Changed(String clubId, String sessionId) { }
    static final String MAX_HOURS = "courses.buildSessionMaxHours";
    static final int ATTEMPTS = 5;
    static final String CONTEXT = "build-sessions";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** An index below its bound per join-code character; a test makes a draw meet a code already taken. */
    private IntUnaryOperator draws = RANDOM::nextInt;

    private final BuildSessionRepository sessions;
    private final PlacementRepository placements;
    private final RingGeometryRepository rings;
    private final VenueRepository venues;
    private final CourseAccess access;
    private final RingSetupService setups;
    private final PlanningCatalogAccess catalogs;
    private final ClubConfigService configs;
    private final CourseSchemas schemas;
    private final ClubClock clocks;
    private final EventPublisher events;
    private final ApplicationEventPublisher signals;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final TransactionRetries retries;
    public BuildSessionService(BuildSessionRepository sessions, PlacementRepository placements, RingGeometryRepository rings, VenueRepository venues,
            CourseAccess access, RingSetupService setups, PlanningCatalogAccess catalogs, ClubConfigService configs, CourseSchemas schemas, ClubClock clocks,
            EventPublisher events, ApplicationEventPublisher signals, ObjectMapper mapper, TransactionTemplate transactions, TransactionRetries retries) {
        this.sessions = sessions; this.placements = placements; this.rings = rings; this.venues = venues; this.access = access; this.setups = setups;
        this.catalogs = catalogs; this.configs = configs; this.schemas = schemas; this.clocks = clocks; this.events = events; this.signals = signals;
        this.mapper = mapper; this.transactions = transactions; this.retries = retries;
    }

    /**
     * `POST /build-sessions {placementId, live, selectedStrategy?}`: a placement of the club (`404`) whose course the caller can read
     * (`COURSE_NOT_VISIBLE`); `NOT_STARTED`, `startedAt = now`, one `NOT_PLACED` obstacle per resolved obstacle, a join code unique
     * among the club's open sessions and a QR token. Publishes `BuildSessionStarted{sessionId, placementId, live}`.
     */
    public BuildSession start(String placementId, boolean live, BuildStrategy strategy) { return start(placementId, live, strategy, null); }
    /** The keyed start: {@code answer} gives the bytes of its `201`, stored with the `Idempotency-Key` in the attempt that commits. */
    public BuildSession start(String placementId, boolean live, BuildStrategy strategy, Function<BuildSession, byte[]> answer) {
        return keyed(201, answer, () -> started(placementId, live, strategy));
    }
    private BuildSession started(String placementId, boolean live, BuildStrategy strategy) {
        String clubId = TenantContext.require(), actor = CourseAccess.accountId();
        var placement = placement(placementId);
        access.course(placement.courseId(), false);
        var now = clocks.now(clubId).toInstant();
        String code = BuildSessionRules.joinCode(draws);
        while (sessions.findOpenByCode(code).isPresent()) { code = BuildSessionRules.joinCode(draws); }
        var obstacles = new ArrayList<BuildObstacle>(); var seen = new HashSet<String>();
        for (var resolved : placement.resolvedObstacles()) {
            if (seen.add(resolved.obstacleId())) { obstacles.add(new BuildObstacle(resolved.obstacleId(), ObstacleStatus.NOT_PLACED, null, null, null)); }
        }
        var session = new BuildSession(UUID.randomUUID().toString(), clubId, placement.id(), placement.ringId(), BuildStatus.NOT_STARTED,
                strategy == null ? BuildStrategy.EQUIPMENT_TYPE : strategy, live, actor, now, null, null, null, 0, null, code, token(), now,
                obstacles, List.of(), null, 0, now, actor, now, actor);
        // Two sessions drawing the same code at once meet on the partial unique index `open_join_code`: the retry draws again.
        sessions.insert(session);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("sessionId", session.id()); payload.put("placementId", session.placementId()); payload.put("live", session.live());
        publish(CourseEvent.Kind.BuildSessionStarted, session, payload, actor, now);
        return session;
    }

    /** `GET /build-sessions/{id}`: any open or closed session of the club. */
    public BuildSession get(String id) { return sessions.findById(id).orElseThrow(() -> new ApiException(NOT_FOUND)); }
    /** The live streams' read of a session of the current club, if it still exists. */
    public Optional<BuildSession> current(String id) { return sessions.findById(id); }
    /**
     * R-16-11 «> 12 h es tanca sola» (E9-T06): {@code session} of the current club is still open but has outlived
     * `courses.buildSessionMaxHours`, so it takes no write and its streams end now, before S15 P9 records it `ABANDONED`.
     */
    public boolean over(BuildSession session) {
        return BuildSessionRules.open(session.status()) && BuildSessionRules.outlived(session.status(), session.startedAt(), session.createdAt(),
                maxHours(), clocks.now(TenantContext.require()).toInstant());
    }

    /**
     * `PATCH /build-sessions/{id}/obstacles/{obstacleId} {status}`: last write wins per obstacle (the request's `version` is not
     * compared, R-16-11); the first change moves the session to `IN_PROGRESS`. A closed or outlived session → `422
     * BUILD_SESSION_FINISHED`. Publishes `BuildSessionProgress{sessionId, obstacleId, status}`.
     */
    public BuildSession mark(String id, String obstacleId, ObstacleStatus status) { return retrying(() -> marked(id, obstacleId, status)); }
    private BuildSession marked(String id, String obstacleId, ObstacleStatus status) {
        var session = get(id);
        if (session.obstacles().stream().noneMatch(o -> o.obstacleId().equals(obstacleId))) { throw new ApiException(NOT_FOUND); }
        var now = writable(session);
        String actor = CourseAccess.accountId();
        var marked = sessions.markObstacle(id, obstacleId, status, actor, now).orElseThrow(() -> new ApiException(BUILD_SESSION_FINISHED));
        var payload = new LinkedHashMap<String, Object>();
        payload.put("sessionId", id); payload.put("obstacleId", obstacleId); payload.put("status", status.name());
        publish(CourseEvent.Kind.BuildSessionProgress, marked, payload, actor, now);
        return marked;
    }

    /**
     * `POST /build-sessions/{id}/finish`: whoever may write to the session closes it, `COMPLETED` with `finishedAt` (E118: not
     * gated by `courses.allowInstructorPublish`). The ring's setup (R-16-07 through {@link RingSetupService#built}) follows that
     * switch: an ADMIN, or an INSTRUCTOR while it is on, registers or refreshes it; an INSTRUCTOR with it off registers none, and
     * the session's `setupId` stays null (an ADMIN registers it with `POST /ring-setups`). The placement's course must still be
     * readable (`COURSE_NOT_VISIBLE`). Publishes `BuildSessionFinished{sessionId, setupId}`.
     */
    public BuildSession finish(String id) { return finish(id, null); }
    /** The keyed `finish`: {@code answer} gives the bytes of its `200`, stored with the `Idempotency-Key` in the attempt that commits. */
    public BuildSession finish(String id, Function<BuildSession, byte[]> answer) { return keyed(200, answer, () -> finished(id)); }
    private BuildSession finished(String id) {
        var session = get(id);
        var now = writable(session);
        var placement = placement(session.placementId());
        var course = access.course(placement.courseId(), false);
        String actor = CourseAccess.accountId();
        boolean registers = CourseAccess.role("ADMIN") || access.enabled("courses.allowInstructorPublish");
        String setupId = registers ? setups.built(placement, course, actor) : null;
        var finished = sessions.close(id, setupId, actor, now).orElseThrow(() -> new ApiException(BUILD_SESSION_FINISHED));
        var payload = new LinkedHashMap<String, Object>();
        payload.put("sessionId", id); payload.put("setupId", setupId);
        publish(CourseEvent.Kind.BuildSessionFinished, finished, payload, actor, now);
        return finished;
    }

    /**
     * `POST /build-sessions/join {code}`: the join code (`CODE`, case-insensitive) or the QR token (`QR`) of an open session of the
     * club, else `404` (an outlived one included). The caller joins once; a second join returns the session unchanged.
     */
    public BuildSession join(String typed) { return join(typed, null); }
    /** The keyed join: {@code answer} gives the bytes of its `200`, stored with the `Idempotency-Key` in the attempt that commits. */
    public BuildSession join(String typed, Function<BuildSession, byte[]> answer) { return keyed(200, answer, () -> joined(typed)); }
    private BuildSession joined(String typed) {
        String clubId = TenantContext.require(), actor = CourseAccess.accountId();
        var byCode = sessions.findOpenByCode(BuildSessionRules.normalizeCode(typed));
        var session = byCode.or(() -> typed == null ? Optional.empty() : sessions.findOpenByToken(typed.strip())).orElseThrow(() -> new ApiException(NOT_FOUND));
        var now = clocks.now(clubId).toInstant();
        if (BuildSessionRules.outlived(session.status(), session.startedAt(), session.createdAt(), maxHours(), now)) { throw new ApiException(NOT_FOUND); }
        if (session.participants().stream().anyMatch(p -> p.accountId().equals(actor))) { return session; }
        var joined = new BuildParticipant(actor, now, byCode.isPresent() ? JoinedVia.CODE : JoinedVia.QR);
        var updated = sessions.join(session.id(), joined, now);
        if (updated.isEmpty()) { return get(session.id()); }
        signal(updated.get());
        return updated.get();
    }

    /**
     * `GET /build-sessions/{id}/export`: `BuildSessionExportV1` (A9), self-checked against the committed schema. The ring needs
     * its geometry (`422 RING_WITHOUT_GEOMETRY`) with exactly six **active** markers, the A–F the Unity app calibrates with (E118:
     * fewer → `422 GEOMETRY_INVALID {violations: [{path: markers, rule: TOO_FEW_MARKERS}]}`, more → `TOO_MANY_MARKERS`; R-16-04
     * keeps «≥ 3» for the geometry itself); only those six are exported. The course must still be readable. A result the schema
     * refuses is the server's fault: `500 INTERNAL_ERROR` (E98), never a client error.
     */
    public ObjectNode export(String id) {
        var session = get(id);
        var placement = placement(session.placementId());
        var course = access.course(placement.courseId(), false);
        var ring = rings.findById(placement.ringId()).orElseThrow(() -> new ApiException(NOT_FOUND));
        if (ring.geometry() == null) { throw new ApiException(RING_WITHOUT_GEOMETRY); }
        long active = ring.geometry().markers().stream().filter(RingMarker::isActive).count();
        if (active != BuildSessionExport.MARKERS) {
            var rule = active < BuildSessionExport.MARKERS ? RingGeometryRules.Rule.TOO_FEW_MARKERS : RingGeometryRules.Rule.TOO_MANY_MARKERS;
            throw new ApiException(GEOMETRY_INVALID, Map.of("violations", List.of(Map.of("path", "markers", "rule", rule.name()))));
        }
        var venue = venues.club().orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, new IllegalStateException("Club without a venue")));
        String name = catalogs.rings().stream().filter(r -> r.id().equals(ring.id())).map(PlanningCatalogAccess.RingView::name).findFirst().orElse(ring.id());
        var export = BuildSessionExport.build(mapper, session, placement, course, venue, new BuildSessionExport.Ring(ring.id(), name, ring.geometry()),
                clocks.now(TenantContext.require()).toInstant());
        schemas.validateExport(export);
        return export;
    }

    // ---- S15 P9 ----

    /** S15 P9 (R-16-11): the club's open sessions started more than `courses.buildSessionMaxHours` before {@code now}. */
    public List<BuildSession> outlived(Instant now) { return sessions.outlived(now.minus(Duration.ofHours(maxHours()))); }
    /** S15 P9, one item: still open and outlived at {@code now} → `ABANDONED` (no S16 §7 event); false when no longer in scope. */
    public boolean abandon(String id, Instant now) {
        var abandoned = sessions.abandon(id, now.minus(Duration.ofHours(maxHours())), now);
        abandoned.ifPresent(this::signal);
        return abandoned.isPresent();
    }

    // ---- helpers ----

    /** The club-local now of a write on {@code session}: closed or outlived → `422 BUILD_SESSION_FINISHED`. */
    private Instant writable(BuildSession session) {
        var now = clocks.now(TenantContext.require()).toInstant();
        if (!BuildSessionRules.open(session.status())
                || BuildSessionRules.outlived(session.status(), session.startedAt(), session.createdAt(), maxHours(), now)) {
            throw new ApiException(BUILD_SESSION_FINISHED);
        }
        return now;
    }
    /**
     * A keyed write in {@link #retrying}: each attempt locks the request's `Idempotency-Key` row first and, when {@code answer} is
     * given, stores {@code status} and the answer's bytes last, so only the attempt that commits records them. Without a key (or
     * outside the HTTP path) the row calls do nothing.
     */
    private BuildSession keyed(int status, Function<BuildSession, byte[]> answer, java.util.function.Supplier<BuildSession> work) {
        return retrying(() -> {
            IdempotentOperation.lock();
            var result = work.get();
            if (answer != null) { IdempotentOperation.complete(status, answer.apply(result)); }
            return result;
        });
    }
    /** One transaction, retried whole on a write conflict or a duplicate key; inside a caller's transaction, joined once. */
    private <T> T retrying(java.util.function.Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { return work.get(); }
        for (int attempt = 1; ; attempt++) {
            try { return transactions.execute(status -> work.get()); }
            catch (RuntimeException failure) {
                if (failure instanceof ApiException || !TransactionRetries.conflict(failure)) { throw failure; }
                if (attempt >= ATTEMPTS) { retries.exhausted(CONTEXT); throw new ApiException(STALE_VERSION, failure); }
                retries.retried(CONTEXT, failure);
                try { Thread.sleep(TransactionRetries.jitter()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new ApiException(STALE_VERSION, failure); }
            }
        }
    }
    private Placement placement(String id) { return placements.findById(id).orElseThrow(() -> new ApiException(NOT_FOUND)); }
    /** Tests only: the join-code draws. */
    void draws(IntUnaryOperator draws) { this.draws = draws; }
    private int maxHours() { return configs.get(TenantContext.require()).get(MAX_HOURS, Integer.class); }
    private static String token() {
        var bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private void publish(CourseEvent.Kind kind, BuildSession session, Map<String, Object> payload, String actor, Instant at) {
        events.publish(new CourseEvent(kind, session.clubId(), session.id(), at, payload, actor, null, DomainEvent.Origin.INSTRUCTOR));
        signal(session);
    }
    private void signal(BuildSession session) { signals.publishEvent(new Changed(session.clubId(), session.id())); }
}
