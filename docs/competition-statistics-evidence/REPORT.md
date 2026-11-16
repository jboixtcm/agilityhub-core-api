# Competition statistics — API validation

Status: implementation and final clean verification complete. This is an executor report, not organizer verification or production acceptance.

## Changes

Added tenant-scoped source adapters, strict FCAG/Flow parsers, exact-licence preview, explicit assignments and atomic confirmation, stale/replayed request protection, member statistics, ownership/module/role guards and privacy export/erasure. The OpenAPI snapshot adds five routes and ten uniquely named schemas; the semantic comparison found no changed or removed existing values. Frontend and backend snapshots are identical.

## Request and boundaries

User-requested implementation on separate `feature/competition-statistics` branches. The isolated checkouts are under `implementation/competition-statistics/`. Existing organizer tasks and verification sections remain unchanged. Production deployment was subsequently authorized as a coordinated release and is recorded separately in the workspace release evidence. No provider mutation, scheduled synchronization or live result import is part of this source validation; STATS activation is a separate club setting.

See [behavior, configuration and limitations](../COMPETITION_STATISTICS.md). FCAG HTTP fetch plus parsing returned 57 rows for 2026–2027. Flow's authenticated server fetch and deployed import preview were verified on 16 November after an owner-entered private login. This resolved the earlier blocked browser-session transfer without reading browser credentials. The session was provisioned in private deployment environments only; the local temporary file was removed and no session was committed. Positive Flow point labels still need a live example; the checked run contained `-`.

## Assumptions and review scope

- This feature uses STATS; SOCIAL_LEAGUE remains separate. New screen copy is proposed in Catalan, Spanish and English because no approved import-screen mockup exists.
- FCAG official net totals are preserved. Flow official points are explicit and never inferred from speed or a clear run.
- Each Flow print page represents one run. Its trial identity uses title plus organizer/date because no trial UUID is exposed; consistent reimport is needed after a provider rename.
- The CSV download is our reviewed interchange template, not a native Flow export claim.
- Task-local test identifiers are T-STATS-01 through T-STATS-32. No existing catalog error, audit or notification code was added.

## Validation

The final clean build passed all tests, architecture rules and package coverage gates. Exact totals and changed test classes are in [final-clean-summary.json](final-clean-summary.json). The complete source fixture tests use fictional identities. The live FCAG probe prints aggregate counts only; no real result rows or browser session values are included in this evidence directory.

## Live FCAG source probe

Command: `java --class-path target/classes:/tmp/competition-m2/org/jsoup/jsoup/1.24.1/jsoup-1.24.1.jar:/tmp/competition-m2/io/swagger/core/v3/swagger-annotations-jakarta/2.2.55/swagger-annotations-jakarta-2.2.55.jar docs/competition-statistics-evidence/VerifyFcagRemote.java`

Exit: 0. Complete aggregate-only output: [fcag-real-parser.log](fcag-real-parser.log). No source rows or personal identities are included.

Last 40 lines:

```text
FCAG server adapter: HTTP fetch + parse succeeded; 57 rows; season 2026-2027
```

## Final clean verification

Command: `./mvnw -q -Dmaven.repo.local=/tmp/competition-m2 clean verify`

Exit: 0. Unit tests: 5385; integration tests: 2587; zero failures/errors/skips. Complete sanitized output: [final-clean.log](final-clean.log).

Last 40 lines:

```text
Migration run: [id truncated]
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run requiring review: [id truncated]
Migration run: [id truncated]
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 159 PENDING records (more than one dispatch() batch)
Migration run: [id truncated]
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: [id truncated]
Migration run requiring review: [id truncated]
Migration run: [id truncated]
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 161 PENDING records (more than one dispatch() batch)
Migration run: [id truncated]
2026-11-15T23:10:46.251+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.migration.application.MigrationWelcomeCommandIT]: MigrationWelcomeCommandIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-11-15T23:10:46.352+01:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.migration.application.MigrationWelcomeCommandIT
2026-11-15T23:10:46.353+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.migration.application.MigrationWelcomeCommandIT]: MigrationWelcomeCommandIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-11-15T23:10:46.353+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.support.AbstractIntegrationTest]: AbstractIntegrationTest does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-11-15T23:10:46.354+01:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.migration.application.MigrationWelcomeCommandIT
E5-T07 outbox backlog discarded before MigrationWelcomeCommandIT: 158 PENDING records (more than one dispatch() batch)
2026-11-15T23:10:46.710+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.migration.MigrationWelcomeIT]: MigrationWelcomeIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-11-15T23:10:46.710+01:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.migration.MigrationWelcomeIT
2026-11-15T23:10:46.711+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.migration.MigrationWelcomeIT]: MigrationWelcomeIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-11-15T23:10:46.711+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.support.AbstractIntegrationTest]: AbstractIntegrationTest does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-11-15T23:10:46.711+01:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.migration.MigrationWelcomeIT

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v4.1.1)

2026-11-15T23:10:46.742+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : Starting MigrationWelcomeIT using Java [redacted] with PID 98280 (started by jordib in /Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/implementation/competition-statistics/api)
2026-11-15T23:10:46.743+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : The following 1 profile is active: "test"
2026-11-15T23:10:47.319+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Bootstrapping Spring Data MongoDB repositories in DEFAULT mode.
2026-11-15T23:10:47.408+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Finished Spring Data repository scanning in 87 ms. Found 0 MongoDB repository interfaces.
2026-11-15T23:10:47.483+01:00 INFO  [main] org.mongodb.driver.client traceId= clubId= accountId= : MongoClient with metadata {"driver": {"name": "mongo-java-driver|spring-boot|sync", "version": "5.8.1"}, "os": {"type": "Darwin", "name": "Mac OS X", "architecture": "aarch64", "version": "26.4.1"}, "platform": "Java/Homebrew/[redacted]"} created with settings MongoClientSettings{readPreference=primary, writeConcern=WriteConcern{w=null, wTimeout=null ms, journal=null}, retryWrites=true, retryReads=true, readConcern=ReadConcern{level=null}, credential=null, transportSettings=null, commandListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener@1711ba0a], codecRegistry=ProvidersCodecRegistry{codecProviders=[ValueCodecProvider{}, BsonValueCodecProvider{}, DBRefCodecProvider{}, DBObjectCodecProvider{}, DocumentCodecProvider{}, CollectionCodecProvider{}, IterableCodecProvider{}, MapCodecProvider{}, GeoJsonCodecProvider{}, GridFSFileCodecProvider{}, Jsr310CodecProvider{}, JsonObjectCodecProvider{}, BsonCodecProvider{}, com.mongodb.client.model.mql.ExpressionCodecProvider@7623cc0d, com.mongodb.Jep395RecordCodecProvider@632dad9b, com.mongodb.KotlinCodecProvider@1ecfdaf4, EnumCodecProvider{}]}, loggerSettings=LoggerSettings{maxDocumentLength=1000}, clusterSettings={hosts=[localhost:56576], srvServiceName=mongodb, mode=SINGLE, requiredClusterType=UNKNOWN, requiredReplicaSetName='null', serverSelector='null', clusterListeners='[]', serverSelectionTimeout='2000 ms', localThreshold='15 ms'}, socketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=0, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], heartbeatSocketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=2000, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], connectionPoolSettings=ConnectionPoolSettings{maxSize=100, minSize=0, maxWaitTimeMS=2000, maxConnectionLifeTimeMS=0, maxConnectionIdleTimeMS=0, maintenanceInitialDelayMS=0, maintenanceFrequencyMS=60000, connectionPoolListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener@4d8f601c], maxConnecting=2}, serverSettings=ServerSettings{heartbeatFrequencyMS=10000, minHeartbeatFrequencyMS=500, serverMonitoringMode=AUTO, serverListeners='[]', serverMonitorListeners='[]'}, sslSettings=SslSettings{enabled=false, invalidHostNameAllowed=false, context=null}, applicationName='null', compressorList=[], uuidRepresentation=JAVA_LEGACY, serverApi=null, autoEncryptionSettings=null, dnsClient=null, inetAddressResolver=null, contextProvider=null, timeoutMS=null}
2026-11-15T23:10:47.485+01:00 INFO  [cluster-6afa2de7586027d648e4a063-localhost:56576] org.mongodb.driver.cluster traceId= clubId= accountId= : Monitor thread successfully connected to server with description ServerDescription{address=localhost:56576, type=REPLICA_SET_PRIMARY, cryptd=false, state=CONNECTED, ok=true, minWireVersion=0, maxWireVersion=21, maxDocumentSize=16777216, logicalSessionTimeoutMinutes=30, roundTripTimeNanos=1470292, minRoundTripTimeNanos=0, setName='docker-rs', canonicalAddress=ecb6aa099dbe:27017, hosts=[ecb6aa099dbe:27017], passives=[], arbiters=[], primary='ecb6aa099dbe:27017', tagSet=TagSet{[]}, electionId=7fffffff0000000000000001, setVersion=1, topologyVersion=TopologyVersion{processId=6afa23d6491a86c0322a1b74, counter=6}, lastWriteDate=Sun Nov 15 23:10:46 CET 2026, lastUpdateTimeNanos=[redacted]}
2026-11-15T23:10:51.078+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : Started MigrationWelcomeIT in 4.351 seconds (process running for 2584.279)
```

## Earlier failures and corrections

The initial full run found two member-data ZIP failures caused by missing competition section labels and one MongoDB timeout. The labels and a per-section/per-locale guard were added; see [export regression evidence](EXPORT_REGRESSION.md). A subsequent local setup attempt omitted two tracked mockup HTML fixtures after a sparse-checkout update; both files are now explicitly included. No product assertion or timeout was weakened. Earlier logs are retained separately.

## Production scope

Jordi subsequently authorized a joint release with the other agents’ changes and explicitly deferred Stripe. This feature branch is preserved separately. Deployment and exact official CI evidence are tracked in the workspace `ops/evidence/2026-11-15-joint-release`; a local green build alone is not a production claim.

## Official merged release CI

[Main CI 43762983600](https://github.com/jboixtcm/agilityhub-core-api/actions/runs/43762983600) passed on `fcbc2617`: 5,387 unit tests and 2,587 integration tests, zero failures/errors/skips. Coverage and OpenAPI checks, secret/dependency/image scans, and multiarchitecture image publication succeeded. This merged tree also contains the logo-cache changes, accounting for two additional unit tests versus the feature branch. Package counters are retained in [official-coverage.json](official-coverage.json).

## Authenticated Flow production verification — 16 November

The owner-created session passed a direct server probe and `POST /api/v1/competition-imports/preview` with only the Flow run URL (no supplied HTML). Three rows matched the provider date/category/discipline and reported score fields; none matched a club dog licence automatically. No confirmation was sent and history remained unchanged. Aggregate proof: [flow-production-preview.json](flow-production-preview.json). Ops login/provisioning helpers passed 18 tests, including destination restrictions, private storage, tenant preservation and failed-restart rollback. The running image and all other runtime settings were preserved.
