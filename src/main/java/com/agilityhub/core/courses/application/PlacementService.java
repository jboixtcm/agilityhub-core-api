package com.agilityhub.core.courses.application;

import com.agilityhub.core.courses.application.ports.CourseReferencePort;
import com.agilityhub.core.courses.domain.CourseEvent;
import com.agilityhub.core.courses.domain.CourseParts.*;
import com.agilityhub.core.courses.domain.CourseTypes.*;
import com.agilityhub.core.courses.domain.PlacementRules;
import com.agilityhub.core.courses.persistence.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S16 R-16-05 (placement and warnings), R-16-06 (a `PUT` clears `stale`) and R-16-13 (a placement of an activity). course-core
 * resolves the obstacles and the warnings on the client; the core stores them as they come and applies {@link PlacementRules}.
 * A placement names a course the caller can read at its current `version` (the model the client placed) and a ring of the club
 * with a geometry (`422 RING_WITHOUT_GEOMETRY`); `activityId` needs `ACTIVITIES`. A `PUT` keeps the course and the ring (a class
 * or a setup of the ring plans it: `422 PLACEMENT_RING_MISMATCH` for another ring). Each write publishes its §7 event, and a
 * forced `critical` warning writes `COURSE_FORCED_PLACEMENT`, in the same transaction. Each write also references the ring's
 * geometry and the course at the versions it read (E115): a concurrent change or removal of either is `409 STALE_VERSION` for one
 * of the two writes, never a placement left on a geometry or a course that is gone.
 */
@Service
public class PlacementService {
    public record PlacementInput(String courseId, long courseVersion, String ringId, PlacementTransform transform, PlacementMode placementMode,
            Double warningThresholdM, List<ResolvedObstacle> resolvedObstacles, List<PlacementWarning> warnings, String activityId, String name,
            boolean force, PlacementStatus status, PlacementUse mode, Instant scheduledAt, List<String> grades, List<DogSize> sizes, String displayName) { }
    /** A placement with S06's classes that plan it (§3 `classSessionIds[]`, read only). */
    public record PlacementView(Placement placement, List<String> classSessionIds) { }
    static final String ENTITY = "Placement";

    private final PlacementRepository placements;
    private final RingGeometryRepository rings;
    private final CourseAccess access;
    private final CourseReferencePort references;
    private final ClubConfigService configs;
    private final AuditWriter audit;
    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final CourseFiles files;
    public PlacementService(PlacementRepository placements, RingGeometryRepository rings, CourseAccess access, CourseReferencePort references,
            ClubConfigService configs, AuditWriter audit, EventPublisher events, ObjectMapper mapper, Clock clock, CourseFiles files) {
        this.placements = placements; this.rings = rings; this.access = access; this.references = references; this.configs = configs;
        this.audit = audit; this.events = events; this.mapper = mapper; this.clock = clock; this.files = files;
    }

    /** `GET /placements?ringId=&courseId=&activityId=`: each reference must be the club's (a course the caller can read). */
    public List<PlacementView> list(String ringId, String courseId, String activityId) {
        if (ringId != null) { ring(ringId); }
        if (courseId != null) { access.course(courseId, false); }
        if (activityId != null && !references.activityExists(activityId)) { throw new ApiException(NOT_FOUND); }
        return placements.find(ringId, courseId, activityId).stream().map(this::view).toList();
    }
    public PlacementView get(String id) { return view(placement(id)); }

    @Transactional
    public PlacementView create(PlacementInput in) {
        var checked = check(in, () -> { });
        var now = clock.instant(); String actor = CourseAccess.accountId();
        var placement = build(UUID.randomUUID().toString(), in, checked, now, actor, now, 0, null);
        WriteConflicts.guard(() -> { reference(checked); placements.insert(placement); });
        var payload = new LinkedHashMap<String, Object>();
        payload.put("placementId", placement.id()); payload.put("ringId", placement.ringId()); payload.put("courseId", placement.courseId());
        payload.put("warnings", wire(placement.warningsJson()));
        publish(CourseEvent.Kind.PlacementCreated, placement, payload);
        forced(checked, placement);
        return view(placement);
    }

    @Transactional
    public PlacementView update(String id, PlacementInput in, long version) {
        var before = placement(id);
        access.course(in.courseId(), false); ring(in.ringId());
        if (!before.ringId().equals(in.ringId())) { throw new ApiException(PLACEMENT_RING_MISMATCH); }
        if (!before.courseId().equals(in.courseId())) { throw invalid("courseId", "VALIDATION_ERROR"); }
        // E117 point 5: a stale `PUT` is `409 STALE_VERSION` before R-16-05's policy, even when it carries a critical warning.
        var checked = check(in, () -> { if (before.version() != version) { throw new ApiException(STALE_VERSION); } });
        // R-16-06: the client recalculated the placement against the current geometry, so it is no longer stale.
        // INC-62 (E11-T02, decision E121): a placement moved (another transform) loses its build sheet, which no longer fits it.
        boolean moved = !java.util.Objects.equals(before.transform(), in.transform());
        var stored = build(before.id(), in, checked, before.createdAt(), before.createdByAccountId(), clock.instant(), before.version() + 1,
                moved ? null : before.buildSheetFileKey());
        WriteConflicts.guard(() -> { reference(checked); placements.update(stored, version); });
        if (moved) { files.release(before.buildSheetFileKey()); }
        var payload = new LinkedHashMap<String, Object>(); payload.put("placementId", stored.id()); payload.put("stale", false);
        publish(CourseEvent.Kind.PlacementUpdated, stored, payload);
        forced(checked, stored);
        return view(stored);
    }

