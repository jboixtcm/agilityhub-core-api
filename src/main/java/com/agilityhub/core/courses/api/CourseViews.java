package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.application.CourseLibrary;
import com.agilityhub.core.courses.application.PlacementService;
import com.agilityhub.core.courses.application.RingGeometryService;
import com.agilityhub.core.courses.application.RingSetupService;
import com.agilityhub.core.courses.persistence.ObstacleInventory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static com.agilityhub.core.courses.api.CourseContracts.*;
import static com.agilityhub.core.courses.api.CourseRequests.*;

/** Explicit S16 projections of the application's results, and request bodies as application inputs. */
final class CourseViews {
    private CourseViews() { }

    static CourseLibrary.CourseInput input(CourseRequest r) {
        return new CourseLibrary.CourseInput(r.name(), r.discipline(), r.source(), r.normalizedJson(), r.schemaVersion(), r.imageFileKey(), r.sourceFileKey(),
                r.thumbnailFileKey(), r.designerName(), r.designedOn(), r.eventName(), r.agilityhubLevel(), r.levelIds(), r.tags(), r.notes(), r.visibility(), r.sizeCategory(),
                r.pathLengthM());
    }
    static CourseLibrary.CourseInput input(CoursePatchRequest r) {
        return new CourseLibrary.CourseInput(r.name(), r.discipline(), r.source(), r.normalizedJson(), r.schemaVersion(), r.imageFileKey(), r.sourceFileKey(),
                r.thumbnailFileKey(), r.designerName(), r.designedOn(), r.eventName(), r.agilityhubLevel(), r.levelIds(), r.tags(), r.notes(), r.visibility(), r.sizeCategory(),
                r.pathLengthM());
    }
    static Course course(CourseLibrary.CourseView view) {
        var c = view.course();
        return new Course(c.id(), c.ownerType(), c.clubId(), c.ownerAccountId(), c.visibility(), view.name(), view.nameI18n(), c.discipline(), c.designerName(),
                c.designedOn(), c.eventName(), c.agilityhubLevel(), c.levelIds(), c.tags(), c.notes(), c.source(), c.sourceCourseId(), c.sourceFileKey(),
                c.imageFileKey(), view.imageUrl(), c.thumbnailFileKey(), view.thumbnailUrl(), c.normalizedJson(), c.schemaVersion(), c.designLengthM(),
                c.designWidthM(), c.canvasWidth(), c.canvasHeight(), c.origin(), c.courseGrade(), c.courseType(), c.sizeCategory(), c.stats(), c.version(),
                c.createdAt(), c.createdByAccountId(), c.updatedAt());
    }
    static CourseListItem item(CourseLibrary.CourseView view) {
        var c = view.course();
        return new CourseListItem(c.id(), view.name(), c.discipline(), view.level(), c.designerName(), c.source(), view.thumbnailUrl(), c.stats(), c.ownerType(),
                view.activeOnRings());
    }
    /**
     * CONVENCIONS_API §4: with `fields`, an item keeps its `id` and the requested keys only. The sparse page is written as a map (its
     * typed record would serialize every key); the operation still publishes {@link CoursePage}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static org.springframework.http.ResponseEntity<CoursePage> page(ObjectMapper mapper, CourseLibrary.CoursePage page) {
        int size = page.query().size();
        var typed = new CoursePage(page.items().stream().map(CourseViews::item).toList(), page.query().page(), size, page.total(),
                (int) ((page.total() + size - 1) / size), page.query().filters());
        var fields = page.query().fields();
        if (fields.isEmpty()) { return org.springframework.http.ResponseEntity.ok(typed); }
        Map<String, Object> sparse = mapper.convertValue(typed, new TypeReference<LinkedHashMap<String, Object>>() { });
        sparse.put("items", ((List<Map<String, Object>>) sparse.get("items")).stream().map(row -> {
            var kept = new LinkedHashMap<>(row); kept.keySet().removeIf(key -> !key.equals("id") && !fields.contains(key)); return kept;
        }).toList());
        return (org.springframework.http.ResponseEntity) org.springframework.http.ResponseEntity.ok(sparse);
    }
    static RingGeometryService.GeometryInput geometry(RingGeometryRequest r) {
        return new RingGeometryService.GeometryInput(r.lengthM(), r.widthM(), r.borderClearanceM(), r.orientationDeg(), r.surface(), r.notes(),
                r.doors(), r.noGoZones(), r.markers());
    }
    static RingGeometryService.CalibrationInput calibration(CalibrationRequest r) {
        return new RingGeometryService.CalibrationInput(r.outcome(), r.averageErrorCm(), r.maxErrorCm(), r.markerCount(), r.deviceModel(), r.osVersion(),
                r.appVersion(), r.arProvider(), r.notes(), r.rawMetadata());
    }
    static CalibrationLog calibration(com.agilityhub.core.courses.persistence.CalibrationLog c) {
        return new CalibrationLog(c.id(), c.ringId(), c.outcome(), c.averageErrorCm(), c.maxErrorCm(), c.markerCount(), c.deviceModel(), c.osVersion(),
                c.appVersion(), c.arProvider(), c.notes(), c.rawMetadata(), c.createdByAccountId(), c.createdAt());
    }
    static CourseContracts.ObstacleInventory inventory(ObstacleInventory i) {
        return new CourseContracts.ObstacleInventory(i.scope(), i.ringId(), i.items(), i.updatedAt());
    }

    // ---- E9-T03: placements and ring setups ----

    static PlacementService.PlacementInput placement(PlacementRequest r) {
        return new PlacementService.PlacementInput(r.courseId(), r.courseVersion(), r.ringId(), r.transform(), r.placementMode(), r.warningThresholdM(),
                r.resolvedObstacles(), r.warnings(), r.activityId(), r.name(), Boolean.TRUE.equals(r.force()), r.status(), r.mode(), r.scheduledAt(), r.grades(),
                r.sizes(), r.displayName());
    }
    static PlacementService.PlacementInput placement(PlacementUpdateRequest r) {
        return new PlacementService.PlacementInput(r.courseId(), r.courseVersion(), r.ringId(), r.transform(), r.placementMode(), r.warningThresholdM(),
                r.resolvedObstacles(), r.warnings(), r.activityId(), r.name(), Boolean.TRUE.equals(r.force()), r.status(), r.mode(), r.scheduledAt(), r.grades(),
                r.sizes(), r.displayName());
    }
    static Placement placement(PlacementService.PlacementView view) {
        var p = view.placement();
        return new Placement(p.id(), p.courseId(), p.courseVersion(), p.ringId(), p.transform(), p.placementMode(), p.warningThresholdM(), p.resolvedObstacles(),
                p.warningsJson(), p.stale(), p.status(), p.mode(), p.scheduledAt(), p.grades(), p.sizes(), p.displayName(), p.name(), p.activityId(),
                view.classSessionIds(), p.buildSheetFileKey(), p.createdByAccountId(), p.createdAt(), p.updatedAt(), p.version());
    }
    static RingSetupService.SetupInput setup(RingSetupRequest r) {
        return new RingSetupService.SetupInput(r.ringId(), r.kind(), r.placementId(), r.courseId(), r.imageFileKey(), r.agilityhubLevel(), r.levelIds(),
                r.expectedUntil(), r.notes());
    }
    static RingSetup setup(RingSetupService.SetupView view) {
        var s = view.setup();
        return new RingSetup(s.id(), s.ringId(), s.kind(), s.placementId(), s.courseId(), s.imageFileKey(), view.imageUrl(), s.agilityhubLevel(), s.levelIds(),
                s.builtAt(), s.builtByAccountId(), s.expectedUntil(), s.expiresAt(), s.status(), s.dismantledAt(), s.dismantledByAccountId(), s.notes(), s.createdAt());
    }
    /** R-16-09: a member never sees who built or dismantled the setup, nor its notes. */
    static MemberRingSetup memberSetup(RingSetupService.SetupView view) {
        var s = view.setup();
        return new MemberRingSetup(s.id(), s.ringId(), s.kind(), s.placementId(), s.courseId(), s.imageFileKey(), view.imageUrl(), s.agilityhubLevel(), s.levelIds(),
                s.builtAt(), s.expectedUntil(), s.expiresAt(), s.status(), s.dismantledAt(), s.createdAt());
    }
    static RingSetupPage history(RingSetupService.SetupPage page) {
        return new RingSetupPage(page.items().stream().map(CourseViews::setup).toList(), page.page(), page.size(), page.total(),
                (int) ((page.total() + page.size() - 1) / page.size()));
    }
    static MeRingSetup mine(RingSetupService.RingSummary ring) {
        var s = ring.setup();
        return new MeRingSetup(ring.ringId(), s == null ? null
                : new MeRingSetupSummary(s.id(), s.kind(), s.levelLabel(), s.builtAt(), s.expectedUntil(), s.thumbnailUrl(), s.hasViewer()));
    }
    static BuildSession session(com.agilityhub.core.courses.persistence.BuildSession s) {
        return new BuildSession(s.id(), s.placementId(), s.ringId(), s.status(), s.selectedStrategy(), s.live(), s.startedByAccountId(), s.startedAt(),
                s.finishedAt(), s.setupId(), s.lastKnownMarkerId(), s.localCacheVersion(), s.progressJson(), s.joinCode(), s.joinToken(), s.joinTokenRotatedAt(),
                s.obstacles() == null ? List.of() : s.obstacles(), s.participants() == null ? List.of() : s.participants(), s.buildSheetFileKey(), s.version());
    }
}
