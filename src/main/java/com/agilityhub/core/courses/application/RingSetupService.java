package com.agilityhub.core.courses.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.courses.domain.AgilityHubLevel;
import com.agilityhub.core.courses.domain.CourseEvent;
import com.agilityhub.core.courses.domain.CourseTypes.*;
import com.agilityhub.core.courses.domain.RingSetupRules;
import com.agilityhub.core.courses.persistence.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.ClubClock;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.TransactionRetries;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S16 R-16-07 (one active setup per ring), R-16-08 (expiry, dismantling, renewal, history) and R-16-09 (what a member sees), and
 * the reads S09 (R-09-15), S06 (`placementId`) and S15 (P5 step f) make through their own ports. Registering a setup dismantles the
 * ring's active one and points `Ring.activeSetupId` to the new one in the same transaction (the partial unique index
 * `active_setup` is the guard: a concurrent registration of the ring is `409 STALE_VERSION`), publishes
 * `RingSetupChanged{ACTIVE, replacedSetupId}` and writes `RING_SETUP_REGISTERED`. Ending one (dismantled, expired, its ring
 * deactivated) clears the pointer when it still names it and publishes `RingSetupChanged` with the new status.
 */
@Service
public class RingSetupService {
    public record SetupInput(String ringId, SetupKind kind, String placementId, String courseId, String imageFileKey, AgilityHubLevel agilityhubLevel,
            List<String> levelIds, LocalDate expectedUntil, String notes) { }
    /** A setup with its signed image URL. */
    public record SetupView(RingSetup setup, String imageUrl) { }
    public record Created(SetupView setup, String replacedSetupId) { }
    public record SetupPage(List<SetupView> items, int page, int size, long total) { }
    /** R-16-09's summary of a ring's active setup. */
    public record Summary(String id, SetupKind kind, String levelLabel, Instant builtAt, LocalDate expectedUntil, String thumbnailUrl, boolean hasViewer) { }
    public record RingSummary(String ringId, Summary setup) { }
    /** S09 R-09-15: an active setup as `GET /training-slots` shows it, its level names in a language. */
    public record Active(String id, SetupKind kind, List<String> levelNames, Instant builtAt, LocalDate expectedUntil) { }
    static final String ENTITY = "RingSetup";

    private final RingSetupRepository setups;
    private final PlacementRepository placements;
    private final RingGeometryRepository rings;
    private final CourseRepository courses;
    private final GlobalCourseRepository global;
    private final CourseAccess access;
    private final CourseFiles files;
    private final LevelLabels labels;
    private final PlanningCatalogAccess catalogs;
    private final ClubConfigService configs;
    private final ClubClock clocks;
    private final AuditWriter audit;
    private final EventPublisher events;
    public RingSetupService(RingSetupRepository setups, PlacementRepository placements, RingGeometryRepository rings, CourseRepository courses,
            GlobalCourseRepository global, CourseAccess access, CourseFiles files, LevelLabels labels, PlanningCatalogAccess catalogs, ClubConfigService configs,
            ClubClock clocks, AuditWriter audit, EventPublisher events) {
        this.setups = setups; this.placements = placements; this.rings = rings; this.courses = courses; this.global = global; this.access = access;
        this.files = files; this.labels = labels; this.catalogs = catalogs; this.configs = configs; this.clocks = clocks; this.audit = audit; this.events = events;
    }

    // ---- R-16-07: registering ----

