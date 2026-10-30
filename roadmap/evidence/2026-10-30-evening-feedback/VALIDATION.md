# Josep evening feedback — api validation

Source: `20263010-1_Josep.docx`, 14 screenshots reviewed. `20263010-2_Josep.docx` is empty. No production writes, commit or push. Existing photo, mobile and recovery changes are preserved.

Status: final API verification PASS. Implementation and generated contract are complete. No production deployment performed.

Scope: member/dog PDF exports use a table with repeated headers and continuation for long rows. PDF/Excel census cells contain readable dog/level/plan/family/owner/payment/contact values instead of internal identifiers or colors. Active calendar excludes cancelled classes while retaining finished classes. Onboarding exposes the existing optional image choice, preserves it when omitted, and allows profile-only completion when there is no pending privacy version; required privacy remains enforced by the service. Impersonation cannot accept personal consent. Census queries retain only joins required by selection, sorting and count, then enrich the selected page, with tenant-bound reload and fallback for custom stages/scopes/search.

Regression coverage includes long/multipage PDF rows, readable XLSX/PDF cells, active/cancelled filters, nullable image consent in the API contract, existing image choice preservation, profile-only onboarding, and 15 optimized-versus-original census scenarios (search, computed/raw filters, sorting, paging and tenant isolation).

The fictional census benchmark contains 1,060 members. The final full run measured the default member page at 11.17 ms optimized versus 276.36 ms original; raw status filtering was 9.70 versus 132.22 ms. These are local observations, not production latency or a performance guarantee. The complete verification passed all 15 comparisons; see census-benchmark.txt. Small dog fixtures can be slower because the page reload has fixed overhead.

Earlier attempts: focused tests exposed outdated onboarding request-validation expectations, corrected to the service's conditional privacy requirement. The first full verification failed only the existing password timing check while heavy web compilation was also running (5,006 tests, one failure). No timing assertion or security test was weakened. The final full run passed all 5,006 unit tests, including that unchanged check, and all 2,345 integration tests.

Focused final regression: `ListExportTest`, `OnboardingIT`, and `IdentityContractIT`. Exit: **0**. Log: `06-api-regression.log`; 41 tests (3 export, 27 onboarding, 11 identity contract).

The generated OpenAPI contract and web types include the optional nullable `imageConsent` field and profile-only consent semantics. The approved Cànic privacy URL is supplied by the isolated deployment configuration/patch in the workspace handoff; no global Cànic URL is hardcoded in shared product code.

Full verification command: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH ./mvnw -q clean verify`. Log: `08-api-clean-verify.log`. Exit: **0**. 7,351 tests passed (5,006 unit; 2,345 integration), no failures, errors, skipped tests or flakes. See api-test-summary.json for report totals and changed/sensitive suites.



## Final complete API verification

Command: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH ./mvnw -q clean verify`. Exit: **0**. Full log: `08-api-clean-verify.log`.

