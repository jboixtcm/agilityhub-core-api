package com.agilityhub.core.courses.api;

import com.agilityhub.core.clubs.training.application.ports.RingSetupPort;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.jobs.JobName;
import com.agilityhub.core.platform.application.jobs.JobRunner;
import com.agilityhub.core.platform.application.jobs.JobStatus;
import com.agilityhub.core.platform.persistence.jobs.JobRun;
import com.agilityhub.core.shared.application.OutboxDispatcher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * E9-T03: ring setups, «recorregut muntat» (S16 R-16-07, R-16-08, R-16-09, R-16-15, R-16-16) and their integrations: S15's
 * P5 step f (R-15-15 f), S09's {@link RingSetupPort} (R-09-15) and S05's `RingChanged{deactivated}` (S16 §7, consumed).
 */
class RingSetupsIT extends CourseItSupport {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    /** R-16-07's example: Estel registers a setup on Monday 5-10-2026. */
    static final Instant MONDAY = local("2026-10-05T10:00");
    static final String A1 = "e9t2-ring-a1", A2 = "e9t2-ring-a2", B1 = "e9t2-ring-b1";
    @Autowired JobRunner jobs;
    @Autowired OutboxDispatcher outbox;
    @Autowired RingSetupPort trainingPort;
    @Autowired com.mongodb.client.MongoClient client;

