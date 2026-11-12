# Receipt simulation investigation — 12 November 2026

## Finding

The existing `PROVIDER_DISABLED` incident covers two conditions: the payment provider is disabled, or SEPA_XML is enabled but its creditor name, identifier, account or suffix is incomplete/invalid. `BillingSimulationService.build` excludes otherwise eligible direct debits from the preview in the latter condition. `SepaRemittanceWriter` separately refuses generation with `SEPA_NOT_CONFIGURED`. Removing these guards would produce invalid bank files.

The club seed deliberately enables SEPA_XML without creditor values (`seeds/club-canic.yaml`); its comment says the club must configure the creditor separately. A read-only production check by the parent task confirmed `SEPA_XML: {enabled: true, configured: false}`. The safe operational evidence is in SOFTWARE_CANIC `ops/evidence/2026-11-12-josep-feedback/production-read.json`. Production therefore needs verified creditor configuration, not a provider-enable switch. The parent task owns source verification and any subsequent authorized configuration repair.

Hosted-demo restrictions apply to Stripe live configuration and live webhook events. The SEPA writer has no hosted-demo or test-mode gate. A configured provider can generate an XML file in the application, but generation also creates invoices, collections and billing dates; it is not a read-only simulation. Download does not submit the remittance to the bank. Do not run generation merely to inspect production data.

The supported platform provider update requires a creditor name, a valid creditor identifier, a valid IBAN and a three-character alphanumeric suffix. Do not invent these from an account number or tax identifier. The existing broad incident text should explain incomplete club configuration as well as a disabled provider; member record editing does not repair the club's creditor.

No API source change, production mutation, provider activation, remittance submission, message sending, commit or deployment was performed by this investigation. The follow-up web changes explain the confirmed condition; see the web repository `roadmap/evidence/feedback-20261112-receipts/REPORT.md`.

## Source references

- `src/main/java/com/agilityhub/core/payments/application/BillingSimulationService.java`: enabled SEPA with unusable creditor maps to PROVIDER_DISABLED.
- `src/main/java/com/agilityhub/core/payments/domain/InvoicingRules.java`: disabled method and member-data precedence.
- `src/main/java/com/agilityhub/core/platform/application/BillingProviderSettings.java`: creditor validation and encrypted-account retrieval.
- `src/main/java/com/agilityhub/core/platform/application/PlatformProviders.java`: supported versioned provider update; only live Stripe is blocked by hosted policy.
- `src/main/java/com/agilityhub/core/payments/application/ConsolePaymentValidation.java`: identifier, IBAN and suffix checks.
- `src/main/java/com/agilityhub/core/payments/application/sepa/SepaRemittanceWriter.java`: XML generation, XSD check and rollback-on-failure.

## Verification

Command:

```sh
HEAVY_WAIT_MIN=120 /Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh ./mvnw -q -Dtest=BillingProviderSettingsTest,PaymentProviderFlagsTest,InvoicingRulesTest,SepaRemittanceWriterTest -Dit.test=RemittancesIT test failsafe:integration-test failsafe:verify
```

Exit code: **0**. 47 unit tests and 16 integration tests, no failures, errors or skips. Counts are read from the Surefire/Failsafe XML and retained in `test-summary.json`. Full output: `targeted-tests.log`.

The integration proof checks the fictional census XML against its golden file and XSD, verifies debtor account ownership per invoice, verifies no full bank details escape response DTOs, distinguishes generation from submission, and proves invalid/missing creditors leave no invoices, remittances, collections, moved dates or file behind.

Last 40 output lines (fixture data only):

