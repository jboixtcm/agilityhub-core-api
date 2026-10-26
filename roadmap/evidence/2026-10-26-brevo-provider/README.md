# Brevo integration and internal email-off policy — 26 October 2026

User-authorized continuation of deployment preparation. The final instruction keeps Brevo integrated and selected in the private deployment while `EMAIL_DELIVERY_ENABLED=false` prevents every outbound software email. Public launch, external delivery and provider tracking/inbox verification are separate. No real email was sent. Provider account configuration is deferred at the user’s request; delivery remains disabled.

## Implementation

- Selected provider credentials are mandatory for normal enabled production. Explicit deployed hosted-demo mode may disable delivery without credentials or an allowlist; malformed flags and use outside that policy fail closed.
- Disabled mode constructs no HTTP client or local mailbox. Identity SYSTEM notifications skip before rendering credentials; new engine EMAIL deliveries are persisted as terminal `SKIPPED_NOT_ALLOWED`, including direct/template-test delivery. Existing queued engine deliveries skip when polled; an interrupted SYSTEM send skips when resumed. No provider-attempt increment or next attempt is fabricated; reenabling does not replay terminal skips. Existing in-app notices and provisioned password login remain usable.
- Configured webhook authentication remains active while outbound is disabled; absent credentials refuse callbacks. Brevo uses a verified platform sender, an independent webhook bearer secret, bounded object/batch input, tenant/recipient/message correlation, transactional receipt deduplication and permanent suppression. SendGrid remains the generic default.
- No real address, API key or callback token is stored in fixtures or examples. Loopback HTTP and synthetic provider callbacks exercise the integration.
- Global production configuration also carries the storage agent's explicit filesystem mode and the backup agent's matching encrypted archive mode. `compose.filesystem.yaml` binds pre-provisioned directories with `create_host_path=false`; the release preflight loads it from the resolved filesystem setting and checks private paths, marker, persistent signing key and backup layout. The baseline S3 configuration remains available.

## Verification

The earlier email focused attempts exposed fixture assumptions about direct transaction ownership and skipped-attempt counts, then a real stale `nextAttemptAt` on dispatcher skips. The final implementation clears terminal scheduling fields; the final focused suite below passes. Earlier logs remain as historical evidence rather than successful checks.

### Preflight and environment inventory

Command (exit 0):

```sh
python3 -m unittest deploy.test_release_preflight deploy.test_e11_t09.InventoryTest
```

Full output: [10-preflight-inventory.log](10-preflight-inventory.log). Last 40 lines (or complete shorter output):

```text
...........................
----------------------------------------------------------------------
Ran 27 tests in 0.057s

OK
```

### Final email-focused Maven suite

Command (exit 0):

```sh
HEAVY_WAIT_MIN=120 JAVA_HOME=/opt/homebrew/opt/openjdk@21 /Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh ./mvnw -q -Dtest=BrevoEmailSenderTest,BrevoWebhookIT,EmailConfigurationTest,HostedDemoConfigurationTest,SystemNotificationServiceIT,DisabledEmailDeliveryIT,DisabledEmailIdentityIT,HostedDemoEmailDeliveryTest,OpenApiSnapshotTest,SecurityInventoryIT,AnonymousRateLimitsTest -Dopenapi.snapshot.update=true test
```

Full output: [12-email-final-focus.log](12-email-final-focus.log). Last 40 lines (or complete shorter output):