    @Transactional
    public Created register(SetupInput in) {
        if (!CourseAccess.role("ADMIN") && !access.enabled("courses.allowInstructorPublish")) { throw new ApiException(FORBIDDEN); }
        String clubId = TenantContext.require();
        // The references first (`404`, `COURSE_NOT_VISIBLE`), then the request's own checks (`400`), then R-16-07's (`422`).
        var ring = ring(in.ringId());
        var course = in.courseId() == null ? null : access.course(in.courseId(), false);
        var placement = in.placementId() == null ? null : placements.findById(in.placementId()).orElseThrow(() -> new ApiException(NOT_FOUND));
        var levelIds = levels(in.levelIds());
        var check = RingSetupRules.check(in.kind(), RingSetupRules.sources(in.placementId(), in.courseId(), in.imageFileKey()));
        if (!check.unexpected().isEmpty()) { throw PlacementService.invalid(field(check.unexpected().getFirst()), "VALIDATION_ERROR"); }
        if (placement != null && in.courseId() != null && !in.courseId().equals(placement.courseId())) { throw PlacementService.invalid("courseId", "VALIDATION_ERROR"); }
        var now = clocks.now(clubId);
        if (!RingSetupRules.expectedUntilValid(in.expectedUntil(), now.toLocalDate())) { throw PlacementService.invalid("expectedUntil", "VALIDATION_ERROR"); }
        if (check.missing()) { throw new ApiException(SETUP_SOURCE_REQUIRED); }
        if (placement != null) {
            // «Sense geometria: placementId prohibit» first: no placement of another ring can fit a ring that has no geometry.
            if (ring.geometry() == null) { throw new ApiException(RING_WITHOUT_GEOMETRY); }
            if (!placement.ringId().equals(ring.id())) { throw new ApiException(PLACEMENT_RING_MISMATCH); }
        }
        String id = UUID.randomUUID().toString(), actor = CourseAccess.accountId();
        // R-16-16: «Foto o plànol» is the club's own `COURSE_IMAGE` upload, bound to this setup (another club's key is `404`).
        if (in.imageFileKey() != null) { files.claim(in.imageFileKey(), UploadPurpose.COURSE_IMAGE, ENTITY, id); }
        return activate(id, ring, placement, course, in, levelIds, now, actor);
    }

    /**
     * R-16-11 `finish` of a build session on {@code placement}, inside its transaction: the ring's active setup becomes this
     * placement's, of the course's discipline (`BuildSessionRules.kind`) and level. When the active setup already shows this
     * placement it is refreshed instead (built now by {@code actor}, a new `expiresAt` as a renewal; no event: it stays `ACTIVE`);
     * otherwise a new setup replaces it as `POST /ring-setups` does (`RingSetupChanged{ACTIVE}`, N-31, `RING_SETUP_REGISTERED`).
     * The caller has checked the publishing permission. Returns the setup's id.
     */
    String built(Placement placement, Course course, String actor) {
        String clubId = TenantContext.require();
        var ring = ring(placement.ringId());
        if (ring.geometry() == null) { throw new ApiException(RING_WITHOUT_GEOMETRY); }
        var now = clocks.now(clubId);
        var instant = now.toInstant();
        var current = setups.activeOn(ring.id()).orElse(null);
        if (current != null && placement.id().equals(current.placementId())) {
            var expiresAt = RingSetupRules.expiresAt(instant, null, autoExpireDays(), now.getZone());
            var rebuilt = new RingSetup(current.id(), current.clubId(), current.ringId(), current.kind(), current.placementId(), current.courseId(),
                    current.imageFileKey(), current.agilityhubLevel(), current.levelIds(), instant, actor, null, expiresAt, current.status(), null, null,
                    current.notes(), current.createdAt(), current.createdByAccountId(), instant, actor);
            if (!setups.replaceActive(rebuilt)) { throw new ApiException(STALE_VERSION); }
            return current.id();
        }
        // The course's own levels only while levels are enabled (R-16-15): the setup keeps the course's AgilityHub level anyway.
        var levelIds = access.enabled("levels.enabled") && course.levelIds() != null ? course.levelIds() : List.<String>of();
        var in = new SetupInput(ring.id(), com.agilityhub.core.courses.domain.BuildSessionRules.kind(course.discipline()), placement.id(), null, null,
                course.agilityhubLevel(), levelIds, null, null);
        return activate(UUID.randomUUID().toString(), ring, placement, null, in, levelIds, now, actor).setup().setup().id();
    }

