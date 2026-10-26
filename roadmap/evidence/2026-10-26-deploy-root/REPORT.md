# Deployment API regression — 26 October 2026

Full clean verification completed with exit 0. 7272 tests, zero failures/errors/skips. The 2231 source/build inputs recorded before execution are unchanged.

Command:

```sh
HEAVY_WAIT_MIN=120 /Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh ./mvnw -q clean verify
```

Full output: `01-clean-verify.log`; exit: `01-clean-verify.exit`; exact per-class Surefire/Failsafe totals: `02-test-results.json`.

This includes the hosted-demo policy and prior stabilization changes. This is local verification, not provider delivery or a hosted deployment. Published API image/CI are recorded separately after this gate.

Final output (last 40 lines):

```text
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: 710b9041-f561-408d-ab7b-888401936392
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: d851935a-2482-4f98-a536-8611679798c6
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: 759ebaf2-2a39-41ad-96df-a307def914ff
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: 0de7b543-b1d9-4487-80fb-1dc271c5832d
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run requiring review: 78a6057b-02d1-42e3-bbb4-0ed5c0e9c706
Migration run: f6c836bf-ed73-4269-b1c1-b3d74d432a6d
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 159 PENDING records (more than one dispatch() batch)
Migration run: 5256de85-7566-455b-b1bc-26e072daeb1e
Migration run requiring review: 5256de85-7566-455b-b1bc-26e072daeb1e
Migration run: 40e97939-2ea3-4369-b01b-af9f380320e5
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 161 PENDING records (more than one dispatch() batch)
Migration run: fd55f916-efb8-4a0d-8671-e6d9a687b240
2026-10-26T18:28:36.833+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.migration.MigrationWelcomeIT]: MigrationWelcomeIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-26T18:28:36.834+01:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.migration.MigrationWelcomeIT
2026-10-26T18:28:36.835+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.migration.MigrationWelcomeIT]: MigrationWelcomeIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-26T18:28:36.835+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.support.AbstractIntegrationTest]: AbstractIntegrationTest does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-26T18:28:36.836+01:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.migration.MigrationWelcomeIT

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v4.1.1)

2026-10-26T18:28:36.865+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : Starting MigrationWelcomeIT using Java [redacted] with PID 71869 (started by jordib in /Users/jordib/dev/agilityhub/agilityhub-core-api)
2026-10-26T18:28:36.865+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : The following 1 profile is active: "test"
2026-10-26T18:28:37.589+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Bootstrapping Spring Data MongoDB repositories in DEFAULT mode.
2026-10-26T18:28:37.632+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Finished Spring Data repository scanning in 39 ms. Found 0 MongoDB repository interfaces.
2026-10-26T18:28:37.707+01:00 INFO  [main] org.mongodb.driver.client traceId= clubId= accountId= : MongoClient with metadata {"driver": {"name": "mongo-java-driver|spring-boot|sync", "version": "5.8.1"}, "os": {"type": "Darwin", "name": "Mac OS X", "architecture": "aarch64", "version": "26.4.1"}, "platform": "Java/Homebrew/[redacted]"} created with settings MongoClientSettings{readPreference=primary, writeConcern=WriteConcern{w=null, wTimeout=null ms, journal=null}, retryWrites=true, retryReads=true, readConcern=ReadConcern{level=null}, credential=null, transportSettings=null, commandListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener@5b6aa497], codecRegistry=ProvidersCodecRegistry{codecProviders=[ValueCodecProvider{}, BsonValueCodecProvider{}, DBRefCodecProvider{}, DBObjectCodecProvider{}, DocumentCodecProvider{}, CollectionCodecProvider{}, IterableCodecProvider{}, MapCodecProvider{}, GeoJsonCodecProvider{}, GridFSFileCodecProvider{}, Jsr310CodecProvider{}, JsonObjectCodecProvider{}, BsonCodecProvider{}, com.mongodb.client.model.mql.ExpressionCodecProvider@4098302, com.mongodb.Jep395RecordCodecProvider@691e9cb7, com.mongodb.KotlinCodecProvider@3319792d, EnumCodecProvider{}]}, loggerSettings=LoggerSettings{maxDocumentLength=1000}, clusterSettings={hosts=[localhost:59209], srvServiceName=mongodb, mode=SINGLE, requiredClusterType=UNKNOWN, requiredReplicaSetName='null', serverSelector='null', clusterListeners='[]', serverSelectionTimeout='2000 ms', localThreshold='15 ms'}, socketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=0, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], heartbeatSocketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=2000, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], connectionPoolSettings=ConnectionPoolSettings{maxSize=100, minSize=0, maxWaitTimeMS=2000, maxConnectionLifeTimeMS=0, maxConnectionIdleTimeMS=0, maintenanceInitialDelayMS=0, maintenanceFrequencyMS=60000, connectionPoolListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener@30d9eafc], maxConnecting=2}, serverSettings=ServerSettings{heartbeatFrequencyMS=10000, minHeartbeatFrequencyMS=500, serverMonitoringMode=AUTO, serverListeners='[]', serverMonitorListeners='[]'}, sslSettings=SslSettings{enabled=false, invalidHostNameAllowed=false, context=null}, applicationName='null', compressorList=[], uuidRepresentation=JAVA_LEGACY, serverApi=null, autoEncryptionSettings=null, dnsClient=null, inetAddressResolver=null, contextProvider=null, timeoutMS=null}
2026-10-26T18:28:37.709+01:00 INFO  [cluster-6adf8dc53768419b09bdfe9a-localhost:59209] org.mongodb.driver.cluster traceId= clubId= accountId= : Monitor thread successfully connected to server with description ServerDescription{address=localhost:59209, type=REPLICA_SET_PRIMARY, cryptd=false, state=CONNECTED, ok=true, minWireVersion=0, maxWireVersion=21, maxDocumentSize=16777216, logicalSessionTimeoutMinutes=30, roundTripTimeNanos=1642667, minRoundTripTimeNanos=0, setName='docker-rs', canonicalAddress=db0775a4d0ea:27017, hosts=[db0775a4d0ea:27017], passives=[], arbiters=[], primary='db0775a4d0ea:27017', tagSet=TagSet{[]}, electionId=7fffffff0000000000000001, setVersion=1, topologyVersion=TopologyVersion{processId=6adf85dfd7e112526ec550c2, counter=6}, lastWriteDate=Mon Oct 26 18:28:36 CET 2026, lastUpdateTimeNanos=[redacted]}
2026-10-26T18:28:40.451+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : Started MigrationWelcomeIT in 3.598 seconds (process running for 2028.873)
E5-T07 outbox backlog discarded before MigrationWelcomeIT: 158 PENDING records (more than one dispatch() batch)
```


## CI preparation corrections

Initial publication `6595007` exposed two checks outside Maven. The Compose/deploy inventory test required explicit inventory rows for `HOSTED_DEMO_ENABLED` and `HOSTED_DEMO_EMAIL_ALLOWLIST`; those documentation rows are now present. All seven deployment proofs pass (`03-deploy-proofs.*`).

Gitleaks reported 96 matches, all in `01-clean-verify-inputs.json`. Every value in that 2231-entry manifest was verified as a SHA-256 digest of a source/build file. The existing two-rule, 64-hex-only evidence-manifest exception now includes this one exact file path. No code/resource/log exemption was added. A scanner policy regression proves non-digest credentials at that same path still fail; all eight injected credentials remain detected (`05-secret-policy-final.*`). The initial absolute-path fixture mismatch is retained in `04-secret-policy.*`; the final pattern covers relative and absolute scanner paths. Scanning the complete published change from `de50be0` to `6595007` with the corrected policy reports zero leaks (`06-publish-secret-scan.*`).

Only documentation, scanner configuration and its policy test changed after the green 7272-test run. Application source/build inputs remain unchanged; another full Maven run is not needed for these non-runtime corrections. Hosted deployment still awaits a green publishing workflow and private provider configuration.