```text
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v4.1.1)

2026-10-26T19:01:06.659+01:00 INFO  [main] c.a.c.c.m.a.e.DisabledEmailDeliveryIT traceId= clubId= accountId= : Starting DisabledEmailDeliveryIT using Java [redacted] with PID 84189 (started by jordib in /Users/jordib/dev/agilityhub/agilityhub-core-api)
2026-10-26T19:01:06.659+01:00 INFO  [main] c.a.c.c.m.a.e.DisabledEmailDeliveryIT traceId= clubId= accountId= : The following 1 profile is active: "test"
2026-10-26T19:01:06.891+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Bootstrapping Spring Data MongoDB repositories in DEFAULT mode.
2026-10-26T19:01:06.922+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Finished Spring Data repository scanning in 30 ms. Found 0 MongoDB repository interfaces.
2026-10-26T19:01:06.970+01:00 INFO  [main] org.mongodb.driver.client traceId= clubId= accountId= : MongoClient with metadata {"driver": {"name": "mongo-java-driver|spring-boot|sync", "version": "5.8.1"}, "os": {"type": "Darwin", "name": "Mac OS X", "architecture": "aarch64", "version": "26.4.1"}, "platform": "Java/Homebrew/[redacted]"} created with settings MongoClientSettings{readPreference=primary, writeConcern=WriteConcern{w=null, wTimeout=null ms, journal=null}, retryWrites=true, retryReads=true, readConcern=ReadConcern{level=null}, credential=null, transportSettings=null, commandListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener@3a9d17], codecRegistry=ProvidersCodecRegistry{codecProviders=[ValueCodecProvider{}, BsonValueCodecProvider{}, DBRefCodecProvider{}, DBObjectCodecProvider{}, DocumentCodecProvider{}, CollectionCodecProvider{}, IterableCodecProvider{}, MapCodecProvider{}, GeoJsonCodecProvider{}, GridFSFileCodecProvider{}, Jsr310CodecProvider{}, JsonObjectCodecProvider{}, BsonCodecProvider{}, com.mongodb.client.model.mql.ExpressionCodecProvider@529a3814, com.mongodb.Jep395RecordCodecProvider@7cb1b1db, com.mongodb.KotlinCodecProvider@49a3d64f, EnumCodecProvider{}]}, loggerSettings=LoggerSettings{maxDocumentLength=1000}, clusterSettings={hosts=[localhost:62039], srvServiceName=mongodb, mode=SINGLE, requiredClusterType=UNKNOWN, requiredReplicaSetName='null', serverSelector='null', clusterListeners='[]', serverSelectionTimeout='2000 ms', localThreshold='15 ms'}, socketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=0, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], heartbeatSocketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=2000, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], connectionPoolSettings=ConnectionPoolSettings{maxSize=100, minSize=0, maxWaitTimeMS=2000, maxConnectionLifeTimeMS=0, maxConnectionIdleTimeMS=0, maintenanceInitialDelayMS=0, maintenanceFrequencyMS=60000, connectionPoolListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener@38e45533], maxConnecting=2}, serverSettings=ServerSettings{heartbeatFrequencyMS=10000, minHeartbeatFrequencyMS=500, serverMonitoringMode=AUTO, serverListeners='[]', serverMonitorListeners='[]'}, sslSettings=SslSettings{enabled=false, invalidHostNameAllowed=false, context=null}, applicationName='null', compressorList=[], uuidRepresentation=JAVA_LEGACY, serverApi=null, autoEncryptionSettings=null, dnsClient=null, inetAddressResolver=null, contextProvider=null, timeoutMS=null}
2026-10-26T19:01:06.972+01:00 INFO  [cluster-6adf9562baaf5d34727c236e-localhost:62039] org.mongodb.driver.cluster traceId= clubId= accountId= : Monitor thread successfully connected to server with description ServerDescription{address=localhost:62039, type=REPLICA_SET_PRIMARY, cryptd=false, state=CONNECTED, ok=true, minWireVersion=0, maxWireVersion=21, maxDocumentSize=16777216, logicalSessionTimeoutMinutes=30, roundTripTimeNanos=2028917, minRoundTripTimeNanos=0, setName='docker-rs', canonicalAddress=0f2b64e777b9:27017, hosts=[0f2b64e777b9:27017], passives=[], arbiters=[], primary='0f2b64e777b9:27017', tagSet=TagSet{[]}, electionId=7fffffff0000000000000001, setVersion=1, topologyVersion=TopologyVersion{processId=6adf9547081e357907445bcc, counter=6}, lastWriteDate=Mon Oct 26 19:01:06 CET 2026, lastUpdateTimeNanos=[redacted]}
2026-10-26T19:01:09.096+01:00 INFO  [main] c.a.c.c.m.a.e.DisabledEmailDeliveryIT traceId= clubId= accountId= : Started DisabledEmailDeliveryIT in 2.446 seconds (process running for 34.292)
2026-10-26T19:01:09.671+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.identity.api.DisabledEmailIdentityIT]: DisabledEmailIdentityIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-26T19:01:09.699+01:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.identity.api.DisabledEmailIdentityIT
2026-10-26T19:01:09.699+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.identity.api.DisabledEmailIdentityIT]: DisabledEmailIdentityIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-26T19:01:09.700+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.identity.api.IdentityIntegrationSupport]: IdentityIntegrationSupport does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-26T19:01:09.700+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.support.AbstractIntegrationTest]: AbstractIntegrationTest does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-26T19:01:09.700+01:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.identity.api.DisabledEmailIdentityIT

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v4.1.1)

2026-10-26T19:01:09.713+01:00 INFO  [main] c.a.c.i.api.DisabledEmailIdentityIT traceId= clubId= accountId= : Starting DisabledEmailIdentityIT using Java [redacted] with PID 84189 (started by jordib in /Users/jordib/dev/agilityhub/agilityhub-core-api)
2026-10-26T19:01:09.714+01:00 INFO  [main] c.a.c.i.api.DisabledEmailIdentityIT traceId= clubId= accountId= : The following 1 profile is active: "test"
2026-10-26T19:01:09.986+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Bootstrapping Spring Data MongoDB repositories in DEFAULT mode.
2026-10-26T19:01:10.022+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Finished Spring Data repository scanning in 34 ms. Found 0 MongoDB repository interfaces.
2026-10-26T19:01:10.076+01:00 INFO  [main] org.mongodb.driver.client traceId= clubId= accountId= : MongoClient with metadata {"driver": {"name": "mongo-java-driver|spring-boot|sync", "version": "5.8.1"}, "os": {"type": "Darwin", "name": "Mac OS X", "architecture": "aarch64", "version": "26.4.1"}, "platform": "Java/Homebrew/[redacted]"} created with settings MongoClientSettings{readPreference=primary, writeConcern=WriteConcern{w=null, wTimeout=null ms, journal=null}, retryWrites=true, retryReads=true, readConcern=ReadConcern{level=null}, credential=null, transportSettings=null, commandListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener@46bd2295], codecRegistry=ProvidersCodecRegistry{codecProviders=[ValueCodecProvider{}, BsonValueCodecProvider{}, DBRefCodecProvider{}, DBObjectCodecProvider{}, DocumentCodecProvider{}, CollectionCodecProvider{}, IterableCodecProvider{}, MapCodecProvider{}, GeoJsonCodecProvider{}, GridFSFileCodecProvider{}, Jsr310CodecProvider{}, JsonObjectCodecProvider{}, BsonCodecProvider{}, com.mongodb.client.model.mql.ExpressionCodecProvider@529a3814, com.mongodb.Jep395RecordCodecProvider@7cb1b1db, com.mongodb.KotlinCodecProvider@49a3d64f, EnumCodecProvider{}]}, loggerSettings=LoggerSettings{maxDocumentLength=1000}, clusterSettings={hosts=[localhost:62039], srvServiceName=mongodb, mode=SINGLE, requiredClusterType=UNKNOWN, requiredReplicaSetName='null', serverSelector='null', clusterListeners='[]', serverSelectionTimeout='2000 ms', localThreshold='15 ms'}, socketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=0, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], heartbeatSocketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=2000, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], connectionPoolSettings=ConnectionPoolSettings{maxSize=100, minSize=0, maxWaitTimeMS=2000, maxConnectionLifeTimeMS=0, maxConnectionIdleTimeMS=0, maintenanceInitialDelayMS=0, maintenanceFrequencyMS=60000, connectionPoolListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener@1d2691d5], maxConnecting=2}, serverSettings=ServerSettings{heartbeatFrequencyMS=10000, minHeartbeatFrequencyMS=500, serverMonitoringMode=AUTO, serverListeners='[]', serverMonitorListeners='[]'}, sslSettings=SslSettings{enabled=false, invalidHostNameAllowed=false, context=null}, applicationName='null', compressorList=[], uuidRepresentation=JAVA_LEGACY, serverApi=null, autoEncryptionSettings=null, dnsClient=null, inetAddressResolver=null, contextProvider=null, timeoutMS=null}
2026-10-26T19:01:10.078+01:00 INFO  [cluster-6adf9566baaf5d34727c2372-localhost:62039] org.mongodb.driver.cluster traceId= clubId= accountId= : Monitor thread successfully connected to server with description ServerDescription{address=localhost:62039, type=REPLICA_SET_PRIMARY, cryptd=false, state=CONNECTED, ok=true, minWireVersion=0, maxWireVersion=21, maxDocumentSize=16777216, logicalSessionTimeoutMinutes=30, roundTripTimeNanos=1632750, minRoundTripTimeNanos=0, setName='docker-rs', canonicalAddress=0f2b64e777b9:27017, hosts=[0f2b64e777b9:27017], passives=[], arbiters=[], primary='0f2b64e777b9:27017', tagSet=TagSet{[]}, electionId=7fffffff0000000000000001, setVersion=1, topologyVersion=TopologyVersion{processId=6adf9547081e357907445bcc, counter=6}, lastWriteDate=Mon Oct 26 19:01:09 CET 2026, lastUpdateTimeNanos=[redacted]}
2026-10-26T19:01:12.131+01:00 INFO  [main] o.s.b.t.m.w.SpringBootMockServletContext traceId= clubId= accountId= : Initializing Spring TestDispatcherServlet ''
2026-10-26T19:01:12.131+01:00 INFO  [main] o.s.t.web.servlet.TestDispatcherServlet traceId= clubId= accountId= : Initializing Servlet ''
2026-10-26T19:01:12.135+01:00 INFO  [main] o.s.t.web.servlet.TestDispatcherServlet traceId= clubId= accountId= : Completed initialization in 4 ms
2026-10-26T19:01:12.177+01:00 INFO  [main] c.a.c.i.api.DisabledEmailIdentityIT traceId= clubId= accountId= : Started DisabledEmailIdentityIT in 2.474 seconds (process running for 37.372)
```

