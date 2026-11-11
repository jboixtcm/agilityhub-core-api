# Josep feedback — 11 November 2026

Direct user request: apply both reviewed feedback documents, leave consents unchecked, and test the changes. No roadmap task state or organizer verification was modified.

## Behavior

- The administrator dashboard uses sent class notices rather than the live risk projection. The count and first six rows are limited to the club's current day. View more opens all notices newest first, with 20 rows per page. API aggregation groups administrator copies by event, code and class, preserving separate risk and cancellation occurrences. Frozen class labels retain the sent wording; the notice timestamp gives the historical date. Missing legacy event ids remain separate to avoid merging unrelated events. No recipient/contact data is exposed.
- Level percentages are rounded to whole numbers in both active and recently-booked populations.
- Public identity checks return MEMBER_ALREADY_EXISTS for existing members and send no recognition email. The form explains how to sign in, update details and use My dogs / Add a dog. Authenticated additional-dog signup, pending requests and readmission retain their existing paths. Queued legacy recognition events can still drain safely.
- Privacy and image consent start unchecked. Existing accepted drafts are preserved according to the current consent-version rules.
- Counted/non-swappable weekly classes appear before the remaining swap options, once only, and the block is absent when empty.
- Member notification links retain dogId/taskId. The page focuses only a task belonging to the specified accessible dog, falling back to that dog's card. A visible completion button uses the existing task completion API and busy/error handling.
- Transfer/cash copy belongs to club configuration, not generic product code. Instance seeds and mock cash text are updated. The narrow production configuration payload is in SOFTWARE_CANIC `ops/evidence/2026-11-11-josep-feedback/configuration-change.json`.

## Investigation and unchanged rules

The preceding read-only production investigation found ten administrator cancellation emails plus three instructor copies delivered to the affected administrator on November 11. The screenshot's conversation count was 13. All active administrators were included; no resend or audience change is needed. Private recipient evidence remains outside the source repositories.

The amount actually received in signup review records a manual upfront payment and reduces the outstanding amount. Explicitly confirming zero allows validation while keeping the balance due. Entering this value does not debit a bank account. These billing rules were retained.

## Verification

Final accumulated results after the complete clean suite and corrected-test reruns: **5,021 unit tests and 2,388 integration tests, 0 failures, 0 errors, 0 skipped**. See `test-summary.json`, read from the current Surefire and Failsafe XML reports. Production code was unchanged after the complete clean run; subsequent edits corrected expectations for the newly requested behavior and the extra parameter seed event.

- `./mvnw -q clean verify` (serialized with `HEAVY_WAIT_MIN=120 …/heavy.sh`): exit 1 in `api-verify.log`. All unit tests passed; six integration assertions still expected the old recognition email or old seed values/count. They were updated and rerun. This is not claimed as a single green clean-verify invocation.
- Focused integration rerun: 136/137 passed in `api-final-regression.log`; only the exact seed event count remained stale. The final seed rerun below passed all 21 cases. An intermediate audit-count assertion was corrected back to the unchanged 38; event count is 74.
- Initial run (`api-verify-first.log`) had a password-hashing timing assertion during concurrent host work; the serialized complete run passed all unit tests without changing that test or its timeout.
- Final command: `HEAVY_WAIT_MIN=120 /Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh ./mvnw -q -Dit.test=ClubDefinitionsIT test-compile failsafe:integration-test failsafe:verify jacoco:report@coverage jacoco:check@coverage` — exit **0**, `api-seed-final.log`. Coverage gates pass using coverage produced since the complete clean run.
- OpenAPI snapshot regenerated with the repository snapshot workflow (`OpenApiSnapshotTest`, `bin/openapi-snapshot`); API and web snapshots are byte-identical and TypeScript regeneration is deterministic. Evidence: `openapi.log`, web `api-generate.log`.
- `git diff --check` — exit **0**, no output.

