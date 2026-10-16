package com.agilityhub.core.courses.application;

import com.agilityhub.core.courses.domain.CourseParts.*;
import com.agilityhub.core.courses.domain.CourseTypes.*;
import com.agilityhub.core.courses.persistence.BuildSession;
import com.agilityhub.core.courses.persistence.Course;
import com.agilityhub.core.courses.persistence.Placement;
import com.agilityhub.core.courses.persistence.Venue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * S16 §14.1/§14.2 and E77: `GET /build-sessions/{id}/export` = `BuildSessionExportV1`, the Unity app's contract (D-037), kept
 * byte for byte (A9). The core's `UPPER_SNAKE_CASE` values go back to the planner's spellings (`IN_PROGRESS` → `in_progress`,
 * `PRESERVE_METERS` → `preserveMeters`), course-core's JSON (`normalized_json`, `warnings_json`) is copied as stored, and the
 * planner columns the core does not keep are derived (E9-T01 Fixed conventions): `judge_id` and `event_id` are null (judges and
 * events are R2), `venue_id` is the club's venue, `is_training` comes from the placement's `mode`, and the course theme is the
 * standard palette. Pure: {@link BuildSessionService} reads the documents and self-checks the result against the schema.
 */
public final class BuildSessionExport {
    /** The producer the export names (the planner wrote `agilityhub-web-planner@…`). */
    public static final String PRODUCER = "agilityhub-core-api";
    /** The planner's only asset profile (`assetProfile generic-fci`, S16 §14.1). */
    public static final String ASSET_PROFILE = "generic-fci";
    public static final String PALETTE = "standard";
    /** The Unity app calibrates with six markers, A–F (`markers` has `minItems = maxItems = 6`). */
    public static final int MARKERS = 6;
    private BuildSessionExport() { }

    /** The ring as the export needs it: its S05 name and its S16 geometry. */
    public record Ring(String id, String name, RingGeometry geometry) { }

    public static ObjectNode build(ObjectMapper mapper, BuildSession session, Placement placement, Course course, Venue venue, Ring ring, Instant now) {
        var root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("exportedAt", now.toString());
        root.put("producer", PRODUCER);
        root.set("buildSession", session(mapper, session));
        root.set("placement", placement(mapper, placement, course, venue));
        root.set("course", course(mapper, course));
        root.set("venue", venue(mapper, venue));
        root.set("ring", ring(mapper, ring, venue));
        var profile = root.putObject("assetProfile");
        profile.put("profileId", ASSET_PROFILE);
        profile.putArray("fallbackChain").add(ASSET_PROFILE);
        return root;
    }

    private static ObjectNode session(ObjectMapper mapper, BuildSession s) {
        var node = mapper.createObjectNode();
        node.put("id", s.id());
        node.put("placement_id", s.placementId());
        node.putNull("judge_id");
        node.put("status", planner(s.status()));
        node.put("selected_strategy", planner(s.selectedStrategy()));
        node.put("last_known_marker_id", s.lastKnownMarkerId());
        node.put("started_at", instant(s.startedAt()));
        node.put("completed_at", instant(s.finishedAt()));
        node.put("local_cache_version", s.localCacheVersion());
        var statuses = node.putArray("obstacle_statuses");
        for (var obstacle : s.obstacles()) {
            statuses.addObject().put("course_obstacle_id", obstacle.obstacleId()).put("status", planner(obstacle.status()))
                    .put("updated_at", instant(obstacle.at()));
        }
        return node;
    }

    private static ObjectNode placement(ObjectMapper mapper, Placement p, Course course, Venue venue) {
        var node = mapper.createObjectNode();
        node.put("id", p.id());
        node.put("course_id", p.courseId());
        node.put("venue_id", venue.id());
        node.put("ring_id", p.ringId());
        node.put("name", p.name());
        node.put("display_name", p.displayName());
        node.put("placement_mode", camel(p.placementMode()));
        node.put("offset_x_m", p.transform().offsetXM());
        node.put("offset_y_m", p.transform().offsetYM());
        node.put("rotation_deg", p.transform().rotationDeg());
        node.put("flip_x_bool", p.transform().flipX());
        node.put("flip_y_bool", p.transform().flipY());
        node.put("warning_threshold_m", p.warningThresholdM());
        node.set("warnings_json", mapper.valueToTree(p.warningsJson() == null ? List.of() : p.warningsJson()));
        node.put("status", planner(p.status()));
        node.put("scheduled_at", instant(p.scheduledAt()));
        node.putNull("event_id");
        node.put("course_type", courseType(course.discipline()));
        var grades = node.putArray("grades"); if (p.grades() != null) { p.grades().forEach(grades::add); }
        var sizes = node.putArray("sizes"); if (p.sizes() != null) { p.sizes().forEach(size -> sizes.add(size.name())); }
        node.put("is_training", p.mode() == PlacementUse.TRAINING);
        return node;
    }