```text
Focused final email suite: {'tests': 120, 'failures': 0, 'errors': 0, 'skipped': 0}
BrevoWebhookIT: tests=17, failures=0, errors=0, skipped=0
BrevoEmailSenderTest: tests=12, failures=0, errors=0, skipped=0
SystemNotificationServiceIT: tests=20, failures=0, errors=0, skipped=0
DisabledEmailDeliveryIT: tests=3, failures=0, errors=0, skipped=0
HostedDemoEmailDeliveryTest: tests=1, failures=0, errors=0, skipped=0
EmailConfigurationTest: tests=12, failures=0, errors=0, skipped=0
HostedDemoConfigurationTest: tests=10, failures=0, errors=0, skipped=0
OpenApiSnapshotTest: tests=12, failures=0, errors=0, skipped=0
SecurityInventoryIT: tests=4, failures=0, errors=0, skipped=0
DisabledEmailIdentityIT: tests=1, failures=0, errors=0, skipped=0
AnonymousRateLimitsTest: tests=28, failures=0, errors=0, skipped=0
```

The generated OpenAPI snapshot was reviewed and copied from the successful focused build with `bin/openapi-snapshot --from-build` (exit 0): one Brevo operation and one request schema; existing operations are unchanged. The hidden migration-file route has no published contract delta. Real Compose config merge passed without starting containers (`11-compose.log`): three provisioned binds, read-only backup input and the existing scratch volume retained.

