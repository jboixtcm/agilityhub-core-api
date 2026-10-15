package com.agilityhub.core.courses.domain;

import com.agilityhub.core.courses.domain.CourseParts.*;
import com.agilityhub.core.courses.domain.CourseTypes.DoorSide;
import java.util.*;
import java.util.function.Function;

/**
 * R-16-04: the checks the server makes on a ring geometry before it stores it (`422 GEOMETRY_INVALID {violations[]}`). They are
 * validations, not geometry calculations (R-16-01): sides in [10, 60] m, doors on the perimeter, simple no-go polygons inside the
 * ring, and at least three active, non-collinear markers for the AR. The frame is course-core's: origin at the bottom-left corner,
 * x along `lengthM`, y along `widthM`, so the NORTH and SOUTH sides measure `lengthM` and the EAST and WEST sides `widthM`.
 */
public final class RingGeometryRules {
    public static final double MIN_SIDE_M = 10;
    public static final double MAX_SIDE_M = 60;
    public static final int MIN_MARKERS = 3;
    static final double EPSILON = 1e-9;
    private RingGeometryRules() { }

    /** One failed check (an absent key, such as a marker without an AprilTag id, is never a duplicate): the request path (`doors[0].endM`) and the rule it breaks. */
    public record Violation(String path, Rule rule) { }
    public enum Rule { OUT_OF_RANGE, NEGATIVE, NOT_POSITIVE, DUPLICATE_ID, DUPLICATE_LABEL, DUPLICATE_MARKER_UID, DUPLICATE_APRIL_TAG,
        OUTSIDE_PERIMETER, OUTSIDE_RING, TOO_FEW_POINTS, NOT_SIMPLE, TOO_FEW_MARKERS, COLLINEAR_MARKERS }

    public static List<Violation> check(double lengthM, double widthM, double borderClearanceM, List<RingDoor> doors,
            List<RingNoGoZone> zones, List<RingMarker> markers) {
        var violations = new ArrayList<Violation>();
        if (!within(lengthM, MIN_SIDE_M, MAX_SIDE_M)) { violations.add(new Violation("lengthM", Rule.OUT_OF_RANGE)); }
        if (!within(widthM, MIN_SIDE_M, MAX_SIDE_M)) { violations.add(new Violation("widthM", Rule.OUT_OF_RANGE)); }
        if (borderClearanceM < 0) { violations.add(new Violation("borderClearanceM", Rule.NEGATIVE)); }
        duplicates("doors", doors, RingDoor::id, Rule.DUPLICATE_ID, violations);
        for (int i = 0; i < doors.size(); i++) { door("doors[" + i + "]", doors.get(i), lengthM, widthM, violations); }
        duplicates("noGoZones", zones, RingNoGoZone::id, Rule.DUPLICATE_ID, violations);
        for (int i = 0; i < zones.size(); i++) { zone("noGoZones[" + i + "]", zones.get(i), lengthM, widthM, violations); }
        markers(markers, violations);
        return List.copyOf(violations);
    }

    private static void door(String path, RingDoor door, double lengthM, double widthM, List<Violation> violations) {
        double side = door.side() == DoorSide.NORTH || door.side() == DoorSide.SOUTH ? lengthM : widthM;
        if (door.startM() < 0 || door.startM() >= door.endM()) { violations.add(new Violation(path + ".startM", Rule.OUTSIDE_PERIMETER)); }
        if (door.endM() > side) { violations.add(new Violation(path + ".endM", Rule.OUTSIDE_PERIMETER)); }
        if (door.clearanceM() < 0) { violations.add(new Violation(path + ".clearanceM", Rule.NEGATIVE)); }
    }

    private static void zone(String path, RingNoGoZone zone, double lengthM, double widthM, List<Violation> violations) {
        var points = zone.polygonPointsM();
        if (zone.warningMarginM() < 0) { violations.add(new Violation(path + ".warningMarginM", Rule.NEGATIVE)); }
        if (points.size() < 3) { violations.add(new Violation(path + ".polygonPointsM", Rule.TOO_FEW_POINTS)); return; }
        if (points.stream().anyMatch(p -> !within(p.x(), 0, lengthM) || !within(p.y(), 0, widthM))) {
            violations.add(new Violation(path + ".polygonPointsM", Rule.OUTSIDE_RING));
        }
        if (!simple(points)) { violations.add(new Violation(path + ".polygonPointsM", Rule.NOT_SIMPLE)); }
    }

