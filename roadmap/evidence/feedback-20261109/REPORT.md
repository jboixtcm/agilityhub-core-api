# Member notes privacy and dashboard census — 9 November 2026

Local verification complete. Publication and deployment are recorded separately in SOFTWARE_CANIC operations evidence. Direct user feedback; no roadmap task status or organizer verification changed.

The dashboard includes every active level, including non-progression levels. Inactive and missing levels remain in others. This implements the dated S14 clarification without changing catalog progression flags. T-03-33 also verifies that internal notes stay private in member and impersonated profiles while remaining available to administrators.

Changes: DogActivityBuilder, DashboardData, DashboardBuildersTest, DashboardIT, CensusIT, dated S14 clarification and changelog. No API contract shape change.

## `./mvnw -q clean verify`

Exit code: 0. Complete output: `api-clean-verify.log`.

```text
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: 50bd7a6d-6691-4a12-beb8-cb0d1cb19b32
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: 21d5b3a3-5b7c-49b4-8be0-6361781ddf95
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: 8df09c54-7cc8-441a-87a7-9d1427864847
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run requiring review: 84bdfcaa-b48b-4e83-818a-aa346c4aa828
Migration run: f802175a-4823-40ea-ac9c-19cd21150475
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 159 PENDING records (more than one dispatch() batch)
Migration run: c74240ad-004e-44cf-b5ee-e2f242ef9e0e
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: 4f1442c7-581a-400b-99bc-9258b4a5b254
Migration run requiring review: 4f1442c7-581a-400b-99bc-9258b4a5b254
Migration run: 49536eb1-3b19-48af-8f57-a67442c97a92
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 161 PENDING records (more than one dispatch() batch)
Migration run: 8861a6c6-5c62-469d-9d60-05a7ecdcdfb1
2026-11-09T19:30:11.009+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.migration.MigrationWelcomeIT]: MigrationWelcomeIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-11-09T19:30:11.011+01:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.migration.MigrationWelcomeIT
2026-11-09T19:30:11.012+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.migration.MigrationWelcomeIT]: MigrationWelcomeIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-11-09T19:30:11.012+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.support.AbstractIntegrationTest]: AbstractIntegrationTest does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-11-09T19:30:11.012+01:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.migration.MigrationWelcomeIT

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v4.1.1)

2026-11-09T19:30:11.042+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : Starting MigrationWelcomeIT using Java [redacted] with PID 60834 (started by jordib in /Users/jordib/.config/agilityhub-clubs/production/josep-feedback-20261106/agilityhub-core-api)
2026-11-09T19:30:11.042+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : The following 1 profile is active: "test"
2026-11-09T19:30:11.866+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Bootstrapping Spring Data MongoDB repositories in DEFAULT mode.
2026-11-09T19:30:11.914+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Finished Spring Data repository scanning in 47 ms. Found 0 MongoDB repository interfaces.
2026-11-09T19:30:11.984+01:00 INFO  [main] org.mongodb.driver.client traceId= clubId= accountId= : MongoClient with metadata {"driver": {"name": "mongo-java-driver|spring-boot|sync", "version": "5.8.1"}, "os": {"type": "Darwin", "name": "Mac OS X", "architecture": "aarch64", "version": "26.4.1"}, "platform": "Java/Homebrew/[redacted]"} created with settings MongoClientSettings{readPreference=primary, writeConcern=WriteConcern{w=null, wTimeout=null ms, journal=null}, retryWrites=true, retryReads=true, readConcern=ReadConcern{level=null}, credential=null, transportSettings=null, commandListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener@46cc3439], codecRegistry=ProvidersCodecRegistry{codecProviders=[ValueCodecProvider{}, BsonValueCodecProvider{}, DBRefCodecProvider{}, DBObjectCodecProvider{}, DocumentCodecProvider{}, CollectionCodecProvider{}, IterableCodecProvider{}, MapCodecProvider{}, GeoJsonCodecProvider{}, GridFSFileCodecProvider{}, Jsr310CodecProvider{}, JsonObjectCodecProvider{}, BsonCodecProvider{}, com.mongodb.client.model.mql.ExpressionCodecProvider@3c694116, com.mongodb.Jep395RecordCodecProvider@320aad1b, com.mongodb.KotlinCodecProvider@725b8604, EnumCodecProvider{}]}, loggerSettings=LoggerSettings{maxDocumentLength=1000}, clusterSettings={hosts=[localhost:58214], srvServiceName=mongodb, mode=SINGLE, requiredClusterType=UNKNOWN, requiredReplicaSetName='null', serverSelector='null', clusterListeners='[]', serverSelectionTimeout='2000 ms', localThreshold='15 ms'}, socketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=0, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], heartbeatSocketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=2000, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], connectionPoolSettings=ConnectionPoolSettings{maxSize=100, minSize=0, maxWaitTimeMS=2000, maxConnectionLifeTimeMS=0, maxConnectionIdleTimeMS=0, maintenanceInitialDelayMS=0, maintenanceFrequencyMS=60000, connectionPoolListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener@948417c], maxConnecting=2}, serverSettings=ServerSettings{heartbeatFrequencyMS=10000, minHeartbeatFrequencyMS=500, serverMonitoringMode=AUTO, serverListeners='[]', serverMonitorListeners='[]'}, sslSettings=SslSettings{enabled=false, invalidHostNameAllowed=false, context=null}, applicationName='null', compressorList=[], uuidRepresentation=JAVA_LEGACY, serverApi=null, autoEncryptionSettings=null, dnsClient=null, inetAddressResolver=null, contextProvider=null, timeoutMS=null}
2026-11-09T19:30:11.987+01:00 INFO  [cluster-6af211339eaae0991ea43102-localhost:58214] org.mongodb.driver.cluster traceId= clubId= accountId= : Monitor thread successfully connected to server with description ServerDescription{address=localhost:58214, type=REPLICA_SET_PRIMARY, cryptd=false, state=CONNECTED, ok=true, minWireVersion=0, maxWireVersion=21, maxDocumentSize=16777216, logicalSessionTimeoutMinutes=30, roundTripTimeNanos=1831959, minRoundTripTimeNanos=0, setName='docker-rs', canonicalAddress=c299a653e6d0:27017, hosts=[c299a653e6d0:27017], passives=[], arbiters=[], primary='c299a653e6d0:27017', tagSet=TagSet{[]}, electionId=7fffffff0000000000000001, setVersion=1, topologyVersion=TopologyVersion{processId=6af2097fac29b0685cc0926c, counter=6}, lastWriteDate=Mon Nov 09 19:30:10 CET 2026, lastUpdateTimeNanos=[redacted]}
2026-11-09T19:30:14.338+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : Started MigrationWelcomeIT in 3.309 seconds (process running for 1977.785)
E5-T07 outbox backlog discarded before MigrationWelcomeIT: 158 PENDING records (more than one dispatch() batch)
```