The combined full clean Maven verification passed in `13-clean-verify.log`; exit 0 and final totals are recorded in `13-clean-verify.summary`. `13-tested-inputs.json` records the frozen source/config inputs. Storage and backup focused/restore evidence is owned by the corresponding agents and is not substituted for the combined gate.

## Deferred provider evidence

Brevo sender/account verification, real acceptance and authenticated delivery callback, inbox rendering and the absence of magic/reset-link rewriting remain unproven while delivery is intentionally off. Official send-email and secured-webhook contracts are linked in `docs/DEPLOY.md`; no unsupported per-message tracking header is assumed. No public release or provider operation is authorized by a local green test alone.

## Combined final gate and server HTTPS configuration

The frozen Java/application inputs passed `./mvnw -q clean verify` (exit 0). After that gate, the only runtime-input change was the requested Let's Encrypt CA line in `deploy/Caddyfile`. All other inputs remained byte-identical (`18-final-tested-inputs.json`). Real Caddy/access-log/routing, preflight and runner checks passed (40 tests in `15-final-deployment-checks.log`); that combined command also tried to import the backup-container suite from the host and failed only its import. The next host discovery attempt lacked the container's boto3 dependency (`16`). The correct existing container command passed all 16 backup-helper tests (`17-backup-checks.log`, exit 0). No product changes were needed for either invocation error.