Changed/new integration classes: ClassNoticesIT 4; SignupIT 35; SignupMinorFixesIT 23; SignupSecurityFixesIT 54; ClubDefinitionsIT 21. All pass with no skipped cases. New endpoint cases cover tenant/admin authorization, invalid paging, deduplication, historical ordering, empty data, legacy records and a late recipient copy across local midnight.

Final seed + coverage command, last 40 lines:

```text
21:15:55.710 [main] INFO org.testcontainers.utility.RyukResourceReaper -- Ryuk started - will monitor and terminate Testcontainers containers on JVM exit
21:15:55.710 [main] INFO org.testcontainers.DockerClientFactory -- Checking the system...
21:15:55.710 [main] INFO org.testcontainers.DockerClientFactory -- ✔︎ Docker server version should be at least 1.6.0
21:15:55.711 [main] INFO tc.mongo:7 -- Creating container for image: mongo:7
21:15:55.772 [main] INFO tc.mongo:7 -- Container mongo:7 is starting: 8e961148f313e714623d5ecb529c2ac2ea156bab2b6a4052f5b5c9f46afbfc84
21:15:56.401 [main] INFO tc.mongo:7 -- Container mongo:7 started in PT0.690044S
Integration JVM availableProcessors=18

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v4.1.1)

2026-11-11T21:15:57.171+01:00 INFO  [main] c.a.c.p.a.definition.ClubDefinitionsIT traceId= clubId= accountId= : Starting ClubDefinitionsIT using Java [redacted] with PID 74342 (started by jordib in /Users/jordib/.config/agilityhub-clubs/production/josep-feedback-20261106/agilityhub-core-api)
2026-11-11T21:15:57.173+01:00 INFO  [main] c.a.c.p.a.definition.ClubDefinitionsIT traceId= clubId= accountId= : The following 1 profile is active: "test"
2026-11-11T21:15:58.324+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Bootstrapping Spring Data MongoDB repositories in DEFAULT mode.
2026-11-11T21:15:58.377+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Finished Spring Data repository scanning in 49 ms. Found 0 MongoDB repository interfaces.
2026-11-11T21:15:58.839+01:00 INFO  [main] org.mongodb.driver.client traceId= clubId= accountId= : MongoClient with metadata {"driver": {"name": "mongo-java-driver|spring-boot|sync", "version": "5.8.1"}, "os": {"type": "Darwin", "name": "Mac OS X", "architecture": "aarch64", "version": "26.4.1"}, "platform": "Java/Homebrew/[redacted]"} created with settings MongoClientSettings{readPreference=primary, writeConcern=WriteConcern{w=null, wTimeout=null ms, journal=null}, retryWrites=true, retryReads=true, readConcern=ReadConcern{level=null}, credential=null, transportSettings=null, commandListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener@495e3dd2], codecRegistry=ProvidersCodecRegistry{codecProviders=[ValueCodecProvider{}, BsonValueCodecProvider{}, DBRefCodecProvider{}, DBObjectCodecProvider{}, DocumentCodecProvider{}, CollectionCodecProvider{}, IterableCodecProvider{}, MapCodecProvider{}, GeoJsonCodecProvider{}, GridFSFileCodecProvider{}, Jsr310CodecProvider{}, JsonObjectCodecProvider{}, BsonCodecProvider{}, com.mongodb.client.model.mql.ExpressionCodecProvider@2102d36f, com.mongodb.Jep395RecordCodecProvider@32bce8fe, com.mongodb.KotlinCodecProvider@20ad6dcd, EnumCodecProvider{}]}, loggerSettings=LoggerSettings{maxDocumentLength=1000}, clusterSettings={hosts=[localhost:59949], srvServiceName=mongodb, mode=SINGLE, requiredClusterType=UNKNOWN, requiredReplicaSetName='null', serverSelector='null', clusterListeners='[]', serverSelectionTimeout='2000 ms', localThreshold='15 ms'}, socketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=0, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], heartbeatSocketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=2000, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], connectionPoolSettings=ConnectionPoolSettings{maxSize=100, minSize=0, maxWaitTimeMS=2000, maxConnectionLifeTimeMS=0, maxConnectionIdleTimeMS=0, maintenanceInitialDelayMS=0, maintenanceFrequencyMS=60000, connectionPoolListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener@18a564a7], maxConnecting=2}, serverSettings=ServerSettings{heartbeatFrequencyMS=10000, minHeartbeatFrequencyMS=500, serverMonitoringMode=AUTO, serverListeners='[]', serverMonitorListeners='[]'}, sslSettings=SslSettings{enabled=false, invalidHostNameAllowed=false, context=null}, applicationName='null', compressorList=[], uuidRepresentation=JAVA_LEGACY, serverApi=null, autoEncryptionSettings=null, dnsClient=null, inetAddressResolver=null, contextProvider=null, timeoutMS=null}
2026-11-11T21:15:58.846+01:00 INFO  [cluster-6af4ccfe8eb9a74658d02871-localhost:59949] org.mongodb.driver.cluster traceId= clubId= accountId= : Monitor thread successfully connected to server with description ServerDescription{address=localhost:59949, type=REPLICA_SET_PRIMARY, cryptd=false, state=CONNECTED, ok=true, minWireVersion=0, maxWireVersion=21, maxDocumentSize=16777216, logicalSessionTimeoutMinutes=30, roundTripTimeNanos=11196375, minRoundTripTimeNanos=0, setName='docker-rs', canonicalAddress=8e961148f313:27017, hosts=[8e961148f313:27017], passives=[], arbiters=[], primary='8e961148f313:27017', tagSet=TagSet{[]}, electionId=7fffffff0000000000000001, setVersion=1, topologyVersion=TopologyVersion{processId=6af4ccfbe97c6251fb8945d3, counter=6}, lastWriteDate=Wed Nov 11 21:15:56 CET 2026, lastUpdateTimeNanos=[redacted]}
2026-11-11T21:16:00.011+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-11T21:16:00.030+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-11T21:16:00.056+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK. Please add Mockito as an agent to your build as described in Mockito's documentation: https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html#0.3
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
WARNING: A Java agent has been loaded dynamically (/Users/jordib/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.11/byte-buddy-agent-1.18.11.jar)
WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading to hide this warning
WARNING: If a serviceability tool is not in use, please run with -Djdk.instrument.traceUsage for more information
WARNING: Dynamic loading of agents will be disallowed by default in a future release
2026-11-11T21:16:01.569+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-11T21:16:01.584+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-11T21:16:01.597+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-11T21:16:01.682+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-11T21:16:04.866+01:00 INFO  [main] o.s.b.t.m.w.SpringBootMockServletContext traceId= clubId= accountId= : Initializing Spring TestDispatcherServlet ''
2026-11-11T21:16:04.866+01:00 INFO  [main] o.s.t.web.servlet.TestDispatcherServlet traceId= clubId= accountId= : Initializing Servlet ''
2026-11-11T21:16:04.871+01:00 INFO  [main] o.s.t.web.servlet.TestDispatcherServlet traceId= clubId= accountId= : Completed initialization in 5 ms
2026-11-11T21:16:04.941+01:00 INFO  [main] c.a.c.p.a.definition.ClubDefinitionsIT traceId= clubId= accountId= : Started ClubDefinitionsIT in 7.989 seconds (process running for 11.269)
```

## Release handoff

No commit, push, deployment, production data change or message send occurred. Publish API and web together because the web consumes the new class-notices endpoint and identity-check outcome. For an already provisioned club, seed changes alone do not update its settings: read current versions and update only MANUAL.instructions and signup.text.cashConditions via the supported APIs, preserving enabled state and all other configuration. Do not apply a full seed or replay notifications.