```text
08:43:32.003 [main] INFO org.testcontainers.DockerClientFactory -- Checking the system...
08:43:32.004 [main] INFO org.testcontainers.DockerClientFactory -- ✔︎ Docker server version should be at least 1.6.0
08:43:32.004 [main] INFO tc.mongo:7 -- Creating container for image: mongo:7
08:43:32.055 [main] INFO tc.mongo:7 -- Container mongo:7 is starting: eaab0f02733ba346a51957ba95ba195a15661c0bf0daf4b9f0c35690f9ea2c66
08:43:32.642 [main] INFO tc.mongo:7 -- Container mongo:7 started in PT0.637901S
Integration JVM availableProcessors=18

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v4.1.1)

2026-11-12T08:43:33.490+01:00 INFO  [main] c.a.core.payments.api.RemittancesIT traceId= clubId= accountId= : Starting RemittancesIT using Java [redacted] with PID 84435 (started by jordib in /Users/jordib/.config/agilityhub-clubs/production/josep-feedback-20261106/agilityhub-core-api)
2026-11-12T08:43:33.493+01:00 INFO  [main] c.a.core.payments.api.RemittancesIT traceId= clubId= accountId= : The following 1 profile is active: "test"
2026-11-12T08:43:35.297+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Bootstrapping Spring Data MongoDB repositories in DEFAULT mode.
2026-11-12T08:43:35.374+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Finished Spring Data repository scanning in 72 ms. Found 0 MongoDB repository interfaces.
2026-11-12T08:43:35.990+01:00 INFO  [main] org.mongodb.driver.client traceId= clubId= accountId= : MongoClient with metadata {"driver": {"name": "mongo-java-driver|spring-boot|sync", "version": "5.8.1"}, "os": {"type": "Darwin", "name": "Mac OS X", "architecture": "aarch64", "version": "26.4.1"}, "platform": "Java/Homebrew/[redacted]"} created with settings MongoClientSettings{readPreference=primary, writeConcern=WriteConcern{w=null, wTimeout=null ms, journal=null}, retryWrites=true, retryReads=true, readConcern=ReadConcern{level=null}, credential=null, transportSettings=null, commandListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener@5eb82ec9], codecRegistry=ProvidersCodecRegistry{codecProviders=[ValueCodecProvider{}, BsonValueCodecProvider{}, DBRefCodecProvider{}, DBObjectCodecProvider{}, DocumentCodecProvider{}, CollectionCodecProvider{}, IterableCodecProvider{}, MapCodecProvider{}, GeoJsonCodecProvider{}, GridFSFileCodecProvider{}, Jsr310CodecProvider{}, JsonObjectCodecProvider{}, BsonCodecProvider{}, com.mongodb.client.model.mql.ExpressionCodecProvider@115bb033, com.mongodb.Jep395RecordCodecProvider@20f80c94, com.mongodb.KotlinCodecProvider@bf43fd, EnumCodecProvider{}]}, loggerSettings=LoggerSettings{maxDocumentLength=1000}, clusterSettings={hosts=[localhost:63891], srvServiceName=mongodb, mode=SINGLE, requiredClusterType=UNKNOWN, requiredReplicaSetName='null', serverSelector='null', clusterListeners='[]', serverSelectionTimeout='2000 ms', localThreshold='15 ms'}, socketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=0, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], heartbeatSocketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=2000, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], connectionPoolSettings=ConnectionPoolSettings{maxSize=100, minSize=0, maxWaitTimeMS=2000, maxConnectionLifeTimeMS=0, maxConnectionIdleTimeMS=0, maintenanceInitialDelayMS=0, maintenanceFrequencyMS=60000, connectionPoolListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener@1feefb45], maxConnecting=2}, serverSettings=ServerSettings{heartbeatFrequencyMS=10000, minHeartbeatFrequencyMS=500, serverMonitoringMode=AUTO, serverListeners='[]', serverMonitorListeners='[]'}, sslSettings=SslSettings{enabled=false, invalidHostNameAllowed=false, context=null}, applicationName='null', compressorList=[], uuidRepresentation=JAVA_LEGACY, serverApi=null, autoEncryptionSettings=null, dnsClient=null, inetAddressResolver=null, contextProvider=null, timeoutMS=null}
2026-11-12T08:43:36.000+01:00 INFO  [cluster-6af56e278bcd97a2e426283d-localhost:63891] org.mongodb.driver.cluster traceId= clubId= accountId= : Monitor thread successfully connected to server with description ServerDescription{address=localhost:63891, type=REPLICA_SET_PRIMARY, cryptd=false, state=CONNECTED, ok=true, minWireVersion=0, maxWireVersion=21, maxDocumentSize=16777216, logicalSessionTimeoutMinutes=30, roundTripTimeNanos=14770500, minRoundTripTimeNanos=0, setName='docker-rs', canonicalAddress=eaab0f02733b:27017, hosts=[eaab0f02733b:27017], passives=[], arbiters=[], primary='eaab0f02733b:27017', tagSet=TagSet{[]}, electionId=7fffffff0000000000000001, setVersion=1, topologyVersion=TopologyVersion{processId=6af56e2446c8baf7d05905fd, counter=6}, lastWriteDate=Thu Nov 12 08:43:33 CET 2026, lastUpdateTimeNanos=[redacted]}
2026-11-12T08:43:37.428+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-12T08:43:37.452+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-12T08:43:37.478+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-12T08:43:38.587+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-12T08:43:38.611+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-12T08:43:38.629+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-12T08:43:38.741+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-12T08:43:42.976+01:00 INFO  [main] o.s.b.t.m.w.SpringBootMockServletContext traceId= clubId= accountId= : Initializing Spring TestDispatcherServlet ''
2026-11-12T08:43:42.976+01:00 INFO  [main] o.s.t.web.servlet.TestDispatcherServlet traceId= clubId= accountId= : Initializing Servlet ''
2026-11-12T08:43:42.986+01:00 INFO  [main] o.s.t.web.servlet.TestDispatcherServlet traceId= clubId= accountId= : Completed initialization in 9 ms
2026-11-12T08:43:43.107+01:00 INFO  [main] c.a.core.payments.api.RemittancesIT traceId= clubId= accountId= : Started RemittancesIT in 9.9 seconds (process running for 13.57)
Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK. Please add Mockito as an agent to your build as described in Mockito's documentation: https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html#0.3
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
WARNING: A Java agent has been loaded dynamically (/Users/jordib/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.11/byte-buddy-agent-1.18.11.jar)
WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading to hide this warning
WARNING: If a serviceability tool is not in use, please run with -Djdk.instrument.traceUsage for more information
WARNING: Dynamic loading of agents will be disallowed by default in a future release
2026-11-12T08:43:56.909+01:00 WARN  [main] c.a.c.p.a.sepa.SepaRemittanceWriter traceId=7045abd2-890c-499f-82ed-09c26f6f18e7 clubId=bill-a accountId=bill-admin : Remittance bill-a-2026-09-1 of run 0e5eb7ba-b217-4cb4-9738-744552e8490c: pain.008 invalid at line 40, column 46
```
