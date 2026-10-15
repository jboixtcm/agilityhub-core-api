package com.agilityhub.core.courses.domain;

import com.agilityhub.core.courses.domain.CourseParts.*;
import com.agilityhub.core.courses.domain.CourseTypes.*;
import com.agilityhub.core.courses.domain.RingGeometryRules.Rule;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** E9-T02: S16 R-16-04's geometry checks, each with a passing and a failing case. */
class RingGeometryRulesTest {
    static PointM p(double x, double y) { return new PointM(x, y); }
    static RingDoor door(String id, DoorSide side, double start, double end, double clearance) {
        return new RingDoor(id, "D", side, start, end, DoorFlow.BOTH, clearance, List.of(), true, null);
    }
    static RingNoGoZone zone(String id, double margin, double... xy) {
        var points = new ArrayList<PointM>();
        for (int i = 0; i < xy.length; i += 2) { points.add(p(xy[i], xy[i + 1])); }
        return new RingNoGoZone(id, "Z", points, margin);
    }
    static RingMarker marker(String id, String label, double x, double y, boolean active, Integer tag) {
        return new RingMarker(id, label, "uid-" + id, "ANCHOR", x, y, 0.0, 0.0, 0.2, 0.2, true, active, tag, null);
    }
    static List<RingMarker> corners() {
        return List.of(marker("m0", "A", 0, 0, true, 0), marker("m1", "B", 40, 0, true, 1), marker("m2", "C", 40, 20, true, 2), marker("m3", "D", 0, 20, true, 3));
    }
    static List<String> check(double length, double width, double border, List<RingDoor> doors, List<RingNoGoZone> zones, List<RingMarker> markers) {
        return RingGeometryRules.check(length, width, border, doors, zones, markers).stream().map(v -> v.path() + ":" + v.rule()).toList();
    }

    @Test void T_16_06_theSpecExampleIsValid() {
        assertThat(check(40, 22, 1, List.of(door("n", DoorSide.NORTH, 3, 5, 1.5)), List.of(zone("bassa", 0.5, 10, 10, 13, 10, 13, 13, 10, 13)), corners())).isEmpty();
    }

    @Test void T_16_06_sidesAreBetweenTenAndSixtyMetres() {
        assertThat(check(10, 60, 0, List.of(), List.of(), corners())).isEmpty();
        assertThat(check(9.99, 60.01, -0.1, List.of(), List.of(), corners()))
                .containsExactly("lengthM:" + Rule.OUT_OF_RANGE, "widthM:" + Rule.OUT_OF_RANGE, "borderClearanceM:" + Rule.NEGATIVE);
    }

    @Test void T_16_06_doorsLieOnTheirSideOfThePerimeter() {
        // NORTH and SOUTH measure lengthM (40); EAST and WEST measure widthM (20).
        assertThat(check(40, 20, 0, List.of(door("n", DoorSide.SOUTH, 0, 40, 0), door("e", DoorSide.WEST, 0, 20, 0)), List.of(), corners())).isEmpty();
        assertThat(check(40, 20, 0, List.of(door("e", DoorSide.EAST, 18, 21, 0), door("s", DoorSide.SOUTH, -1, 2, -1), door("w", DoorSide.WEST, 4, 4, 0),
                door("w", DoorSide.NORTH, 1, 2, 0)), List.of(), corners())).containsExactly(
                "doors[3]:" + Rule.DUPLICATE_ID, "doors[0].endM:" + Rule.OUTSIDE_PERIMETER, "doors[1].startM:" + Rule.OUTSIDE_PERIMETER,
                "doors[1].clearanceM:" + Rule.NEGATIVE, "doors[2].startM:" + Rule.OUTSIDE_PERIMETER);
    }

