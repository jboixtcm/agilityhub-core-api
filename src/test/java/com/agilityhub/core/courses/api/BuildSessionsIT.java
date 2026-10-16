package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.application.CourseSchemas;
import com.agilityhub.core.identity.application.PasswordHasher;
import com.agilityhub.core.identity.application.TokenService;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.jobs.JobName;
import com.agilityhub.core.platform.application.jobs.JobRunner;
import com.agilityhub.core.platform.application.jobs.JobStatus;
import com.agilityhub.core.platform.persistence.jobs.JobRun;
import com.agilityhub.core.shared.application.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * E9-T04: live build sessions (S16 R-16-11, T-16-10): the session, its `text/event-stream` with two real subscribers, the join code,
 * `finish` registering the ring's setup, the 12-hour end and S15 P9, the query token accepted only by the stream, and
 * `BuildSessionExportV1` (E77, A9) with its self-check (E98). A real server, so the streams go through Tomcat, the security chain
 * and the async dispatch that ends them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "shared.scheduling.enabled=false")
class BuildSessionsIT extends CourseItSupport {
    static final String A1 = "e9t2-ring-a1", A2 = "e9t2-ring-a2";
    static final String PASSWORD = "Fictional-builder-2030!";
    static final Duration LIVE = Duration.ofSeconds(2);
    @LocalServerPort int port;
    @Autowired TokenService tokens;
    @Autowired PasswordHasher passwords;
    @Autowired JobRunner jobs;
    @Autowired CourseSchemas schemas;
    private final List<Stream> streams = new ArrayList<>();

    @AfterEach void closeStreams() { streams.forEach(Stream::close); }

    // ---- fixtures ----

