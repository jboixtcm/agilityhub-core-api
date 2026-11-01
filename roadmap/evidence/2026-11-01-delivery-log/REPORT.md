# Admin delivery log — safe recoverable email diagnostics

User-requested feedback point 4, 2026-11-01. This evidence records local validation and source preparation; deployment and provider configuration have separate receipts.

## Result

The existing ADMIN notification log searches notification code, stored safe subject and recipient identity within the authenticated club. The list, facets and export use the same search contract. Access emails keep empty credential-bearing content and receive a safe client label.

Brevo `soft_bounce` and `deferred` record receipt time and a bounded classified reason on the correlated EMAIL delivery. The provider payload and raw SMTP reason are not persisted. Tenant, recipient and provider-message correlation, authenticated webhook access and event deduplication remain enforced. Temporary incidents do not suppress a recipient, enqueue a retry or change QUEUED/SENT into a terminal failure. A subsequent delivery succeeds and retains history; a late temporary event cannot regress a delivered notification. Optional metadata is omitted when absent for backwards-compatible JSON.

SYSTEM emails lacking a club remain outside club administration. The user-requested reconciliation is separately scoped to two already identified club-bound notifications and is not part of these tests. No production data or provider operation is included in this commit.

The web distinguishes provider acceptance from confirmed delivery and displays temporary history. Existing exports retain the technical status; temporary incident history is available in the detail view, not in CSV/PDF.

## Validation

- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx2g' ./mvnw -q clean verify`: exit 0. Surefire: {'tests': 5007, 'failures': 0, 'errors': 0, 'skipped': 0}. Failsafe: {'tests': 2357, 'failures': 0, 'errors': 0, 'skipped': 0}. Coverage gates passed. Complete output: `01-clean-verify.log`; class totals: `05-test-summary.json`.
- Affected unit/contract/integration subset: 76 passed, no failures/errors (`02-affected-tests.log`).
- `bin/openapi-snapshot`: exit 0 (`03-openapi-snapshot.log`). Generated contract copied into the web API client and regenerated there.
- Earlier full attempts stopped on stale contract expectations: the response field whitelist and the newly searchable log/facets/export assertions were updated; the facet controller now declares optional `q`. Logs `attempt-01-contract-assertions.log` and `attempt-02-search-contract.log` are retained. No production rule was relaxed.
- Baseline: `92c97cf04d8f`; all validated input hashes and changed paths: `04-tested-inputs.json`.

Changed test classes:

```json
[
  {
    "name": "com.agilityhub.core.clubs.messaging.application.BrevoTemporaryReasonTest",
    "tests": 1,
    "failures": 0,
    "errors": 0,
    "skipped": 0
  },
  {
    "name": "com.agilityhub.core.configuration.E5BackOfficeContractTest",
    "tests": 7,
    "failures": 0,
    "errors": 0,
    "skipped": 0
  },
  {
    "name": "com.agilityhub.core.configuration.E7ResponseContractTest",
    "tests": 5,
    "failures": 0,
    "errors": 0,
    "skipped": 0
  },
  {
    "name": "com.agilityhub.core.clubs.bookings.api.BackOfficeContractIT",
    "tests": 8,
    "failures": 0,
    "errors": 0,
    "skipped": 0
  },
  {
    "name": "com.agilityhub.core.clubs.messaging.api.BrevoWebhookIT",
    "tests": 20,
    "failures": 0,
    "errors": 0,
    "skipped": 0
  },
  {
    "name": "com.agilityhub.core.clubs.messaging.api.NotificationLogIT",
    "tests": 6,
    "failures": 0,
    "errors": 0,
    "skipped": 0
  },
  {
    "name": "com.agilityhub.core.configuration.E7ContractIT",
    "tests": 29,
    "failures": 0,
    "errors": 0,
    "skipped": 0
  }
]
```

Final command tail:

```text
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: 7ab60157-6a38-468c-b717-36d9300355af
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: 95483fe2-9e55-4e4e-8b67-c3c1a1fedcb3
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: ea8d5d1f-f865-423f-8479-f709ca5e0a23
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: 38f7e7b1-8113-4daa-a408-31243330d3b8
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run requiring review: 58f4ab67-5d0a-4d48-9588-38c6ddb88adb
Migration run: 6532b99b-45dd-45f3-adb2-fc44f9e2ca7c
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 159 PENDING records (more than one dispatch() batch)
Migration run: be3fee41-23ef-46d5-b3db-f49fa47d63e2
Migration run requiring review: be3fee41-23ef-46d5-b3db-f49fa47d63e2
Migration run: 2daf21c3-f6ff-4d91-a19f-4452e209cc35
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 161 PENDING records (more than one dispatch() batch)
Migration run: 7e782a9a-3505-4f18-bda9-501ec96f819d
2026-11-01T19:18:31.640+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.migration.MigrationWelcomeIT]: MigrationWelcomeIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-11-01T19:18:31.641+01:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.migration.MigrationWelcomeIT
2026-11-01T19:18:31.642+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.migration.MigrationWelcomeIT]: MigrationWelcomeIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-11-01T19:18:31.642+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.support.AbstractIntegrationTest]: AbstractIntegrationTest does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-11-01T19:18:31.642+01:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.migration.MigrationWelcomeIT

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v4.1.1)

2026-11-01T19:18:31.660+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : Starting MigrationWelcomeIT using Java [redacted] with PID 90795 (started by jordib in /Users/jordib/.config/agilityhub-clubs/production/admin-delivery-log-20261101/api-source)
2026-11-01T19:18:31.661+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : The following 1 profile is active: "test"
2026-11-01T19:18:32.327+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Bootstrapping Spring Data MongoDB repositories in DEFAULT mode.
2026-11-01T19:18:32.361+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Finished Spring Data repository scanning in 33 ms. Found 0 MongoDB repository interfaces.
2026-11-01T19:18:32.407+01:00 INFO  [main] org.mongodb.driver.client traceId= clubId= accountId= : MongoClient with metadata {"driver": {"name": "mongo-java-driver|spring-boot|sync", "version": "5.8.1"}, "os": {"type": "Darwin", "name": "Mac OS X", "architecture": "aarch64", "version": "26.4.1"}, "platform": "Java/Homebrew/[redacted]"} created with settings MongoClientSettings{readPreference=primary, writeConcern=WriteConcern{w=null, wTimeout=null ms, journal=null}, retryWrites=true, retryReads=true, readConcern=ReadConcern{level=null}, credential=null, transportSettings=null, commandListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener@4be7b2e8], codecRegistry=ProvidersCodecRegistry{codecProviders=[ValueCodecProvider{}, BsonValueCodecProvider{}, DBRefCodecProvider{}, DBObjectCodecProvider{}, DocumentCodecProvider{}, CollectionCodecProvider{}, IterableCodecProvider{}, MapCodecProvider{}, GeoJsonCodecProvider{}, GridFSFileCodecProvider{}, Jsr310CodecProvider{}, JsonObjectCodecProvider{}, BsonCodecProvider{}, com.mongodb.client.model.mql.ExpressionCodecProvider@674982c3, com.mongodb.Jep395RecordCodecProvider@3360dbeb, com.mongodb.KotlinCodecProvider@79625fa, EnumCodecProvider{}]}, loggerSettings=LoggerSettings{maxDocumentLength=1000}, clusterSettings={hosts=[localhost:65298], srvServiceName=mongodb, mode=SINGLE, requiredClusterType=UNKNOWN, requiredReplicaSetName='null', serverSelector='null', clusterListeners='[]', serverSelectionTimeout='2000 ms', localThreshold='15 ms'}, socketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=0, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], heartbeatSocketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=2000, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], connectionPoolSettings=ConnectionPoolSettings{maxSize=100, minSize=0, maxWaitTimeMS=2000, maxConnectionLifeTimeMS=0, maxConnectionIdleTimeMS=0, maintenanceInitialDelayMS=0, maintenanceFrequencyMS=60000, connectionPoolListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener@68d5cc10], maxConnecting=2}, serverSettings=ServerSettings{heartbeatFrequencyMS=10000, minHeartbeatFrequencyMS=500, serverMonitoringMode=AUTO, serverListeners='[]', serverMonitorListeners='[]'}, sslSettings=SslSettings{enabled=false, invalidHostNameAllowed=false, context=null}, applicationName='null', compressorList=[], uuidRepresentation=JAVA_LEGACY, serverApi=null, autoEncryptionSettings=null, dnsClient=null, inetAddressResolver=null, contextProvider=null, timeoutMS=null}
2026-11-01T19:18:32.414+01:00 INFO  [cluster-6ae78278af7a999743c30917-localhost:65298] org.mongodb.driver.cluster traceId= clubId= accountId= : Monitor thread successfully connected to server with description ServerDescription{address=localhost:65298, type=REPLICA_SET_PRIMARY, cryptd=false, state=CONNECTED, ok=true, minWireVersion=0, maxWireVersion=21, maxDocumentSize=16777216, logicalSessionTimeoutMinutes=30, roundTripTimeNanos=5973667, minRoundTripTimeNanos=0, setName='docker-rs', canonicalAddress=3a344095ac2f:27017, hosts=[3a344095ac2f:27017], passives=[], arbiters=[], primary='3a344095ac2f:27017', tagSet=TagSet{[]}, electionId=7fffffff0000000000000001, setVersion=1, topologyVersion=TopologyVersion{processId=6ae77bbf3ead2637fd727192, counter=6}, lastWriteDate=Sun Nov 01 19:18:31 CET 2026, lastUpdateTimeNanos=[redacted]}
2026-11-01T19:18:34.552+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : Started MigrationWelcomeIT in 2.901 seconds (process running for 1728.164)
E5-T07 outbox backlog discarded before MigrationWelcomeIT: 158 PENDING records (more than one dispatch() batch)
```
