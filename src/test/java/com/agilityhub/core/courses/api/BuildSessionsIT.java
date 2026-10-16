package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.application.BuildSessionDraws;
import com.agilityhub.core.courses.application.BuildSessionService;
import com.agilityhub.core.courses.application.CourseSchemas;
import com.agilityhub.core.shared.application.TransactionRetries;
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
 * and the async dispatch that ends them. E9-T06 (review of E9-T04, ruling E118): keyed writes that race are retried, never a 500;
 * `finish` without the publish switch; a refreshed setup keeps its `expectedUntil`; six active markers for the export; a stream
 * lives no longer than its token and ends with its outlived session; the query token stays out of every other route and the logs.
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
    @Autowired BuildSessionService service;
    @Autowired TransactionRetries retries;
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
    /** A placement on A1 (six markers) of a new course with {@code count} resolved obstacles, `obstacle-0` … */
    String placementWith(int count) throws Exception {
        ok(as(json(put("/api/v1/rings/" + A1 + "/geometry"), sixMarkers()), CLUB, "ADMIN"), 200);
        String courseId = ok(as(json(post("/api/v1/courses"), course("Central " + count, model(count, false))), CLUB, "INSTRUCTOR"), 201).path("id").asText();
        var body = placementBody(courseId, A1);
        var resolved = body.putArray("resolvedObstacles");
        for (int i = 0; i < count; i++) {
            resolved.addObject().put("obstacleId", "obstacle-" + i).put("type", "Jump").put("x", 5 + i * 4).put("y", 6).put("rotationDeg", 0).putArray("numbers").add(i + 1);
        }
        return createPlacement("INSTRUCTOR", body, 201).path("id").asText();
    }
    /** The requests sent at once, each from its own thread; their responses in the same order. */
    List<org.springframework.mock.web.MockHttpServletResponse> race(List<MockHttpServletRequestBuilder> requests) throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(requests.size());
        try {
            var go = new java.util.concurrent.CountDownLatch(1);
            var answers = new ArrayList<java.util.concurrent.Future<org.springframework.mock.web.MockHttpServletResponse>>();
            for (var request : requests) { answers.add(pool.submit(() -> { go.await(); return mvc.perform(request).andReturn().getResponse(); })); }
            go.countDown();
            var responses = new ArrayList<org.springframework.mock.web.MockHttpServletResponse>();
            for (var answer : answers) { responses.add(answer.get(60, TimeUnit.SECONDS)); }
            return responses;
        } finally { pool.shutdownNow(); }
    }
    String code(org.springframework.mock.web.MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString();
        return body.startsWith("{") ? mapper.readTree(body).path("code").asText() : "";
    }
    /** A one-shot request to the real server, the URL (and so any token) passed on stdin, never on the command line; its HTTP status. */
    int curl(String method, String path) throws Exception {
        var process = new ProcessBuilder("curl", "--silent", "--output", "/dev/null", "--write-out", "%{http_code}", "--request", method, "--config", "-")
                .redirectErrorStream(true).start();
        try (var input = process.outputWriter()) { input.write("url = \"http://localhost:" + port + path + "\"\nheader = \"Host: " + HOST + "\"\n"); }
        String status = new String(process.getInputStream().readAllBytes()).strip();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        return Integer.parseInt(status);
    }
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
     * MEMBER's real token → `403`. E9-T06: every other bearer operation of the contract, and every other method on the stream's
     * path, refuses it with `401`.
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
        // E9-T06 step 9: no other operation of the published contract that needs a bearer takes it, and no other method on the
        // stream's path does either.
        var contract = mapper.readTree(java.nio.file.Path.of("docs/openapi/openapi.json").toFile());
        var refused = new ArrayList<String>();
        for (var path : contract.path("paths").properties()) {
            for (var operation : path.getValue().properties()) {
                if (!operation.getValue().path("security").isMissingNode()) { continue; } // only the global bearer requirement
                var method = org.springframework.http.HttpMethod.valueOf(operation.getKey().toUpperCase(Locale.ROOT));
                String uri = path.getKey().replaceAll("\\{[^}]+}", "e9t06-x") + "?access_token=" + instructor;
                var answer = mvc.perform(request(method, uri).header("Host", HOST)).andReturn().getResponse();
                assertThat(answer.getStatus() + ":" + code(answer)).as(method + " " + path.getKey()).isEqualTo("401:UNAUTHENTICATED");
                refused.add(method + " " + path.getKey());
            }
        }
        assertThat(refused).hasSizeGreaterThan(300).contains("GET /api/v1/build-sessions/{id}", "POST /api/v1/build-sessions/{id}/finish")
                .doesNotContain("GET /api/v1/build-sessions/{id}/events");
        for (var method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            error(request(org.springframework.http.HttpMethod.valueOf(method), events + "?access_token=" + instructor).header("Host", HOST), 401, "UNAUTHENTICATED");
        }
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
     * R-16-11 «crea/actualitza el `RingSetup`» (E118): `finish` is not gated by `courses.allowInstructorPublish`; with it off, an
     * INSTRUCTOR's finish closes the session (`COMPLETED`, `BuildSessionFinished{setupId: null}`) and registers no setup
     * (`setupId` null in the answer and in `GET`), and an ADMIN registers it with `POST /ring-setups`; an ADMIN's finish registers
     * one. A session on the placement the ring's active setup already shows refreshes that setup (built now, a new `expiresAt`, no
     * `RingSetupChanged`); a session on another placement of the ring replaces it.
     */
    @Test void T_16_10_finishRegistersOrRefreshesTheRingsSetupUnderThePublishingSwitch() throws Exception {
        String placementId = placement(A1, true);
        String first = start(placementId, "INSTRUCTOR", 201).path("id").asText();
        parameter("courses.allowInstructorPublish", false, "bool");
        var closed = ok(as(finish(first), CLUB, "INSTRUCTOR"), 200);
        assertThat(closed.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(closed.path("finishedAt").asText()).isEqualTo(clock.instant().toString());
        assertThat(closed.has("setupId")).isTrue();
        assertThat(closed.path("setupId").isNull()).as("no setup registered").isTrue();
        assertThat(ok(as(get("/api/v1/build-sessions/" + first), CLUB, "INSTRUCTOR"), 200).path("setupId").isNull()).isTrue();
        assertThat(session(first).getString("status")).isEqualTo("COMPLETED");
        assertThat(ringDocument(A1).get("activeSetupId")).isNull();
        assertThat(clubEvents("RingSetupChanged")).isEmpty();
        assertThat(clubEvents("BuildSessionFinished").getLast().get("payload", Document.class))
                .isEqualTo(new Document("sessionId", first).append("setupId", null));
        error(as(finish(first), CLUB, "ADMIN"), 422, "BUILD_SESSION_FINISHED");
        var registered = registerSetup(CLUB, "ADMIN", setupBody(A1, "AGILITY").put("placementId", placementId), 201);
        String adminSetup = registered.at("/setup/id").asText();
        assertThat(ringDocument(A1).getString("activeSetupId")).isEqualTo(adminSetup);

        String byAdmin = start(placementId, "ADMIN", 201).path("id").asText();
        var adminFinished = ok(as(finish(byAdmin), CLUB, "ADMIN"), 200);
        String setupId = ringDocument(A1).getString("activeSetupId");
        assertThat(adminFinished.path("setupId").asText()).isEqualTo(setupId).isEqualTo(adminSetup);
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
     * takes no tap and no participant, and its live streams end at the next poll with `finished {ABANDONED, finishedAt: null}`
     * (E9-T06, review #7); P9 lists it (`WOULD_ABANDON_BUILD_SESSION`), then records it `ABANDONED` once (counter
     * `buildSessionsAbandoned`), with its `finishedAt` from then on. With `COURSES` off, P9 leaves sessions alone.
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
        // E9-T06 (review #7): the next poll ends the stream, before P9 has recorded anything; a stream opened now ends at once.
        var over = stream.event("finished", LIVE);
        assertThat(over.path("status").asText()).isEqualTo("ABANDONED");
        assertThat(over.path("finishedAt").isNull()).isTrue();
        assertThat(stream.exit(Duration.ofSeconds(5))).isZero();
        var late = new Stream(id, token("late-watcher", Role.INSTRUCTOR), false);
        assertThat(late.event("finished", LIVE).path("status").asText()).isEqualTo("ABANDONED");
        assertThat(late.exit(Duration.ofSeconds(5))).isZero();
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
        var after = new Stream(id, token("after-watcher", Role.INSTRUCTOR), true);
        var recorded = after.event("finished", LIVE);
        assertThat(recorded.path("status").asText()).isEqualTo("ABANDONED");
        assertThat(recorded.path("finishedAt").asText()).isEqualTo(clock.instant().toString());
        assertThat(after.exit(Duration.ofSeconds(5))).isZero();
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
     * schema; a ring with four markers → `422 GEOMETRY_INVALID {violations: [{markers, TOO_FEW_MARKERS}]}`, and only six active
     * markers export (E118: five active → `TOO_FEW_MARKERS`, seven → `TOO_MANY_MARKERS`); a stored course the
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
        // E118 (review #4): exactly six **active** markers. Six with one inactive are five; seven active are too many; seven with
        // one inactive export the six active ones only.
        var ringA1 = Query.query(Criteria.where("_id").is(A1));
        String exportPath = "/api/v1/build-sessions/" + id + "/export";
        mongo.updateFirst(ringA1, new Update().set("geometry.markers.5.isActive", false), "rings");
        assertThat(error(as(get(exportPath), CLUB, "INSTRUCTOR"), 422, "GEOMETRY_INVALID").path("violations")).singleElement()
                .satisfies(v -> assertThat(v.path("path").asText() + ":" + v.path("rule").asText()).isEqualTo("markers:TOO_FEW_MARKERS"));
        var seventh = new Document("id", "marker-6").append("label", "G").append("markerUid", "uid-6").append("role", "ANCHOR").append("xM", 30.0)
                .append("yM", 11.0).append("zM", 0.0).append("rotationYDeg", 0.0).append("physicalWidthM", 0.2).append("physicalHeightM", 0.2)
                .append("isFixed", true).append("isActive", true).append("aprilTagId", 6).append("notes", null);
        mongo.updateFirst(ringA1, new Update().set("geometry.markers.5.isActive", true), "rings");
        mongo.updateFirst(ringA1, new Update().push("geometry.markers", seventh), "rings");
        assertThat(error(as(get(exportPath), CLUB, "INSTRUCTOR"), 422, "GEOMETRY_INVALID").path("violations")).singleElement()
                .satisfies(v -> assertThat(v.path("path").asText() + ":" + v.path("rule").asText()).isEqualTo("markers:TOO_MANY_MARKERS"));
        mongo.updateFirst(ringA1, new Update().set("geometry.markers.6.isActive", false), "rings");
        var sixActive = ok(as(get(exportPath), CLUB, "INSTRUCTOR"), 200);
        schemas.validateExport((ObjectNode) sixActive);
        assertThat(sixActive.at("/ring/markers")).extracting(m -> m.path("id").asText())
                .containsExactly("marker-0", "marker-1", "marker-2", "marker-3", "marker-4", "marker-5");
        assertThat(sixActive.at("/ring/markers")).allMatch(m -> m.path("is_active").asBoolean());

        var placementDoc = mongo.findOne(Query.query(Criteria.where("_id").is(placementId)), Document.class, "placements");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(placementDoc.getString("courseId"))), new Update().unset("normalizedJson.title"), "courses");
        assertThat(error(as(get("/api/v1/build-sessions/" + id + "/export"), CLUB, "INSTRUCTOR"), 500, "INTERNAL_ERROR")).isEmpty();
    }

    // ---- E9-T06 ----

    /**
     * Review #1 (INC-47's pattern): a keyed `finish` racing taps — their commits meet its write on the session document — runs in
     * its own transaction retried whole, never a 500. In each of three rounds seven phones tap while an eighth finishes: the finish
     * answers 200 and closes the session once; each tap answers 200 (before it) or 422 BUILD_SESSION_FINISHED (after it).
     */
    @Test void T_16_10_aKeyedFinishRacingTapsIsRetriedAndNeverAnswers500() throws Exception {
        String placementId = placementWith(8);
        for (int round = 0; round < 3; round++) {
            String id = start(placementId, "INSTRUCTOR", 201).path("id").asText();
            var requests = new ArrayList<MockHttpServletRequestBuilder>();
            for (int i = 1; i < 8; i++) { requests.add(as(mark(id, "obstacle-" + i, "PLACED"), CLUB, "INSTRUCTOR", "e9t2-phone-" + i)); }
            requests.add(as(finish(id), CLUB, "INSTRUCTOR", "e9t2-finisher"));
            var answers = race(requests);
            var finished = answers.getLast();
            assertThat(finished.getStatus()).as("round %d finish: %s", round, finished.getContentAsString()).isEqualTo(200);
            assertThat(mapper.readTree(finished.getContentAsString()).path("status").asText()).isEqualTo("COMPLETED");
            for (var tap : answers.subList(0, 7)) {
                assertThat(tap.getStatus() + ":" + code(tap)).as("round %d tap: %s", round, tap.getContentAsString()).isIn("200:", "422:BUILD_SESSION_FINISHED");
            }
            assertThat(session(id).getString("status")).isEqualTo("COMPLETED");
            assertThat(clubEvents("BuildSessionFinished")).filteredOn(e -> id.equals(e.get("payload", Document.class).getString("sessionId"))).hasSize(1);
        }
    }

    /**
     * Review #1: phones joining one session at once, each with its own `Idempotency-Key` (their pushes to `participants` meet on
     * the session document), all join, never a 500: three sessions, eight phones each.
     */
    @Test void T_16_10_phonesJoiningAtOnceWithKeysAllJoin() throws Exception {
        String placementId = placement(A1, true);
        for (int round = 0; round < 3; round++) {
            var started = start(placementId, "INSTRUCTOR", 201);
            String id = started.path("id").asText(), code = started.path("joinCode").asText();
            var requests = new ArrayList<MockHttpServletRequestBuilder>();
            for (int i = 0; i < 8; i++) {
                requests.add(as(join(code), CLUB, "INSTRUCTOR", "e9t2-joiner-" + i).header("Idempotency-Key", UUID.randomUUID().toString()));
            }
            for (var answer : race(requests)) { assertThat(answer.getStatus()).as("round %d: %s", round, answer.getContentAsString()).isEqualTo(200); }
            assertThat(session(id).getList("participants", Document.class)).extracting(p -> p.getString("accountId")).hasSize(8).doesNotHaveDuplicates();
        }
    }

    /**
     * Review #1: a keyed `POST /build-sessions` whose join code another session took after this transaction began (it cannot see
     * it, and the partial unique index `open_join_code` refuses the insert) is retried whole and draws again: `201` with another
     * code, never a 500. The answer is stored with its key in the attempt that committed: the same request again replays it.
     */
    @Test void T_16_10_aKeyedStartThatDrawsATakenJoinCodeDrawsAgain() throws Exception {
        String placementId = placement(A1, true);
        var draws = new java.util.concurrent.atomic.AtomicInteger();
        var random = new java.security.SecureRandom();
        double before = retries.retries("build-sessions");
        BuildSessionDraws.set(service, bound -> {
            if (draws.incrementAndGet() == 1) {
                // Another phone's session takes `AAAAAA` now, committed outside this request's transaction.
                java.util.concurrent.CompletableFuture.runAsync(() -> mongo.getCollection("build_sessions").insertOne(new Document("_id", "e9t2-taken")
                        .append("clubId", CLUB).append("status", "NOT_STARTED").append("joinCode", "AAAAAA").append("placementId", placementId))).join();
            }
            return draws.get() <= 6 ? 0 : random.nextInt(bound);
        });
        try {
            String key = UUID.randomUUID().toString();
            var request = json(post("/api/v1/build-sessions"), Map.of("placementId", placementId, "live", true));
            var started = ok(as(request, CLUB, "INSTRUCTOR").header("Idempotency-Key", key), 201);
            assertThat(started.path("joinCode").asText()).isNotEqualTo("AAAAAA").matches("[ABCDEFGHJKMNPQRSTUVWXYZ2-9]{6}");
            assertThat(draws.get()).as("drawn again after the retry").isGreaterThan(6);
            assertThat(retries.retries("build-sessions")).isGreaterThan(before);
            var replayed = ok(as(json(post("/api/v1/build-sessions"), Map.of("placementId", placementId, "live", true)), CLUB, "INSTRUCTOR")
                    .header("Idempotency-Key", key), 201);
            assertThat(replayed).isEqualTo(started);
            assertThat(clubEvents("BuildSessionStarted")).hasSize(1);
        } finally { BuildSessionDraws.set(service, random::nextInt); }
    }

    /**
     * Review #3: a finish that refreshes the ring's active setup keeps the `expectedUntil` a person set and recomputes `expiresAt`
     * from it, as a renewal does: `min(expectedUntil, builtAt + courses.setupAutoExpireDays)`; like a renewal it writes
     * `updatedAt`/`updatedByAccountId` and no audit entry or event.
     */
    @Test void T_16_08_T_16_10_aRefreshedSetupKeepsItsExpectedUntil() throws Exception {
        clock.setInstant(RingSetupsIT.MONDAY);
        String placementId = placement(A1, true);
        String setupId = registerSetup(CLUB, "ADMIN", setupBody(A1, "AGILITY").put("placementId", placementId).put("expectedUntil", "2026-10-09"), 201)
                .at("/setup/id").asText();
        int changes = clubEvents("RingSetupChanged").size();
        long audits = count("audit_entries", Query.query(Criteria.where("clubId").is(CLUB)));
        clock.setInstant(RingSetupsIT.local("2026-10-07T18:00"));
        String id = start(placementId, "INSTRUCTOR", 201).path("id").asText();
        assertThat(ok(as(finish(id), CLUB, "INSTRUCTOR", "e9t2-rebuilder"), 200).path("setupId").asText()).isEqualTo(setupId);
        var refreshed = ok(as(get("/api/v1/ring-setups/" + setupId), CLUB, "ADMIN"), 200);
        assertThat(refreshed.path("expectedUntil").asText()).isEqualTo("2026-10-09");
        // «Fins quan?» Friday 9-10 is earlier than Wednesday + 7 days: it still ends when Saturday 10-10 starts in Madrid.
        assertThat(Instant.parse(refreshed.path("expiresAt").asText())).isEqualTo(RingSetupsIT.local("2026-10-10T00:00"));
        assertThat(refreshed.path("builtAt").asText()).isEqualTo(clock.instant().toString());
        assertThat(refreshed.path("builtByAccountId").asText()).isEqualTo("e9t2-rebuilder");
        var stored = setupDocument(setupId);
        assertThat(stored.getDate("updatedAt").toInstant()).isEqualTo(clock.instant());
        assertThat(stored.getString("updatedByAccountId")).isEqualTo("e9t2-rebuilder");
        assertThat(clubEvents("RingSetupChanged")).hasSize(changes);
        assertThat(count("audit_entries", Query.query(Criteria.where("clubId").is(CLUB)))).isEqualTo(audits);
    }

    /**
     * Review #6: a stream lives no longer than the access token it opened with. A real token with three seconds left opens the
     * stream, which ends cleanly (curl exits 0, EventSource reconnects with a new token) once the token has expired, not after 30 min.
     */
    @Test void T_16_10_aStreamEndsWhenItsTokenExpires() throws Exception {
        String id = start(placement(A1, true), "INSTRUCTOR", 201).path("id").asText();
        String shortLived = token("short-lived", Role.INSTRUCTOR);
        clock.advance(TokenService.ACCESS_TTL.minusSeconds(3));
        var stream = new Stream(id, shortLived, true);
        stream.next(":open", LIVE);
        long opened = System.nanoTime();
        assertThat(stream.exit(Duration.ofSeconds(10))).as("a clean end").isZero();
        assertThat(Duration.ofNanos(System.nanoTime() - opened)).isGreaterThan(Duration.ofSeconds(2)).isLessThan(Duration.ofSeconds(8));
        assertThat(session(id).getString("status")).isEqualTo("NOT_STARTED");
    }

    /** Review #8: a finish on a ring that lost its geometry → `422 RING_WITHOUT_GEOMETRY`, as the published contract lists it. */
    @Test void T_16_10_aFinishOnARingWithoutGeometryIsInItsContract() throws Exception {
        String id = start(placement(A1, true), "INSTRUCTOR", 201).path("id").asText();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(A1)), new Update().unset("geometry"), "rings");
        error(as(finish(id), CLUB, "ADMIN"), 422, "RING_WITHOUT_GEOMETRY");
        assertThat(session(id).getString("status")).isEqualTo("NOT_STARTED");
        var contract = mapper.readTree(java.nio.file.Path.of("docs/openapi/openapi.json").toFile());
        assertThat(contract.at("/paths/~1api~1v1~1build-sessions~1{id}~1finish/post/responses/422/description").asText()).contains("RING_WITHOUT_GEOMETRY");
    }

    /**
     * E9-T04's question 3: the query token never reaches a log. Through the real server, a stream opened with `?access_token=`, a
     * tap it receives, the same token on another route and on a non-GET to the stream's path (both `401`), and an invalid one:
     * nothing the app writes (RequestTraceFilter's MDC, the security chain, Tomcat, the stream) holds the token or any query string.
     */
    @Test @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void T_16_10_theQueryTokenNeverReachesTheLogs(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String id = start(placement(A1, true), "INSTRUCTOR", 201).path("id").asText();
        String token = token("logged", Role.INSTRUCTOR);
        var stream = new Stream(id, token, true);
        stream.next(":open", LIVE);
        ok(as(mark(id, "obstacle-0", "PLACED"), CLUB, "INSTRUCTOR"), 200);
        stream.event("obstacle", LIVE);
        assertThat(curl("GET", "/api/v1/build-sessions/" + id + "?access_token=" + token)).isEqualTo(401);
        assertThat(curl("POST", "/api/v1/build-sessions/" + id + "/events?access_token=" + token)).isEqualTo(401);
        assertThat(curl("GET", "/api/v1/build-sessions/" + id + "/events?access_token=not-a-token")).isEqualTo(401);
        ok(as(finish(id), CLUB, "INSTRUCTOR"), 200);
        assertThat(stream.exit(Duration.ofSeconds(5))).isZero();
        String signature = token.substring(token.lastIndexOf('.') + 1);
        assertThat(output.getAll()).doesNotContain(token, signature, "not-a-token", "access_token", "access_[redacted]");
    }
}