    /** R-16-04's example ring with the six markers A–F the Unity export needs (E and F at the middle of the long sides). */
    ObjectNode sixMarkers() {
        var body = geometry(0);
        var markers = body.withArray("markers");
        double[][] middles = {{20, 0}, {20, 22}};
        for (int i = 0; i < 2; i++) {
            markers.addObject().put("id", "marker-" + (4 + i)).put("label", i == 0 ? "E" : "F").put("markerUid", "uid-" + (4 + i)).put("role", "ANCHOR")
                    .put("xM", middles[i][0]).put("yM", middles[i][1]).put("zM", 0).put("rotationYDeg", 0).put("physicalWidthM", 0.2)
                    .put("physicalHeightM", 0.2).put("isFixed", true).put("isActive", true).put("aprilTagId", 4 + i).putNull("notes");
        }
        return body;
    }
    /** A placement of a new club course (AGILITY) on {@code ringId}, with two resolved obstacles. */
    String placement(String ringId, boolean sixMarkers) throws Exception {
        ok(as(json(put("/api/v1/rings/" + ringId + "/geometry"), sixMarkers ? sixMarkers() : geometry(0)), CLUB, "ADMIN"), 200);
        var body = placementBody(createCourse("Central " + ringId), ringId);
        body.withArray("resolvedObstacles").addObject().put("obstacleId", "obstacle-1").put("type", "Jump").put("x", 20).put("y", 6).put("rotationDeg", 90)
                .putArray("numbers").add(5);
        return createPlacement("INSTRUCTOR", body, 201).path("id").asText();
    }
    JsonNode start(String placementId, String role, int status) throws Exception {
        return ok(as(json(post("/api/v1/build-sessions"), Map.of("placementId", placementId, "live", true)), CLUB, role)
                .header("Idempotency-Key", UUID.randomUUID().toString()), status);
    }
    MockHttpServletRequestBuilder mark(String id, String obstacleId, String status) throws Exception {
        return json(patch("/api/v1/build-sessions/" + id + "/obstacles/" + obstacleId), Map.of("status", status));
    }
    MockHttpServletRequestBuilder finish(String id) {
        return post("/api/v1/build-sessions/" + id + "/finish").header("Idempotency-Key", UUID.randomUUID().toString());
    }
    MockHttpServletRequestBuilder join(String code) throws Exception { return json(post("/api/v1/build-sessions/join"), Map.of("code", code)); }
    Document session(String id) { return mongo.findOne(Query.query(Criteria.where("_id").is(id)), Document.class, "build_sessions"); }
    List<Document> clubEvents(String type) { return events(type).stream().filter(e -> CLUB.equals(e.getString("clubId"))).toList(); }
    JobRun cleanup(boolean dry) {
        var run = jobs.manual(CLUB, JobName.CLEANUP, dry, "e9t2-ADMIN");
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
    /** A real access token of a fictional account with {@code role} in the club (password grant), as a phone holds it. */
    String token(String name, Role role) {
        String id = "e9t2-account-" + name, email = name + "@example.test";
        mongo.insert(new Account(id, email, "Fictional " + name, "ca", passwords.hash(PASSWORD), Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
        mongo.insert(new Membership("e9t2-membership-" + name, id, CLUB, null, Set.of(role), Membership.Status.ACTIVE, role));
        try (var tenant = TenantContext.open(CLUB)) { return tokens.password(email, PASSWORD, "clubs-app").access().getTokenValue(); }
    }

    /** A real SSE client (curl, as a phone's EventSource), the bearer as a header or as `?access_token=`; its lines read in the background. */
    final class Stream implements AutoCloseable {
        final Process process;
        final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Stream(String sessionId, String bearer, boolean query) throws IOException {
            String url = "http://localhost:" + port + "/api/v1/build-sessions/" + sessionId + "/events" + (query ? "?access_token=" + bearer : "");
            process = new ProcessBuilder("curl", "--silent", "--show-error", "--no-buffer", "--include", "--config", "-").redirectErrorStream(true).start();
            try (var input = process.outputWriter()) {
                input.write("url = \"" + url + "\"\nheader = \"Host: " + HOST + "\"\n" + (query ? "" : "header = \"Authorization: Bearer " + bearer + "\"\n"));
            }
            var reader = new Thread(() -> {
                try (var output = process.inputReader()) { for (String line; (line = output.readLine()) != null; ) { lines.add(line.strip()); } }
                catch (IOException closed) { lines.add("<closed>"); }
            });
            reader.setDaemon(true); reader.start();
            streams.add(this);
        }
        /** The next line starting with {@code prefix}, within {@code timeout}. */
        String next(String prefix, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                String line = lines.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (line != null && line.startsWith(prefix)) { return line; }
            }
            throw new AssertionError("No line starting with «" + prefix + "» within " + timeout);
        }
        /** The data of the next event named {@code name}, within {@code timeout}. */
        JsonNode event(String name, Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            next("event:" + name, timeout);
            return mapper.readTree(next("data:", Duration.ofNanos(Math.max(1, deadline - System.nanoTime()))).substring("data:".length()));
        }
        int exit(Duration timeout) throws InterruptedException {
            assertThat(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)).as("the stream ended").isTrue();
            return process.exitValue();
        }
        @Override public void close() { process.destroyForcibly(); }
    }

    // ---- T-16-10 ----

    /**
     * T-16-10 (R-16-11): «Dos instructors munten Central: cada toc es veu a l'altre mòbil en < 2 s». A session starts with every
     * obstacle `NOT_PLACED`, a join code and a QR token, and publishes `BuildSessionStarted`; two real subscribers (one with the
     * bearer header, one with `?access_token=`) receive each tap as an `obstacle` event in under two seconds; `finish` creates the
     * ring's setup, ends both streams with `finished` (a clean end: curl exits 0 after the async dispatch) and publishes
     * `BuildSessionFinished{sessionId, setupId}`; a `PATCH` or a second `finish` afterwards → `422 BUILD_SESSION_FINISHED`.
     */
    @Test void T_16_10_twoLiveSubscribersSeeEachTapWithinTwoSecondsAndFinishCreatesTheSetup() throws Exception {
        String placementId = placement(A1, true);
        var started = start(placementId, "INSTRUCTOR", 201);
        String id = started.path("id").asText();
        assertThat(started.path("status").asText()).isEqualTo("NOT_STARTED");
        assertThat(started.path("ringId").asText()).isEqualTo(A1);
        assertThat(started.path("live").asBoolean()).isTrue();
        assertThat(started.path("selectedStrategy").asText()).isEqualTo("EQUIPMENT_TYPE");
        assertThat(started.path("startedByAccountId").asText()).isEqualTo("e9t2-INSTRUCTOR");
        assertThat(started.path("joinCode").asText()).matches("[ABCDEFGHJKMNPQRSTUVWXYZ2-9]{6}");
        assertThat(started.path("joinToken").asText()).hasSize(43);
        assertThat(started.path("obstacles")).extracting(o -> o.path("obstacleId").asText() + ":" + o.path("status").asText())
                .containsExactly("obstacle-0:NOT_PLACED", "obstacle-1:NOT_PLACED");
        assertThat(clubEvents("BuildSessionStarted").getLast().get("payload", Document.class))
                .isEqualTo(new Document("sessionId", id).append("placementId", placementId).append("live", true));

        String first = token("builder-a", Role.INSTRUCTOR), second = token("builder-b", Role.INSTRUCTOR);
        var header = new Stream(id, first, false);
        var query = new Stream(id, second, true);
        for (var stream : List.of(header, query)) {
            assertThat(stream.next("HTTP/", LIVE)).contains(" 200");
            assertThat(stream.next("Content-Type:", LIVE)).contains("text/event-stream");
            stream.next(":open", LIVE);
        }

        long tapped = System.nanoTime();
        var marked = ok(as(mark(id, "obstacle-1", "PLACED"), CLUB, "INSTRUCTOR"), 200);
        assertThat(marked.path("status").asText()).isEqualTo("IN_PROGRESS");
        for (var stream : List.of(header, query)) {
            var event = stream.event("obstacle", LIVE);
            assertThat(Duration.ofNanos(System.nanoTime() - tapped)).isLessThan(LIVE);
            assertThat(event.path("sessionId").asText()).isEqualTo(id);
            assertThat(event.path("obstacleId").asText()).isEqualTo("obstacle-1");
            assertThat(event.path("status").asText()).isEqualTo("PLACED");
            assertThat(event.path("byAccountId").asText()).isEqualTo("e9t2-INSTRUCTOR");
            assertThat(event.path("version").asLong()).isEqualTo(marked.path("version").asLong());
        }
        assertThat(clubEvents("BuildSessionProgress").getLast().get("payload", Document.class))
                .isEqualTo(new Document("sessionId", id).append("obstacleId", "obstacle-1").append("status", "PLACED"));
        // Last write wins per obstacle: a second phone's tap with a stale version still applies.
        tapped = System.nanoTime();
        var stale = mark(id, "obstacle-1", "NEEDS_CHECK").content("{\"status\":\"NEEDS_CHECK\",\"version\":0}");
        assertThat(ok(as(stale, CLUB, "ADMIN", "e9t2-other-ADMIN"), 200).at("/obstacles/1/status").asText()).isEqualTo("NEEDS_CHECK");
        for (var stream : List.of(header, query)) {
            assertThat(stream.event("obstacle", LIVE).path("byAccountId").asText()).isEqualTo("e9t2-other-ADMIN");
            assertThat(Duration.ofNanos(System.nanoTime() - tapped)).isLessThan(LIVE);
        }

        var finished = ok(as(finish(id), CLUB, "INSTRUCTOR"), 200);
        assertThat(finished.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(finished.path("finishedAt").asText()).isEqualTo(clock.instant().toString());
        for (var stream : List.of(header, query)) {
            var event = stream.event("finished", LIVE);
            assertThat(event.path("status").asText()).isEqualTo("COMPLETED");
            assertThat(stream.exit(Duration.ofSeconds(5))).as("a clean end of the stream").isZero();
        }
        String setupId = ringDocument(A1).getString("activeSetupId");
        var setup = setupDocument(setupId);
        assertThat(setup.getString("kind")).isEqualTo("AGILITY");
        assertThat(setup.getString("placementId")).isEqualTo(placementId);
        assertThat(setup.getString("status")).isEqualTo("ACTIVE");
        assertThat(setup.getString("builtByAccountId")).isEqualTo("e9t2-INSTRUCTOR");
        assertThat(clubEvents("BuildSessionFinished").getLast().get("payload", Document.class))
                .isEqualTo(new Document("sessionId", id).append("setupId", setupId));
        assertThat(clubEvents("RingSetupChanged").getLast().get("payload", Document.class)).containsEntry("setupId", setupId).containsEntry("status", "ACTIVE");

        assertThat(error(as(mark(id, "obstacle-0", "PLACED"), CLUB, "INSTRUCTOR"), 422, "BUILD_SESSION_FINISHED")).isEmpty();
        error(as(finish(id), CLUB, "ADMIN"), 422, "BUILD_SESSION_FINISHED");
        error(as(mark(id, "obstacle-9", "PLACED"), CLUB, "INSTRUCTOR"), 404, "NOT_FOUND");
        // A stream opened on a closed session gets `finished` at once and ends.
        var late = new Stream(id, first, true);
        assertThat(late.event("finished", LIVE).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(late.exit(Duration.ofSeconds(5))).isZero();
    }

    /**
     * R-16-11 «last-write-wins per obstacle»: eight phones tapping eight obstacles of one session at once all succeed (the taps meet
     * as Mongo write conflicts on the session document and are retried whole), never a `409` or a `500`; every tap is stored, counted
     * in `version` and published once.
     */
    @Test void T_16_10_simultaneousTapsOnOneSessionAllApply() throws Exception {
        ok(as(json(put("/api/v1/rings/" + A1 + "/geometry"), sixMarkers()), CLUB, "ADMIN"), 200);
        String courseId = ok(as(json(post("/api/v1/courses"), course("Central 8", model(8, false))), CLUB, "INSTRUCTOR"), 201).path("id").asText();
        var body = placementBody(courseId, A1);
        var resolved = body.putArray("resolvedObstacles");
        for (int i = 0; i < 8; i++) {
            resolved.addObject().put("obstacleId", "obstacle-" + i).put("type", "Jump").put("x", 5 + i * 4).put("y", 6).put("rotationDeg", 0).putArray("numbers").add(i + 1);
        }
        String id = start(createPlacement("INSTRUCTOR", body, 201).path("id").asText(), "INSTRUCTOR", 201).path("id").asText();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var taps = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 8; i++) {
                String obstacle = "obstacle-" + i, phone = "e9t2-phone-" + i;
                taps.add(pool.submit(() -> {
                    start.await();
                    var result = mvc.perform(as(mark(id, obstacle, "PLACED"), CLUB, "INSTRUCTOR", phone)).andReturn().getResponse();
                    return result.getStatus();
                }));
            }
            start.countDown();
            for (var tap : taps) { assertThat(tap.get(30, TimeUnit.SECONDS)).isEqualTo(200); }
        } finally { pool.shutdownNow(); }
        var stored = session(id);
        assertThat(stored.getList("obstacles", Document.class)).extracting(o -> o.getString("status")).containsOnly("PLACED").hasSize(8);
        assertThat(stored.get("version", Number.class).longValue()).isEqualTo(8);
        assertThat(clubEvents("BuildSessionProgress")).hasSize(8);
    }

    /**
     * T-16-10 «token per query només a `/events`»: a real token as `?access_token=` opens the stream and nothing else (`GET`,
     * `PATCH`, `finish`, export → `401 UNAUTHENTICATED`); without a token, with an invalid one, or with the header too → `401`; a
     * MEMBER's real token → `403`.
     */
    @Test void T_16_10_theQueryTokenIsAcceptedOnlyByTheEventStream() throws Exception {
        String id = start(placement(A1, true), "INSTRUCTOR", 201).path("id").asText();
        String instructor = token("query-instructor", Role.INSTRUCTOR), member = token("query-member", Role.MEMBER);
        String events = "/api/v1/build-sessions/" + id + "/events";
        var stream = mvc.perform(get(events).header("Host", HOST).param("access_token", instructor)).andReturn();
        assertThat(stream.getResponse().getStatus()).isEqualTo(200);
        assertThat(stream.getRequest().isAsyncStarted()).isTrue();
        assertThat(stream.getResponse().getContentType()).startsWith("text/event-stream");
        for (var request : List.of(get("/api/v1/build-sessions/" + id), mark(id, "obstacle-0", "PLACED"), finish(id),
                get("/api/v1/build-sessions/" + id + "/export"), join("ABCDEF"))) {
            error(request.header("Host", HOST).param("access_token", instructor), 401, "UNAUTHENTICATED");
        }
        assertThat(session(id).getString("status")).isEqualTo("NOT_STARTED");
        error(get(events).header("Host", HOST), 401, "UNAUTHENTICATED");
        error(get(events).header("Host", HOST).param("access_token", "not-a-token"), 401, "UNAUTHENTICATED");
        error(get(events).header("Host", HOST).param("access_token", instructor).header("Authorization", "Bearer " + instructor), 401, "UNAUTHENTICATED");
        error(get(events).header("Host", HOST).param("access_token", member), 403, "FORBIDDEN");
        // The header still works there, as everywhere.
        assertThat(mvc.perform(get("/api/v1/build-sessions/" + id).header("Host", HOST).header("Authorization", "Bearer " + instructor))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    /**
     * S16 §14.2 `joinCode`/`joinToken` («unir-se des d'un altre mòbil»): the code, in any case and with blanks, joins as `CODE`; the
     * QR's token as `QR`; once per account (a second join changes nothing); an unknown code, or a closed session's → `404`.
     */
    @Test void T_16_10_anotherPhoneJoinsByCodeOrQrOnce() throws Exception {
        var started = start(placement(A1, true), "INSTRUCTOR", 201);
        String id = started.path("id").asText(), code = started.path("joinCode").asText();
        var joined = ok(as(join("  " + code.toLowerCase(Locale.ROOT) + " "), CLUB, "INSTRUCTOR", "e9t2-joiner"), 200);
        assertThat(joined.path("id").asText()).isEqualTo(id);
        assertThat(joined.path("participants")).singleElement().satisfies(p -> {
            assertThat(p.path("accountId").asText()).isEqualTo("e9t2-joiner");
            assertThat(p.path("joinedVia").asText()).isEqualTo("CODE");
            assertThat(p.path("joinedAt").asText()).isEqualTo(clock.instant().toString());
        });
        var again = ok(as(join(code), CLUB, "INSTRUCTOR", "e9t2-joiner"), 200);
        assertThat(again.path("version").asLong()).isEqualTo(joined.path("version").asLong());
        assertThat(again.path("participants")).hasSize(1);
        var scanned = ok(as(join(started.path("joinToken").asText()), CLUB, "ADMIN"), 200);
        assertThat(scanned.path("participants")).extracting(p -> p.path("accountId").asText() + ":" + p.path("joinedVia").asText())
                .containsExactly("e9t2-joiner:CODE", "e9t2-ADMIN:QR");
        error(as(join("ZZZZZZ"), CLUB, "INSTRUCTOR"), 404, "NOT_FOUND");
        ok(as(finish(id), CLUB, "ADMIN"), 200);
        error(as(join(code), CLUB, "INSTRUCTOR", "e9t2-late"), 404, "NOT_FOUND");
    }

    /**
     * R-16-11 «crea/actualitza el `RingSetup`»: `finish` publishes like `POST /ring-setups`, so an INSTRUCTOR needs
     * `courses.allowInstructorPublish` (`403`, nothing changes); a session on the placement the ring's active setup already shows
     * refreshes that setup (built now, a new `expiresAt`, no `RingSetupChanged`); a session on another placement of the ring replaces it.
     */
    @Test void T_16_10_finishRegistersOrRefreshesTheRingsSetupUnderThePublishingSwitch() throws Exception {
        String placementId = placement(A1, true);
        String first = start(placementId, "INSTRUCTOR", 201).path("id").asText();
        parameter("courses.allowInstructorPublish", false, "bool");
        error(as(finish(first), CLUB, "INSTRUCTOR"), 403, "FORBIDDEN");
        assertThat(session(first).getString("status")).isEqualTo("NOT_STARTED");
        assertThat(ringDocument(A1).get("activeSetupId")).isNull();
        ok(as(finish(first), CLUB, "ADMIN"), 200);
        String setupId = ringDocument(A1).getString("activeSetupId");
        assertThat(setupDocument(setupId).getString("builtByAccountId")).isEqualTo("e9t2-ADMIN");
        int changes = clubEvents("RingSetupChanged").size();

        clock.advance(Duration.ofDays(2));
        String second = start(placementId, "ADMIN", 201).path("id").asText();
        ok(as(finish(second), CLUB, "ADMIN", "e9t2-rebuilder"), 200);
        assertThat(ringDocument(A1).getString("activeSetupId")).isEqualTo(setupId);
        var refreshed = setupDocument(setupId);
        assertThat(refreshed.getDate("builtAt").toInstant()).isEqualTo(clock.instant());
        assertThat(refreshed.getString("builtByAccountId")).isEqualTo("e9t2-rebuilder");
        assertThat(refreshed.getDate("expiresAt").toInstant()).isAfter(clock.instant());
        assertThat(clubEvents("RingSetupChanged")).hasSize(changes);
        assertThat(clubEvents("BuildSessionFinished").getLast().get("payload", Document.class)).containsEntry("setupId", setupId);

        var other = placementBody(createCourse("Jumping"), A1);
        String otherPlacement = createPlacement("ADMIN", other, 201).path("id").asText();
        String third = start(otherPlacement, "ADMIN", 201).path("id").asText();
        ok(as(finish(third), CLUB, "ADMIN"), 200);
        String replacing = ringDocument(A1).getString("activeSetupId");
        assertThat(replacing).isNotEqualTo(setupId);
        assertThat(setupDocument(setupId).getString("status")).isEqualTo("DISMANTLED");
        assertThat(clubEvents("RingSetupChanged").getLast().get("payload", Document.class)).containsEntry("replacedSetupId", setupId);
    }

    /**
     * R-16-11 «Una sessió oberta > 12 h es tanca sola (S15 P9 neteja)»: from `courses.buildSessionMaxHours` after its start a session
     * takes no tap and no participant; P9 lists it (`WOULD_ABANDON_BUILD_SESSION`), then records it `ABANDONED` once (counter
     * `buildSessionsAbandoned`) and its live stream ends with `finished {ABANDONED}`. With `COURSES` off, P9 leaves sessions alone.
     */
    @Test void T_16_10_aSessionOpenForTwelveHoursIsOverAndP9AbandonsIt() throws Exception {
        String placementId = placement(A1, true);
        var started = start(placementId, "INSTRUCTOR", 201);
        String id = started.path("id").asText();
        var stream = new Stream(id, token("watcher", Role.INSTRUCTOR), true);
        stream.next(":open", LIVE);
        clock.advance(Duration.ofHours(12).minusMinutes(1));
        ok(as(mark(id, "obstacle-0", "PLACED"), CLUB, "INSTRUCTOR"), 200);
        stream.event("obstacle", LIVE);
        assertThat(cleanup(false).items()).noneMatch(item -> item.action().contains("BUILD_SESSION"));
        clock.advance(Duration.ofMinutes(1));
        error(as(mark(id, "obstacle-1", "PLACED"), CLUB, "INSTRUCTOR"), 422, "BUILD_SESSION_FINISHED");
        error(as(finish(id), CLUB, "INSTRUCTOR"), 422, "BUILD_SESSION_FINISHED");
        error(as(join(started.path("joinCode").asText()), CLUB, "INSTRUCTOR", "e9t2-late"), 404, "NOT_FOUND");
        assertThat(session(id).getString("status")).isEqualTo("IN_PROGRESS");

        var dry = cleanup(true);
        assertThat(dry.items()).filteredOn(item -> item.action().contains("BUILD_SESSION")).extracting(JobRun.Item::action, JobRun.Item::entityId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("WOULD_ABANDON_BUILD_SESSION", id));
        assertThat(session(id).getString("status")).isEqualTo("IN_PROGRESS");
        var run = cleanup(false);
        assertThat(counter(run, "buildSessionsAbandoned")).isEqualTo(1);
        var abandoned = session(id);
        assertThat(abandoned.getString("status")).isEqualTo("ABANDONED");
        assertThat(abandoned.getDate("finishedAt").toInstant()).isEqualTo(clock.instant());
        assertThat(stream.event("finished", LIVE).path("status").asText()).isEqualTo("ABANDONED");
        assertThat(stream.exit(Duration.ofSeconds(5))).isZero();
        clock.advance(Duration.ofMinutes(5));
        assertThat(counter(cleanup(false), "buildSessionsAbandoned")).isZero();
        assertThat(ringDocument(A1).get("activeSetupId")).as("an abandoned session registers no setup").isNull();

        String next = start(placementId, "INSTRUCTOR", 201).path("id").asText();
        clock.advance(Duration.ofHours(13));
        modules(Arrays.stream(Module.values()).filter(m -> m != Module.COURSES).toList());
        assertThat(cleanup(false).items()).noneMatch(item -> item.action().contains("BUILD_SESSION"));
        assertThat(session(next).getString("status")).isEqualTo("NOT_STARTED");
    }

    /**
     * E77 / A9: `GET /build-sessions/{id}/export` = `BuildSessionExportV1` with the planner's spellings, which passes the committed
     * schema; a ring with four markers → `422 GEOMETRY_INVALID {violations: [{markers, TOO_FEW_MARKERS}]}`; a stored course the
     * schema refuses makes the self-check fail → `500 INTERNAL_ERROR` without details (E98), never `400 COURSE_MODEL_INVALID`.
     */
    @Test void T_16_03_T_16_10_theExportIsTheUnityContractAndAFailedSelfCheckIsAServerError() throws Exception {
        String placementId = placement(A1, true);
        String id = start(placementId, "INSTRUCTOR", 201).path("id").asText();
        ok(as(mark(id, "obstacle-0", "PLACED"), CLUB, "INSTRUCTOR"), 200);
        var result = perform(as(get("/api/v1/build-sessions/" + id + "/export"), CLUB, "INSTRUCTOR"), 200);
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
        var export = body(result);
        schemas.validateExport(export);
        var venue = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB)), Document.class, "venues");
        assertThat(export.at("/buildSession/id").asText()).isEqualTo(id);
        assertThat(export.at("/buildSession/status").asText()).isEqualTo("in_progress");
        assertThat(export.at("/buildSession/obstacle_statuses/0/status").asText()).isEqualTo("placed");
        assertThat(export.at("/buildSession/obstacle_statuses/1/status").asText()).isEqualTo("not_placed");
        assertThat(export.at("/placement/id").asText()).isEqualTo(placementId);
        assertThat(export.at("/placement/placement_mode").asText()).isEqualTo("preserveMeters");
        assertThat(export.at("/placement/venue_id").asText()).isEqualTo(venue.getString("_id"));
        assertThat(export.at("/ring/name").asText()).isEqualTo("Ring A1");
        assertThat(export.at("/ring/markers")).hasSize(6);
        assertThat(export.at("/course/normalized_json")).isEqualTo(model(3, false));
        assertThat(export.at("/exportedAt").asText()).isEqualTo(clock.instant().toString());

        String fourMarkers = start(placement(A2, false), "INSTRUCTOR", 201).path("id").asText();
        var details = error(as(get("/api/v1/build-sessions/" + fourMarkers + "/export"), CLUB, "INSTRUCTOR"), 422, "GEOMETRY_INVALID");
        assertThat(details.path("violations")).singleElement().satisfies(v -> {
            assertThat(v.path("path").asText()).isEqualTo("markers");
            assertThat(v.path("rule").asText()).isEqualTo("TOO_FEW_MARKERS");
        });

        var placementDoc = mongo.findOne(Query.query(Criteria.where("_id").is(placementId)), Document.class, "placements");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(placementDoc.getString("courseId"))), new Update().unset("normalizedJson.title"), "courses");
        assertThat(error(as(get("/api/v1/build-sessions/" + id + "/export"), CLUB, "INSTRUCTOR"), 500, "INTERNAL_ERROR")).isEmpty();
    }
}