    private static ObjectNode course(ObjectMapper mapper, Course c) {
        JsonNode data = c.normalizedJson() == null ? mapper.nullNode() : c.normalizedJson();
        var node = mapper.createObjectNode();
        node.put("id", c.id());
        node.put("title", c.name() == null ? data.path("title").asText() : c.name().resolve(c.name().defaultLocale()).value());
        node.set("title_raw", data.path("titleRaw").isMissingNode() ? mapper.nullNode() : data.path("titleRaw"));
        node.set("source", data.path("source").isMissingNode() ? mapper.nullNode() : data.path("source"));
        node.putNull("source_version");
        node.put("units", data.path("units").asText().toLowerCase(Locale.ROOT));
        node.set("design_length_m", data.path("designLengthMeters"));
        node.set("design_width_m", data.path("designWidthMeters"));
        node.set("canvas_width", data.path("canvasWidth"));
        node.set("canvas_height", data.path("canvasHeight"));
        node.set("origin", data.path("origin"));
        node.set("normalized_json", data);
        var theme = node.putObject("theme");
        theme.putNull("id");
        theme.put("palette_key", PALETTE);
        theme.putObject("theme_json");
        return node;
    }

    private static ObjectNode venue(ObjectMapper mapper, Venue v) {
        var node = mapper.createObjectNode();
        node.put("id", v.id());
        node.put("slug", v.slug());
        node.put("name", v.name());
        node.put("country", v.country());
        node.put("city", v.city());
        node.put("surface", lower(v.surface()));
        node.put("indoor_outdoor", lower(v.indoorOutdoor()));
        return node;
    }

    private static ObjectNode ring(ObjectMapper mapper, Ring ring, Venue venue) {
        var g = ring.geometry();
        var node = mapper.createObjectNode();
        node.put("id", ring.id());
        node.put("venue_id", venue.id());
        node.put("name", ring.name());
        node.put("length_m", g.lengthM());
        node.put("width_m", g.widthM());
        node.put("border_clearance_m", g.borderClearanceM());
        node.put("surface", lower(g.surface()));
        var doors = node.putArray("doors");
        for (var door : g.doors()) {
            var d = doors.addObject();
            d.put("id", door.id()).put("label", door.label());
            points(d.putArray("polygon_points_m"), door.polygonPointsM());
            d.put("clearance_m", door.clearanceM()).put("is_active", door.isActive()).put("flow", lower(door.flow())).put("side", lower(door.side()))
                    .put("start_m", door.startM()).put("end_m", door.endM());
        }
        var zones = node.putArray("no_go_zones");
        for (var zone : g.noGoZones()) {
            var z = zones.addObject();
            z.put("id", zone.id()).put("label", zone.label());
            points(z.putArray("polygon_points_m"), zone.polygonPointsM());
            z.put("warning_margin_m", zone.warningMarginM());
        }
        // E118: the six active markers the app calibrates with; an inactive one is not there to be seen.
        var markers = node.putArray("markers");
        for (var marker : g.markers().stream().filter(RingMarker::isActive).toList()) {
            markers.addObject().put("id", marker.id()).put("label", marker.label()).put("marker_uid", marker.markerUid()).put("role", marker.role())
                    .put("x_m", marker.xM()).put("y_m", marker.yM()).put("z_m", marker.zM()).put("rotation_y_deg", marker.rotationYDeg())
                    .put("physical_width_m", marker.physicalWidthM()).put("physical_height_m", marker.physicalHeightM())
                    .put("is_fixed", marker.isFixed()).put("is_active", marker.isActive()).put("april_tag_id", marker.aprilTagId());
        }
        return node;
    }

    private static void points(ArrayNode target, List<PointM> points) {
        if (points != null) { points.forEach(point -> target.addObject().put("x", point.x()).put("y", point.y())); }
    }
    /** The planner's `snake_case` spelling of a core value (`READY_TO_BUILD` → `ready_to_build`); null stays null. */
    static String planner(Enum<?> value) { return lower(value); }
    static String lower(Enum<?> value) { return value == null ? null : value.name().toLowerCase(Locale.ROOT); }
    /** `PRESERVE_METERS` → `preserveMeters`, the planner's `placement_mode` spelling. */
    static String camel(Enum<?> value) {
        var parts = value.name().toLowerCase(Locale.ROOT).split("_");
        var result = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) { result.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1)); }
        return result.toString();
    }
    /** The planner's `course_type` (`Agility` · `Jumping`), null for any other discipline. */
    static String courseType(Discipline discipline) {
        return switch (discipline) { case AGILITY -> "Agility"; case JUMPING -> "Jumping"; case OTHER -> null; };
    }
    private static String instant(Instant value) { return value == null ? null : value.toString(); }
}
