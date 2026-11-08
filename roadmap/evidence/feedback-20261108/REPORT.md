# Josep feedback — 8 November 2026

Status: locally implemented and validated. No commit, push or deployment.

Source: SOFTWARE_CANIC `Proves/202608-1_Josep.docx`. Direct user request; no existing roadmap task or organizer verification was changed.

Missing FCAG categories initialize from RSCE and remain independent afterwards, including Playoff reimports. Explicit values, erased records and version fences are preserved. CLI commands do not run the backfill. Normal API startup will initialize existing records on deployment.

Test totals and changed classes:
```json
{
  "surefire": {
    "totals": {
      "tests": 5019,
      "failures": 0,
      "errors": 0,
      "skipped": 0
    },
    "changedClasses": [
      {
        "class": "com.agilityhub.core.clubs.census.persistence.LicenseCategoryBackfillTest",
        "tests": 1,
        "failures": 0,
        "errors": 0,
        "skipped": 0
      },
      {
        "class": "com.agilityhub.core.clubs.census.domain.LicenseCategoriesTest",
        "tests": 1,
        "failures": 0,
        "errors": 0,
        "skipped": 0
      }
    ]
  },
  "failsafe": {
    "totals": {
      "tests": 2383,
      "failures": 0,
      "errors": 0,
      "skipped": 0
    },
    "changedClasses": [
      {
        "class": "com.agilityhub.core.clubs.census.api.CensusIT",
        "tests": 70,
        "failures": 0,
        "errors": 0,
        "skipped": 0
      },
      {
        "class": "com.agilityhub.core.migration.PlayoffMigrationIT",
        "tests": 52,
        "failures": 0,
        "errors": 0,
        "skipped": 0
      }
    ]
  }
}
```

## `python3 roadmap/tools/check.py --render`

Exit code: 0. Complete output: `api-roadmap.log`.

```text
STATUS.md rendered (150 tasks)
```

## `./mvnw -q clean verify`

Exit code: 0. Complete output: `api-verified.log`.

