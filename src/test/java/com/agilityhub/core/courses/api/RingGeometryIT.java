package com.agilityhub.core.courses.api;

import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** E9-T02: ring geometry (S16 R-16-04, R-16-06's trigger), §14.2's calibrations and obstacle inventories (R-16-12). */
class RingGeometryIT extends CourseItSupport {
    static final String RING = "e9t2-ring-a1";

    JsonNode putGeometry(String club, ObjectNode body, int status) throws Exception { return ok(as(json(put("/api/v1/rings/" + RING + "/geometry"), body), club, "ADMIN"), status); }
    Document ring() { return mongo.findOne(Query.query(Criteria.where("_id").is(RING)), Document.class, "rings"); }
    void placement(String id, String ring, String status) {
        mongo.insert(new Document("_id", id).append("clubId", CLUB).append("ringId", ring).append("courseId", "e9t2-course").append("courseVersion", 0L)
                .append("warningThresholdM", 1.5).append("stale", false).append("status", status).append("version", 0L), "placements");
    }
    List<String> rules(JsonNode details) { var rules = new ArrayList<String>(); details.path("violations").forEach(v -> rules.add(v.path("path").asText() + ":" + v.path("rule").asText())); return rules; }

    @Test @AuditCovers(AuditAction.RING_GEOMETRY_CHANGED)
    void T_16_06_aValidGeometryIsVersionedAuditedPublishedAndMarksTheRingsPlacementsStale() throws Exception {
        placement("e9t2-placement-1", RING, "READY_TO_BUILD");
        placement("e9t2-placement-2", "e9t2-ring-a2", "READY_TO_BUILD");
        var saved = putGeometry(CLUB, geometry(0), 200);
        assertThat(saved.path("version").asLong()).isEqualTo(1);
        assertThat(saved.path("updatedAt").asText()).isEqualTo(clock.instant().toString());
        assertThat(ok(as(get("/api/v1/rings/" + RING + "/geometry"), CLUB, "INSTRUCTOR"), 200)).isEqualTo(saved);
        // R-16-06: the ring's placements keep their obstacles and become stale; another ring's do not.
        assertThat(mongo.findOne(Query.query(Criteria.where("_id").is("e9t2-placement-1")), Document.class, "placements").getBoolean("stale")).isTrue();
        assertThat(mongo.findOne(Query.query(Criteria.where("_id").is("e9t2-placement-2")), Document.class, "placements").getBoolean("stale")).isFalse();
        var event = events("RingGeometryChanged").stream().filter(e -> RING.equals(e.getString("aggregateId"))).toList();
        assertThat(event).hasSize(1);
        assertThat(event.getFirst().get("payload", Document.class)).containsEntry("ringId", RING).containsEntry("version", 1L);
        var audit = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("RING_GEOMETRY_CHANGED")), Document.class, "audit_entries");
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().getString("entityType")).isEqualTo("RingGeometry");
        assertThat(audit.getFirst().getString("entityId")).isEqualTo(RING);
        // S05's own fields and lock survive the geometry write.
        assertThat(ring().getString("name")).isEqualTo("Ring A1");
        assertThat(ring().get("version", Number.class).longValue()).isEqualTo(3);
        // The second save takes version 1; a stale version is refused and changes nothing.
        clock.advance(Duration.ofMinutes(1));
        assertThat(putGeometry(CLUB, geometry(1).put("lengthM", 45), 200).path("version").asLong()).isEqualTo(2);
        error(as(json(put("/api/v1/rings/" + RING + "/geometry"), geometry(1)), CLUB, "ADMIN"), 409, "STALE_VERSION");
        assertThat(ring().get("geometry", Document.class).get("lengthM", Number.class).doubleValue()).isEqualTo(45);
        // S16 §14.2: the club's venue is created once, with the club.
        var venues = mongo.find(Query.query(Criteria.where("clubId").is(CLUB)), Document.class, "venues");
        assertThat(venues).hasSize(1);
        assertThat(venues.getFirst().getString("slug")).isEqualTo(CLUB);
        // Tenant and role.
        error(as(json(put("/api/v1/rings/" + RING + "/geometry"), geometry(2)), OTHER, "ADMIN"), 404, "NOT_FOUND");
        error(as(json(put("/api/v1/rings/" + RING + "/geometry"), geometry(2)), CLUB, "INSTRUCTOR"), 403, "FORBIDDEN");
        error(as(get("/api/v1/rings/" + RING + "/geometry"), OTHER, "ADMIN"), 404, "NOT_FOUND");
    }

    /**
     * INC-61 (E11-T02; review of E9-T02 #8, S16 §14.2): the club's slug is already another venue's (a member's, without a
     * club). The first geometry is saved and the club's venue takes the first free slug, `{slug}-2`; before the fix the
     * unique `venue_slug` index answered 500 and the valid geometry was rolled back.
     */
    @Test void T_16_06_INC_61_theFirstGeometryCreatesTheClubsVenueEvenWhenItsSlugIsTaken() throws Exception {
        mongo.insert(new Document("_id", "e9t2-inc61-member-venue").append("slug", CLUB).append("name", "A member's venue"), "venues");
        assertThat(putGeometry(CLUB, geometry(0), 200).path("version").asLong()).isEqualTo(1);
        var venues = mongo.find(Query.query(Criteria.where("clubId").is(CLUB)), Document.class, "venues");
        assertThat(venues).singleElement().satisfies(venue -> assertThat(venue.getString("slug")).isEqualTo(CLUB + "-2"));
        assertThat(mongo.findOne(Query.query(Criteria.where("_id").is("e9t2-inc61-member-venue")), Document.class, "venues").getString("slug")).isEqualTo(CLUB);
    }

    @Test void T_16_06_invalidGeometriesAreGeometryInvalidWithEveryFailedCheck() throws Exception {
        var twoMarkers = geometry(0); ((ArrayNode) twoMarkers.path("markers")).remove(3); ((ArrayNode) twoMarkers.path("markers")).remove(2);
        assertThat(rules(error(as(json(put("/api/v1/rings/" + RING + "/geometry"), twoMarkers), CLUB, "ADMIN"), 422, "GEOMETRY_INVALID")))
                .containsExactly("markers:TOO_FEW_MARKERS");
        var collinear = geometry(0);
        collinear.path("markers").forEach(m -> ((ObjectNode) m).put("yM", 0));
        assertThat(rules(error(as(json(put("/api/v1/rings/" + RING + "/geometry"), collinear), CLUB, "ADMIN"), 422, "GEOMETRY_INVALID")))
                .containsExactly("markers:COLLINEAR_MARKERS");
        var broken = geometry(0).put("lengthM", 61);
        ((ObjectNode) broken.at("/doors/0")).put("endM", 23);
        ((ObjectNode) broken.at("/doors/0")).put("side", "EAST");
        var bowtie = (ArrayNode) broken.at("/noGoZones/0/polygonPointsM");
        bowtie.removeAll();
        for (double[] p : new double[][] {{10, 10}, {13, 13}, {13, 10}, {10, 13}}) { bowtie.addObject().put("x", p[0]).put("y", p[1]); }
        ((ObjectNode) broken.at("/markers/1")).put("label", "A");
        assertThat(rules(error(as(json(put("/api/v1/rings/" + RING + "/geometry"), broken), CLUB, "ADMIN"), 422, "GEOMETRY_INVALID")))
                .containsExactlyInAnyOrder("lengthM:OUT_OF_RANGE", "doors[0].endM:OUTSIDE_PERIMETER", "noGoZones[0].polygonPointsM:NOT_SIMPLE", "markers[1]:DUPLICATE_LABEL");
        var outside = geometry(0); ((ObjectNode) outside.at("/noGoZones/0/polygonPointsM/2")).put("y", 30);
        assertThat(rules(error(as(json(put("/api/v1/rings/" + RING + "/geometry"), outside), CLUB, "ADMIN"), 422, "GEOMETRY_INVALID")))
                .containsExactly("noGoZones[0].polygonPointsM:OUTSIDE_RING");
        assertThat(ring().get("geometry")).isNull();
        assertThat(events("RingGeometryChanged").stream().filter(e -> RING.equals(e.getString("aggregateId")))).isEmpty();
        assertThat(count("audit_entries", Query.query(Criteria.where("clubId").is(CLUB)))).isZero();
    }

    @Test @AuditCovers(AuditAction.RING_GEOMETRY_CHANGED)
    void T_16_06_aGeometryInUseCannotBeRemovedAndAFreeOneCan() throws Exception {
        putGeometry(CLUB, geometry(0), 200);
        placement("e9t2-placement-1", RING, "READY_TO_BUILD");
        error(as(delete("/api/v1/rings/" + RING + "/geometry").param("version", "1"), CLUB, "ADMIN"), 409, "RING_GEOMETRY_IN_USE");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("e9t2-placement-1")), new org.springframework.data.mongodb.core.query.Update().set("status", "ARCHIVED"), "placements");
        mongo.insert(new Document("_id", "e9t2-setup-1").append("clubId", CLUB).append("ringId", RING).append("status", "ACTIVE").append("placementId", "e9t2-placement-1"), "ring_setups");
        error(as(delete("/api/v1/rings/" + RING + "/geometry").param("version", "1"), CLUB, "ADMIN"), 409, "RING_GEOMETRY_IN_USE");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("e9t2-setup-1")), new org.springframework.data.mongodb.core.query.Update().set("status", "DISMANTLED"), "ring_setups");
        error(as(delete("/api/v1/rings/" + RING + "/geometry").param("version", "0"), CLUB, "ADMIN"), 409, "STALE_VERSION");
        perform(as(delete("/api/v1/rings/" + RING + "/geometry").param("version", "1"), CLUB, "ADMIN"), 204);
        assertThat(ring().get("geometry")).isNull();
        assertThat(ring().getString("name")).isEqualTo("Ring A1");
        error(as(get("/api/v1/rings/" + RING + "/geometry"), CLUB, "ADMIN"), 404, "NOT_FOUND");
        error(as(delete("/api/v1/rings/" + RING + "/geometry").param("version", "1"), CLUB, "ADMIN"), 404, "NOT_FOUND");
        assertThat(count("audit_entries", Query.query(Criteria.where("clubId").is(CLUB).and("action").is("RING_GEOMETRY_CHANGED")))).isEqualTo(2);
        // A new geometry starts again from version 0.
        assertThat(putGeometry(CLUB, geometry(0), 200).path("version").asLong()).isEqualTo(1);
    }

    @Test void R_16_04_calibrationsAreStoredPerRingAndListedNewestFirst() throws Exception {
        var request = Map.of("outcome", "GREEN", "averageErrorCm", 2.5, "maxErrorCm", 4.0, "markerCount", 4, "rawMetadata", Map.of("markers", List.of(Map.of("id", "A", "errorCm", 2))));
        var first = ok(as(json(post("/api/v1/rings/" + RING + "/calibrations"), request), CLUB, "INSTRUCTOR"), 201);
        assertThat(first.path("ringId").asText()).isEqualTo(RING);
        assertThat(first.path("createdByAccountId").asText()).isEqualTo("e9t2-INSTRUCTOR");
        assertThat(first.at("/rawMetadata/markers/0/errorCm").asInt()).isEqualTo(2);
        clock.advance(Duration.ofMinutes(5));
        var second = ok(as(json(post("/api/v1/rings/" + RING + "/calibrations"), Map.of("outcome", "RED", "averageErrorCm", 40, "maxErrorCm", 80,
                "markerCount", 3, "rawMetadata", Map.of())), CLUB, "ADMIN"), 201);
        var list = ok(as(get("/api/v1/rings/" + RING + "/calibrations"), CLUB, "INSTRUCTOR"), 200);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).path("id").asText()).isEqualTo(second.path("id").asText());
        assertThat(list.get(1).path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(ok(as(get("/api/v1/rings/e9t2-ring-a2/calibrations"), CLUB, "INSTRUCTOR"), 200)).isEmpty();
        error(as(get("/api/v1/rings/" + RING + "/calibrations"), OTHER, "ADMIN"), 404, "NOT_FOUND");
        error(as(json(post("/api/v1/rings/" + RING + "/calibrations"), request), OTHER, "ADMIN"), 404, "NOT_FOUND");
    }

    @Test void T_16_11_aRingsOwnInventoryPrevailsOverTheClubsAndAnInvalidOneIsRefused() throws Exception {
        error(as(get("/api/v1/obstacle-inventories/club"), CLUB, "INSTRUCTOR"), 404, "NOT_FOUND");
        error(as(get("/api/v1/obstacle-inventories/ring:" + RING), CLUB, "INSTRUCTOR"), 404, "NOT_FOUND");
        var club = ok(as(json(put("/api/v1/obstacle-inventories/club"), Map.of("items", List.of(Map.of("type", "Jump", "count", 12), Map.of("type", "Tunnel4m", "count", 2)))), CLUB, "ADMIN"), 200);
        assertThat(club.path("scope").asText()).isEqualTo("CLUB");
        assertThat(club.path("ringId").isNull()).isTrue();
        // §3: without its own inventory a ring uses the club's.
        assertThat(ok(as(get("/api/v1/obstacle-inventories/ring:" + RING), CLUB, "INSTRUCTOR"), 200).path("scope").asText()).isEqualTo("CLUB");
        var own = ok(as(json(put("/api/v1/obstacle-inventories/ring:" + RING), Map.of("items", List.of(Map.of("type", "Tunnel4m", "count", 3)))), CLUB, "ADMIN"), 200);
        assertThat(own.path("scope").asText()).isEqualTo("RING");
        var effective = ok(as(get("/api/v1/obstacle-inventories/ring:" + RING), CLUB, "INSTRUCTOR"), 200);
        assertThat(effective.path("ringId").asText()).isEqualTo(RING);
        assertThat(effective.at("/items/0/count").asInt()).isEqualTo(3);
        assertThat(ok(as(get("/api/v1/obstacle-inventories/ring:e9t2-ring-a2"), CLUB, "INSTRUCTOR"), 200).path("scope").asText()).isEqualTo("CLUB");
        // Last write wins on one document per scope.
        ok(as(json(put("/api/v1/obstacle-inventories/club"), Map.of("items", List.of(Map.of("type", "Jump", "count", 10)))), CLUB, "ADMIN"), 200);
        assertThat(count("obstacle_inventories", Query.query(Criteria.where("clubId").is(CLUB)))).isEqualTo(2);
        assertThat(ok(as(get("/api/v1/obstacle-inventories/club"), CLUB, "ADMIN"), 200).at("/items/0/count").asInt()).isEqualTo(10);
        var changed = events("InventoryChanged").stream().filter(e -> CLUB.equals(e.getString("clubId"))).toList();
        assertThat(changed).hasSize(3);
        assertThat(changed).extracting(e -> e.get("payload", Document.class).getString("scope")).containsExactlyInAnyOrder("CLUB", "RING", "CLUB");
        // R-16-12: a negative count or a repeated type is INVENTORY_INVALID; another club's ring is not found.
        error(as(json(put("/api/v1/obstacle-inventories/club"), Map.of("items", List.of(Map.of("type", "Jump", "count", -1)))), CLUB, "ADMIN"), 422, "INVENTORY_INVALID");
        error(as(json(put("/api/v1/obstacle-inventories/club"), Map.of("items", List.of(Map.of("type", "Jump", "count", 1), Map.of("type", "Jump", "count", 2)))), CLUB, "ADMIN"),
                422, "INVENTORY_INVALID");
        error(as(json(put("/api/v1/obstacle-inventories/ring:e9t2-ring-b1"), Map.of("items", List.of())), CLUB, "ADMIN"), 404, "NOT_FOUND");
        error(as(get("/api/v1/obstacle-inventories/club"), OTHER, "ADMIN"), 404, "NOT_FOUND");
        error(as(json(put("/api/v1/obstacle-inventories/club"), Map.of("items", List.of())), CLUB, "INSTRUCTOR"), 403, "FORBIDDEN");
        assertThat(ok(as(get("/api/v1/obstacle-inventories/club"), CLUB, "ADMIN"), 200).at("/items/0/count").asInt()).isEqualTo(10);
    }

    /**
     * E9-T07 step 3 (E9-W04 question 3): an INSTRUCTOR reads `GET /rings/{id}`, as S16 §6 lets them read the ring's geometry, in the
     * reader's projection of S05 §6 (no `trainingCapacity`, `usage` or `lastChange`); writing the ring stays the ADMIN's, a MEMBER
     * reads it only through `GET /rings`, and another club's ring is not found.
     */
    @Test void T_16_06_anInstructorReadsTheRingAndOnlyAnAdminWritesIt() throws Exception {
        putGeometry(CLUB, geometry(0), 200);
        var read = ok(as(get("/api/v1/rings/" + RING), CLUB, "INSTRUCTOR"), 200);
        assertThat(read.path("id").asText()).isEqualTo(RING);
        assertThat(read.path("name").asText()).isEqualTo("Ring A1");
        assertThat(read.path("shortName").asText()).isEqualTo("Ra1");
        assertThat(read.path("active").asBoolean()).isTrue();
        assertThat(read.path("version").asLong()).isEqualTo(3);
        assertThat(read.hasNonNull("trainingCapacity")).isFalse();
        assertThat(read.hasNonNull("usage")).isFalse();
        assertThat(read.hasNonNull("lastChange")).isFalse();
        assertThat(ok(as(get("/api/v1/rings/" + RING), CLUB, "ADMIN"), 200).hasNonNull("usage")).isTrue();
        assertThat(ok(as(get("/api/v1/rings/" + RING + "/geometry"), CLUB, "INSTRUCTOR"), 200).path("lengthM").asDouble()).isEqualTo(40);
        // Tenant and roles: reads only; the ring and its geometry are written by an ADMIN.
        error(as(get("/api/v1/rings/" + RING), OTHER, "INSTRUCTOR"), 404, "NOT_FOUND");
        error(as(get("/api/v1/rings/" + RING), CLUB, "MEMBER"), 403, "FORBIDDEN");
        error(as(json(patch("/api/v1/rings/" + RING), Map.of("version", 3, "name", "Ring A1 nou")), CLUB, "INSTRUCTOR"), 403, "FORBIDDEN");
        error(as(delete("/api/v1/rings/" + RING), CLUB, "INSTRUCTOR"), 403, "FORBIDDEN");
        error(as(json(put("/api/v1/rings/" + RING + "/geometry"), geometry(1)), CLUB, "INSTRUCTOR"), 403, "FORBIDDEN");
        assertThat(ring().getString("name")).isEqualTo("Ring A1");
        assertThat(ring().get("version", Number.class).longValue()).isEqualTo(3);
    }
}