    /** R-16-07's effects once every check passed: the new `ACTIVE` setup replaces the ring's, with its event and audit entry. */
    private Created activate(String id, RingGeometryRepository.GeometryRecord ring, Placement placement, Course course, SetupInput in,
            List<String> levelIds, java.time.ZonedDateTime now, String actor) {
        String clubId = TenantContext.require();
        var instant = now.toInstant();
        var expiresAt = RingSetupRules.expiresAt(instant, in.expectedUntil(), autoExpireDays(), now.getZone());
        var setup = new RingSetup(id, clubId, ring.id(), in.kind(), in.placementId(), in.courseId(), in.imageFileKey(), in.agilityhubLevel(), levelIds,
                instant, actor, in.expectedUntil(), expiresAt, SetupStatus.ACTIVE, null, null, in.notes(), instant, actor, instant, actor);
        String replaced = null;
        try {
            // R-16-07: the ring's pointer is written first, so two registrations of one ring meet on the ring document (a write
            // conflict) before either touches a setup; the partial unique index `active_setup` is the last guard.
            rings.pointActiveSetup(ring.id(), id);
            // E115 (write skew): the geometry a placement needs and the course the setup shows are written at the versions read,
            // so a concurrent removal of either (which `RING_GEOMETRY_IN_USE` / `COURSE_IN_USE` would refuse) conflicts instead.
            if (placement != null) { rings.reference(ring.id(), ring.geometry().version()); }
            var shown = course != null ? course : placement == null ? null : live(placement.courseId());
            if (shown != null) { access.reference(shown); }
            var previous = setups.activeOn(ring.id()).orElse(null);
            if (previous != null) {
                // The active setup is replaced (`DISMANTLED`) in the same transaction; one ended meanwhile is a conflict too.
                if (!setups.replaceActive(ended(previous, SetupStatus.DISMANTLED, instant, actor))) { throw new ApiException(STALE_VERSION); }
                replaced = previous.id();
            }
            setups.insert(setup);
        } catch (RuntimeException conflict) {
            if (!(conflict instanceof ApiException) && TransactionRetries.conflict(conflict)) { throw new ApiException(STALE_VERSION); }
            throw conflict;
        }
        publish(setup, SetupStatus.ACTIVE, replaced, actor, DomainEvent.Origin.BACKOFFICE, instant);
        var after = new LinkedHashMap<String, Object>();
        after.put("ringId", setup.ringId()); after.put("kind", setup.kind().name()); after.put("placementId", setup.placementId());
        after.put("courseId", setup.courseId()); after.put("imageFileKey", setup.imageFileKey());
        after.put("agilityhubLevel", setup.agilityhubLevel() == null ? null : setup.agilityhubLevel().name()); after.put("levelIds", setup.levelIds());
        after.put("expectedUntil", setup.expectedUntil() == null ? null : setup.expectedUntil().toString()); after.put("expiresAt", setup.expiresAt().toString());
        var details = new LinkedHashMap<String, Object>(); if (replaced != null) { details.put("replacedSetupId", replaced); }
        audit.write(new AuditCommand(AuditAction.RING_SETUP_REGISTERED, ENTITY, id, null, null, after, null), details);
        return new Created(view(setup), replaced);
    }

    // ---- reads ----