```text
surefire: {'tests': 4998, 'failures': 0, 'errors': 0, 'skipped': 0}; classes=482
com.agilityhub.core.migration.VolumeMigrationReportStoreTest: tests=3, failures=0, errors=0, skipped=0
com.agilityhub.core.shared.api.AnonymousRateLimitsTest: tests=29, failures=0, errors=0, skipped=0
com.agilityhub.core.clubs.messaging.application.BrevoEmailSenderTest: tests=12, failures=0, errors=0, skipped=0
com.agilityhub.core.configuration.PrivateFileStorageConfigurationTest: tests=9, failures=0, errors=0, skipped=0
com.agilityhub.core.payments.application.HostedDemoStripeWebhooksTest: tests=1, failures=0, errors=0, skipped=0
com.agilityhub.core.platform.application.HostedDemoProvidersTest: tests=1, failures=0, errors=0, skipped=0
com.agilityhub.core.shared.api.SignedFileRequestsTest: tests=1, failures=0, errors=0, skipped=0
com.agilityhub.core.clubs.messaging.application.engine.HostedDemoEmailDeliveryTest: tests=1, failures=0, errors=0, skipped=0
com.agilityhub.core.clubs.followup.persistence.AttachmentStorageTest: tests=4, failures=0, errors=0, skipped=0
com.agilityhub.core.configuration.EmailConfigurationTest: tests=12, failures=0, errors=0, skipped=0
com.agilityhub.core.configuration.HostedDemoConfigurationTest: tests=10, failures=0, errors=0, skipped=0
com.agilityhub.core.clubs.common.application.ExportStorageTest: tests=5, failures=0, errors=0, skipped=0
failsafe: {'tests': 2333, 'failures': 0, 'errors': 0, 'skipped': 0}; classes=177
com.agilityhub.core.clubs.messaging.application.engine.DisabledEmailDeliveryIT: tests=3, failures=0, errors=0, skipped=0
com.agilityhub.core.clubs.messaging.api.BrevoWebhookIT: tests=17, failures=0, errors=0, skipped=0
com.agilityhub.core.identity.api.DisabledEmailIdentityIT: tests=1, failures=0, errors=0, skipped=0
com.agilityhub.core.clubs.messaging.application.SystemNotificationServiceIT: tests=20, failures=0, errors=0, skipped=0
com.agilityhub.core.configuration.SecurityInventoryIT: tests=4, failures=0, errors=0, skipped=0
```

Final full-gate output tail:

