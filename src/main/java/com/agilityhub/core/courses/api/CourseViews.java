package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.application.CourseLibrary;
import com.agilityhub.core.courses.application.RingGeometryService;
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
                r.thumbnailFileKey(), r.designerName(), r.designedOn(), r.eventName(), r.agilityhubLevel(), r.levelIds(), r.tags(), r.notes(), r.visibility(), r.sizeCategory());
    }
    static CourseLibrary.CourseInput input(CoursePatchRequest r) {
        return new CourseLibrary.CourseInput(r.name(), r.discipline(), r.source(), r.normalizedJson(), r.schemaVersion(), r.imageFileKey(), r.sourceFileKey(),
                r.thumbnailFileKey(), r.designerName(), r.designedOn(), r.eventName(), r.agilityhubLevel(), r.levelIds(), r.tags(), r.notes(), r.visibility(), r.sizeCategory());
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
}