    private static void markers(List<RingMarker> markers, List<Violation> violations) {
        duplicates("markers", markers, RingMarker::id, Rule.DUPLICATE_ID, violations);
        duplicates("markers", markers, RingMarker::label, Rule.DUPLICATE_LABEL, violations);
        duplicates("markers", markers, RingMarker::markerUid, Rule.DUPLICATE_MARKER_UID, violations);
        duplicates("markers", markers, RingMarker::aprilTagId, Rule.DUPLICATE_APRIL_TAG, violations);
        for (int i = 0; i < markers.size(); i++) {
            var marker = markers.get(i);
            if (marker.physicalWidthM() <= 0) { violations.add(new Violation("markers[" + i + "].physicalWidthM", Rule.NOT_POSITIVE)); }
            if (marker.physicalHeightM() <= 0) { violations.add(new Violation("markers[" + i + "].physicalHeightM", Rule.NOT_POSITIVE)); }
        }
        var active = markers.stream().filter(RingMarker::isActive).toList();
        if (active.size() < MIN_MARKERS) { violations.add(new Violation("markers", Rule.TOO_FEW_MARKERS)); }
        else if (!spansPlane(active)) { violations.add(new Violation("markers", Rule.COLLINEAR_MARKERS)); }
    }

    /** Three markers that are not on one line exist: some pair of vectors from the first marker has a non-zero cross product. */
    static boolean spansPlane(List<RingMarker> markers) {
        var origin = markers.getFirst();
        for (int i = 1; i < markers.size(); i++) {
            for (int j = i + 1; j < markers.size(); j++) {
                double cross = (markers.get(i).xM() - origin.xM()) * (markers.get(j).yM() - origin.yM())
                        - (markers.get(i).yM() - origin.yM()) * (markers.get(j).xM() - origin.xM());
                if (Math.abs(cross) > EPSILON) { return true; }
            }
        }
        return false;
    }

    /** A simple polygon: a non-zero area and no two non-adjacent edges that touch or cross. */
    static boolean simple(List<PointM> points) {
        int n = points.size();
        double area = 0;
        for (int i = 0; i < n; i++) {
            var a = points.get(i); var b = points.get((i + 1) % n);
            area += a.x() * b.y() - b.x() * a.y();
        }
        if (Math.abs(area) <= EPSILON) { return false; }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                boolean adjacent = j == i + 1 || i == 0 && j == n - 1;
                var a = points.get(i); var b = points.get((i + 1) % n); var c = points.get(j); var d = points.get((j + 1) % n);
                if (adjacent ? overlap(a, b, c, d) : intersect(a, b, c, d)) { return false; }
            }
        }
        return true;
    }

    /** Two consecutive edges share one vertex; they overlap when they fold back on each other (or an edge has no length). */
    private static boolean overlap(PointM a, PointM b, PointM c, PointM d) {
        if (same(a, b) || same(c, d)) { return true; }
        PointM shared = same(b, c) ? b : a; PointM first = shared == b ? a : b; PointM second = shared == b ? d : c;
        return Math.abs(orientation(shared, first, second)) <= EPSILON
                && (first.x() - shared.x()) * (second.x() - shared.x()) + (first.y() - shared.y()) * (second.y() - shared.y()) > 0;
    }

    static boolean intersect(PointM a, PointM b, PointM c, PointM d) {
        double o1 = orientation(a, b, c), o2 = orientation(a, b, d), o3 = orientation(c, d, a), o4 = orientation(c, d, b);
        if (o1 * o2 < 0 && o3 * o4 < 0) { return true; }
        return Math.abs(o1) <= EPSILON && onSegment(a, b, c) || Math.abs(o2) <= EPSILON && onSegment(a, b, d)
                || Math.abs(o3) <= EPSILON && onSegment(c, d, a) || Math.abs(o4) <= EPSILON && onSegment(c, d, b);
    }
    private static double orientation(PointM a, PointM b, PointM c) { return (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x()); }
    private static boolean onSegment(PointM a, PointM b, PointM p) {
        return p.x() >= Math.min(a.x(), b.x()) - EPSILON && p.x() <= Math.max(a.x(), b.x()) + EPSILON
                && p.y() >= Math.min(a.y(), b.y()) - EPSILON && p.y() <= Math.max(a.y(), b.y()) + EPSILON;
    }
    private static boolean same(PointM a, PointM b) { return Math.abs(a.x() - b.x()) <= EPSILON && Math.abs(a.y() - b.y()) <= EPSILON; }
    private static boolean within(double value, double min, double max) { return value >= min && value <= max; }

    private static <T, K> void duplicates(String path, List<T> items, Function<T, K> key, Rule rule, List<Violation> violations) {
        var seen = new HashSet<K>();
        for (int i = 0; i < items.size(); i++) {
            K value = key.apply(items.get(i));
            if (value != null && !seen.add(value)) { violations.add(new Violation(path + "[" + i + "]", rule)); }
        }
    }
}
