package com.agilityhub.core.courses.application;

import com.agilityhub.core.clubs.census.application.DemoSeedCommand;
import com.agilityhub.core.platform.application.definition.*;
import com.agilityhub.core.support.AbstractIntegrationTest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;

/**
 * E9-T05 step 1: `seed:demo` gives the Cànic its S16 data through the services (the obstacle inventory, two ring geometries with six
 * markers and the venue, three club courses, two placements, three active setups — one from a placement, one from a course, one
 * registered by a finished build session — and that session `COMPLETED` with every obstacle placed); the FIFO club, without
 * `COURSES` and without the section, gets none; and a second run changes nothing (idempotent, as every demo section).
 */
@TestPropertySource(properties = "identity.seed-password=Fictional-seed-password")
class DemoCoursesSeedIT extends AbstractIntegrationTest {
    @Autowired ClubDefinitions definitions; @Autowired ClubDefinitionCodec codec; @Autowired DemoSeedCommand command; @Autowired MongoTemplate mongo;
    String club;
    @BeforeEach void clear() {
        wipeDatabaseKeepingBootstrap(); clock.setInstant(Instant.parse("2026-09-09T10:00:00Z"));
        club = definitions.apply(codec.read(Path.of("seeds/club-canic.yaml")), false).id();
    }
    void seed(String slug) { command.run(new DefaultApplicationArguments("--club=" + slug, "--seed=42", "--week-start=2026-09-14")); }
    List<Document> of(String collection, String clubId) { return mongo.find(Query.query(Criteria.where("clubId").is(clubId)), Document.class, collection); }
    String ring(String name) { return mongo.findOne(Query.query(Criteria.where("clubId").is(club).and("name").is(name)), Document.class, "rings").getString("_id"); }
    Map<String, List<Document>> snapshot() {
        var result = new TreeMap<String, List<Document>>();
        for (String name : mongo.getCollectionNames()) { result.put(name, mongo.findAll(Document.class, name)); }
        return result;
    }

    @Test void T_16_08_T_16_10_theDemoSeedHoldsTheS16HappyPathThroughTheServicesAndRepeatsWithoutWrites() {
        seed("canic");
        String central = ring("Central"), carretera = ring("Carretera"), muntanya = ring("Muntanya");
        var courses = of("courses", club);
        assertThat(courses).extracting(c -> c.get("name", Document.class).get("values", Document.class).getString("ca"))
                .containsExactlyInAnyOrder("Jumping C–D de dimarts", "Agility A–B d'iniciació", "Joc de túnels");
        // The club's languages only (ca, es): the seed's English names wait for a club that has English (R-16-17).
        assertThat(courses).allSatisfy(c -> assertThat(((Document) c.get("name")).get("values", Document.class).keySet()).containsExactly("ca", "es"));
        for (var c : courses) {
            assertThat(c).containsEntry("ownerType", "CLUB").containsEntry("visibility", "CLUB").containsEntry("source", "SMARTER").containsEntry("schemaVersion", 1);
            assertThat(c.get("deletedAt")).isNull();
        }
        var jumping = courses.stream().filter(c -> c.getString("discipline").equals("JUMPING")).findFirst().orElseThrow();
        assertThat(jumping.get("stats", Document.class).get("pathLengthM", Number.class).doubleValue()).isEqualTo(64.5);
        assertThat(jumping.getList("levelIds", String.class)).hasSize(2);
        for (String ring : List.of(central, carretera)) {
            var geometry = mongo.findById(ring, Document.class, "rings").get("geometry", Document.class);
            assertThat(geometry.getList("markers", Document.class)).hasSize(6);
            assertThat(geometry.get("version", Number.class).longValue()).isEqualTo(1);
        }
        assertThat(mongo.findById(muntanya, Document.class, "rings").get("geometry")).isNull();
        assertThat(of("venues", club)).hasSize(1);
        assertThat(of("obstacle_inventories", club)).hasSize(1);
        assertThat(of("obstacle_inventories", club).getFirst().getString("scope")).isEqualTo("CLUB");
        var placements = of("placements", club);
        assertThat(placements).hasSize(2);
        for (var p : placements) {
            assertThat(p).containsEntry("status", "READY_TO_BUILD").containsEntry("stale", false);
            assertThat(p.getList("resolvedObstacles", Document.class)).hasSize(10);
        }
        var central1 = placements.stream().filter(p -> p.getString("ringId").equals(central)).findFirst().orElseThrow();
        // The model's first obstacle (4, 4) moved by the offset (1, 1).
        var first = central1.getList("resolvedObstacles", Document.class).getFirst();
        assertThat(first.get("x", Number.class).doubleValue()).isEqualTo(5); assertThat(first.get("y", Number.class).doubleValue()).isEqualTo(5);
        var active = new HashMap<String, Document>();
        of("ring_setups", club).forEach(s -> { assertThat(s.getString("status")).isEqualTo("ACTIVE"); active.put(s.getString("ringId"), s); });
        assertThat(active).containsOnlyKeys(central, carretera, muntanya);
        assertThat(active.get(carretera)).containsEntry("kind", "AGILITY").containsEntry("expectedUntil", "2026-09-12");
        assertThat(active.get(muntanya)).containsEntry("kind", "FUN").containsEntry("agilityhubLevel", "EASY");
        assertThat(active.get(muntanya).getString("courseId")).isNotNull();
        assertThat(active.get(central)).containsEntry("kind", "JUMPING").containsEntry("placementId", central1.getString("_id"));
        for (var entry : active.entrySet()) {
            assertThat(mongo.findById(entry.getKey(), Document.class, "rings").getString("activeSetupId")).isEqualTo(entry.getValue().getString("_id"));
        }
        var session = of("build_sessions", club);
        assertThat(session).hasSize(1);
        assertThat(session.getFirst()).containsEntry("status", "COMPLETED").containsEntry("placementId", central1.getString("_id"));
        assertThat(session.getFirst().getList("obstacles", Document.class)).hasSize(10).allSatisfy(o -> assertThat(o.getString("status")).isEqualTo("PLACED"));
        var finished = mongo.find(Query.query(Criteria.where("clubId").is(club).and("type").is("BuildSessionFinished")), Document.class, "domain_events");
        assertThat(finished).hasSize(1);
        assertThat(finished.getFirst().get("payload", Document.class).getString("setupId")).isEqualTo(active.get(central).getString("_id"));
        var run = mongo.findById(club + ":planning", Document.class, "demo_seed_runs").get("counts", Document.class);
        assertThat(run).containsEntry("courses", 3).containsEntry("ringGeometries", 2).containsEntry("placements", 2).containsEntry("ringSetups", 3)
                .containsEntry("buildSessions", 1).containsEntry("obstacleInventories", 1);

        String fifo = definitions.apply(codec.read(Path.of("seeds/club-fifo.yaml")), false).id();
        seed("fifo");
        for (String collection : List.of("courses", "placements", "ring_setups", "build_sessions", "obstacle_inventories")) { assertThat(of(collection, fifo)).isEmpty(); }

        var saved = snapshot(); seed("canic"); seed("fifo");
        assertThat(snapshot()).isEqualTo(saved);
    }
}