```text
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: ff62a6a2-9f56-42ff-abad-7476c1442bac
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: 33c2061c-17e5-44f3-a507-65c06d5e93ff
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: 838661fd-15f1-4d17-87a6-29194cbd45cc
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: 41794d4c-c04a-4a0c-9320-267c3cd85986
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run requiring review: b8716e90-af2c-4608-9e44-97b9fff3f24a
Migration run: 8b163f65-e440-4f71-afc2-79658beca0bd
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 159 PENDING records (more than one dispatch() batch)
Migration run: 05c5e8f2-7633-4b02-9fa0-c621ac470391
Migration run requiring review: 05c5e8f2-7633-4b02-9fa0-c621ac470391
Migration run: af58d48c-ae41-4cac-9ed7-26aaa6d4bcaf
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 161 PENDING records (more than one dispatch() batch)
Migration run: c67a0bea-6217-4170-b27d-c714833a406c
2026-10-26T19:35:42.005+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.migration.MigrationWelcomeIT]: MigrationWelcomeIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-26T19:35:42.007+01:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.migration.MigrationWelcomeIT
2026-10-26T19:35:42.007+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.migration.MigrationWelcomeIT]: MigrationWelcomeIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-26T19:35:42.007+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.support.AbstractIntegrationTest]: AbstractIntegrationTest does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-26T19:35:42.007+01:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.migration.MigrationWelcomeIT

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v4.1.1)

2026-10-26T19:35:42.030+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : Starting MigrationWelcomeIT using Java [redacted] with PID 85605 (started by jordib in /Users/jordib/dev/agilityhub/agilityhub-core-api)
2026-10-26T19:35:42.031+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : The following 1 profile is active: "test"
2026-10-26T19:35:42.781+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Bootstrapping Spring Data MongoDB repositories in DEFAULT mode.
2026-10-26T19:35:42.814+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Finished Spring Data repository scanning in 32 ms. Found 0 MongoDB repository interfaces.
2026-10-26T19:35:42.870+01:00 INFO  [main] org.mongodb.driver.client traceId= clubId= accountId= : MongoClient with metadata {"driver": {"name": "mongo-java-driver|spring-boot|sync", "version": "5.8.1"}, "os": {"type": "Darwin", "name": "Mac OS X", "architecture": "aarch64", "version": "26.4.1"}, "platform": "Java/Homebrew/[redacted]"} created with settings MongoClientSettings{readPreference=primary, writeConcern=WriteConcern{w=null, wTimeout=null ms, journal=null}, retryWrites=true, retryReads=true, readConcern=ReadConcern{level=null}, credential=null, transportSettings=null, commandListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener@753f4790], codecRegistry=ProvidersCodecRegistry{codecProviders=[ValueCodecProvider{}, BsonValueCodecProvider{}, DBRefCodecProvider{}, DBObjectCodecProvider{}, DocumentCodecProvider{}, CollectionCodecProvider{}, IterableCodecProvider{}, MapCodecProvider{}, GeoJsonCodecProvider{}, GridFSFileCodecProvider{}, Jsr310CodecProvider{}, JsonObjectCodecProvider{}, BsonCodecProvider{}, com.mongodb.client.model.mql.ExpressionCodecProvider@452b2d6c, com.mongodb.Jep395RecordCodecProvider@588d8069, com.mongodb.KotlinCodecProvider@7f21bba, EnumCodecProvider{}]}, loggerSettings=LoggerSettings{maxDocumentLength=1000}, clusterSettings={hosts=[localhost:63167], srvServiceName=mongodb, mode=SINGLE, requiredClusterType=UNKNOWN, requiredReplicaSetName='null', serverSelector='null', clusterListeners='[]', serverSelectionTimeout='2000 ms', localThreshold='15 ms'}, socketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=0, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], heartbeatSocketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=2000, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], connectionPoolSettings=ConnectionPoolSettings{maxSize=100, minSize=0, maxWaitTimeMS=2000, maxConnectionLifeTimeMS=0, maxConnectionIdleTimeMS=0, maintenanceInitialDelayMS=0, maintenanceFrequencyMS=60000, connectionPoolListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener@fcbdbf7], maxConnecting=2}, serverSettings=ServerSettings{heartbeatFrequencyMS=10000, minHeartbeatFrequencyMS=500, serverMonitoringMode=AUTO, serverListeners='[]', serverMonitorListeners='[]'}, sslSettings=SslSettings{enabled=false, invalidHostNameAllowed=false, context=null}, applicationName='null', compressorList=[], uuidRepresentation=JAVA_LEGACY, serverApi=null, autoEncryptionSettings=null, dnsClient=null, inetAddressResolver=null, contextProvider=null, timeoutMS=null}
2026-10-26T19:35:42.873+01:00 INFO  [cluster-6adf9d7ea9e10542a8043c5b-localhost:63167] org.mongodb.driver.cluster traceId= clubId= accountId= : Monitor thread successfully connected to server with description ServerDescription{address=localhost:63167, type=REPLICA_SET_PRIMARY, cryptd=false, state=CONNECTED, ok=true, minWireVersion=0, maxWireVersion=21, maxDocumentSize=16777216, logicalSessionTimeoutMinutes=30, roundTripTimeNanos=2346583, minRoundTripTimeNanos=0, setName='docker-rs', canonicalAddress=a4e488db62a1:27017, hosts=[a4e488db62a1:27017], passives=[], arbiters=[], primary='a4e488db62a1:27017', tagSet=TagSet{[]}, electionId=7fffffff0000000000000001, setVersion=1, topologyVersion=TopologyVersion{processId=6adf96234385682cadb35798, counter=6}, lastWriteDate=Mon Oct 26 19:35:41 CET 2026, lastUpdateTimeNanos=[redacted]}
2026-10-26T19:35:45.403+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : Started MigrationWelcomeIT in 3.383 seconds (process running for 1889.043)
E5-T07 outbox backlog discarded before MigrationWelcomeIT: 158 PENDING records (more than one dispatch() batch)
```