## `bin/openapi-snapshot --from-build`

Exit code: 0. Complete output: `openapi-snapshot.log`.

```text
Updated docs/openapi/openapi.json
```

## `git diff --check`

Exit code: 0. Complete output: `api-diff-check.log`.

```text
```

## Test summary

```json
{
  "surefire-reports": {
    "totals": {
      "tests": 5021,
      "failures": 0,
      "errors": 0,
      "skipped": 0
    },
    "changedClasses": [
      {
        "name": "com.agilityhub.core.clubs.dashboard.domain.DashboardBuildersTest",
        "tests": "7",
        "failures": "0",
        "errors": "0",
        "skipped": "0",
        "time": "0.008"
      }
    ]
  },
  "failsafe-reports": {
    "totals": {
      "tests": 2384,
      "failures": 0,
      "errors": 0,
      "skipped": 0
    },
    "changedClasses": [
      {
        "name": "com.agilityhub.core.clubs.census.api.CensusIT",
        "tests": "71",
        "failures": "0",
        "errors": "0",
        "skipped": "0",
        "time": "17.101"
      },
      {
        "name": "com.agilityhub.core.clubs.dashboard.api.DashboardIT",
        "tests": "11",
        "failures": "0",
        "errors": "0",
        "skipped": "0",
        "time": "6.772"
      }
    ]
  },
  "exitCode": 0
}
```
