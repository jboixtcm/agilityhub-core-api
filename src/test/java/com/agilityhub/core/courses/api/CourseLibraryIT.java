package com.agilityhub.core.courses.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** E9-T02: the course library (S16 R-16-01…03, R-16-14, R-16-16, R-16-17) through the real routes, Mongo and the outbox. */
class CourseLibraryIT extends CourseItSupport {
    JsonNode create(String club, String role, Object body) throws Exception { return ok(as(json(post("/api/v1/courses"), body), club, role), 201); }
    JsonNode read(String club, String role, String id) throws Exception { return ok(as(get("/api/v1/courses/" + id), club, role), 200); }
    JsonNode list(String club, String role, String query) throws Exception { return ok(as(get("/api/v1/courses" + query), club, role), 200); }
    JsonNode library(Object body) throws Exception { return ok(as(json(post("/api/v1/platform/courses"), body), null, "AGILITYHUB_ADMIN"), 201); }
    ObjectNode libraryBody(String name) throws Exception {
        var body = course(name, model(2, false)); body.put("visibility", "PUBLIC").put("agilityhubLevel", "MEDIUM"); return body;
    }
    List<String> ids(JsonNode page) { var ids = new ArrayList<String>(); page.path("items").forEach(item -> ids.add(item.path("id").asText())); return ids; }
    Document stored(String id) { return mongo.findOne(Query.query(Criteria.where("_id").is(id)), Document.class, "courses"); }
    List<Document> events(String type, String aggregateId) { return events(type).stream().filter(e -> aggregateId.equals(e.getString("aggregateId"))).toList(); }