```text
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: 8ef2a437-9e92-4daa-aab8-0c69ee2e932e
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: 05eb9990-2c08-48ae-98d8-440988cee945
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: f2cec4d5-63d3-40fd-b419-e73e3a80ab4e
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run: 19f56dbf-e92e-4abd-ad6c-c0b9ec48e8f8
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
Migration run requiring review: be746e56-d0ce-4abb-9926-1a1c8eaa8baf
Migration run: afbdc2e4-3faf-49db-a534-03ac8ddd4ad8
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 159 PENDING records (more than one dispatch() batch)
Migration run: 95edab25-b95c-45d4-bbef-2feb545bdb6d
Migration run requiring review: 95edab25-b95c-45d4-bbef-2feb545bdb6d
Migration run: 9160480c-95d8-41b3-946a-9963c9c07f55
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 161 PENDING records (more than one dispatch() batch)
Migration run: 9197e139-84b8-4051-9423-b250020d217d
2026-10-30T21:10:40.568+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.migration.MigrationWelcomeIT]: MigrationWelcomeIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-30T21:10:40.569+01:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.migration.MigrationWelcomeIT
2026-10-30T21:10:40.570+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.migration.MigrationWelcomeIT]: MigrationWelcomeIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-30T21:10:40.570+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.support.AbstractIntegrationTest]: AbstractIntegrationTest does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-30T21:10:40.570+01:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.migration.MigrationWelcomeIT

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v4.1.1)

2026-10-30T21:10:40.590+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : Starting MigrationWelcomeIT using Java [redacted] with PID 80767 (started by jordib in /Users/jordib/dev/agilityhub/agilityhub-core-api)
2026-10-30T21:10:40.591+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : The following 1 profile is active: "test"
2026-10-30T21:10:41.295+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Bootstrapping Spring Data MongoDB repositories in DEFAULT mode.
2026-10-30T21:10:41.334+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Finished Spring Data repository scanning in 36 ms. Found 0 MongoDB repository interfaces.
2026-10-30T21:10:41.378+01:00 INFO  [main] org.mongodb.driver.client traceId= clubId= accountId= : MongoClient with metadata {"driver": {"name": "mongo-java-driver|spring-boot|sync", "version": "5.8.1"}, "os": {"type": "Darwin", "name": "Mac OS X", "architecture": "aarch64", "version": "26.4.1"}, "platform": "Java/Homebrew/[redacted]"} created with settings MongoClientSettings{readPreference=primary, writeConcern=WriteConcern{w=null, wTimeout=null ms, journal=null}, retryWrites=true, retryReads=true, readConcern=ReadConcern{level=null}, credential=null, transportSettings=null, commandListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener@517077ee], codecRegistry=ProvidersCodecRegistry{codecProviders=[ValueCodecProvider{}, BsonValueCodecProvider{}, DBRefCodecProvider{}, DBObjectCodecProvider{}, DocumentCodecProvider{}, CollectionCodecProvider{}, IterableCodecProvider{}, MapCodecProvider{}, GeoJsonCodecProvider{}, GridFSFileCodecProvider{}, Jsr310CodecProvider{}, JsonObjectCodecProvider{}, BsonCodecProvider{}, com.mongodb.client.model.mql.ExpressionCodecProvider@6bf9964a, com.mongodb.Jep395RecordCodecProvider@38d425f, com.mongodb.KotlinCodecProvider@4af0b765, EnumCodecProvider{}]}, loggerSettings=LoggerSettings{maxDocumentLength=1000}, clusterSettings={hosts=[localhost:65321], srvServiceName=mongodb, mode=SINGLE, requiredClusterType=UNKNOWN, requiredReplicaSetName='null', serverSelector='null', clusterListeners='[]', serverSelectionTimeout='2000 ms', localThreshold='15 ms'}, socketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=0, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], heartbeatSocketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=2000, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], connectionPoolSettings=ConnectionPoolSettings{maxSize=100, minSize=0, maxWaitTimeMS=2000, maxConnectionLifeTimeMS=0, maxConnectionIdleTimeMS=0, maintenanceInitialDelayMS=0, maintenanceFrequencyMS=60000, connectionPoolListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener@5e6ea6d], maxConnecting=2}, serverSettings=ServerSettings{heartbeatFrequencyMS=10000, minHeartbeatFrequencyMS=500, serverMonitoringMode=AUTO, serverListeners='[]', serverMonitorListeners='[]'}, sslSettings=SslSettings{enabled=false, invalidHostNameAllowed=false, context=null}, applicationName='null', compressorList=[], uuidRepresentation=JAVA_LEGACY, serverApi=null, autoEncryptionSettings=null, dnsClient=null, inetAddressResolver=null, contextProvider=null, timeoutMS=null}
2026-10-30T21:10:41.380+01:00 INFO  [cluster-6ae4f9c164129345c83db048-localhost:65321] org.mongodb.driver.cluster traceId= clubId= accountId= : Monitor thread successfully connected to server with description ServerDescription{address=localhost:65321, type=REPLICA_SET_PRIMARY, cryptd=false, state=CONNECTED, ok=true, minWireVersion=0, maxWireVersion=21, maxDocumentSize=16777216, logicalSessionTimeoutMinutes=30, roundTripTimeNanos=1416958, minRoundTripTimeNanos=0, setName='docker-rs', canonicalAddress=61f69c3dfe00:27017, hosts=[61f69c3dfe00:27017], passives=[], arbiters=[], primary='61f69c3dfe00:27017', tagSet=TagSet{[]}, electionId=7fffffff0000000000000001, setVersion=1, topologyVersion=TopologyVersion{processId=6ae4ef9cd85a774e0c7f3c75, counter=6}, lastWriteDate=Fri Oct 30 21:10:40 CET 2026, lastUpdateTimeNanos=[redacted]}
2026-10-30T21:10:43.339+01:00 INFO  [main] c.a.core.migration.MigrationWelcomeIT traceId= clubId= accountId= : Started MigrationWelcomeIT in 2.759 seconds (process running for 2602.589)
E5-T07 outbox backlog discarded before MigrationWelcomeIT: 158 PENDING records (more than one dispatch() batch)
```

OpenAPI command after successful verification: `bin/openapi-snapshot --from-build`. Exit: **0**; output: `Updated docs/openapi/openapi.json`. Target, API snapshot and web snapshot SHA-256 all match: `97554f07e2da7d4a108208a7e74f5954eaa78eda46d1e14624b060be2e860d66`. The web generated types were already built from this identical snapshot.

Final PDF artifacts are in `export-qa/`: `feedback-table.png` visually reviewed (readable business cells); `page-1.png` reviewed for wrapping; `members.pdf` includes the long row that continues across pages and its end-marker assertion passed.


## Deployment CI checksum classification

The first deployment CI run (41121732955) reported the verified OpenAPI SHA-256 in `api-test-summary.json:89` as `generic-api-key`. The value equals the checksum of `docs/openapi/openapi.json`; no credential was present. An AND-constrained exception accepts only that exact key/value match in that exact evidence file. The policy regression proves an adjacent credential and a changed value still fail. Runtime source and the OpenAPI contract are unchanged.

- `python3 bin/security-secret-policy-test.py --gitleaks <private-tools>/gitleaks/gitleaks`: exit 0; all positive and negative fixtures pass.
- `gitleaks git --redact --no-banner --config .gitleaks.toml --log-opts=a17dde2cc21639ebf2697116ef279d68ed37fa1d..HEAD`: exit 0; no findings. Scanner 8.30.1 matches CI and its published archive checksum was verified.