```text
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: ac36e19b-0ad8-44e2-ab31-b98041a16d3d
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: 74fd5735-ed5e-4986-945a-49e8722bcc4c
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: 04e8a62f-bb01-44e0-aa59-9e2cc8ce9a0e
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run requiring review: f4ff8226-69cf-4838-b91c-2402bdf8de1f
Migration run: 248e5632-59ed-4cd2-94fd-7c15021ce503
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 159 PENDING records (more than one dispatch() batch)
Migration run: e741c276-3e8f-420e-8479-8a9f59470bcc
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: 1fc5dec0-4355-47f5-9965-4454b7f26edd
Migration run requiring review: 1fc5dec0-4355-47f5-9965-4454b7f26edd
Migration run: 99311305-9c3e-4405-8319-426c6f9838f5
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 161 PENDING records (more than one dispatch() batch)
Migration run: c93e96e6-685a-42ba-93d3-07e688347aa7
2026-11-08T14:48:37.895+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.migration.MigrationWelcomeIT]: MigrationWelcomeIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-11-08T14:48:37.898+01:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.migration.MigrationWelcomeIT
2026-11-08T14:48:37.900+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.migration.MigrationWelcomeIT]: MigrationWelcomeIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-11-08T14:48:37.900+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.support.AbstractIntegrationTest]: AbstractIntegrationTest does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-11-08T14:48:37.901+01:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.migration.MigrationWelcomeIT

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v4.1.1)

2026-11-08T14:48:37.937+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : Starting MigrationWelcomeIT using Java [redacted] with PID 13113 (started by jordib in /Users/jordib/.config/agilityhub-clubs/production/josep-feedback-20261106/agilityhub-core-api)
2026-11-08T14:48:37.937+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : The following 1 profile is active: "test"
2026-11-08T14:48:38.696+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Bootstrapping Spring Data MongoDB repositories in DEFAULT mode.
2026-11-08T14:48:38.740+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Finished Spring Data repository scanning in 39 ms. Found 0 MongoDB repository interfaces.
2026-11-08T14:48:38.816+01:00 INFO  [main] org.mongodb.driver.client traceId= clubId= accountId= : MongoClient with metadata {"driver": {"name": "mongo-java-driver|spring-boot|sync", "version": "5.8.1"}, "os": {"type": "Darwin", "name": "Mac OS X", "architecture": "aarch64", "version": "26.4.1"}, "platform": "Java/Homebrew/[redacted]"} created with settings MongoClientSettings{readPreference=primary, writeConcern=WriteConcern{w=null, wTimeout=null ms, journal=null}, retryWrites=true, retryReads=true, readConcern=ReadConcern{level=null}, credential=null, transportSettings=null, commandListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener@111e2ce1], codecRegistry=ProvidersCodecRegistry{codecProviders=[ValueCodecProvider{}, BsonValueCodecProvider{}, DBRefCodecProvider{}, DBObjectCodecProvider{}, DocumentCodecProvider{}, CollectionCodecProvider{}, IterableCodecProvider{}, MapCodecProvider{}, GeoJsonCodecProvider{}, GridFSFileCodecProvider{}, Jsr310CodecProvider{}, JsonObjectCodecProvider{}, BsonCodecProvider{}, com.mongodb.client.model.mql.ExpressionCodecProvider@55d8bf33, com.mongodb.Jep395RecordCodecProvider@12110704, com.mongodb.KotlinCodecProvider@36f08b29, EnumCodecProvider{}]}, loggerSettings=LoggerSettings{maxDocumentLength=1000}, clusterSettings={hosts=[localhost:62867], srvServiceName=mongodb, mode=SINGLE, requiredClusterType=UNKNOWN, requiredReplicaSetName='null', serverSelector='null', clusterListeners='[]', serverSelectionTimeout='2000 ms', localThreshold='15 ms'}, socketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=0, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], heartbeatSocketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=2000, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], connectionPoolSettings=ConnectionPoolSettings{maxSize=100, minSize=0, maxWaitTimeMS=2000, maxConnectionLifeTimeMS=0, maxConnectionIdleTimeMS=0, maintenanceInitialDelayMS=0, maintenanceFrequencyMS=60000, connectionPoolListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener@104add4d], maxConnecting=2}, serverSettings=ServerSettings{heartbeatFrequencyMS=10000, minHeartbeatFrequencyMS=500, serverMonitoringMode=AUTO, serverListeners='[]', serverMonitorListeners='[]'}, sslSettings=SslSettings{enabled=false, invalidHostNameAllowed=false, context=null}, applicationName='null', compressorList=[], uuidRepresentation=JAVA_LEGACY, serverApi=null, autoEncryptionSettings=null, dnsClient=null, inetAddressResolver=null, contextProvider=null, timeoutMS=null}
2026-11-08T14:48:38.818+01:00 INFO  [cluster-6af07db6c608413010c140a1-localhost:62867] org.mongodb.driver.cluster traceId= clubId= accountId= : Monitor thread successfully connected to server with description ServerDescription{address=localhost:62867, type=REPLICA_SET_PRIMARY, cryptd=false, state=CONNECTED, ok=true, minWireVersion=0, maxWireVersion=21, maxDocumentSize=16777216, logicalSessionTimeoutMinutes=30, roundTripTimeNanos=2202250, minRoundTripTimeNanos=0, setName='docker-rs', canonicalAddress=20082eddd2e3:27017, hosts=[20082eddd2e3:27017], passives=[], arbiters=[], primary='20082eddd2e3:27017', tagSet=TagSet{[]}, electionId=7fffffff0000000000000001, setVersion=1, topologyVersion=TopologyVersion{processId=6af0733cbf652dfe559e029e, counter=6}, lastWriteDate=Sun Nov 08 14:48:37 CET 2026, lastUpdateTimeNanos=[redacted]}
2026-11-08T14:48:41.161+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : Started MigrationWelcomeIT in 3.239 seconds (process running for 2691.578)
E5-T07 outbox backlog discarded before MigrationWelcomeIT: 158 PENDING records (more than one dispatch() batch)
```