    /**
     * `GET /ring-setups/{id}`: staff read any setup of the club; a MEMBER only an `ACTIVE` one, while `courses.showSetupToMembers`
     * (else `404`, R-16-09). The projection per role is the caller's.
     */
    public SetupView get(String id) {
        access.memberVisibility();
        var setup = setup(id);
        if (!CourseAccess.staff() && setup.status() != SetupStatus.ACTIVE) { throw new ApiException(NOT_FOUND); }
        return view(setup);
    }
    /** R-16-08: the ring's setups, newest first, the ended ones included. */
    public SetupPage history(String ringId, int page, int size) {
        ring(ringId);
        return new SetupPage(setups.history(ringId, page, size).stream().map(this::view).toList(), page, size, setups.countOn(ringId));
    }
    /**
     * R-16-09 `GET /me/ring-setups`: the named rings of the club (each must exist), else its active rings in catalog order, each with
     * its active setup's summary or `null`. A MEMBER gets `null` for every ring while `courses.showSetupToMembers = false`.
     */
    public List<RingSummary> mine(List<String> ringIds) {
        List<String> ids;
        if (ringIds == null || ringIds.isEmpty()) {
            ids = catalogs.rings().stream().filter(PlanningCatalogAccess.RingView::active).map(PlanningCatalogAccess.RingView::id).toList();
        } else {
            ids = ringIds.stream().distinct().toList();
            ids.forEach(this::ring);
        }
        boolean hidden = !CourseAccess.staff() && !access.enabled("courses.showSetupToMembers");
        var active = new HashMap<String, RingSetup>();
        if (!hidden) { setups.active().forEach(setup -> active.put(setup.ringId(), setup)); }
        var codes = labels.codes();
        return ids.stream().map(ringId -> new RingSummary(ringId, active.containsKey(ringId) ? summary(active.get(ringId), codes) : null)).toList();
    }
    private Summary summary(RingSetup setup, Map<String, String> codes) {
        var course = shownCourse(setup);
        String thumbnail = setup.imageFileKey() != null ? files.url(setup.imageFileKey()) : course == null ? null : files.url(course.thumbnailFileKey());
        boolean model = course != null && course.normalizedJson() != null && !course.normalizedJson().isNull();
        return new Summary(setup.id(), setup.kind(), LevelLabels.label(codes, setup.levelIds(), setup.agilityhubLevel()), setup.builtAt(), setup.expectedUntil(),
                thumbnail, RingSetupRules.hasViewer(setup.placementId(), model));
    }
    /** The course a setup shows: its placement's, else its own; a deleted or missing one is none. */
    private Course shownCourse(RingSetup setup) {
        return live(setup.placementId() != null ? placements.findById(setup.placementId()).map(Placement::courseId).orElse(setup.courseId()) : setup.courseId());
    }
    private Course live(String courseId) {
        if (courseId == null) { return null; }
        return courses.findById(courseId).or(() -> global.findById(courseId)).filter(course -> course.deletedAt() == null).orElse(null);
    }

    // ---- R-16-08: ending and renewing ----

    @Transactional
    public SetupView dismantle(String id) {
        var setup = setup(id);
        if (setup.status() != SetupStatus.ACTIVE) { throw new ApiException(INVALID_STATE); }
        var now = clocks.now(TenantContext.require()).toInstant(); String actor = CourseAccess.accountId();
        // E117: a race with another write of the setup or of its ring's pointer (P5 f, a replacement, a deactivation) is `409
        // STALE_VERSION`, never a 500. The jobs and consumers that call `end` keep the raw conflict for their own retries.
        var ended = WriteConflicts.guard(() -> end(setup, SetupStatus.DISMANTLED, now, actor, DomainEvent.Origin.BACKOFFICE));
        return view(ended.orElseThrow(() -> new ApiException(INVALID_STATE)));
    }
    /** R-16-08: an `ACTIVE` setup gets a new `expiresAt`, counted from today (no event: it stays `ACTIVE`). */
    @Transactional
    public SetupView renew(String id, LocalDate expectedUntil) {
        var setup = setup(id);
        if (setup.status() != SetupStatus.ACTIVE) { throw new ApiException(INVALID_STATE); }
        var now = clocks.now(TenantContext.require());
        if (!RingSetupRules.expectedUntilValid(expectedUntil, now.toLocalDate())) { throw PlacementService.invalid("expectedUntil", "VALIDATION_ERROR"); }
        var expiresAt = RingSetupRules.expiresAt(now.toInstant(), expectedUntil, autoExpireDays(), now.getZone());
        var renewed = new RingSetup(setup.id(), setup.clubId(), setup.ringId(), setup.kind(), setup.placementId(), setup.courseId(), setup.imageFileKey(),
                setup.agilityhubLevel(), setup.levelIds(), setup.builtAt(), setup.builtByAccountId(), expectedUntil, expiresAt, setup.status(), null, null,
                setup.notes(), setup.createdAt(), setup.createdByAccountId(), now.toInstant(), CourseAccess.accountId());
        if (!WriteConflicts.guard(() -> setups.replaceActive(renewed))) { throw new ApiException(INVALID_STATE); }
        return view(renewed);
    }
    /** S15 R-15-15 step f: the club's active setups whose `expiresAt` is not after {@code now}. */
    public List<RingSetup> lapsed(Instant now) { return setups.lapsed(now); }
    /** S15 R-15-15 step f, one item: still `ACTIVE` and lapsed at {@code now} → `EXPIRED` (false when no longer in scope). */
    public boolean expire(String id, Instant now) {
        var setup = setups.findById(id).filter(s -> s.status() == SetupStatus.ACTIVE && !s.expiresAt().isAfter(now)).orElse(null);
        return setup != null && end(setup, SetupStatus.EXPIRED, now, null, DomainEvent.Origin.SYSTEM).isPresent();
    }
    /** S16 §7: `RingChanged{deactivated}` → the ring's active setup is dismantled by the system. */
    public boolean ringDeactivated(String ringId) {
        var setup = setups.activeOn(ringId).orElse(null);
        return setup != null && end(setup, SetupStatus.DISMANTLED, clocks.now(TenantContext.require()).toInstant(), null, DomainEvent.Origin.SYSTEM).isPresent();
    }
    private Optional<RingSetup> end(RingSetup setup, SetupStatus status, Instant now, String actor, DomainEvent.Origin origin) {
        var ended = ended(setup, status, now, actor);
        if (!setups.replaceActive(ended)) { return Optional.empty(); }
        rings.releaseActiveSetup(setup.ringId(), setup.id());
        publish(setup, status, null, actor, origin, now);
        return Optional.of(ended);
    }
    private static RingSetup ended(RingSetup s, SetupStatus status, Instant now, String actor) {
        boolean dismantled = status == SetupStatus.DISMANTLED;
        return new RingSetup(s.id(), s.clubId(), s.ringId(), s.kind(), s.placementId(), s.courseId(), s.imageFileKey(), s.agilityhubLevel(), s.levelIds(),
                s.builtAt(), s.builtByAccountId(), s.expectedUntil(), s.expiresAt(), status, dismantled ? now : null, dismantled ? actor : null, s.notes(),
                s.createdAt(), s.createdByAccountId(), now, actor);
    }