    @Test void T_16_06_noGoZonesAreSimplePolygonsInsideTheRing() {
        assertThat(check(40, 20, 0, List.of(), List.of(zone("tri", 0, 1, 1, 5, 1, 3, 4), zone("concave", 0, 1, 1, 9, 1, 9, 9, 5, 4, 1, 9)), corners())).isEmpty();
        assertThat(check(40, 20, 0, List.of(), List.of(
                zone("two", 0, 1, 1, 2, 2),
                zone("outside", -1, 30, 10, 41, 10, 35, 15),
                zone("bowtie", 0, 1, 1, 5, 5, 5, 1, 1, 5),
                zone("flat", 0, 1, 1, 3, 1, 5, 1),
                zone("bowtie", 0, 1, 1, 5, 1, 5, 5, 5, 3, 1, 5)), corners())).containsExactly(
                "noGoZones[4]:" + Rule.DUPLICATE_ID,
                "noGoZones[0].polygonPointsM:" + Rule.TOO_FEW_POINTS,
                "noGoZones[1].warningMarginM:" + Rule.NEGATIVE, "noGoZones[1].polygonPointsM:" + Rule.OUTSIDE_RING,
                "noGoZones[2].polygonPointsM:" + Rule.NOT_SIMPLE,
                "noGoZones[3].polygonPointsM:" + Rule.NOT_SIMPLE,
                "noGoZones[4].polygonPointsM:" + Rule.NOT_SIMPLE);
    }

    @Test void T_16_06_aPolygonWithARepeatedVertexOrATouchingEdgeIsNotSimple() {
        assertThat(RingGeometryRules.simple(List.of(p(1, 1), p(1, 1), p(5, 1), p(3, 4)))).isFalse();
        // The vertex (3, 1) touches the edge from (1, 1) to (5, 1) only through a non-adjacent edge.
        assertThat(RingGeometryRules.simple(List.of(p(1, 1), p(5, 1), p(5, 5), p(3, 1), p(1, 5)))).isFalse();
        assertThat(RingGeometryRules.intersect(p(0, 0), p(2, 0), p(3, 0), p(4, 0))).isFalse();
        assertThat(RingGeometryRules.intersect(p(0, 0), p(2, 0), p(2, 0), p(4, 1))).isTrue();
        assertThat(RingGeometryRules.intersect(p(0, 0), p(2, 2), p(0, 2), p(2, 0))).isTrue();
    }

    @Test void T_16_06_atLeastThreeActiveNonCollinearMarkersWithUniqueKeys() {
        var inactive = new ArrayList<>(corners().subList(0, 2)); inactive.add(marker("m2", "C", 40, 20, false, 2));
        assertThat(check(40, 20, 0, List.of(), List.of(), inactive)).containsExactly("markers:" + Rule.TOO_FEW_MARKERS);
        assertThat(check(40, 20, 0, List.of(), List.of(), List.of(marker("a", "A", 0, 0, true, null), marker("b", "B", 10, 0, true, null),
                marker("c", "C", 30, 0, true, null)))).containsExactly("markers:" + Rule.COLLINEAR_MARKERS);
        var duplicates = List.of(marker("a", "A", 0, 0, true, 7), new RingMarker("a", "A", "uid-a", "ANCHOR", 40.0, 0.0, 0.0, 0.0, 0.0, -1.0, true, true, 7, null),
                marker("c", "C", 40, 20, true, null), marker("d", "D", 0, 20, true, null));
        assertThat(check(40, 20, 0, List.of(), List.of(), duplicates)).containsExactly("markers[1]:" + Rule.DUPLICATE_ID, "markers[1]:" + Rule.DUPLICATE_LABEL,
                "markers[1]:" + Rule.DUPLICATE_MARKER_UID, "markers[1]:" + Rule.DUPLICATE_APRIL_TAG, "markers[1].physicalWidthM:" + Rule.NOT_POSITIVE,
                "markers[1].physicalHeightM:" + Rule.NOT_POSITIVE);
        // A marker without an AprilTag id is never a duplicate of another one.
        assertThat(check(40, 20, 0, List.of(), List.of(), List.of(marker("a", "A", 0, 0, true, null), marker("b", "B", 40, 0, true, null),
                marker("c", "C", 40, 20, true, null)))).isEmpty();
    }
}