    /** What a write checked: the policy's outcome over the client's warnings and the threshold it stores. */
    private record Checked(PlacementRules.Outcome outcome, List<PlacementWarning> critical, double threshold, Course course,
            RingGeometryRepository.GeometryRecord ring) { }
    /** E115: the geometry and the course this write relied on, written at the versions it read (write skew). */
    private void reference(Checked checked) {
        rings.reference(checked.ring().id(), checked.ring().geometry().version());
        access.reference(checked.course());
    }
    /**
     * The references first (`404`, `COURSE_NOT_VISIBLE`), then the request's own checks (`400`, `409`; {@code versionCheck} is the
     * placement's own on a `PUT`), then R-16-05's (`422`).
     */
    private Checked check(PlacementInput in, Runnable versionCheck) {
        if (in.force() && !CourseAccess.role("ADMIN")) { throw new ApiException(FORBIDDEN); }
        var course = access.course(in.courseId(), false);
        var ring = ring(in.ringId());
        var config = configs.get(TenantContext.require());
        if (in.activityId() != null) {
            if (!config.modules().contains(Module.ACTIVITIES)) { throw invalid("activityId", "MODULE_DISABLED"); }
            if (!references.activityExists(in.activityId())) { throw new ApiException(NOT_FOUND); }
        }
        if (course.normalizedJson() == null || course.normalizedJson().isNull()) { throw invalid("courseId", "COURSE_MODEL_INVALID"); }
        if (course.version() != in.courseVersion()) { throw new ApiException(STALE_VERSION); }
        if (ring.geometry() == null) { throw new ApiException(RING_WITHOUT_GEOMETRY); }
        versionCheck.run();
        var outcome = PlacementRules.outcome(in.warnings(), in.force());
        var critical = PlacementRules.critical(in.warnings());
        if (outcome == PlacementRules.Outcome.BLOCKED) { throw new ApiException(PLACEMENT_BLOCKED, Map.of("warnings", wire(critical))); }
        double threshold = in.warningThresholdM() != null ? in.warningThresholdM()
                : config.get("courses.defaultWarningThresholdM", Number.class).doubleValue();
        return new Checked(outcome, critical, threshold, course, ring);
    }
    /** The stored placement (never `stale`: it was just resolved); `classSessionIds` is read from S06, never stored. */
    private Placement build(String id, PlacementInput in, Checked checked, Instant createdAt, String createdBy, Instant now, long version, String buildSheetFileKey) {
        return new Placement(id, TenantContext.require(), in.courseId(), in.courseVersion(), in.ringId(), in.transform(), in.placementMode(), checked.threshold(),
                List.copyOf(in.resolvedObstacles()), List.copyOf(in.warnings()), false, in.status() == null ? PlacementStatus.DRAFT : in.status(),
                in.mode() == null ? PlacementUse.TRAINING : in.mode(), in.scheduledAt(), in.grades() == null ? List.of() : List.copyOf(in.grades()),
                in.sizes() == null ? List.of() : List.copyOf(in.sizes()), in.displayName(), in.name(), in.activityId(), List.of(), buildSheetFileKey, createdBy,
                createdAt, now, version, CourseAccess.accountId());
    }
    /** R-16-05: a stored `critical` warning is audited with the ids of the warnings the ADMIN overrode. */
    private void forced(Checked checked, Placement placement) {
        if (checked.outcome() != PlacementRules.Outcome.FORCED) { return; }
        var after = new LinkedHashMap<String, Object>();
        after.put("courseId", placement.courseId()); after.put("ringId", placement.ringId()); after.put("version", placement.version());
        after.put("warnings", wire(checked.critical()));
        audit.write(new AuditCommand(AuditAction.COURSE_FORCED_PLACEMENT, ENTITY, placement.id(), null, null, after, null),
                Map.of("warningIds", checked.critical().stream().map(PlacementWarning::id).toList()));
    }

    private PlacementView view(Placement placement) { return new PlacementView(placement, references.classSessionIds(placement.id())); }
    private Placement placement(String id) { return placements.findById(id).orElseThrow(() -> new ApiException(NOT_FOUND)); }
    private RingGeometryRepository.GeometryRecord ring(String id) { return rings.findById(id).orElseThrow(() -> new ApiException(NOT_FOUND)); }
    /** course-core's own spelling of the warnings (`critical`, `door-clearance` …), as the client sent them. */
    private List<Map<String, Object>> wire(List<PlacementWarning> warnings) { return mapper.convertValue(warnings, new TypeReference<>() { }); }
    private void publish(CourseEvent.Kind kind, Placement placement, Map<String, Object> payload) {
        events.publish(new CourseEvent(kind, placement.clubId(), placement.id(), clock.instant(), payload, CourseAccess.accountId(), null, DomainEvent.Origin.BACKOFFICE));
    }
    static ApiException invalid(String field, String code) {
        return new ApiException(VALIDATION_ERROR, Map.of("fieldErrors", List.of(Map.of("field", field, "code", code))));
    }
}