    // ---- other contexts' reads ----

    /** S09 R-09-15 (`RingSetupPort`): the club's setup {@code id} while it is `ACTIVE`. */
    public Optional<Active> active(String id, Locale locale) {
        return setups.findById(id).filter(setup -> setup.status() == SetupStatus.ACTIVE)
                .map(setup -> new Active(setup.id(), setup.kind(), labels.names(setup.levelIds(), locale), setup.builtAt(), setup.expectedUntil()));
    }
    /** S06's `placementId` (R-16-13, T-16-20): the ring of a placement of the club, or empty when the club has no such placement. */
    public Optional<String> placementRing(String placementId) { return placements.findById(placementId).map(Placement::ringId); }

    // ---- helpers ----

    private SetupView view(RingSetup setup) { return new SetupView(setup, files.url(setup.imageFileKey())); }
    private RingSetup setup(String id) { return setups.findById(id).orElseThrow(() -> new ApiException(NOT_FOUND)); }
    private RingGeometryRepository.GeometryRecord ring(String id) { return rings.findById(id).orElseThrow(() -> new ApiException(NOT_FOUND)); }
    private int autoExpireDays() { return configs.get(TenantContext.require()).get("courses.setupAutoExpireDays", Integer.class); }
    /**
     * R-16-15: none while `levels.enabled = false` (`422 LEVELS_DISABLED`, CATALEG_ERRORS §1, as S03 and S06 throw it; only
     * `agilityhubLevel` is left), else the club's levels only (`404`).
     */
    private List<String> levels(List<String> ids) {
        if (ids == null || ids.isEmpty()) { return List.of(); }
        if (!access.enabled("levels.enabled")) { throw new ApiException(LEVELS_DISABLED); }
        if (!catalogs.levelRefs().keySet().containsAll(ids)) { throw new ApiException(NOT_FOUND); }
        return ids.stream().distinct().toList();
    }
    private static String field(RingSetupRules.Source source) {
        return switch (source) { case PLACEMENT -> "placementId"; case COURSE -> "courseId"; case IMAGE -> "imageFileKey"; };
    }
    private void publish(RingSetup setup, SetupStatus status, String replaced, String actor, DomainEvent.Origin origin, Instant at) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("ringId", setup.ringId()); payload.put("setupId", setup.id()); payload.put("status", status.name());
        payload.put("replacedSetupId", replaced); payload.put("kind", setup.kind().name()); payload.put("byAccountId", actor);
        events.publish(new CourseEvent(CourseEvent.Kind.RingSetupChanged, setup.clubId(), setup.id(), at, payload, actor, null, origin));
    }
}
