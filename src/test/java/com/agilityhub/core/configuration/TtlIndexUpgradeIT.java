package com.agilityhub.core.configuration;

import com.agilityhub.core.identity.persistence.ImpersonationGrant;
import com.agilityhub.core.identity.persistence.ImpersonationGrantRepository;
import com.agilityhub.core.platform.persistence.SecurityEvent;
import com.agilityhub.core.platform.persistence.SecurityEventRepository;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOptions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import static org.assertj.core.api.Assertions.*;

/**
 * INC-82 (E11-T16 step 5, E11-T09 round-2 verification): a TTL index whose retention changed is upgraded with drop + create,
 * never with `collMod`. The production user holds `readWrite` on the database only (`deploy/mongo-health.js`), which grants
 * `createIndex` and `dropIndex` but not `collMod` (MongoDB 7 built-in roles): an `alterIndex` at startup is `Unauthorized (13)`
 * and the Core does not start. Here a Mongo 7 with authentication: root creates `coreApp` as the deploy does, `coreApp` stores
 * an index at another retention (a volume that predates the value), then runs the repositories' startup step.
 */
class TtlIndexUpgradeIT {
    static final String DATABASE = "agilityhub", USER = "coreApp";
    // Generated for each run: no credential is written down anywhere.
    static final String ROOT_PASSWORD = UUID.randomUUID().toString(), APP_PASSWORD = UUID.randomUUID().toString();
    static GenericContainer<?> mongo;
    static MongoClient root, app;

    @BeforeAll static void start() {
        // The image's entrypoint creates the root user on a first mongod, then restarts it with authentication: two «Waiting for connections».
        mongo = new GenericContainer<>("mongo:7").withEnv("MONGO_INITDB_ROOT_USERNAME", "root").withEnv("MONGO_INITDB_ROOT_PASSWORD", ROOT_PASSWORD)
                .withExposedPorts(27017).waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 2).withStartupTimeout(Duration.ofMinutes(2)));
        mongo.start();
        String address = mongo.getHost() + ":" + mongo.getMappedPort(27017);
        root = MongoClients.create("mongodb://root:" + ROOT_PASSWORD + "@" + address + "/?authSource=admin");
        root.getDatabase("admin").runCommand(new Document("createUser", USER).append("pwd", APP_PASSWORD)
                .append("roles", List.of(new Document("role", "readWrite").append("db", DATABASE))));
        app = MongoClients.create("mongodb://" + USER + ":" + APP_PASSWORD + "@" + address + "/" + DATABASE + "?authSource=admin");
    }
    @AfterAll static void stop() {
        if (app != null) { app.close(); }
        if (root != null) { root.close(); }
        if (mongo != null) { mongo.stop(); }
    }
    @BeforeEach void emptyDatabase() { root.getDatabase(DATABASE).drop(); }

    static MongoTemplate asCoreApp() { return new MongoTemplate(app, DATABASE); }
    /** The single-field indexes on {@code field}, whatever their name. */
    static List<IndexInfo> on(MongoTemplate template, Class<?> type, String field) {
        return template.indexOps(type).getIndexInfo().stream()
                .filter(index -> index.getIndexFields().size() == 1 && index.getIndexFields().getFirst().getKey().equals(field)).toList();
    }

    @Test void INC_82_theRoleOfTheProductionUserCannotRunCollMod() {
        var template = asCoreApp();
        template.indexOps(ImpersonationGrant.class).createIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO));
        assertThatThrownBy(() -> template.indexOps(ImpersonationGrant.class).alterIndex("expiresAt_1", IndexOptions.expireAfter(Duration.ofDays(1))))
                .isInstanceOfSatisfying(org.springframework.data.mongodb.UncategorizedMongoDbException.class,
                        failure -> assertThat(((com.mongodb.MongoException) failure.getCause()).getCode()).isEqualTo(13));
    }

    /** INC-37's one day of retention for the impersonation grants, over an `expiresAt_1` stored with `expireAfter(0)`. */
    @Test void INC_82_anImpersonationGrantIndexAtAnotherRetentionIsDroppedAndCreatedAgain() {
        var template = asCoreApp();
        template.indexOps(ImpersonationGrant.class).createIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO));
        System.out.println("E11-T16 step 5 before: " + on(template, ImpersonationGrant.class, "expiresAt"));
        new ImpersonationGrantRepository(template).ensureIndexes();
        System.out.println("E11-T16 step 5 after: " + on(template, ImpersonationGrant.class, "expiresAt"));
        assertThat(on(template, ImpersonationGrant.class, "expiresAt")).singleElement()
                .satisfies(index -> assertThat(index.getExpireAfter()).contains(Duration.ofDays(1)));
        // A second start finds it right and leaves it alone.
        String name = on(template, ImpersonationGrant.class, "expiresAt").getFirst().getName();
        new ImpersonationGrantRepository(template).ensureIndexes();
        assertThat(on(template, ImpersonationGrant.class, "expiresAt")).singleElement().satisfies(index -> assertThat(index.getName()).isEqualTo(name));
    }

    /** `security.eventRetentionDays` changed from 30 to 90: the stored `security_event_retention` index follows. */
    @Test void INC_82_aSecurityEventIndexAtAnotherRetentionIsDroppedAndCreatedAgain() {
        var template = asCoreApp();
        template.indexOps(SecurityEvent.class).createIndex(new Index().on("at", Sort.Direction.ASC).expire(Duration.ofDays(30)).named("security_event_retention"));
        System.out.println("E11-T16 step 5 before: " + on(template, SecurityEvent.class, "at"));
        new SecurityEventRepository(template).ensureIndexes(90);
        System.out.println("E11-T16 step 5 after: " + on(template, SecurityEvent.class, "at"));
        assertThat(on(template, SecurityEvent.class, "at")).singleElement()
                .satisfies(index -> assertThat(index.getExpireAfter()).contains(Duration.ofDays(90)));
        new SecurityEventRepository(template).ensureIndexes(90);
        assertThat(on(template, SecurityEvent.class, "at")).singleElement().satisfies(index -> assertThat(index.getExpireAfter()).contains(Duration.ofDays(90)));
    }

    /** A plain `at_1` index (no TTL) is the retention index's key too: it becomes the TTL index instead of conflicting with it. */
    @Test void INC_82_aSecurityEventIndexWithoutTtlOnTheSameKeyBecomesTheRetentionIndex() {
        var template = asCoreApp();
        template.indexOps(SecurityEvent.class).createIndex(new Index().on("at", Sort.Direction.ASC));
        new SecurityEventRepository(template).ensureIndexes(90);
        assertThat(on(template, SecurityEvent.class, "at")).singleElement()
                .satisfies(index -> assertThat(index.getExpireAfter()).contains(Duration.ofDays(90)));
    }
}
