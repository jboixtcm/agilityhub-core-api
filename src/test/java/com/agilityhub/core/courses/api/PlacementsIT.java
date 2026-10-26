package com.agilityhub.core.courses.api;

import com.agilityhub.core.clubs.activities.persistence.ActivityRepository;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * E9-T03: placements (S16 R-16-05 the warnings' policy, R-16-06 `stale`, R-16-13 a placement of an activity or a class). The
 * client's course-core resolves the obstacles and the warnings; the core stores them as sent and applies the policy.
 */
class PlacementsIT extends CourseItSupport {
    static final String A1 = "e9t2-ring-a1", A2 = "e9t2-ring-a2", B1 = "e9t2-ring-b1";
    @Autowired ActivityRepository activities;
    @Autowired com.mongodb.client.MongoClient client;

    @Test void T_16_07_placementFreezesTunnelControlPointsWhenTheCourseChanges() throws Exception {
        String course = createCourse("Frozen tunnel");
        drawRing(A1);
        var body = placementBody(course, A1);
        var obstacle = (ObjectNode) body.at("/resolvedObstacles/0");
        obstacle.put("type", "Tunnel6m");
        var points = obstacle.putArray("controlPointsMeters");
        points.addObject().put("x", 12.0).put("y", 4.0);
        points.addObject().put("x", 14.0).put("y", 6.0);
        points.addObject().put("x", 16.0).put("y", 4.0);
        var created = createPlacement("INSTRUCTOR", body, 201);
        assertThat(created.at("/resolvedObstacles/0/controlPointsMeters")).isEqualTo(points);
        String id = created.path("id").asText();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(course)), new Update()
                .set("normalizedJson.obstacles.0.controlPointsMeters", List.of(new Document("x", 1).append("y", 1)))
                .inc("version", 1), "courses");
        var reloaded = ok(as(get("/api/v1/placements/" + id), CLUB, "INSTRUCTOR"), 200);
        assertThat(reloaded.at("/resolvedObstacles/0/controlPointsMeters")).isEqualTo(points);
        error(as(get("/api/v1/placements/" + id), OTHER, "ADMIN"), 404, "NOT_FOUND");
        error(as(get("/api/v1/placements/" + id), CLUB, "MEMBER"), 403, "FORBIDDEN");
    }

    @Test void T_16_07_placementControlPointsAcceptLegacyAbsenceAndExplicitEmptyButRejectIncompletePoints() throws Exception {
        String course = createCourse("Tunnel compatibility");
        drawRing(A1);
        var body = placementBody(course, A1);
        assertThat(createPlacement("INSTRUCTOR", body, 201).at("/resolvedObstacles/0/controlPointsMeters").isNull()).isTrue();
        var obstacle = (ObjectNode) body.at("/resolvedObstacles/0");
        obstacle.putArray("controlPointsMeters");
        assertThat(createPlacement("INSTRUCTOR", body, 201).at("/resolvedObstacles/0/controlPointsMeters")).isEmpty();
        obstacle.putArray("controlPointsMeters").addObject().put("x", 12);
        placementError("INSTRUCTOR", body, 400, "VALIDATION_ERROR");
        obstacle.putArray("controlPointsMeters").addNull();
        placementError("INSTRUCTOR", body, 400, "VALIDATION_ERROR");
    }

    JsonNode placementError(String role, ObjectNode body, int status, String code) throws Exception {
        return error(as(json(post("/api/v1/placements"), body), CLUB, role).header("Idempotency-Key", UUID.randomUUID().toString()), status, code);
    }
    JsonNode replace(String role, String id, ObjectNode body, int status) throws Exception {
        return ok(as(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/placements/" + id), body), CLUB, role), status);
    }
    JsonNode putError(String role, String id, ObjectNode body, int status, String code) throws Exception {
        return error(as(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/placements/" + id), body), CLUB, role), status, code);
    }
    Document placementDocument(String id) { return mongo.findOne(Query.query(Criteria.where("_id").is(id)), Document.class, "placements"); }
    List<Document> audits(String action) {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("action").is(action)), Document.class, "audit_entries");
    }
    void activity(String id, String club) { mongo.insert(new Document("_id", id).append("clubId", club).append("state", "PUBLISHED"), "activities"); }
    void modules(List<Module> modules) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)), new Update().set("modules", modules.stream().map(Enum::name).toList()), "clubs");
        configs.invalidate(CLUB); hosts.invalidate();
    }

    /**
     * T-16-07 (R-16-05): a placement with a `critical` warning is `422 PLACEMENT_BLOCKED {warnings}` (the critical ones) unless
     * an ADMIN forces it, which audits `COURSE_FORCED_PLACEMENT`; an INSTRUCTOR with `force` is `403`. `warning` and `info` are
     * stored as sent; course-core's `resolvedObstacles` too (R-16-05's example: (12, 3) moved by (2, 1) → (14, 4)).
     */
    @Test @AuditCovers(AuditAction.COURSE_FORCED_PLACEMENT)
    void T_16_07_aCriticalWarningBlocksThePlacementUnlessAnAdminForcesIt() throws Exception {
        String course = createCourse("Jumping 1");
        drawRing(A1);
        var critical = warning("w-nogo", "ring-no-go", "critical").put("obstacleSourceId", "source-0").put("ringNoGoZoneId", "bassa");
        var margin = warning("w-border", "border-clearance", "warning").put("distanceMeters", 0.8).put("thresholdMeters", 1.0);
        for (String role : List.of("INSTRUCTOR", "ADMIN")) {
            var details = placementError(role, placementBody(course, A1, critical, margin), 422, "PLACEMENT_BLOCKED");
            assertThat(details.path("warnings")).hasSize(1);
            assertThat(details.at("/warnings/0/id").asText()).isEqualTo("w-nogo");
            assertThat(details.at("/warnings/0/ruleId").asText()).isEqualTo("ring-no-go");
            assertThat(details.at("/warnings/0/severity").asText()).isEqualTo("critical");
        }
        placementError("INSTRUCTOR", placementBody(course, A1, critical).put("force", true), 403, "FORBIDDEN");
        assertThat(count("placements", Query.query(Criteria.where("clubId").is(CLUB)))).isZero();
        assertThat(audits("COURSE_FORCED_PLACEMENT")).isEmpty();

        var forced = createPlacement("ADMIN", placementBody(course, A1, critical, margin).put("force", true), 201);
        assertThat(forced.path("warnings")).extracting(w -> w.path("id").asText()).containsExactly("w-nogo", "w-border");
        assertThat(forced.at("/warnings/1/distanceMeters").asDouble()).isEqualTo(0.8);
        var audit = audits("COURSE_FORCED_PLACEMENT");
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().getString("entityType")).isEqualTo("Placement");
        assertThat(audit.getFirst().getString("entityId")).isEqualTo(forced.path("id").asText());
        assertThat(audit.getFirst().get("details", Document.class).getList("warningIds", String.class)).containsExactly("w-nogo");

        // warning and info only: stored by an instructor as they came, no audit.
        var info = warning("w-inv", "obstacle-inventory", "info");
        var stored = createPlacement("INSTRUCTOR", placementBody(course, A1, margin, info), 201);
        assertThat(stored.path("stale").asBoolean()).isFalse();
        assertThat(stored.path("version").asLong()).isZero();
        assertThat(stored.path("status").asText()).isEqualTo("DRAFT");
        assertThat(stored.path("mode").asText()).isEqualTo("TRAINING");
        assertThat(stored.path("warningThresholdM").asDouble()).isEqualTo(1.5); // courses.defaultWarningThresholdM
        assertThat(stored.path("createdByAccountId").asText()).isEqualTo("e9t2-INSTRUCTOR");
        assertThat(stored.at("/resolvedObstacles/0/x").asDouble()).isEqualTo(14);
        assertThat(stored.at("/resolvedObstacles/0/y").asDouble()).isEqualTo(4);
        assertThat(stored.path("warnings")).extracting(w -> w.path("ruleId").asText()).containsExactly("border-clearance", "obstacle-inventory");
        assertThat(placementDocument(stored.path("id").asText()).getList("warningsJson", Document.class)).hasSize(2);
        assertThat(audits("COURSE_FORCED_PLACEMENT")).hasSize(1);
        assertThat(createPlacement("INSTRUCTOR", placementBody(course, A1).put("warningThresholdM", 2.5), 201).path("warningThresholdM").asDouble()).isEqualTo(2.5);
        // §7: PlacementCreated {placementId, ringId, courseId, warnings[]}.
        var created = events("PlacementCreated").stream().filter(e -> stored.path("id").asText().equals(e.getString("aggregateId"))).toList();
        assertThat(created).hasSize(1);
        var payload = created.getFirst().get("payload", Document.class);
        assertThat(payload).containsEntry("placementId", stored.path("id").asText()).containsEntry("ringId", A1).containsEntry("courseId", course);
        assertThat(payload.getList("warnings", Document.class)).extracting(w -> w.getString("severity")).containsExactly("warning", "info");
    }

    /**
     * R-16-05's references: a ring without geometry is `422 RING_WITHOUT_GEOMETRY`; the course is read at its current version
     * (`409 STALE_VERSION`) and needs a model; another club's ring or course is `404`; `activityId` needs `ACTIVITIES`
     * (`400 VALIDATION_ERROR {activityId: MODULE_DISABLED}`) and an activity of the club.
     */
    @Test void T_16_07_aPlacementNamesAReadableCourseAtItsVersionAndADrawnRing() throws Exception {
        String course = createCourse("Jumping 2");
        placementError("INSTRUCTOR", placementBody(course, A1), 422, "RING_WITHOUT_GEOMETRY");
        drawRing(A1);
        placementError("INSTRUCTOR", placementBody(course, A1).put("courseVersion", 3), 409, "STALE_VERSION");
        placementError("INSTRUCTOR", placementBody(course, B1), 404, "NOT_FOUND");
        String foreign = ok(as(json(post("/api/v1/courses"), course("Altre club", model(2, false))), OTHER, "INSTRUCTOR"), 201).path("id").asText();
        placementError("INSTRUCTOR", placementBody(foreign, A1), 404, "NOT_FOUND");
        error(as(json(post("/api/v1/placements"), placementBody(course, A1)), OTHER, "ADMIN").header("Idempotency-Key", UUID.randomUUID().toString()), 404, "NOT_FOUND");
        placementError("MEMBER", placementBody(course, A1), 403, "FORBIDDEN");
        // A course without a model (a photo) cannot be placed.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(foreign)), new Update().set("clubId", CLUB).set("normalizedJson", null), "courses");
        var details = placementError("INSTRUCTOR", placementBody(foreign, A1), 400, "VALIDATION_ERROR");
        assertThat(details.at("/fieldErrors/0/field").asText()).isEqualTo("courseId");
        assertThat(details.at("/fieldErrors/0/code").asText()).isEqualTo("COURSE_MODEL_INVALID");
        // An activity of the club, only with ACTIVITIES.
        activity("e9t2-activity-a", CLUB); activity("e9t2-activity-b", OTHER);
        placementError("INSTRUCTOR", placementBody(course, A1).put("activityId", "e9t2-activity-b"), 404, "NOT_FOUND");
        var planned = createPlacement("INSTRUCTOR", placementBody(course, A1).put("activityId", "e9t2-activity-a").put("name", "Recorregut 1"), 201);
        assertThat(planned.path("activityId").asText()).isEqualTo("e9t2-activity-a");
        modules(Arrays.stream(Module.values()).filter(m -> m != Module.ACTIVITIES).toList());
        details = placementError("INSTRUCTOR", placementBody(course, A1).put("activityId", "e9t2-activity-a"), 400, "VALIDATION_ERROR");
        assertThat(details.at("/fieldErrors/0/field").asText()).isEqualTo("activityId");
        assertThat(details.at("/fieldErrors/0/code").asText()).isEqualTo("MODULE_DISABLED");
        // COURSES off: the vertical is absent (T-16-12).
        modules(Arrays.stream(Module.values()).filter(m -> m != Module.COURSES).toList());
        placementError("ADMIN", placementBody(course, A1), 404, "MODULE_DISABLED");
        error(as(get("/api/v1/placements/" + planned.path("id").asText()), CLUB, "ADMIN"), 404, "MODULE_DISABLED");
    }

    /**
     * Another request's transaction that wrote {@code update} on the document {@code id} of {@code collection} and is still open
     * while {@code during} runs (it is then rolled back): the two meet on that document as a Mongo write conflict.
     */
    void whileOpen(String collection, String id, org.bson.conversions.Bson update, org.junit.jupiter.api.function.Executable during) throws Throwable {
        try (var session = client.startSession()) {
            session.startTransaction();
            var database = client.getDatabase(mongo.getDb().getName());
            assertThat(database.getCollection(collection).updateOne(session, com.mongodb.client.model.Filters.eq("_id", id), update).getModifiedCount()).isEqualTo(1);
            try { during.execute(); } finally { session.abortTransaction(); }
        }
    }

    /**
     * E115 (E9-T02 review #7, write skew): a placement written while the removal of its ring's geometry or of its course is in
     * flight would be left on something gone, since each side checks the other's documents. The placement writes the ring and the
     * course at the versions it read, so the two meet: the placement is `409 STALE_VERSION` and nothing is stored. The other way
     * round, a geometry or course removal that meets a placement in flight is `409 STALE_VERSION` too, never a `500`.
     */
    @Test void T_16_07_aPlacementRacingTheRemovalOfItsGeometryOrCourseIsStale() throws Throwable {
        String course = createCourse("Jumping 7");
        drawRing(A1);
        // A removal in flight (DELETE /rings/{id}/geometry, DELETE /courses/{id}): the placement loses.
        whileOpen("rings", A1, com.mongodb.client.model.Updates.unset("geometry"),
                () -> placementError("INSTRUCTOR", placementBody(course, A1), 409, "STALE_VERSION"));
        whileOpen("courses", course, com.mongodb.client.model.Updates.set("deletedAt", java.util.Date.from(clock.instant())),
                () -> placementError("INSTRUCTOR", placementBody(course, A1), 409, "STALE_VERSION"));
        assertThat(count("placements", Query.query(Criteria.where("clubId").is(CLUB)))).isZero();
        // A placement in flight (its references): the removal loses, with a 409 instead of a 500.
        whileOpen("rings", A1, com.mongodb.client.model.Updates.inc("geometryReferences", 1),
                () -> error(as(delete("/api/v1/rings/" + A1 + "/geometry").param("version", "1"), CLUB, "ADMIN"), 409, "STALE_VERSION"));
        whileOpen("courses", course, com.mongodb.client.model.Updates.inc("references", 1),
                () -> error(as(delete("/api/v1/courses/" + course).param("version", "0"), CLUB, "ADMIN"), 409, "STALE_VERSION"));
        // Once the other side is gone, the placement goes through and writes its references.
        String id = createPlacement("INSTRUCTOR", placementBody(course, A1), 201).path("id").asText();
        assertThat(ringDocument(A1).get("geometryReferences", Number.class).intValue()).isEqualTo(1);
        assertThat(mongo.findOne(Query.query(Criteria.where("_id").is(course)), Document.class, "courses").get("references", Number.class).intValue()).isEqualTo(1);
        replace("INSTRUCTOR", id, placementBody(course, A1).put("version", 0), 200);
        assertThat(ringDocument(A1).get("geometryReferences", Number.class).intValue()).isEqualTo(2);
        // A placement keeps the geometry in use and the course deletable only by R-16-03's rule (no active setup shows it).
        error(as(delete("/api/v1/rings/" + A1 + "/geometry").param("version", "1"), CLUB, "ADMIN"), 409, "RING_GEOMETRY_IN_USE");
        // The references never touch S05's lock nor the course's version.
        assertThat(ringDocument(A1).get("version", Number.class).longValue()).isEqualTo(3);
        assertThat(ok(as(get("/api/v1/courses/" + course), CLUB, "INSTRUCTOR"), 200).path("version").asLong()).isZero();
    }

    /**
     * T-16-17 (R-16-06): after `PUT /rings/{id}/geometry` the ring's placements read `stale: true` with their obstacles kept, and
     * an active setup on one of them shows it to the staff; the client's `PUT /placements/{id}` with the recalculated obstacles
     * clears it (`PlacementUpdated {placementId, stale: false}`). The `PUT` is optimistic (`version`) and keeps the ring and course.
     */
    @Test void T_16_17_aGeometryChangeMarksThePlacementStaleUntilTheClientPutsItAgain() throws Exception {
        String course = createCourse("Jumping 3");
        drawRing(A1);
        var created = createPlacement("INSTRUCTOR", placementBody(course, A1), 201);
        String id = created.path("id").asText();
        String setup = registerSetup(CLUB, "INSTRUCTOR", setupBody(A1, "JUMPING").put("placementId", id), 201).at("/setup/id").asText();
        clock.advance(Duration.ofMinutes(1));
        ok(as(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/rings/" + A1 + "/geometry"), geometry(1).put("lengthM", 42)), CLUB, "ADMIN"), 200);
        var stale = ok(as(get("/api/v1/placements/" + id), CLUB, "INSTRUCTOR"), 200);
        assertThat(stale.path("stale").asBoolean()).isTrue();
        assertThat(stale.path("resolvedObstacles")).isEqualTo(created.path("resolvedObstacles"));
        // «Geometria canviada: revisa la col·locació»: the staff read the active setup's placement as stale.
        assertThat(ok(as(get("/api/v1/ring-setups/" + setup), CLUB, "INSTRUCTOR"), 200).path("placementId").asText()).isEqualTo(id);
        assertThat(ok(as(get("/api/v1/placements").param("ringId", A1), CLUB, "INSTRUCTOR"), 200).get(0).path("stale").asBoolean()).isTrue();

        var recalculated = placementBody(course, A1).put("version", 0);
        ((ObjectNode) recalculated.at("/resolvedObstacles/0")).put("x", 15).put("y", 5);
        putError("INSTRUCTOR", id, recalculated.deepCopy().put("version", 5), 409, "STALE_VERSION");
        putError("INSTRUCTOR", id, recalculated.deepCopy().put("ringId", A2), 422, "PLACEMENT_RING_MISMATCH");
        clock.advance(Duration.ofMinutes(1));
        var updated = replace("INSTRUCTOR", id, recalculated, 200);
        assertThat(updated.path("stale").asBoolean()).isFalse();
        assertThat(updated.path("version").asLong()).isEqualTo(1);
        assertThat(updated.at("/resolvedObstacles/0/x").asDouble()).isEqualTo(15);
        assertThat(updated.path("createdAt").asText()).isEqualTo(created.path("createdAt").asText());
        assertThat(updated.path("updatedAt").asText()).isEqualTo(clock.instant().toString());
        var event = events("PlacementUpdated").stream().filter(e -> id.equals(e.getString("aggregateId"))).toList();
        assertThat(event).hasSize(1);
        assertThat(event.getFirst().get("payload", Document.class)).containsEntry("placementId", id).containsEntry("stale", false);
        assertThat(placementDocument(id).getBoolean("stale")).isFalse();
        // The old version is stale now; the same policy applies to a PUT.
        putError("INSTRUCTOR", id, recalculated, 409, "STALE_VERSION");
        putError("INSTRUCTOR", id, placementBody(course, A1, warning("w", "outside-ring", "critical")).put("version", 1), 422, "PLACEMENT_BLOCKED");
        // E117 point 5: the version is checked before R-16-05's policy, so a stale PUT with a critical warning is 409, not 422.
        putError("INSTRUCTOR", id, placementBody(course, A1, warning("w", "outside-ring", "critical")).put("version", 0), 409, "STALE_VERSION");
        putError("INSTRUCTOR", id, placementBody(course, A1).put("version", 1).put("force", true), 403, "FORBIDDEN");
        String other = createCourse("Jumping 4");
        putError("INSTRUCTOR", id, placementBody(other, A1).put("version", 1), 400, "VALIDATION_ERROR");
        putError("ADMIN", "e9t2-missing", placementBody(course, A1).put("version", 1), 404, "NOT_FOUND");
        error(as(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/placements/" + id), placementBody(course, A1).put("version", 1)), OTHER, "ADMIN"),
                404, "NOT_FOUND");
    }

    /**
     * T-16-19 (R-16-10, E9-T05): the client's PDF, uploaded with purpose `BUILD_SHEET`, is bound by `POST /placements/{id}/build-sheet`
     * and read back by `GET` as `application/pdf`; the placement's version stays and a later `PUT` keeps the sheet. Without one, `GET` is
     * `404`; another club's key, another purpose's, an unknown one or one bound to another placement is `404` and binds nothing
     * (R-16-16); a MEMBER is `403`; another club reads nothing of it.
     */
    @Test void T_16_19_aBuildSheetIsTheClubsOwnPdfUploadAndReadsBackAsPdf() throws Exception {
        String course = createCourse("Jumping 9");
        drawRing(A1);
        String id = createPlacement("INSTRUCTOR", placementBody(course, A1), 201).path("id").asText();
        String path = "/api/v1/placements/" + id + "/build-sheet";
        error(as(get(path), CLUB, "INSTRUCTOR"), 404, "NOT_FOUND");
        byte[] pdf = "%PDF-1.4\n% fictional build sheet\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sheet = upload(CLUB, "BUILD_SHEET", "application/pdf", pdf, 204, Duration.ZERO);
        String foreign = upload(OTHER, "BUILD_SHEET", "application/pdf", pdf, 204, Duration.ZERO);
        String smarter = upload(CLUB, "SMARTER_SOURCE", "text/plain", "+++++++SAD".getBytes(java.nio.charset.StandardCharsets.UTF_8), 204, Duration.ZERO);
        for (String key : List.of(foreign, smarter, CLUB + "/courses/" + UUID.randomUUID())) {
            error(as(json(post(path), Map.of("fileKey", key)), CLUB, "INSTRUCTOR"), 404, "NOT_FOUND");
        }
        error(as(json(post(path), Map.of("fileKey", sheet)), CLUB, "MEMBER"), 403, "FORBIDDEN");
        assertThat(placementDocument(id).get("buildSheetFileKey")).isNull();

        var attached = ok(as(json(post(path), Map.of("fileKey", sheet)), CLUB, "INSTRUCTOR"), 200);
        assertThat(attached.path("buildSheetFileKey").asText()).isEqualTo(sheet);
        assertThat(attached.path("version").asLong()).isZero();
        var read = perform(as(get(path), CLUB, "ADMIN"), 200);
        assertThat(read.getResponse().getContentType()).startsWith("application/pdf");
        assertThat(read.getResponse().getContentAsByteArray()).isEqualTo(pdf);
        // The same key again is the same sheet; a PUT of the placement keeps it.
        ok(as(json(post(path), Map.of("fileKey", sheet)), CLUB, "ADMIN"), 200);
        assertThat(replace("INSTRUCTOR", id, placementBody(course, A1).put("version", 0), 200).path("buildSheetFileKey").asText()).isEqualTo(sheet);
        error(as(get(path), OTHER, "ADMIN"), 404, "NOT_FOUND");
        String second = createPlacement("INSTRUCTOR", placementBody(course, A1), 201).path("id").asText();
        error(as(json(post("/api/v1/placements/" + second + "/build-sheet"), Map.of("fileKey", sheet)), CLUB, "INSTRUCTOR"), 404, "NOT_FOUND");
        assertThat(placementDocument(second).get("buildSheetFileKey")).isNull();
    }

    /**
     * INC-62 points 3 and 4 (E11-T02; R-16-10, decision E121): a second sheet replaces the first, whose file is released (its
     * upload grant goes, and the stored bytes once the request commits). A `PUT` that keeps the placement where it is keeps
     * its sheet; one that moves it (another transform) clears `buildSheetFileKey` and releases the sheet, which no longer fits.
     */
    @Test void T_16_19_INC_62_aReplacedOrMovedPlacementsBuildSheetIsReleased() throws Exception {
        String course = createCourse("Jumping 10");
        drawRing(A1);
        String id = createPlacement("INSTRUCTOR", placementBody(course, A1), 201).path("id").asText();
        String path = "/api/v1/placements/" + id + "/build-sheet";
        byte[] pdf = "%PDF-1.4\n% fictional build sheet\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String first = upload(CLUB, "BUILD_SHEET", "application/pdf", pdf, 204, Duration.ZERO), second = upload(CLUB, "BUILD_SHEET", "application/pdf", pdf, 204, Duration.ZERO);
        ok(as(json(post(path), Map.of("fileKey", first)), CLUB, "INSTRUCTOR"), 200);
        ok(as(json(post(path), Map.of("fileKey", second)), CLUB, "INSTRUCTOR"), 200);
        assertThat(placementDocument(id).getString("buildSheetFileKey")).isEqualTo(second);
        assertThat(grant(first)).as("the replaced sheet's upload is released").isNull();
        assertThat(grant(second)).isNotNull();
        assertThat(perform(as(get(path), CLUB, "INSTRUCTOR"), 200).getResponse().getContentAsByteArray()).isEqualTo(pdf);
        // The same transform: the sheet stays.
        assertThat(replace("INSTRUCTOR", id, placementBody(course, A1).put("version", 0), 200).path("buildSheetFileKey").asText()).isEqualTo(second);
        // Moved: the sheet no longer fits it.
        var moved = placementBody(course, A1).put("version", 1); ((ObjectNode) moved.path("transform")).put("offsetXM", 3);
        assertThat(replace("INSTRUCTOR", id, moved, 200).path("buildSheetFileKey").isNull()).isTrue();
        assertThat(placementDocument(id).get("buildSheetFileKey")).isNull();
        assertThat(grant(second)).as("the moved placement's sheet is released").isNull();
        error(as(get(path), CLUB, "INSTRUCTOR"), 404, "NOT_FOUND");
    }
    /** The upload grant of a scoped course key (`{clubId}/courses/{id}`), or null once it is released. */
    Document grant(String key) { return mongo.findOne(Query.query(Criteria.where("_id").is(key.substring(key.lastIndexOf('/') + 1))), Document.class, "attachment_uploads"); }

    /**
     * R-16-06 across rings: a placement keeps its ring. Moving it to another drawn ring is `422 PLACEMENT_RING_MISMATCH`
     * (classes and setups of the ring plan it); the list filters by ring, course and activity (R-16-13: the activity's
     * placements, which S07's `ActivityRepository.placementIds` reads too), and `classSessionIds` are S06's classes that plan it.
     */
    @Test void T_16_20_theListFiltersByRingCourseAndActivityAndNamesTheClassesThatPlanAPlacement() throws Exception {
        String course = createCourse("Jumping 5"), other = createCourse("Jumping 6");
        drawRing(A1); drawRing(A2);
        activity("e9t2-activity-a", CLUB);
        String first = createPlacement("INSTRUCTOR", placementBody(course, A1).put("activityId", "e9t2-activity-a"), 201).path("id").asText();
        clock.advance(Duration.ofMinutes(1));
        String second = createPlacement("INSTRUCTOR", placementBody(other, A2), 201).path("id").asText();
        clock.advance(Duration.ofMinutes(1));
        String third = createPlacement("ADMIN", placementBody(course, A2), 201).path("id").asText();
        putError("INSTRUCTOR", second, placementBody(other, A1).put("version", 0), 422, "PLACEMENT_RING_MISMATCH");
        for (int i = 0; i < 2; i++) {
            mongo.insert(new Document("_id", "e9t2-class-" + i).append("clubId", CLUB).append("placementId", third)
                    .append("startsAt", java.util.Date.from(clock.instant().plus(Duration.ofDays(2 - i)))), "class_sessions");
        }
        mongo.insert(new Document("_id", "e9t2-class-other").append("clubId", OTHER).append("placementId", third), "class_sessions");
        var all = ok(as(get("/api/v1/placements"), CLUB, "INSTRUCTOR"), 200);
        assertThat(all).extracting(p -> p.path("id").asText()).containsExactly(third, second, first);
        assertThat(all.get(0).path("classSessionIds")).extracting(JsonNode::asText).containsExactly("e9t2-class-1", "e9t2-class-0");
        assertThat(ok(as(get("/api/v1/placements").param("ringId", A2), CLUB, "INSTRUCTOR"), 200)).extracting(p -> p.path("id").asText()).containsExactly(third, second);
        assertThat(ok(as(get("/api/v1/placements").param("courseId", course), CLUB, "INSTRUCTOR"), 200)).extracting(p -> p.path("id").asText()).containsExactly(third, first);
        assertThat(ok(as(get("/api/v1/placements").param("ringId", A2).param("courseId", course), CLUB, "ADMIN"), 200)).extracting(p -> p.path("id").asText()).containsExactly(third);
        assertThat(ok(as(get("/api/v1/placements").param("activityId", "e9t2-activity-a"), CLUB, "ADMIN"), 200)).extracting(p -> p.path("id").asText()).containsExactly(first);
        try (var tenant = TenantContext.open(CLUB)) { assertThat(activities.placementIds("e9t2-activity-a")).containsExactly(first); }
        assertThat(ok(as(get("/api/v1/placements/" + third), CLUB, "ADMIN"), 200).path("classSessionIds")).hasSize(2);
        // Tenant and roles.
        assertThat(ok(as(get("/api/v1/placements"), OTHER, "ADMIN"), 200)).isEmpty();
        error(as(get("/api/v1/placements/" + first), OTHER, "ADMIN"), 404, "NOT_FOUND");
        error(as(get("/api/v1/placements").param("ringId", B1), CLUB, "ADMIN"), 404, "NOT_FOUND");
        error(as(get("/api/v1/placements").param("activityId", "e9t2-activity-missing"), CLUB, "ADMIN"), 404, "NOT_FOUND");
        error(as(get("/api/v1/placements"), CLUB, "MEMBER"), 403, "FORBIDDEN");
        error(as(get("/api/v1/placements/" + first), CLUB, "MEMBER"), 403, "FORBIDDEN");
    }
}