    static Instant local(String dateTime) { return LocalDateTime.parse(dateTime).atZone(MADRID).toInstant(); }
    String id(JsonNode created) { return created.at("/setup/id").asText(); }
    JsonNode register(String role, ObjectNode body) throws Exception { return registerSetup(CLUB, role, body, 201); }
    JsonNode registerError(String club, String role, ObjectNode body, int status, String code) throws Exception {
        return error(as(json(post("/api/v1/ring-setups"), body), club, role).header("Idempotency-Key", UUID.randomUUID().toString()), status, code);
    }
    ObjectNode course(String ringId, String kind, String courseId) { return setupBody(ringId, kind).put("courseId", courseId); }
    JsonNode setup(String id, String role) throws Exception { return ok(as(get("/api/v1/ring-setups/" + id), CLUB, role), 200); }
    JsonNode mine(String role, String... ringIds) throws Exception {
        var request = get("/api/v1/me/ring-setups"); if (ringIds.length > 0) { request.param("ringIds", ringIds); }
        return ok(as(request, CLUB, role), 200);
    }
    JsonNode act(String role, String id, String verb, Object body, int status) throws Exception {
        var request = as(json(post("/api/v1/ring-setups/" + id + "/" + verb), body == null ? Map.of() : body), CLUB, role).header("Idempotency-Key", UUID.randomUUID().toString());
        return ok(request, status);
    }
    List<Document> changes() {
        return events("RingSetupChanged").stream().filter(e -> CLUB.equals(e.getString("clubId"))).toList();
    }
    List<Document> active(String ringId) {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("ringId").is(ringId).and("status").is("ACTIVE")), Document.class, "ring_setups");
    }
    JobRun expirations(Instant at, boolean dry) {
        clock.setInstant(at);
        var run = jobs.manual(CLUB, JobName.EXPIRATIONS, dry, "e9t2-ADMIN");
        assertThat(run.status()).as("errors %s", run.errors()).isEqualTo(JobStatus.SUCCEEDED);
        return run;
    }
    static long counter(JobRun run, String name) {
        return run.counters().stream().filter(e -> e.key().equals(name)).mapToLong(e -> ((Number) e.value()).longValue()).findFirst().orElse(-1);
    }
    void modules(List<Module> modules) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)), new Update().set("modules", modules.stream().map(Enum::name).toList()), "clubs");
        configs.invalidate(CLUB); hosts.invalidate();
    }

    /**
     * T-16-08 (R-16-07): two registrations in a row on one ring. The first becomes `DISMANTLED` (replaced) in the same request,
     * `Ring.activeSetupId` names the second, `RingSetupChanged{ACTIVE, replacedSetupId}` is published and `RING_SETUP_REGISTERED`
     * audited. `expiresAt` = the start of the day `courses.setupAutoExpireDays` after the club-local day of registration, or of the
     * day after `expectedUntil` when that is earlier. `kind = EMPTY` leaves the ring «buida» and names no source.
     */
    @Test @AuditCovers(AuditAction.RING_SETUP_REGISTERED)
    void T_16_08_aSecondSetupReplacesTheActiveOneOfTheRingInTheSameTransaction() throws Exception {
        clock.setInstant(MONDAY);
        String course = createCourse("Jumping C–D");
        var first = register("INSTRUCTOR", course(A1, "JUMPING", course).put("notes", "Muntat per l'Estel"));
        assertThat(first.at("/setup/status").asText()).isEqualTo("ACTIVE");
        assertThat(first.at("/setup/view").asText()).isEqualTo("STAFF");
        assertThat(first.path("replacedSetupId").isNull()).isTrue();
        assertThat(first.at("/setup/builtAt").asText()).isEqualTo(MONDAY.toString());
        assertThat(first.at("/setup/builtByAccountId").asText()).isEqualTo("e9t2-INSTRUCTOR");
        // R-16-07's example: registered on Monday 5-10, it expires when Monday 12-10 starts in Madrid.
        assertThat(Instant.parse(first.at("/setup/expiresAt").asText())).isEqualTo(local("2026-10-12T00:00"));
        assertThat(ringDocument(A1).getString("activeSetupId")).isEqualTo(id(first));

        clock.advance(Duration.ofHours(2));
        var second = register("ADMIN", course(A1, "AGILITY", course).put("expectedUntil", "2026-10-07"));
        assertThat(second.path("replacedSetupId").asText()).isEqualTo(id(first));
        // «Fins quan?» 7-10 is the last day shown: earlier than the automatic end, so it ends when 8-10 starts.
        assertThat(Instant.parse(second.at("/setup/expiresAt").asText())).isEqualTo(local("2026-10-08T00:00"));
        var replaced = setupDocument(id(first));
        assertThat(replaced.getString("status")).isEqualTo("DISMANTLED");
        assertThat(replaced.getDate("dismantledAt").toInstant()).isEqualTo(clock.instant());
        assertThat(replaced.getString("dismantledByAccountId")).isEqualTo("e9t2-ADMIN");
        assertThat(ringDocument(A1).getString("activeSetupId")).isEqualTo(id(second));
        assertThat(active(A1)).extracting(d -> d.getString("_id")).containsExactly(id(second));
        // S05's own fields and lock are untouched by the pointer.
        assertThat(ringDocument(A1).get("version", Number.class).longValue()).isEqualTo(3);

        var events = changes();
        assertThat(events).hasSize(2);
        assertThat(events.get(1).get("payload", Document.class)).containsEntry("ringId", A1).containsEntry("setupId", id(second))
                .containsEntry("status", "ACTIVE").containsEntry("replacedSetupId", id(first)).containsEntry("kind", "AGILITY")
                .containsEntry("byAccountId", "e9t2-ADMIN");
        assertThat(events.get(0).get("payload", Document.class)).containsEntry("replacedSetupId", null).containsEntry("kind", "JUMPING");
        var audit = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("RING_SETUP_REGISTERED")).with(org.springframework.data.domain.Sort.by("at")),
                Document.class, "audit_entries");
        assertThat(audit).hasSize(2);
        assertThat(audit).extracting(a -> a.getString("entityType")).containsOnly("RingSetup");
        assertThat(audit.get(1).getString("entityId")).isEqualTo(id(second));
        assertThat(audit.get(1).get("details", Document.class)).containsEntry("replacedSetupId", id(first));

        // kind = EMPTY: the ring is empty now; it names no source (400) and a non-empty kind needs one (422).
        registerError(CLUB, "INSTRUCTOR", course(A1, "EMPTY", course), 400, "VALIDATION_ERROR");
        registerError(CLUB, "INSTRUCTOR", setupBody(A1, "FUN"), 422, "SETUP_SOURCE_REQUIRED");
        var empty = register("INSTRUCTOR", setupBody(A1, "EMPTY"));
        assertThat(empty.path("replacedSetupId").asText()).isEqualTo(id(second));
        assertThat(ringDocument(A1).getString("activeSetupId")).isEqualTo(id(empty));
        assertThat(active(A1)).hasSize(1);
        // A refused registration changed nothing: three setups, three events.
        assertThat(count("ring_setups", Query.query(Criteria.where("clubId").is(CLUB)))).isEqualTo(3);
        assertThat(changes()).hasSize(3);
    }

    /**
     * T-16-08 (R-16-07): «Sense geometria: `placementId` prohibit (`422 RING_WITHOUT_GEOMETRY`), `courseId`/imatge permesos»; a
     * placement of another ring is `422 PLACEMENT_RING_MISMATCH`; the photo is the club's own `COURSE_IMAGE` upload (R-16-16).
     * Tenant and roles: another club's ring, placement or file is `404`; a MEMBER cannot register; an INSTRUCTOR only while
     * `courses.allowInstructorPublish`; `expectedUntil` before today is refused.
     */
    @Test void T_16_08_aPlacementNeedsTheRingsGeometryAndOneOfItsOwnPlacements() throws Exception {
        clock.setInstant(MONDAY);
        String course = createCourse("Agility A");
        drawRing(A1);
        String placement = createPlacement("INSTRUCTOR", placementBody(course, A1), 201).path("id").asText();
        // A2 has no geometry: no placement, but a course or a photo.
        registerError(CLUB, "INSTRUCTOR", setupBody(A2, "AGILITY").put("placementId", placement), 422, "RING_WITHOUT_GEOMETRY");
        var withCourse = register("INSTRUCTOR", course(A2, "AGILITY", course));
        byte[] png = "fictional png".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String photo = upload(CLUB, "COURSE_IMAGE", "image/png", png, 204, Duration.ZERO);
        var withPhoto = register("INSTRUCTOR", setupBody(A2, "FUN").put("imageFileKey", photo));
        assertThat(withPhoto.at("/setup/imageFileKey").asText()).isEqualTo(photo);
        assertThat(withPhoto.at("/setup/imageUrl").asText()).isNotEmpty();
        assertThat(withPhoto.path("replacedSetupId").asText()).isEqualTo(id(withCourse));
        // The photo is bound to that setup: another setup cannot claim it again, and another club's key is not found.
        registerError(CLUB, "INSTRUCTOR", setupBody(A1, "FUN").put("imageFileKey", photo), 404, "NOT_FOUND");
        String foreign = upload(OTHER, "COURSE_IMAGE", "image/png", png, 204, Duration.ZERO);
        registerError(CLUB, "INSTRUCTOR", setupBody(A1, "FUN").put("imageFileKey", foreign), 404, "NOT_FOUND");
        // Now A2 has a geometry: A1's placement is another ring's.
        drawRing(A2);
        registerError(CLUB, "INSTRUCTOR", setupBody(A2, "AGILITY").put("placementId", placement), 422, "PLACEMENT_RING_MISMATCH");
        // With a placement, a courseId must be its course.
        String other = createCourse("Una altra");
        registerError(CLUB, "INSTRUCTOR", setupBody(A1, "AGILITY").put("placementId", placement).put("courseId", other), 400, "VALIDATION_ERROR");
        var placed = register("INSTRUCTOR", setupBody(A1, "AGILITY").put("placementId", placement).put("courseId", course));
        assertThat(placed.at("/setup/placementId").asText()).isEqualTo(placement);
        // «Fins quan?» cannot be a past day of the club (Sunday 4-10 in Madrid).
        registerError(CLUB, "INSTRUCTOR", course(A1, "AGILITY", course).put("expectedUntil", "2026-10-04"), 400, "VALIDATION_ERROR");
        // Tenant and roles.
        registerError(CLUB, "INSTRUCTOR", course(B1, "AGILITY", course), 404, "NOT_FOUND");
        registerError(OTHER, "ADMIN", course(A1, "AGILITY", course), 404, "NOT_FOUND");
        registerError(OTHER, "ADMIN", setupBody(B1, "AGILITY").put("placementId", placement), 404, "NOT_FOUND");
        registerError(CLUB, "MEMBER", course(A1, "AGILITY", course), 403, "FORBIDDEN");
        parameter("courses.allowInstructorPublish", false, "bool");
        registerError(CLUB, "INSTRUCTOR", course(A1, "AGILITY", course), 403, "FORBIDDEN");
        register("ADMIN", course(A1, "AGILITY", course));
        assertThat(active(A1)).hasSize(1);
        assertThat(active(A2)).extracting(d -> d.getString("_id")).containsExactly(id(withPhoto));
    }

    /**
     * T-16-08 and T-16-18 (R-16-08): a renewal gives an `ACTIVE` setup a new `expiresAt`, counted from today; dismantling ends it
     * (`Ring.activeSetupId = null`, `RingSetupChanged{DISMANTLED}`); an ended setup cannot be renewed or dismantled again
     * (`409 INVALID_STATE`). `GET /rings/{id}/setups` is the ring's history, newest first, the ended ones included.
     */
    @Test void T_16_08_T_16_18_aRenewalMovesTheExpiryAndADismantledSetupStaysInTheHistory() throws Exception {
        clock.setInstant(MONDAY);
        String course = createCourse("Agility B");
        String first = id(register("INSTRUCTOR", course(A1, "AGILITY", course)));
        clock.setInstant(local("2026-10-08T19:00"));
        // «Renova» on Thursday 8-10: seven days from Thursday, or the given last day.
        var renewed = act("INSTRUCTOR", first, "renewal", Map.of(), 200);
        assertThat(Instant.parse(renewed.path("expiresAt").asText())).isEqualTo(local("2026-10-15T00:00"));
        assertThat(renewed.path("status").asText()).isEqualTo("ACTIVE");
        renewed = act("INSTRUCTOR", first, "renewal", Map.of("expectedUntil", "2026-10-10"), 200);
        assertThat(Instant.parse(renewed.path("expiresAt").asText())).isEqualTo(local("2026-10-11T00:00"));
        assertThat(renewed.path("expectedUntil").asText()).isEqualTo("2026-10-10");
        assertThat(Instant.parse(setupDocument(first).getDate("expiresAt").toInstant().toString())).isEqualTo(local("2026-10-11T00:00"));
        error(as(json(post("/api/v1/ring-setups/" + first + "/renewal"), Map.of("expectedUntil", "2026-10-07")), CLUB, "INSTRUCTOR")
                .header("Idempotency-Key", UUID.randomUUID().toString()), 400, "VALIDATION_ERROR");
        assertThat(changes()).hasSize(1); // a renewal publishes nothing: the setup stays ACTIVE
        clock.advance(Duration.ofMinutes(5));
        String second = id(register("INSTRUCTOR", course(A1, "JUMPING", course)));
        clock.advance(Duration.ofMinutes(5));
        var dismantled = act("ADMIN", second, "dismantle", null, 200);
        assertThat(dismantled.path("status").asText()).isEqualTo("DISMANTLED");
        assertThat(dismantled.path("dismantledAt").asText()).isEqualTo(clock.instant().toString());
        assertThat(dismantled.path("dismantledByAccountId").asText()).isEqualTo("e9t2-ADMIN");
        assertThat(ringDocument(A1).get("activeSetupId")).isNull();
        assertThat(changes().getLast().get("payload", Document.class)).containsEntry("status", "DISMANTLED").containsEntry("setupId", second)
                .containsEntry("byAccountId", "e9t2-ADMIN");
        for (String ended : List.of(first, second)) {
            error(as(json(post("/api/v1/ring-setups/" + ended + "/renewal"), Map.of()), CLUB, "INSTRUCTOR").header("Idempotency-Key", UUID.randomUUID().toString()), 409, "INVALID_STATE");
            error(as(json(post("/api/v1/ring-setups/" + ended + "/dismantle"), Map.of()), CLUB, "INSTRUCTOR").header("Idempotency-Key", UUID.randomUUID().toString()), 409, "INVALID_STATE");
        }
        // The history: newest first, both ended statuses, paged.
        var history = ok(as(get("/api/v1/rings/" + A1 + "/setups"), CLUB, "INSTRUCTOR"), 200);
        assertThat(history.path("totalItems").asLong()).isEqualTo(2);
        assertThat(history.path("items")).extracting(item -> item.path("id").asText()).containsExactly(second, first);
        assertThat(history.path("items")).extracting(item -> item.path("status").asText()).containsExactly("DISMANTLED", "DISMANTLED");
        var page = ok(as(get("/api/v1/rings/" + A1 + "/setups").param("page", "1").param("size", "1"), CLUB, "ADMIN"), 200);
        assertThat(page.path("items")).extracting(item -> item.path("id").asText()).containsExactly(first);
        assertThat(ok(as(get("/api/v1/rings/" + A2 + "/setups"), CLUB, "ADMIN"), 200).path("items")).isEmpty();
        // Tenant and roles.
        error(as(get("/api/v1/rings/" + A1 + "/setups"), OTHER, "ADMIN"), 404, "NOT_FOUND");
        error(as(get("/api/v1/rings/" + A1 + "/setups"), CLUB, "MEMBER"), 403, "FORBIDDEN");
        error(as(json(post("/api/v1/ring-setups/" + first + "/renewal"), Map.of()), OTHER, "ADMIN").header("Idempotency-Key", UUID.randomUUID().toString()), 404, "NOT_FOUND");
        error(as(json(post("/api/v1/ring-setups/" + first + "/dismantle"), Map.of()), CLUB, "MEMBER").header("Idempotency-Key", UUID.randomUUID().toString()), 403, "FORBIDDEN");
    }

    /**
     * T-16-08, T-16-18 and S15 T-15-23 (R-16-08, R-15-15 f): P5 at 06:00 on Monday 12-10 expires the setup registered on Monday
     * 5-10 (`EXPIRED`, `Ring.activeSetupId = null`, `RingSetupChanged{EXPIRED}` by the system), once: a second run finds nothing.
     * A dry run reports `WOULD_EXPIRE_SETUP` and writes nothing; the day before nothing lapses; another ring's later setup stays;
     * an expired setup cannot be renewed (`409`). With `COURSES` off the step is skipped (T-16-12).
     */
    @Test void T_16_08_T_15_23_p5ExpiresALapsedSetupOnceByTheClubsClock() throws Exception {
        clock.setInstant(MONDAY);
        String course = createCourse("Agility C");
        String lapsing = id(register("INSTRUCTOR", course(A1, "AGILITY", course)));
        clock.setInstant(local("2026-10-07T10:00"));
        String later = id(register("INSTRUCTOR", course(A2, "AGILITY", course)));
        var dayBefore = expirations(local("2026-10-11T06:00"), false);
        assertThat(counter(dayBefore, "expiredSetups")).isZero();
        assertThat(setupDocument(lapsing).getString("status")).isEqualTo("ACTIVE");
        var dry = expirations(local("2026-10-12T06:00"), true);
        assertThat(dry.items()).filteredOn(i -> i.action().contains("SETUP")).extracting(JobRun.Item::action, JobRun.Item::entityId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("WOULD_EXPIRE_SETUP", lapsing));
        assertThat(setupDocument(lapsing).getString("status")).isEqualTo("ACTIVE");
        var run = expirations(local("2026-10-12T06:00"), false);
        assertThat(counter(run, "expiredSetups")).isEqualTo(1);
        assertThat(run.items()).filteredOn(i -> i.action().contains("SETUP")).extracting(JobRun.Item::action).containsExactly("EXPIRE_SETUP");
        var expired = setupDocument(lapsing);
        assertThat(expired.getString("status")).isEqualTo("EXPIRED");
        assertThat(expired.get("dismantledAt")).isNull();
        assertThat(ringDocument(A1).get("activeSetupId")).isNull();
        assertThat(ringDocument(A2).getString("activeSetupId")).isEqualTo(later);
        var event = changes().getLast();
        assertThat(event.get("payload", Document.class)).containsEntry("status", "EXPIRED").containsEntry("setupId", lapsing).containsEntry("byAccountId", null);
        assertThat(event.getString("origin")).isEqualTo("SYSTEM");
        // Idempotent by state: the next run (and a late one) expires nothing more.
        assertThat(counter(expirations(local("2026-10-12T06:05"), false), "expiredSetups")).isZero();
        assertThat(changes().stream().filter(e -> "EXPIRED".equals(e.get("payload", Document.class).getString("status")))).hasSize(1);
        error(as(json(post("/api/v1/ring-setups/" + lapsing + "/renewal"), Map.of()), CLUB, "INSTRUCTOR").header("Idempotency-Key", UUID.randomUUID().toString()), 409, "INVALID_STATE");
        // The history keeps it, marked EXPIRED.
        assertThat(ok(as(get("/api/v1/rings/" + A1 + "/setups"), CLUB, "INSTRUCTOR"), 200).at("/items/0/status").asText()).isEqualTo("EXPIRED");
        // T-16-12: COURSES off → P5 omits step f, even for a lapsed setup.
        modules(Arrays.stream(Module.values()).filter(m -> m != Module.COURSES).toList());
        var off = expirations(local("2026-10-15T06:00"), false);
        assertThat(off.items()).noneMatch(i -> i.action().contains("SETUP"));
        assertThat(setupDocument(later).getString("status")).isEqualTo("ACTIVE");
    }

    /**
     * T-16-09 (R-16-09): `GET /me/ring-setups` gives a member, for each ring, the active setup's summary (`levelLabel` in level
     * order, `hasViewer`, the photo as `thumbnailUrl`) or `setup = null`, never who built it, its notes or the inventory;
     * `GET /ring-setups/{id}` projects a member's view (`MEMBER`) and hides ended setups from them. With
     * `courses.showSetupToMembers = false` the member's setups are absent (`setup = null`, `404`) and the instructor's present.
     */
    @Test void T_16_09_aMemberSeesTheSummaryWithoutTheBuilderNotesOrEndedSetups() throws Exception {
        clock.setInstant(MONDAY);
        String course = createCourse("Agility D");
        String mounted = id(register("INSTRUCTOR", course(A1, "AGILITY", course).put("notes", "Nota privada").putPOJO("levelIds", List.of("e9t2-level-aB", "e9t2-level-aA"))));
        clock.advance(Duration.ofDays(2));
        var list = mine("MEMBER");
        assertThat(list).extracting(item -> item.path("ringId").asText()).containsExactly(A1, A2);
        var summary = list.get(0).path("setup");
        assertThat(summary.path("id").asText()).isEqualTo(mounted);
        assertThat(summary.path("kind").asText()).isEqualTo("AGILITY");
        assertThat(summary.path("levelLabel").asText()).isEqualTo("A–B");
        assertThat(summary.path("builtAt").asText()).isEqualTo(MONDAY.toString());
        assertThat(summary.path("hasViewer").asBoolean()).isTrue();
        assertThat(list.get(1).path("setup").isNull()).isTrue();
        assertThat(list.toString()).doesNotContain("builtByAccountId", "e9t2-INSTRUCTOR", "Nota privada", "notes", "inventory");
        // ringIds: the given rings in that order; another club's ring is not found.
        assertThat(mine("MEMBER", A2, A1)).extracting(item -> item.path("ringId").asText()).containsExactly(A2, A1);
        error(as(get("/api/v1/me/ring-setups").param("ringIds", B1), CLUB, "MEMBER"), 404, "NOT_FOUND");
        // The detail per role.
        var member = setup(mounted, "MEMBER");
        assertThat(member.path("view").asText()).isEqualTo("MEMBER");
        assertThat(member.has("builtByAccountId")).isFalse();
        assertThat(member.has("notes")).isFalse();
        assertThat(member.has("dismantledByAccountId")).isFalse();
        var staff = setup(mounted, "INSTRUCTOR");
        assertThat(staff.path("view").asText()).isEqualTo("STAFF");
        assertThat(staff.path("notes").asText()).isEqualTo("Nota privada");
        assertThat(staff.path("builtByAccountId").asText()).isEqualTo("e9t2-INSTRUCTOR");
        error(as(get("/api/v1/ring-setups/" + mounted), OTHER, "MEMBER"), 404, "NOT_FOUND");
        // A photo: the summary's thumbnail, no viewer.
        byte[] png = "fictional png".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String photo = id(register("INSTRUCTOR", setupBody(A2, "FUN").put("imageFileKey", upload(CLUB, "COURSE_IMAGE", "image/png", png, 204, Duration.ZERO))));
        var withPhoto = mine("MEMBER", A2).get(0).path("setup");
        assertThat(withPhoto.path("id").asText()).isEqualTo(photo);
        assertThat(withPhoto.path("thumbnailUrl").asText()).isNotEmpty();
        assertThat(withPhoto.path("hasViewer").asBoolean()).isFalse();
        assertThat(withPhoto.path("levelLabel").isNull()).isTrue();
        // An ended setup: gone from the member's list and detail, still the staff's.
        act("INSTRUCTOR", photo, "dismantle", null, 200);
        assertThat(mine("MEMBER", A2).get(0).path("setup").isNull()).isTrue();
        error(as(get("/api/v1/ring-setups/" + photo), CLUB, "MEMBER"), 404, "NOT_FOUND");
        assertThat(setup(photo, "INSTRUCTOR").path("status").asText()).isEqualTo("DISMANTLED");
        // showSetupToMembers = false: absent for the member, present for the instructor (T-09-22).
        parameter("courses.showSetupToMembers", false, "bool");
        assertThat(mine("MEMBER")).allSatisfy(item -> assertThat(item.path("setup").isNull()).isTrue());
        assertThat(mine("INSTRUCTOR").get(0).at("/setup/id").asText()).isEqualTo(mounted);
        error(as(get("/api/v1/ring-setups/" + mounted), CLUB, "MEMBER"), 404, "NOT_FOUND");
        assertThat(setup(mounted, "INSTRUCTOR").path("id").asText()).isEqualTo(mounted);
    }

    /**
     * R-16-16 (E9-T03, the organizer note on `getCourse`): a MEMBER reads a `CLUB` course only while an `ACTIVE` setup of their
     * club shows it, directly or through its placement, and `courses.showSetupToMembers` is on; otherwise `COURSE_NOT_VISIBLE`
     * (`NOT_FOUND` while hidden, and for another club's member). The setup never widens an instructor's write.
     */
    @Test void T_16_05_aMemberReadsAClubCourseOnlyWhileAVisibleSetupShowsIt() throws Exception {
        clock.setInstant(MONDAY);
        String course = createCourse("Agility E");
        error(as(get("/api/v1/courses/" + course), CLUB, "MEMBER"), 404, "COURSE_NOT_VISIBLE");
        String direct = id(register("INSTRUCTOR", course(A1, "AGILITY", course)));
        assertThat(ok(as(get("/api/v1/courses/" + course), CLUB, "MEMBER"), 200).path("id").asText()).isEqualTo(course);
        error(as(get("/api/v1/courses/" + course), OTHER, "MEMBER"), 404, "NOT_FOUND");
        parameter("courses.showSetupToMembers", false, "bool");
        error(as(get("/api/v1/courses/" + course), CLUB, "MEMBER"), 404, "NOT_FOUND");
        mongo.remove(Query.query(Criteria.where("_id").regex("^e9t2-param-")), "parameters"); configs.invalidate(CLUB);
        act("INSTRUCTOR", direct, "dismantle", null, 200);
        error(as(get("/api/v1/courses/" + course), CLUB, "MEMBER"), 404, "COURSE_NOT_VISIBLE");
        // Through its placement.
        drawRing(A1);
        String placement = createPlacement("INSTRUCTOR", placementBody(course, A1), 201).path("id").asText();
        error(as(get("/api/v1/courses/" + course), CLUB, "MEMBER"), 404, "COURSE_NOT_VISIBLE");
        register("INSTRUCTOR", setupBody(A1, "AGILITY").put("placementId", placement));
        assertThat(ok(as(get("/api/v1/courses/" + course), CLUB, "MEMBER"), 200).path("id").asText()).isEqualTo(course);
        // Reading is all it grants: a member still cannot list or write courses.
        error(as(get("/api/v1/courses"), CLUB, "MEMBER"), 403, "FORBIDDEN");
        error(as(json(patch("/api/v1/courses/" + course), Map.of("version", 0, "notes", "x")), CLUB, "MEMBER"), 403, "FORBIDDEN");
    }

    /**
     * T-16-12 (R-16-15): with `levels.enabled = false` a setup takes no club level (`422 LEVELS_DISABLED`, CATALEG_ERRORS §1),
     * only `agilityhubLevel`, which is then its `levelLabel`; with levels on, another club's level is `404`.
     */
    @Test void T_16_12_withoutLevelsASetupTakesOnlyTheAgilityHubLevel() throws Exception {
        clock.setInstant(MONDAY);
        String course = createCourse("Agility F");
        registerError(CLUB, "INSTRUCTOR", course(A1, "AGILITY", course).putPOJO("levelIds", List.of("e9t2-level-bA")), 404, "NOT_FOUND");
        parameter("levels.enabled", false, "bool");
        registerError(CLUB, "INSTRUCTOR", course(A1, "AGILITY", course).putPOJO("levelIds", List.of("e9t2-level-aA")), 422, "LEVELS_DISABLED");
        assertThat(count("ring_setups", Query.query(Criteria.where("clubId").is(CLUB)))).isZero();
        String mounted = id(register("INSTRUCTOR", course(A1, "AGILITY", course).put("agilityhubLevel", "MEDIUM")));
        assertThat(setup(mounted, "INSTRUCTOR").path("levelIds")).isEmpty();
        assertThat(mine("MEMBER", A1).get(0).at("/setup/levelLabel").asText()).isEqualTo("MEDIUM");
    }

    /**
     * R-16-07 under concurrency: registrations racing on one ring never leave two `ACTIVE` setups or a pointer to an ended
     * one; each answers `201` or `409 STALE_VERSION` (never a `500`), and the ring points to the only active setup.
     */
    @Test void T_16_08_concurrentRegistrationsOnOneRingKeepOneActiveSetup() throws Exception {
        clock.setInstant(MONDAY);
        String course = createCourse("Agility G");
        var pool = Executors.newFixedThreadPool(4);
        try {
            var calls = new ArrayList<Callable<Integer>>();
            for (int i = 0; i < 8; i++) {
                calls.add(() -> mvc.perform(as(json(post("/api/v1/ring-setups"), course(A1, "AGILITY", course)), CLUB, "INSTRUCTOR")
                        .header("Idempotency-Key", UUID.randomUUID().toString())).andReturn().getResponse().getStatus());
            }
            var statuses = new ArrayList<Integer>();
            for (var future : pool.invokeAll(calls)) { statuses.add(future.get()); }
            assertThat(statuses).allMatch(status -> status == 201 || status == 409).contains(201);
        } finally { pool.shutdownNow(); }
        var actives = active(A1);
        assertThat(actives).hasSize(1);
        assertThat(ringDocument(A1).getString("activeSetupId")).isEqualTo(actives.getFirst().getString("_id"));
        long created = changes().size();
        assertThat(count("ring_setups", Query.query(Criteria.where("clubId").is(CLUB)))).isEqualTo(created);
    }

    /**
     * S09 R-09-15 across the module boundary (E9-T03): the training context's {@link RingSetupPort} is S16's adapter. It
     * answers the club's `ACTIVE` setup with its kind and level names in the asked language (level order), and nothing for an
     * ended setup or another club's.
     */
    @Test void T_09_22_theTrainingGridReadsTheActiveSetupThroughItsPort() throws Exception {
        clock.setInstant(MONDAY);
        String course = createCourse("Agility H");
        String mounted = id(register("INSTRUCTOR", course(A1, "OBSTACLE_DRILL", course).putPOJO("levelIds", List.of("e9t2-level-aB", "e9t2-level-aA"))
                .put("expectedUntil", "2026-10-09")));
        try (var tenant = TenantContext.open(CLUB)) {
            var setup = trainingPort.active(mounted, Locale.forLanguageTag("ca")).orElseThrow();
            assertThat(setup.kind()).isEqualTo("OBSTACLE_DRILL");
            assertThat(setup.levelNames()).containsExactly("Nivell A", "Nivell B");
            assertThat(setup.builtAt()).isEqualTo(MONDAY);
            assertThat(setup.expectedUntil()).hasToString("2026-10-09");
        }
        try (var tenant = TenantContext.open(OTHER)) { assertThat(trainingPort.active(mounted, Locale.ROOT)).isEmpty(); }
        act("INSTRUCTOR", mounted, "dismantle", null, 200);
        try (var tenant = TenantContext.open(CLUB)) { assertThat(trainingPort.active(mounted, Locale.ROOT)).isEmpty(); }
    }

    /**
     * S16 §7 (consumed): S05's `RingChanged` that deactivates a ring dismantles its active setup by the system
     * (`RingSetupChanged{DISMANTLED}`, `Ring.activeSetupId = null`); any other ring change leaves it.
     */
    /**
     * INC-62 point 9 (E11-T02; E9-W03 request, decision E119): a dismantled setup says why — `REPLACED` (with `replacedBySetupId`),
     * `MANUAL` or `RING_INACTIVE` — so D16 does not infer «Substituït» from the instants; an expired or active one says nothing.
     */
    @Test void T_16_08_INC_62_aDismantledSetupTellsWhyAndWhichSetupReplacedIt() throws Exception {
        clock.setInstant(MONDAY);
        String course = createCourse("Agility R");
        String first = id(register("INSTRUCTOR", course(A1, "AGILITY", course)));
        assertThat(setupDocument(first).get("dismantledReason")).isNull();
        clock.advance(Duration.ofMinutes(5));
        String second = id(register("INSTRUCTOR", course(A1, "JUMPING", course)));
        var replaced = ok(as(get("/api/v1/ring-setups/" + first), CLUB, "INSTRUCTOR"), 200);
        assertThat(replaced.path("status").asText()).isEqualTo("DISMANTLED");
        assertThat(replaced.path("dismantledReason").asText()).isEqualTo("REPLACED");
        assertThat(replaced.path("replacedBySetupId").asText()).isEqualTo(second);
        assertThat(ok(as(get("/api/v1/ring-setups/" + second), CLUB, "INSTRUCTOR"), 200).path("dismantledReason").isNull()).isTrue();
        clock.advance(Duration.ofMinutes(5));
        var manual = act("ADMIN", second, "dismantle", null, 200);
        assertThat(manual.path("dismantledReason").asText()).isEqualTo("MANUAL"); assertThat(manual.path("replacedBySetupId").isNull()).isTrue();
        clock.advance(Duration.ofMinutes(5));
        String third = id(register("INSTRUCTOR", course(A1, "AGILITY", course)));
        outbox.dispatch();
        clock.advance(Duration.ofMinutes(1));
        ok(as(json(patch("/api/v1/rings/" + A1), Map.of("version", ringDocument(A1).get("version"), "active", false)), CLUB, "ADMIN"), 200);
        outbox.dispatch(); outbox.dispatch();
        assertThat(setupDocument(third).getString("dismantledReason")).isEqualTo("RING_INACTIVE");
        assertThat(setupDocument(third).get("replacedBySetupId")).isNull();
        var history = ok(as(get("/api/v1/rings/" + A1 + "/setups"), CLUB, "INSTRUCTOR"), 200).path("items");
        var reasons = new java.util.ArrayList<String>(); history.forEach(item -> reasons.add(item.path("id").asText() + ":" + item.path("dismantledReason").asText()));
        assertThat(reasons).containsExactly(third + ":RING_INACTIVE", second + ":MANUAL", first + ":REPLACED");
    }

    @Test void T_16_08_deactivatingARingDismantlesItsActiveSetup() throws Exception {
        clock.setInstant(MONDAY);
        String course = createCourse("Agility I");
        String mounted = id(register("INSTRUCTOR", course(A1, "AGILITY", course)));
        outbox.dispatch();
        ok(as(json(patch("/api/v1/rings/" + A1), Map.of("version", 3, "name", "Ring A1 nou")), CLUB, "ADMIN"), 200);
        outbox.dispatch();
        assertThat(setupDocument(mounted).getString("status")).isEqualTo("ACTIVE");
        // A later instant, so the events read in order (`occurredAt`, then a random `_id`).
        clock.advance(Duration.ofMinutes(1));
        ok(as(json(patch("/api/v1/rings/" + A1), Map.of("version", 4, "active", false)), CLUB, "ADMIN"), 200);
        outbox.dispatch(); outbox.dispatch();
        var ended = setupDocument(mounted);
        assertThat(ended.getString("status")).isEqualTo("DISMANTLED");
        assertThat(ended.get("dismantledByAccountId")).isNull();
        assertThat(ringDocument(A1).get("activeSetupId")).isNull();
        assertThat(ringDocument(A1).getBoolean("active")).isFalse();
        var events = changes();
        assertThat(events.getLast().get("payload", Document.class)).containsEntry("status", "DISMANTLED").containsEntry("setupId", mounted);
        assertThat(events.getLast().getString("origin")).isEqualTo("SYSTEM");
        // Redelivery changes nothing more.
        outbox.dispatch();
        assertThat(changes()).hasSize(events.size());
    }

    /**
     * E115 (write skew): a setup that shows a course whose deletion is in flight (`DELETE /courses/{id}` checks that no active setup
     * shows it, then writes the course) writes the course too, so the two meet: the registration is `409 STALE_VERSION` and the
     * ring keeps no setup. The same through a placement of the course.
     */
    @Test void T_16_08_aSetupRacingTheDeletionOfItsCourseIsStale() throws Throwable {
        clock.setInstant(MONDAY);
        String course = createCourse("Agility L");
        drawRing(A1);
        String placement = createPlacement("INSTRUCTOR", placementBody(course, A1), 201).path("id").asText();
        for (var body : List.of(course(A1, "AGILITY", course), setupBody(A1, "AGILITY").put("placementId", placement))) {
            try (var session = client.startSession()) {
                session.startTransaction();
                client.getDatabase(mongo.getDb().getName()).getCollection("courses")
                        .updateOne(session, com.mongodb.client.model.Filters.eq("_id", course), com.mongodb.client.model.Updates.set("deletedAt", java.util.Date.from(MONDAY)));
                try { registerError(CLUB, "INSTRUCTOR", body, 409, "STALE_VERSION"); } finally { session.abortTransaction(); }
            }
        }
        assertThat(count("ring_setups", Query.query(Criteria.where("clubId").is(CLUB)))).isZero();
        assertThat(ringDocument(A1).get("activeSetupId")).isNull();
        register("INSTRUCTOR", course(A1, "AGILITY", course));
        error(as(delete("/api/v1/courses/" + course).param("version", "0"), CLUB, "ADMIN"), 409, "COURSE_IN_USE");
    }

    /**
     * E117 point 4 (E9-T03 review #7): a setup from a placement whose course was deleted answers `404` (as a build session on it) and
     * writes nothing; R-16-09's `hasViewer` comes from the live course, so a setup whose placed course is gone has no viewer.
     */
    @Test void T_16_08_T_16_09_aPlacementWhoseCourseIsDeletedNeitherRegistersNorHasAViewer() throws Exception {
        clock.setInstant(MONDAY);
        drawRing(A1);
        String shown = createCourse("Agility V"), gone = createCourse("Agility W");
        String placed = createPlacement("INSTRUCTOR", placementBody(shown, A1), 201).path("id").asText();
        String orphan = createPlacement("INSTRUCTOR", placementBody(gone, A1), 201).path("id").asText();
        String setup = id(register("INSTRUCTOR", setupBody(A1, "AGILITY").put("placementId", placed)));
        assertThat(mine("MEMBER", A1).at("/0/setup/hasViewer").asBoolean()).isTrue();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(gone)), new Update().set("deletedAt", java.util.Date.from(MONDAY)), "courses");
        registerError(CLUB, "INSTRUCTOR", setupBody(A1, "AGILITY").put("placementId", orphan), 404, "NOT_FOUND");
        assertThat(count("ring_setups", Query.query(Criteria.where("clubId").is(CLUB)))).isEqualTo(1);
        assertThat(ringDocument(A1).getString("activeSetupId")).isEqualTo(setup);
        // The active setup's own course deleted behind the api's back: the member's summary no longer offers the viewer.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(shown)), new Update().set("deletedAt", java.util.Date.from(MONDAY)), "courses");
        assertThat(mine("MEMBER", A1).at("/0/setup/hasViewer").asBoolean()).isFalse();
    }

    /**
     * INC-62 point 2 (E11-T02; decision E119, R-16-16): registering a setup from a placement checks that the caller may read its
     * course, as a build session's start does. An INSTRUCTOR who cannot read a colleague's `PRIVATE` course gets `404
     * COURSE_NOT_VISIBLE` and no setup; its author registers it.
     */
    @Test void T_16_08_INC_62_aSetupFromAColleaguesPrivateCourseIsCourseNotVisible() throws Exception {
        clock.setInstant(MONDAY);
        drawRing(A1);
        var body = course("Agility privat", model(3, false)).put("visibility", "PRIVATE");
        String course = ok(as(json(post("/api/v1/courses"), body), CLUB, "INSTRUCTOR"), 201).path("id").asText();
        String placed = createPlacement("INSTRUCTOR", placementBody(course, A1), 201).path("id").asText();
        error(as(json(post("/api/v1/ring-setups"), setupBody(A1, "AGILITY").put("placementId", placed)), CLUB, "INSTRUCTOR", "e9t2-colleague")
                .header("Idempotency-Key", UUID.randomUUID().toString()), 404, "COURSE_NOT_VISIBLE");
        assertThat(active(A1)).isEmpty();
        assertThat(id(register("INSTRUCTOR", setupBody(A1, "AGILITY").put("placementId", placed)))).isNotBlank();
    }

    /**
     * E117 (E9-T03 review #1): a dismantle or renewal that races another write of the setup (P5 f's `expire`, a replacement, the
     * ring's deactivation) or of its ring's `activeSetupId` meets it as a Mongo write conflict, answered `409 STALE_VERSION` (the
     * client reads again), never a `500`; the setup and the ring stay as the other writer leaves them.
     */
    @Test void T_16_08_T_16_18_aDismantleOrRenewalRacingAnotherWriteOfTheSetupIsStale() throws Throwable {
        clock.setInstant(MONDAY);
        String setup = id(register("INSTRUCTOR", course(A1, "AGILITY", createCourse("Agility M"))));
        // A renewal at the registration's instant would replace the setup with identical bytes, which Mongo skips (no write, no conflict).
        clock.advance(Duration.ofHours(1));
        var database = client.getDatabase(mongo.getDb().getName());
        String[][] races = {{"ring_setups", setup, "dismantle"}, {"ring_setups", setup, "renewal"}, {"rings", A1, "dismantle"}};
        for (String[] race : races) {
            try (var session = client.startSession()) {
                session.startTransaction();
                database.getCollection(race[0]).updateOne(session, com.mongodb.client.model.Filters.eq("_id", race[1]),
                        com.mongodb.client.model.Updates.set("updatedByAccountId", "e9t2-racer"));
                try { error(as(json(post("/api/v1/ring-setups/" + setup + "/" + race[2]), Map.of()), CLUB, "INSTRUCTOR")
                        .header("Idempotency-Key", UUID.randomUUID().toString()), 409, "STALE_VERSION"); }
                finally { session.abortTransaction(); }
            }
        }
        assertThat(setupDocument(setup).getString("status")).isEqualTo("ACTIVE");
        assertThat(ringDocument(A1).getString("activeSetupId")).isEqualTo(setup);
        assertThat(act("INSTRUCTOR", setup, "dismantle", null, 200).path("status").asText()).isEqualTo("DISMANTLED");
    }

    /**
     * R-16-07 with §6's `Idempotency-Key`: a retried registration (same key, same body) answers the first one's `201` again and
     * never replaces the setup it created; a new key is a new registration.
     */
    @Test void T_16_08_aRetriedRegistrationIsReplayedNotRepeated() throws Exception {
        clock.setInstant(MONDAY);
        String course = createCourse("Agility K"), key = UUID.randomUUID().toString();
        var first = ok(as(json(post("/api/v1/ring-setups"), course(A1, "AGILITY", course)), CLUB, "INSTRUCTOR").header("Idempotency-Key", key), 201);
        var replay = ok(as(json(post("/api/v1/ring-setups"), course(A1, "AGILITY", course)), CLUB, "INSTRUCTOR").header("Idempotency-Key", key), 201);
        assertThat(replay).isEqualTo(first);
        assertThat(active(A1)).extracting(d -> d.getString("_id")).containsExactly(id(first));
        assertThat(count("ring_setups", Query.query(Criteria.where("clubId").is(CLUB)))).isEqualTo(1);
        assertThat(changes()).hasSize(1);
        assertThat(register("INSTRUCTOR", course(A1, "AGILITY", course)).path("replacedSetupId").asText()).isEqualTo(id(first));
    }

    /**
     * S06 §2 (10/23: the course icon on the ring's column) across the module boundary: the day grid's `activeSetupId` is the
     * pointer R-16-07 writes and R-16-08 clears; with `COURSES` off the column omits it (R-16-15).
     */
    @Test void T_16_08_theDayGridColumnNamesTheRingsActiveSetup() throws Exception {
        clock.setInstant(MONDAY);
        String course = createCourse("Agility J");
        String mounted = id(register("INSTRUCTOR", course(A1, "AGILITY", course)));
        var columns = ok(as(get("/api/v1/day-grid").param("date", "2026-10-05").param("view", "instructor"), CLUB, "INSTRUCTOR"), 200).path("columns");
        assertThat(columns).filteredOn(c -> c.path("ringId").asText().equals(A1)).singleElement().satisfies(c -> assertThat(c.path("activeSetupId").asText()).isEqualTo(mounted));
        assertThat(columns).filteredOn(c -> c.path("ringId").asText().equals(A2)).singleElement().satisfies(c -> assertThat(c.hasNonNull("activeSetupId")).isFalse());
        act("INSTRUCTOR", mounted, "dismantle", null, 200);
        columns = ok(as(get("/api/v1/day-grid").param("date", "2026-10-05").param("view", "instructor"), CLUB, "INSTRUCTOR"), 200).path("columns");
        assertThat(columns).filteredOn(c -> c.path("ringId").asText().equals(A1)).singleElement().satisfies(c -> assertThat(c.hasNonNull("activeSetupId")).isFalse());
        id(register("INSTRUCTOR", course(A1, "AGILITY", course)));
        modules(Arrays.stream(Module.values()).filter(m -> m != Module.COURSES).toList());
        columns = ok(as(get("/api/v1/day-grid").param("date", "2026-10-05").param("view", "instructor"), CLUB, "INSTRUCTOR"), 200).path("columns");
        assertThat(columns).isNotEmpty().allSatisfy(c -> assertThat(c.has("activeSetupId")).isFalse());
    }

    // ---- E9-T07: the reads of the members' app (E9-W04's review) ----

    /** A fictional account of the club with {@code role}, named {@code name}: the builder `as(…, role)` signs in as. */
    void builder(String club, String role, String name) {
        String id = "e9t2-" + role;
        mongo.insert(new com.agilityhub.core.identity.persistence.Account(id, "e9t2-" + club + "-" + role.toLowerCase(Locale.ROOT) + "@example.test", name, "ca", null, Set.of(),
                com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, new com.agilityhub.core.identity.persistence.Account.Security(0, null, null, 0),
                Map.of(), false, clock.instant()));
        var granted = com.agilityhub.core.identity.domain.Role.valueOf(role);
        mongo.insert(new com.agilityhub.core.identity.persistence.Membership("e9t2-membership-" + club + "-" + role, id, club, null, Set.of(granted),
                com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, granted));
    }
    /** A build session on {@code placementId}, started and finished by {@code role} (R-16-11): the id of the setup its `finish` registered. */
    String built(String placementId, String role) throws Exception {
        String session = ok(as(json(post("/api/v1/build-sessions"), Map.of("placementId", placementId, "live", false)), CLUB, role)
                .header("Idempotency-Key", UUID.randomUUID().toString()), 201).path("id").asText();
        return ok(as(post("/api/v1/build-sessions/" + session + "/finish").header("Idempotency-Key", UUID.randomUUID().toString()), CLUB, role), 200)
                .path("setupId").asText();
    }

    /**
     * E9-T07 step 1 (R-16-16, R-16-09; E9-W04 question 1): the setup a build session's `finish` registers names only its placement.
     * The member's projection carries the course it shows (`courseId`, the placement's) and where it stands on the ring (`placement`:
     * the transform, the resolved obstacles and the ring's size), so the members' viewer draws it in the ring's frame; the member reads
     * that course through it, and still no placement. A setup without a placement has no `placement`. With
     * `courses.showSetupToMembers = false` the member reads nothing.
     */
    @Test void T_16_09_T_16_16_aMemberReadsTheCourseOfASetupABuildSessionRegistered() throws Exception {
        clock.setInstant(MONDAY);
        drawRing(A1);
        String course = createCourse("Agility P");
        String placement = createPlacement("INSTRUCTOR", placementBody(course, A1), 201).path("id").asText();
        String setup = built(placement, "INSTRUCTOR");
        assertThat(setupDocument(setup).getString("placementId")).isEqualTo(placement);
        assertThat(setupDocument(setup).get("courseId")).as("the setup stores only its placement").isNull();
        var member = setup(setup, "MEMBER");
        assertThat(member.path("view").asText()).isEqualTo("MEMBER");
        assertThat(member.path("placementId").asText()).isEqualTo(placement);
        assertThat(member.path("courseId").asText()).isEqualTo(course);
        var placed = member.path("placement");
        assertThat(placed.at("/transform/offsetXM").asDouble()).isEqualTo(2);
        assertThat(placed.at("/transform/offsetYM").asDouble()).isEqualTo(1);
        assertThat(placed.at("/transform/rotationDeg").asDouble()).isZero();
        assertThat(placed.at("/transform/flipX").asBoolean()).isFalse();
        assertThat(placed.at("/transform/flipY").asBoolean()).isFalse();
        assertThat(placed.path("resolvedObstacles")).hasSize(1);
        var obstacle = placed.at("/resolvedObstacles/0");
        assertThat(obstacle.path("obstacleId").asText()).isEqualTo("obstacle-0");
        assertThat(obstacle.path("type").asText()).isEqualTo("Jump");
        assertThat(obstacle.path("x").asDouble()).isEqualTo(14);
        assertThat(obstacle.path("y").asDouble()).isEqualTo(4);
        assertThat(obstacle.path("numbers")).extracting(JsonNode::asInt).containsExactly(4);
        assertThat(placed.path("ringLengthM").asDouble()).isEqualTo(40);
        assertThat(placed.path("ringWidthM").asDouble()).isEqualTo(22);
        assertThat(placed.has("warnings")).as("the placement's warnings stay the staff's").isFalse();
        assertThat(ok(as(get("/api/v1/courses/" + member.path("courseId").asText()), CLUB, "MEMBER"), 200).path("id").asText()).isEqualTo(course);
        assertThat(mine("MEMBER", A1).at("/0/setup/hasViewer").asBoolean()).isTrue();
        // R-16-16 stays the limit: the placement itself is the staff's; the staff's projection is what was registered.
        error(as(get("/api/v1/placements/" + placement), CLUB, "MEMBER"), 403, "FORBIDDEN");
        var staff = setup(setup, "INSTRUCTOR");
        assertThat(staff.path("view").asText()).isEqualTo("STAFF");
        assertThat(staff.path("courseId").isNull()).isTrue();
        assertThat(staff.has("placement")).isFalse();
        // A setup of a library course (no placement): its own course, no placement.
        String plain = id(register("INSTRUCTOR", course(A2, "AGILITY", course)));
        var unplaced = setup(plain, "MEMBER");
        assertThat(unplaced.path("courseId").asText()).isEqualTo(course);
        assertThat(unplaced.path("placement").isNull()).isTrue();
        // Another club's member reads nothing of it.
        error(as(get("/api/v1/ring-setups/" + setup), OTHER, "MEMBER"), 404, "NOT_FOUND");
        // showSetupToMembers = false: nothing for the member.
        parameter("courses.showSetupToMembers", false, "bool");
        error(as(get("/api/v1/ring-setups/" + setup), CLUB, "MEMBER"), 404, "NOT_FOUND");
        error(as(get("/api/v1/courses/" + course), CLUB, "MEMBER"), 404, "NOT_FOUND");
        assertThat(mine("MEMBER", A1).at("/0/setup").isNull()).isTrue();
        assertThat(setup(setup, "INSTRUCTOR").path("id").asText()).isEqualTo(setup);
    }

    /**
     * E9-T07 step 2 (S16 §2 «muntat … per {instructor}», l'autor només per a instructors; E9-W04 question 2): every staff read of a
     * setup names who built it (`builtByName`, the builder's name in the club), the member's never does. A build session's builder is
     * named too; a builder with no account in the club has no name (`null`).
     */
    @Test void T_16_09_theStaffSeeWhoBuiltASetupAndAMemberDoesNot() throws Exception {
        clock.setInstant(MONDAY);
        builder(CLUB, "INSTRUCTOR", "Fictional Estel");
        builder(OTHER, "ADMIN", "Fictional Other Admin");
        String course = createCourse("Agility N");
        var created = register("INSTRUCTOR", course(A1, "AGILITY", course).put("notes", "Nota privada"));
        assertThat(created.at("/setup/builtByName").asText()).isEqualTo("Fictional Estel");
        String id = id(created);
        assertThat(setup(id, "ADMIN").path("builtByName").asText()).isEqualTo("Fictional Estel");
        assertThat(setup(id, "INSTRUCTOR").path("builtByAccountId").asText()).isEqualTo("e9t2-INSTRUCTOR");
        assertThat(ok(as(get("/api/v1/rings/" + A1 + "/setups"), CLUB, "INSTRUCTOR"), 200).at("/items/0/builtByName").asText()).isEqualTo("Fictional Estel");
        assertThat(act("ADMIN", id, "renewal", Map.of(), 200).path("builtByName").asText()).isEqualTo("Fictional Estel");
        // The member's projection and summary never name the builder.
        var member = setup(id, "MEMBER");
        assertThat(member.has("builtByName")).isFalse();
        assertThat(member.toString()).doesNotContain("Fictional Estel", "e9t2-INSTRUCTOR");
        assertThat(mine("MEMBER", A1).toString()).doesNotContain("Fictional Estel", "builtBy");
        // A build session's `finish` names its builder.
        drawRing(A2);
        String placement = createPlacement("INSTRUCTOR", placementBody(course, A2), 201).path("id").asText();
        assertThat(setup(built(placement, "INSTRUCTOR"), "ADMIN").path("builtByName").asText()).isEqualTo("Fictional Estel");
        // `e9t2-ADMIN` has an account only in the other club: no name here (tenant), and a dismantled setup keeps its builder.
        String byAdmin = id(registerSetup(CLUB, "ADMIN", course(A1, "JUMPING", course), 201));
        assertThat(setup(byAdmin, "INSTRUCTOR").has("builtByName")).isTrue();
        assertThat(setup(byAdmin, "INSTRUCTOR").path("builtByName").isNull()).isTrue();
        assertThat(act("INSTRUCTOR", byAdmin, "dismantle", null, 200).path("builtByName").isNull()).isTrue();
        assertThat(setup(id, "INSTRUCTOR").path("status").asText()).isEqualTo("DISMANTLED");
        assertThat(setup(id, "INSTRUCTOR").path("builtByName").asText()).isEqualTo("Fictional Estel");
    }
}