    @Test void T_16_03_aSmarterImportIsValidatedStoredWithSchemaVersionOneAndPublishedAsCourseImported() throws Exception {
        var created = create(CLUB, "INSTRUCTOR", course("Pista de prova", model(3, true)).put("designerName", "Example Designer"));
        String id = created.path("id").asText();
        assertThat(created.path("ownerType").asText()).isEqualTo("CLUB");
        assertThat(created.path("clubId").asText()).isEqualTo(CLUB);
        assertThat(created.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(created.path("version").asLong()).isZero();
        // S16 §14.2: the promoted fields and §3's stats come from the validated CourseData.
        assertThat(created.path("designLengthM").asDouble()).isEqualTo(40);
        assertThat(created.path("designWidthM").asDouble()).isEqualTo(20);
        assertThat(created.path("origin").asText()).isEqualTo("LB");
        assertThat(created.path("courseType").asText()).isEqualTo("Agility");
        assertThat(created.at("/stats/obstacleCount").asInt()).isEqualTo(3);
        assertThat(created.at("/stats/bbox/w").asDouble()).isEqualTo(40);
        assertThat(created.path("normalizedJson")).isEqualTo(model(3, true));
        assertThat(created.at("/nameI18n/ca").asText()).isEqualTo("Pista de prova");
        var event = events("CourseImported", id);
        assertThat(event).hasSize(1);
        assertThat(event.getFirst().getString("clubId")).isEqualTo(CLUB);
        assertThat(event.getFirst().get("payload", Document.class).getList("warnings", Document.class))
                .extracting(w -> w.getString("code") + ":" + w.getString("obstacleId")).containsExactly("UNKNOWN_OBSTACLE:obstacle-2");
        // An EDITOR course is CourseCreated.
        var editor = create(CLUB, "ADMIN", course("Dibuixat", model(1, false)).put("source", "EDITOR"));
        assertThat(events("CourseCreated", editor.path("id").asText()).getFirst().get("payload", Document.class).getString("source")).isEqualTo("EDITOR");
    }

    @Test void T_16_03_aModelThatCannotBeStoredOrIsMissingIsRefusedAndNothingIsWritten() throws Exception {
        long before = count("courses", Query.query(Criteria.where("clubId").is(CLUB)));
        // E98: a number beyond every storable range is a client error with its path, never a 500.
        String body = mapper.writeValueAsString(course("Gran", model(1, false))).replace("\"xMeters\":10", "\"xMeters\":1e400");
        var details = error(as(json(post("/api/v1/courses"), body), CLUB, "ADMIN"), 400, "COURSE_MODEL_INVALID");
        assertThat(details.at("/schemaErrors/0/path").asText()).isEqualTo("$.obstacles[0].xMeters");
        var noModel = course("Sense model", null); noModel.remove(List.of("normalizedJson", "schemaVersion"));
        error(as(json(post("/api/v1/courses"), noModel), CLUB, "ADMIN"), 400, "COURSE_MODEL_INVALID");
        error(as(json(post("/api/v1/courses"), noModel.deepCopy().put("source", "AGILITYHUB_COPY")), CLUB, "ADMIN"), 400, "VALIDATION_ERROR");
        assertThat(error(as(json(post("/api/v1/courses"), noModel.deepCopy().put("source", "IMAGE")), CLUB, "ADMIN"), 400, "VALIDATION_ERROR")
                .at("/fieldErrors/0/code").asText()).isEqualTo("REQUIRED");
        var english = course("Course", model(1, false)); english.putObject("name").put("fr", "Parcours");
        assertThat(error(as(json(post("/api/v1/courses"), english), CLUB, "ADMIN"), 400, "VALIDATION_ERROR").at("/fieldErrors/0/code").asText())
                .isEqualTo("LOCALE_NOT_ENABLED");
        var blankTag = course("Etiquetes", model(1, false)); blankTag.putArray("tags").add(" ");
        error(as(json(post("/api/v1/courses"), blankTag), CLUB, "ADMIN"), 400, "VALIDATION_ERROR");
        error(as(json(post("/api/v1/courses"), course("Privat", model(1, false)).put("visibility", "PUBLIC")), CLUB, "ADMIN"), 400, "VALIDATION_ERROR");
        assertThat(count("courses", Query.query(Criteria.where("clubId").is(CLUB)))).isEqualTo(before);
        assertThat(events("CourseCreated").stream().filter(e -> CLUB.equals(e.getString("clubId")))).isEmpty();
    }

    @Test void T_16_05_aClubCourseIsInvisibleToAnotherClubWhileTheLibraryIsVisibleToBoth() throws Exception {
        String own = create(CLUB, "INSTRUCTOR", course("Del club A", model(1, false))).path("id").asText();
        String shared = library(libraryBody("AH · Foundations 07")).path("id").asText();
        error(as(get("/api/v1/courses/" + own), OTHER, "ADMIN"), 404, "NOT_FOUND");
        error(as(json(patch("/api/v1/courses/" + own), Map.of("version", 0, "notes", "x")), OTHER, "ADMIN"), 404, "NOT_FOUND");
        error(as(delete("/api/v1/courses/" + own).param("version", "0"), OTHER, "ADMIN"), 404, "NOT_FOUND");
        error(as(json(post("/api/v1/courses/" + own + "/copy-to-club"), Map.of()), OTHER, "ADMIN"), 404, "NOT_FOUND");
        assertThat(ids(list(CLUB, "INSTRUCTOR", ""))).contains(own, shared);
        assertThat(ids(list(OTHER, "INSTRUCTOR", ""))).contains(shared).doesNotContain(own);
        assertThat(read(OTHER, "INSTRUCTOR", shared).path("ownerType").asText()).isEqualTo("AGILITYHUB");
        // A club never edits the library (403) and the library carries no club levels or files.
        error(as(json(patch("/api/v1/courses/" + shared), Map.of("version", 0, "notes", "x")), CLUB, "ADMIN"), 403, "FORBIDDEN");
        error(as(json(post("/api/v1/courses/" + shared + "/duplicate"), Map.of()), CLUB, "ADMIN"), 403, "FORBIDDEN");
    }

    @Test void T_16_05_copyToClubMakesAnEditableClubCopyThatTheOriginalNeverReaches() throws Exception {
        String shared = library(libraryBody("AH · Foundations 07")).path("id").asText();
        var copy = ok(as(json(post("/api/v1/courses/" + shared + "/copy-to-club"), Map.of()), CLUB, "INSTRUCTOR"), 201);
        String id = copy.path("id").asText();
        assertThat(copy.path("ownerType").asText()).isEqualTo("CLUB");
        assertThat(copy.path("visibility").asText()).isEqualTo("CLUB");
        assertThat(copy.path("source").asText()).isEqualTo("AGILITYHUB_COPY");
        assertThat(copy.path("sourceCourseId").asText()).isEqualTo(shared);
        assertThat(copy.path("name").asText()).isEqualTo("AH · Foundations 07");
        assertThat(copy.path("version").asLong()).isZero();
        assertThat(events("CourseCopied", id).getFirst().get("payload", Document.class).getString("fromCourseId")).isEqualTo(shared);
        var edited = ok(as(json(patch("/api/v1/courses/" + id), Map.of("version", 0, "name", Map.of("ca", "La nostra còpia"))), CLUB, "ADMIN"), 200);
        assertThat(edited.path("version").asLong()).isEqualTo(1);
        assertThat(read(CLUB, "ADMIN", shared).path("name").asText()).isEqualTo("AH · Foundations 07");
        var named = ok(as(json(post("/api/v1/courses/" + shared + "/copy-to-club"), Map.of("name", Map.of("ca", "Segona"))), CLUB, "ADMIN"), 201);
        assertThat(named.path("name").asText()).isEqualTo("Segona");
        // Only a PUBLIC course is copied to the club.
        error(as(json(post("/api/v1/courses/" + id + "/copy-to-club"), Map.of()), CLUB, "ADMIN"), 400, "VALIDATION_ERROR");
    }

    @Test void T_16_05_privateCoursesAreReadOnlyByTheirAuthor() throws Exception {
        String privateClub = create(CLUB, "INSTRUCTOR", course("Esborrany", model(1, false)).put("visibility", "PRIVATE")).path("id").asText();
        assertThat(read(CLUB, "INSTRUCTOR", privateClub).path("visibility").asText()).isEqualTo("PRIVATE");
        error(as(get("/api/v1/courses/" + privateClub), CLUB, "ADMIN"), 404, "COURSE_NOT_VISIBLE");
        assertThat(ids(list(CLUB, "ADMIN", ""))).doesNotContain(privateClub);
        // «Comparteix amb el club»: the author shares it.
        ok(as(json(patch("/api/v1/courses/" + privateClub), Map.of("version", 0, "visibility", "CLUB")), CLUB, "INSTRUCTOR"), 200);
        assertThat(read(CLUB, "ADMIN", privateClub).path("visibility").asText()).isEqualTo("CLUB");
        // An account course (ACCOUNT, PRIVATE) is its author's only, in any club, and listed under owner MINE.
        var account = new Document(stored(privateClub)); account.put("_id", "e9t2-account"); account.put("ownerType", "ACCOUNT");
        account.remove("clubId"); account.put("ownerAccountId", "e9t2-INSTRUCTOR"); account.put("visibility", "PRIVATE"); account.put("createdByAccountId", "e9t2-INSTRUCTOR");
        mongo.insert(account, "courses");
        assertThat(ids(list(OTHER, "INSTRUCTOR", "?filter=owner:eq:MINE"))).containsExactly("e9t2-account");
        assertThat(ids(list(CLUB, "ADMIN", "?filter=owner:eq:MINE"))).isEmpty();
        error(as(get("/api/v1/courses/e9t2-account"), CLUB, "ADMIN"), 404, "COURSE_NOT_VISIBLE");
        var duplicate = ok(as(json(post("/api/v1/courses/e9t2-account/duplicate"), Map.of()), OTHER, "INSTRUCTOR"), 201);
        assertThat(duplicate.path("ownerType").asText()).isEqualTo("ACCOUNT");
        assertThat(duplicate.path("ownerAccountId").asText()).isEqualTo("e9t2-INSTRUCTOR");
        assertThat(duplicate.path("clubId").isNull()).isTrue();
        error(as(json(patch("/api/v1/courses/e9t2-account"), Map.of("version", 0, "visibility", "CLUB")), OTHER, "INSTRUCTOR"), 400, "VALIDATION_ERROR");
    }

    @Test void S16_3_editsAreInPlaceWithTheLastTenModelsInHistoryAndADiffEvent() throws Exception {
        String id = create(CLUB, "INSTRUCTOR", course("Versions", model(1, false))).path("id").asText();
        for (int version = 0; version < 11; version++) {
            var body = mapper.createObjectNode().put("version", version).put("schemaVersion", 1);
            body.set("normalizedJson", model(version + 2, false));
            if (version == 0) { body.putObject("name").put("ca", "Versions 2"); }
            clock.advance(Duration.ofSeconds(1));
            assertThat(ok(as(json(patch("/api/v1/courses/" + id), body), CLUB, "INSTRUCTOR"), 200).at("/stats/obstacleCount").asInt()).isEqualTo(version + 2);
        }
        var document = stored(id);
        assertThat(document.get("version", Number.class).longValue()).isEqualTo(11);
        var history = document.getList("history", Document.class);
        assertThat(history).hasSize(10);
        assertThat(history).extracting(h -> h.get("version", Number.class).longValue()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(history.getLast().get("normalizedJson", Document.class).getList("obstacles", Document.class)).hasSize(11);
        var first = events("CourseUpdated", id).getFirst().get("payload", Document.class).get("diff", Document.class);
        assertThat(first.keySet()).containsExactlyInAnyOrder("name", "normalizedJson");
        assertThat(first.get("normalizedJson", Document.class).get("after", Number.class).longValue()).isEqualTo(1);
        // A stale version is refused; an unchanged patch neither writes nor publishes.
        error(as(json(patch("/api/v1/courses/" + id), Map.of("version", 3, "notes", "x")), CLUB, "INSTRUCTOR"), 409, "STALE_VERSION");
        ok(as(json(patch("/api/v1/courses/" + id), Map.of("version", 11)), CLUB, "INSTRUCTOR"), 200);
        assertThat(stored(id).get("version", Number.class).longValue()).isEqualTo(11);
        assertThat(events("CourseUpdated", id)).hasSize(11);
    }

    @Test void T_16_06_aCourseShownByAnActiveSetupCannotBeDeletedAndADeletedOneIsGone() throws Exception {
        String direct = create(CLUB, "INSTRUCTOR", course("Muntat", model(1, false))).path("id").asText();
        String placed = create(CLUB, "INSTRUCTOR", course("Col·locat", model(1, false))).path("id").asText();
        mongo.insert(new Document("_id", "e9t2-setup-1").append("clubId", CLUB).append("ringId", "e9t2-ring-a1").append("status", "ACTIVE").append("courseId", direct), "ring_setups");
        mongo.insert(new Document("_id", "e9t2-placement-1").append("clubId", CLUB).append("ringId", "e9t2-ring-a2").append("courseId", placed).append("courseVersion", 0L).append("warningThresholdM", 1.5).append("stale", false).append("version", 0L), "placements");
        mongo.insert(new Document("_id", "e9t2-setup-2").append("clubId", CLUB).append("ringId", "e9t2-ring-a2").append("status", "ACTIVE").append("placementId", "e9t2-placement-1"), "ring_setups");
        for (String id : List.of(direct, placed)) { error(as(delete("/api/v1/courses/" + id).param("version", "0"), CLUB, "ADMIN"), 409, "COURSE_IN_USE"); }
        // §6 activeOnRings: the ring of each active setup that shows the course.
        var page = list(CLUB, "ADMIN", "?sort=name,asc");
        var rows = new HashMap<String, JsonNode>(); page.path("items").forEach(item -> rows.put(item.path("id").asText(), item));
        assertThat(rows.get(direct).at("/activeOnRings/0/name").asText()).isEqualTo("Ring A1");
        assertThat(rows.get(placed).at("/activeOnRings/0/id").asText()).isEqualTo("e9t2-ring-a2");
        mongo.updateMulti(Query.query(Criteria.where("clubId").is(CLUB)), new Update().set("status", "DISMANTLED"), "ring_setups");
        error(as(delete("/api/v1/courses/" + direct).param("version", "1"), CLUB, "ADMIN"), 409, "STALE_VERSION");
        perform(as(delete("/api/v1/courses/" + direct).param("version", "0"), CLUB, "ADMIN"), 204);
        assertThat(stored(direct).get("deletedAt")).isNotNull();
        assertThat(stored(direct).get("version", Number.class).longValue()).isEqualTo(1);
        error(as(get("/api/v1/courses/" + direct), CLUB, "ADMIN"), 404, "NOT_FOUND");
        assertThat(ids(list(CLUB, "ADMIN", ""))).doesNotContain(direct);
        assertThat(events("CourseDeleted", direct)).hasSize(1);
    }

    @Test void T_16_22_theNameFallsBackToTheClubDefaultAndOnlyEditorsSeeTheTranslations() throws Exception {
        String id = create(CLUB, "INSTRUCTOR", course("Només en català", model(1, false))).path("id").asText();
        var english = ok(as(get("/api/v1/courses/" + id).header("Accept-Language", "en"), CLUB, "ADMIN"), 200);
        assertThat(english.path("name").asText()).isEqualTo("Només en català");
        var translated = course("Català", model(1, false)); ((ObjectNode) translated.path("name")).put("en", "English");
        String both = create(CLUB, "ADMIN", translated).path("id").asText();
        assertThat(ok(as(get("/api/v1/courses/" + both).header("Accept-Language", "en"), CLUB, "ADMIN"), 200).path("name").asText()).isEqualTo("English");
        String shared = library(libraryBody("Library")).path("id").asText();
        assertThat(read(CLUB, "ADMIN", shared).path("nameI18n").isNull()).isTrue();
        assertThat(read(CLUB, "ADMIN", both).at("/nameI18n/en").asText()).isEqualTo("English");
    }

    @Test void T_16_05_theUniversalListFiltersSortsSearchesAndSelectsFields() throws Exception {
        String small = create(CLUB, "INSTRUCTOR", course("Bravo", model(2, false)).put("designerName", "Anna Exemple")).path("id").asText();
        var leveled = course("alfa", model(5, false)).put("source", "EDITOR").put("discipline", "JUMPING");
        leveled.putArray("levelIds").add("e9t2-level-aB").add("e9t2-level-aA"); leveled.putArray("tags").add("tecnic");
        String big = create(CLUB, "INSTRUCTOR", leveled).path("id").asText();
        String shared = library(libraryBody("Charlie")).path("id").asText();
        assertThat(ids(list(CLUB, "ADMIN", "?sort=name,asc"))).containsSubsequence(big, small, shared);
        assertThat(ids(list(CLUB, "ADMIN", "?filter=owner:eq:AGILITYHUB"))).contains(shared).doesNotContain(small, big);
        assertThat(ids(list(CLUB, "ADMIN", "?filter=owner:eq:CLUB&filter=obstacleCount:gte:3"))).containsExactly(big);
        assertThat(ids(list(CLUB, "ADMIN", "?filter=discipline:eq:JUMPING"))).containsExactly(big);
        assertThat(ids(list(CLUB, "ADMIN", "?filter=levelId:eq:e9t2-level-aA"))).containsExactly(big);
        assertThat(ids(list(CLUB, "ADMIN", "?filter=tag:in:tecnic,altre"))).containsExactly(big);
        assertThat(ids(list(CLUB, "ADMIN", "?filter=owner:eq:CLUB&filter=tag:exists:false"))).containsExactly(small);
        assertThat(ids(list(CLUB, "ADMIN", "?filter=owner:eq:CLUB&filter=agilityhubLevel:ne:MEDIUM&sort=name,desc"))).containsExactly(small, big);
        assertThat(ids(list(CLUB, "ADMIN", "?q=EXEMPLE"))).containsExactly(small);
        var levels = list(CLUB, "ADMIN", "?filter=owner:eq:CLUB&sort=name,asc");
        assertThat(levels.at("/items/0/level").asText()).isEqualTo("A–B");
        assertThat(levels.at("/items/1/level").isNull()).isTrue();
        // INC-62 (E11-T02, S16 §2): D18's list shows each course's date.
        assertThat(levels.at("/items/0/updatedAt").asText()).isEqualTo(clock.instant().toString());
        assertThat(list(CLUB, "ADMIN", "?fields=updatedAt&filter=owner:eq:CLUB").at("/items/0/updatedAt").asText()).isEqualTo(clock.instant().toString());
        assertThat(levels.path("totalItems").asLong()).isEqualTo(2);
        var sparse = list(CLUB, "ADMIN", "?fields=name&size=20&page=0&sort=name,asc");
        assertThat(sparse.path("items").get(0).size()).isEqualTo(2);
        assertThat(sparse.path("totalPages").asInt()).isEqualTo(1);
        assertThat(list(CLUB, "ADMIN", "?size=20&page=1").path("items")).isEmpty();
        error(as(get("/api/v1/courses").param("filter", "unknown:eq:x"), CLUB, "ADMIN"), 400, "INVALID_FILTER");
        error(as(get("/api/v1/courses"), CLUB, "MEMBER"), 403, "FORBIDDEN");
        // The platform list is the AgilityHub library only.
        assertThat(ids(ok(as(get("/api/v1/platform/courses"), null, "AGILITYHUB_ADMIN"), 200))).contains(shared).doesNotContain(small, big);
    }

    @Test void T_16_12_levelsBelongToTheClubAndAreRefusedWhileLevelsAreDisabled() throws Exception {
        var body = course("Nivells", model(1, false)); body.putArray("levelIds").add("e9t2-level-bA");
        error(as(json(post("/api/v1/courses"), body), CLUB, "ADMIN"), 404, "NOT_FOUND");
        parameter("levels.enabled", false, "bool");
        body.putArray("levelIds").add("e9t2-level-aA");
        // E115 (E9-T02 review #3): a top-level 422, as CATALEG_ERRORS §1 and every other context have it; checked before the level's club.
        error(as(json(post("/api/v1/courses"), body), CLUB, "ADMIN"), 422, "LEVELS_DISABLED");
        body.putArray("levelIds").add("e9t2-level-bA");
        error(as(json(post("/api/v1/courses"), body), CLUB, "ADMIN"), 422, "LEVELS_DISABLED");
        body.putArray("levelIds");
        String id = create(CLUB, "ADMIN", body).path("id").asText();
        error(as(json(patch("/api/v1/courses/" + id), Map.of("version", 0, "levelIds", List.of("e9t2-level-aA"))), CLUB, "ADMIN"), 422, "LEVELS_DISABLED");
    }

    /**
     * R-16-01 (E115, E9-T02 review #2): the back computes no geometry, so `stats.pathLengthM` is the length course-core's summary
     * reports with the model (`pathLengthM`), never a polyline drawn in Java through the model's `numbers`. A new model without it
     * stores 0; a PATCH that names neither keeps it; a course without a model takes none.
     */
    @Test void T_16_03_thePathLengthIsCourseCoresAndTravelsWithItsModel() throws Exception {
        var created = create(CLUB, "INSTRUCTOR", course("Amb recorregut", model(3, false)).put("pathLengthM", 27.5));
        String id = created.path("id").asText();
        assertThat(created.at("/stats/pathLengthM").asDouble()).isEqualTo(27.5);
        assertThat(stored(id).get("stats", Document.class).getDouble("pathLengthM")).isEqualTo(27.5);
        assertThat(create(CLUB, "INSTRUCTOR", course("Sense longitud", model(3, false))).at("/stats/pathLengthM").asDouble()).isZero();
        error(as(json(post("/api/v1/courses"), course("Negativa", model(1, false)).put("pathLengthM", -1)), CLUB, "ADMIN"), 400, "VALIDATION_ERROR");
        // A PATCH that names neither keeps it; one that names it changes it (CourseUpdated.diff); a new model without it reports none.
        var kept = ok(as(json(patch("/api/v1/courses/" + id), Map.of("version", 0, "notes", "Nota")), CLUB, "INSTRUCTOR"), 200);
        assertThat(kept.at("/stats/pathLengthM").asDouble()).isEqualTo(27.5);
        var longer = ok(as(json(patch("/api/v1/courses/" + id), Map.of("version", 1, "pathLengthM", 31.25)), CLUB, "INSTRUCTOR"), 200);
        assertThat(longer.at("/stats/pathLengthM").asDouble()).isEqualTo(31.25);
        assertThat(events("CourseUpdated", id).stream().map(e -> e.get("payload", Document.class).get("diff", Document.class))
                .filter(diff -> diff.containsKey("pathLengthM")).toList()).singleElement()
                .satisfies(diff -> assertThat(diff.get("pathLengthM", Document.class)).containsEntry("before", 27.5).containsEntry("after", 31.25));
        var remodelled = ok(as(json(patch("/api/v1/courses/" + id), Map.of("version", 2, "normalizedJson", model(4, false), "schemaVersion", 1)), CLUB, "INSTRUCTOR"), 200);
        assertThat(remodelled.at("/stats/pathLengthM").asDouble()).isZero();
        assertThat(remodelled.at("/stats/obstacleCount").asInt()).isEqualTo(4);
        // A photo has no model to measure.
        byte[] png = "fictional png".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var photo = course("Foto", null); photo.remove(List.of("normalizedJson", "schemaVersion")); photo.put("source", "IMAGE");
        photo.put("imageFileKey", upload(CLUB, "COURSE_IMAGE", "image/png", png, 204, Duration.ZERO));
        assertThat(error(as(json(post("/api/v1/courses"), photo.deepCopy().put("pathLengthM", 10)), CLUB, "ADMIN"), 400, "VALIDATION_ERROR")
                .at("/fieldErrors/0/field").asText()).isEqualTo("pathLengthM");
        assertThat(create(CLUB, "ADMIN", photo).at("/stats/pathLengthM").asDouble()).isZero();
    }

    /**
     * E115 (E9-T02 review #6): a new file key is claimed only once the write is known to apply. A stale PATCH naming a new, foreign
     * or bound key answers `409 STALE_VERSION` and binds nothing; a create refused by a later check answers that check, not `404`.
     */
    @Test void T_16_21_aRefusedWriteClaimsNoFileAndAnswersItsOwnError() throws Exception {
        byte[] png = "fictional png".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String id = create(CLUB, "ADMIN", course("Amb miniatura", model(1, false))).path("id").asText();
        String thumbnail = upload(CLUB, "THUMBNAIL", "image/png", png, 204, Duration.ZERO);
        String foreign = upload(OTHER, "THUMBNAIL", "image/png", png, 204, Duration.ZERO);
        for (String key : List.of(thumbnail, foreign, CLUB + "/courses/not-a-key")) {
            error(as(json(patch("/api/v1/courses/" + id), Map.of("version", 7, "thumbnailFileKey", key)), CLUB, "ADMIN"), 409, "STALE_VERSION");
        }
        assertThat(ok(as(json(patch("/api/v1/courses/" + id), Map.of("version", 0, "thumbnailFileKey", thumbnail)), CLUB, "ADMIN"), 200)
                .path("thumbnailFileKey").asText()).isEqualTo(thumbnail);
        // A create with a foreign key and a club course made PUBLIC: the visibility answers, not the file.
        var refused = course("Pública", model(1, false)).put("visibility", "PUBLIC").put("thumbnailFileKey", foreign);
        assertThat(error(as(json(post("/api/v1/courses"), refused), CLUB, "ADMIN"), 400, "VALIDATION_ERROR").at("/fieldErrors/0/field").asText())
                .isEqualTo("visibility");
    }

    @Test void T_16_21_theAgilityHubLibraryIsGlobalPublicAndVersioned() throws Exception {
        var created = library(libraryBody("AH · Foundations 07"));
        String id = created.path("id").asText();
        assertThat(created.path("ownerType").asText()).isEqualTo("AGILITYHUB");
        assertThat(created.path("clubId").isNull()).isTrue();
        assertThat(created.path("agilityhubLevel").asText()).isEqualTo("MEDIUM");
        assertThat(events("CourseImported", id).getFirst().get("clubId")).isNull();
        var patched = ok(as(json(patch("/api/v1/platform/courses/" + id), Map.of("version", 0, "agilityhubLevel", "HARD")), null, "AGILITYHUB_ADMIN"), 200);
        assertThat(patched.path("agilityhubLevel").asText()).isEqualTo("HARD");
        assertThat(patched.path("nameI18n").isObject()).isTrue();
        error(as(json(patch("/api/v1/platform/courses/" + id), Map.of("version", 0, "notes", "x")), null, "AGILITYHUB_ADMIN"), 409, "STALE_VERSION");
        var withFile = libraryBody("Amb fitxer").put("imageFileKey", CLUB + "/courses/00000000-0000-0000-0000-000000000000");
        error(as(json(post("/api/v1/platform/courses"), withFile), null, "AGILITYHUB_ADMIN"), 404, "NOT_FOUND");
        String club = create(CLUB, "ADMIN", course("Del club", model(1, false))).path("id").asText();
        error(as(json(patch("/api/v1/platform/courses/" + club), Map.of("version", 0)), null, "AGILITYHUB_ADMIN"), 404, "NOT_FOUND");
    }

    @Test void T_16_14_T_16_21_courseFilesAreTheClubsOwnSignedUploadsUnderItsPrefix() throws Exception {
        byte[] png = "fictional png".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String image = upload(CLUB, "COURSE_IMAGE", "image/png", png, 204, Duration.ZERO);
        assertThat(image).startsWith(CLUB + "/courses/");
        // T-16-14: a signed URL expires.
        String late = upload(CLUB, "THUMBNAIL", "image/png", png, 403, Duration.ofSeconds(301));
        String source = upload(CLUB, "SMARTER_SOURCE", "text/plain", "+++++++SAD".getBytes(java.nio.charset.StandardCharsets.UTF_8), 204, Duration.ZERO);
        String other = upload(OTHER, "COURSE_IMAGE", "image/png", png, 204, Duration.ZERO);
        var imageCourse = course("Foto", null); imageCourse.remove(List.of("normalizedJson", "schemaVersion")); imageCourse.put("source", "IMAGE");
        // T-16-21: another club's key, a never uploaded one and a key of another purpose are not found, and nothing is bound.
        for (String key : List.of(other, late, source, CLUB + "/courses/not-a-key")) {
            error(as(json(post("/api/v1/courses"), imageCourse.deepCopy().put("imageFileKey", key)), CLUB, "ADMIN"), 404, "NOT_FOUND");
        }
        var created = create(CLUB, "ADMIN", imageCourse.deepCopy().put("imageFileKey", image));
        assertThat(created.path("imageFileKey").asText()).isEqualTo(image);
        String imageUrl = created.path("imageUrl").asText();
        assertThat(imageUrl).contains("signature=");
        assertThat(mvc.perform(get(java.net.URI.create(imageUrl))).andReturn().getResponse().getContentAsByteArray()).isEqualTo(png);
        // A key bound to one course is not another course's new file.
        error(as(json(post("/api/v1/courses"), imageCourse.deepCopy().put("imageFileKey", image)), CLUB, "ADMIN"), 404, "NOT_FOUND");
        var smarter = create(CLUB, "INSTRUCTOR", course("Amb font", model(1, false)).put("sourceFileKey", source));
        assertThat(smarter.path("sourceFileKey").asText()).isEqualTo(source);
        // The other club's file stays theirs: its own course takes it.
        assertThat(ok(as(json(post("/api/v1/courses"), imageCourse.deepCopy().put("imageFileKey", other)), OTHER, "ADMIN"), 201).path("imageUrl").asText()).isNotEmpty();
    }
}
