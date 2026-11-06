# November 6 feedback — executor report

Status: implemented and locally checked; not published. Direct user request outside the roadmap queue. Source snapshot from the November 5-3 publication checkout. No organizer status or verification section changed.

## Result

- S04/R-04-10: separate upfront CARD/BANK_TRANSFER/CASH from recurring payment. Enabled providers gate the options and submissions. Manual charges remain DUE; only a positive CARD upfront amount requires checkout. Legacy requests may omit the new optional fields. Public/add-dog submissions store the selected initial method; administrator review exposes it.
- S09/R-09-03: split each saved daily band into complete training intervals and fill uncovered opening hours on the wall-clock grid. Slot listing, placement validation and concurrency locks use the same grid. Ungenerated days retain the opening-hour grid.
- Club-specific transfer instructions match the supplied feedback in both canonical and consumer seeds. Live provider configuration is still unchanged; operations has the instructions payload prepared for a later club-provider update.
- OpenAPI and the web client were regenerated. Existing error codes and tenant boundaries were preserved. Specifications and changelog updated.

## Validation outcome

The clean full run executed 5,017 unit tests and 2,379 integration tests. It exited 1 on seven assertions concerning the former API shape, one-start-per-band expectation, the consumer seed copy and the old transfer text. Those expectations/data were corrected, and all four affected test classes passed separately: E3ContractIT 17, PlanningIT 16, DemoSeedsIT 12, ClubDefinitionsIT 21 (66 total). Every originally failing test was matched by name to a passing rerun. There are no unresolved failures. The original failing clean-run log is retained; it is not presented as an exit-0 full run.

Production Java/resource sources were compared byte-for-byte with the isolated rerun checkout and match. The final post-full-run edits affect test expectations and the matching consumer seed, not application bytecode. Coverage was checked using the execution data produced by the clean full run: all configured package thresholds pass (domain/application 85% lines, 80% branches; API 70% lines). See the operations `resolucio-validacio-api.json`, `validacio-api.json` and `cobertura.json` for totals and resolved assertions.

Earlier attempts: missing add-dog error documentation was corrected; the password timing assertion passed under lower competing load; the seed instructions retain the club's supported ca/es locales. Operations `attempts.json` and archived logs retain these attempts. No production writes, notifications or payments were performed.

## Commands and final evidence

### `./mvnw -q clean verify`

Exit: 1. Full output: `04-clean-verify.log`.

```text
= modules unchanged
~ paymentProviders changed
  paymentProviders.MANUAL: {"enabled":true} -> {"enabled":true,"instructions":{"ca":"Pot fer la transferència al compte ES08 2100 0416 5702 0016 4955 a nom de Club d’agility Canic posant com a concepte el seu nom i el del gos","es":"Puede hacer la transferencia a la cuenta ES08 2100 0416 5702 0016 4955 a nombre de Club d’agility Canic indicando como concepto su nombre y el del perro"}}
= legal unchanged
= pwa unchanged
= parameters.signup.enabled unchanged
= parameters.signup.pendingExpiryDays unchanged
= parameters.signup.requireDogDocumentAtSignup unchanged
= parameters.signup.firstMonthSplitDay unchanged
= parameters.signup.allowFamilyGroupPending unchanged
= admins: 0 to provision
= accounts: 0 to provision
= catalogs: 0 catalog changes
= messageTemplates: 0 templates to seed
= pages: 0 page changes
1 changes (dry run)"
to contain:
  ["~ paymentProviders changed",
    "paymentProviders.MANUAL: {"enabled":true} -> {"enabled":true,"instructions":{"ca":"El pagament de l'entrada"]
but could not find:
  ["paymentProviders.MANUAL: {"enabled":true} -> {"enabled":true,"instructions":{"ca":"El pagament de l'entrada"]
 
[ERROR]   ClubDefinitionsIT.T_02_12_canicSeedEnablesDirectDebitAndCashWithoutConfiguringThem:113 
expected: {"MANUAL"={"enabled"=true, "instructions"={"ca"="El pagament de l'entrada i el mes en curs (o el proper) és necessari per a la validació de l'alta. Pot fer-se per transferència al compte ES08 2100 0416 5702 0016 4955 (Club d'Agility Cànic) o per Bizum al 607 475 945.", "es"="El pago de la entrada y del mes en curso (o del siguiente) es necesario para la validación del alta. Puede hacerse por transferencia a la cuenta ES08 2100 0416 5702 0016 4955 (Club d'Agility Cànic) o por Bizum al 607 475 945."}}, "SEPA_XML"={"enabled"=true}}
 but was: {"MANUAL"={"enabled"=true, "instructions"={"ca"="Pot fer la transferència al compte ES08 2100 0416 5702 0016 4955 a nom de Club d’agility Canic posant com a concepte el seu nom i el del gos", "es"="Puede hacer la transferencia a la cuenta ES08 2100 0416 5702 0016 4955 a nombre de Club d’agility Canic indicando como concepto su nombre y el del perro"}}, "SEPA_XML"={"enabled"=true}}
[ERROR]   ClubDefinitionsIT.T_17_01_enablingSeededProvidersIsOneChangeAndKeepsTheirStoredConfiguration:380 
expected: {"MANUAL"={"enabled"=true, "instructions"={"ca"="El pagament de l'entrada i el mes en curs (o el proper) és necessari per a la validació de l'alta. Pot fer-se per transferència al compte ES08 2100 0416 5702 0016 4955 (Club d'Agility Cànic) o per Bizum al 607 475 945.", "es"="El pago de la entrada y del mes en curso (o del siguiente) es necesario para la validación del alta. Puede hacerse por transferencia a la cuenta ES08 2100 0416 5702 0016 4955 (Club d'Agility Cànic) o por Bizum al 607 475 945."}}, "SEPA_XML"={"creditorName"="Fictional Creditor", "enabled"=true}}
 but was: {"MANUAL"={"enabled"=true, "instructions"={"ca"="Pot fer la transferència al compte ES08 2100 0416 5702 0016 4955 a nom de Club d’agility Canic posant com a concepte el seu nom i el del gos", "es"="Puede hacer la transferencia a la cuenta ES08 2100 0416 5702 0016 4955 a nombre de Club d’agility Canic indicando como concepto su nombre y el del perro"}}, "SEPA_XML"={"creditorName"="Fictional Creditor", "enabled"=true}}
[ERROR] Tests run: 2379, Failures: 7, Errors: 0, Skipped: 0
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-failsafe-plugin:3.5.6:verify (default) on project agilityhub-core-api: There are test failures.
[ERROR] 
[ERROR] See /Users/jordib/dev/agilityhub/feedback-20261106/api/target/failsafe-reports for the individual test results.
[ERROR] See dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.
[ERROR] -> [Help 1]
[ERROR] 
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR] 
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
```

### `./mvnw -q test-compile failsafe:integration-test failsafe:verify -Dit.test=E3ContractIT`

Exit: 0. Full output: `05-contract-final.log`.

```text
13:04:59.643 [main] INFO org.springframework.boot.test.context.SpringBootTestContextBootstrapper -- Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.configuration.E3ContractIT
13:04:59.734 [main] INFO org.springframework.test.context.support.AnnotationConfigContextLoaderUtils -- Could not detect default configuration classes for test class [com.agilityhub.core.configuration.E3ContractIT]: E3ContractIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
13:04:59.735 [main] INFO org.springframework.test.context.support.AnnotationConfigContextLoaderUtils -- Could not detect default configuration classes for test class [com.agilityhub.core.support.AbstractIntegrationTest]: AbstractIntegrationTest does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
13:04:59.739 [main] INFO org.springframework.boot.test.context.SpringBootTestContextBootstrapper -- Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.configuration.E3ContractIT

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v4.1.1)

2026-11-06T13:05:00.277+01:00 INFO  [main] c.a.core.configuration.E3ContractIT traceId= clubId= accountId= : Starting E3ContractIT using Java [redacted] with PID 91241 (started by jordib in /private/tmp/canic-nov6-contract-check)
2026-11-06T13:05:00.280+01:00 INFO  [main] c.a.core.configuration.E3ContractIT traceId= clubId= accountId= : The following 1 profile is active: "test"
2026-11-06T13:05:03.219+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Bootstrapping Spring Data MongoDB repositories in DEFAULT mode.
2026-11-06T13:05:03.323+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Finished Spring Data repository scanning in 96 ms. Found 0 MongoDB repository interfaces.
2026-11-06T13:05:04.595+01:00 INFO  [main] org.mongodb.driver.client traceId= clubId= accountId= : MongoClient with metadata {"driver": {"name": "mongo-java-driver|spring-boot|sync", "version": "5.8.1"}, "os": {"type": "Darwin", "name": "Mac OS X", "architecture": "aarch64", "version": "26.4.1"}, "platform": "Java/Homebrew/[redacted]"} created with settings MongoClientSettings{readPreference=primary, writeConcern=WriteConcern{w=null, wTimeout=null ms, journal=null}, retryWrites=true, retryReads=true, readConcern=ReadConcern{level=null}, credential=null, transportSettings=null, commandListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener@5b3ab554], codecRegistry=ProvidersCodecRegistry{codecProviders=[ValueCodecProvider{}, BsonValueCodecProvider{}, DBRefCodecProvider{}, DBObjectCodecProvider{}, DocumentCodecProvider{}, CollectionCodecProvider{}, IterableCodecProvider{}, MapCodecProvider{}, GeoJsonCodecProvider{}, GridFSFileCodecProvider{}, Jsr310CodecProvider{}, JsonObjectCodecProvider{}, BsonCodecProvider{}, com.mongodb.client.model.mql.ExpressionCodecProvider@46c22594, com.mongodb.Jep395RecordCodecProvider@5170c12d, com.mongodb.KotlinCodecProvider@27446428, EnumCodecProvider{}]}, loggerSettings=LoggerSettings{maxDocumentLength=1000}, clusterSettings={hosts=[localhost:49963], srvServiceName=mongodb, mode=SINGLE, requiredClusterType=UNKNOWN, requiredReplicaSetName='null', serverSelector='null', clusterListeners='[]', serverSelectionTimeout='2000 ms', localThreshold='15 ms'}, socketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=0, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], heartbeatSocketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=2000, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], connectionPoolSettings=ConnectionPoolSettings{maxSize=100, minSize=0, maxWaitTimeMS=2000, maxConnectionLifeTimeMS=0, maxConnectionIdleTimeMS=0, maintenanceInitialDelayMS=0, maintenanceFrequencyMS=60000, connectionPoolListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener@295af581], maxConnecting=2}, serverSettings=ServerSettings{heartbeatFrequencyMS=10000, minHeartbeatFrequencyMS=500, serverMonitoringMode=AUTO, serverListeners='[]', serverMonitorListeners='[]'}, sslSettings=SslSettings{enabled=false, invalidHostNameAllowed=false, context=null}, applicationName='null', compressorList=[], uuidRepresentation=JAVA_LEGACY, serverApi=null, autoEncryptionSettings=null, dnsClient=null, inetAddressResolver=null, contextProvider=null, timeoutMS=null}
2026-11-06T13:05:04.610+01:00 INFO  [cluster-6aedc2703e27492f140fa216-localhost:49963] org.mongodb.driver.cluster traceId= clubId= accountId= : Monitor thread successfully connected to server with description ServerDescription{address=localhost:49963, type=REPLICA_SET_PRIMARY, cryptd=false, state=CONNECTED, ok=true, minWireVersion=0, maxWireVersion=21, maxDocumentSize=16777216, logicalSessionTimeoutMinutes=30, roundTripTimeNanos=21902166, minRoundTripTimeNanos=0, setName='docker-rs', canonicalAddress=962c3d81156d:27017, hosts=[962c3d81156d:27017], passives=[], arbiters=[], primary='962c3d81156d:27017', tagSet=TagSet{[]}, electionId=7fffffff0000000000000001, setVersion=1, topologyVersion=TopologyVersion{processId=6aedc269ea66cff4ddeceadc, counter=6}, lastWriteDate=Fri Nov 06 13:04:58 CET 2026, lastUpdateTimeNanos=[redacted]}
2026-11-06T13:05:07.374+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T13:05:07.419+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T13:05:07.470+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T13:05:09.359+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T13:05:09.405+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T13:05:09.472+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T13:05:09.678+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T13:05:16.662+01:00 INFO  [main] o.s.b.t.m.w.SpringBootMockServletContext traceId= clubId= accountId= : Initializing Spring TestDispatcherServlet ''
2026-11-06T13:05:16.662+01:00 INFO  [main] o.s.t.web.servlet.TestDispatcherServlet traceId= clubId= accountId= : Initializing Servlet ''
2026-11-06T13:05:16.675+01:00 INFO  [main] o.s.t.web.servlet.TestDispatcherServlet traceId= clubId= accountId= : Completed initialization in 13 ms
2026-11-06T13:05:16.836+01:00 INFO  [main] c.a.core.configuration.E3ContractIT traceId= clubId= accountId= : Started E3ContractIT in 17.034 seconds (process running for 23.555)
Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK. Please add Mockito as an agent to your build as described in Mockito's documentation: https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html#0.3
WARNING: A Java agent has been loaded dynamically (/Users/jordib/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.11/byte-buddy-agent-1.18.11.jar)
WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading to hide this warning
WARNING: If a serviceability tool is not in use, please run with -Djdk.instrument.traceUsage for more information
WARNING: Dynamic loading of agents will be disallowed by default in a future release
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
2026-11-06T13:05:21.322+01:00 WARN  [main] c.a.core.shared.api.ApiExceptionHandler traceId=0333991b-403c-4e51-acaa-7af728755b8b clubId=e3-club-a accountId=- : Framework request rejected status=400 code=VALIDATION_ERROR traceId=0333991b-403c-4e51-acaa-7af728755b8b
2026-11-06T13:05:21.395+01:00 WARN  [main] c.a.core.shared.api.ApiExceptionHandler traceId=85663a5e-9c50-4b63-a5e8-f16edac60af3 clubId=e3-club-a accountId=user : Framework request rejected status=400 code=VALIDATION_ERROR traceId=85663a5e-9c50-4b63-a5e8-f16edac60af3
2026-11-06T13:05:26.468+01:00 INFO  [main] o.springdoc.api.AbstractOpenApiResource traceId=c0291bf3-c84c-4290-b85c-3103ea568b66 clubId=- accountId=- : Init duration for springdoc-openapi is: 5002 ms
```

### `./mvnw -q test-compile failsafe:integration-test failsafe:verify -Dit.test=PlanningIT`

Exit: 0. Full output: `07-planning-final.log`.

```text
	at org.junit.platform.engine.support.hierarchical.ThrowableCollector.execute(ThrowableCollector.java:74)
	at org.junit.platform.engine.support.hierarchical.NodeTestTask.lambda$executeRecursively$1(NodeTestTask.java:166)
	at org.junit.platform.engine.support.hierarchical.Node.around(Node.java:138)
	at org.junit.platform.engine.support.hierarchical.NodeTestTask.lambda$executeRecursively$0(NodeTestTask.java:164)
	at org.junit.platform.engine.support.hierarchical.ThrowableCollector.execute(ThrowableCollector.java:74)
	at org.junit.platform.engine.support.hierarchical.NodeTestTask.executeRecursively(NodeTestTask.java:163)
	at org.junit.platform.engine.support.hierarchical.NodeTestTask.execute(NodeTestTask.java:116)
	at org.junit.platform.engine.support.hierarchical.SameThreadHierarchicalTestExecutorService.submit(SameThreadHierarchicalTestExecutorService.java:36)
	at org.junit.platform.engine.support.hierarchical.HierarchicalTestExecutor.execute(HierarchicalTestExecutor.java:52)
	at org.junit.platform.engine.support.hierarchical.HierarchicalTestEngine.execute(HierarchicalTestEngine.java:58)
	at org.junit.platform.launcher.core.EngineExecutionOrchestrator.executeEngine(EngineExecutionOrchestrator.java:246)
	at org.junit.platform.launcher.core.EngineExecutionOrchestrator.failOrExecuteEngine(EngineExecutionOrchestrator.java:218)
	at org.junit.platform.launcher.core.EngineExecutionOrchestrator.execute(EngineExecutionOrchestrator.java:179)
	at org.junit.platform.launcher.core.EngineExecutionOrchestrator.execute(EngineExecutionOrchestrator.java:108)
	at org.junit.platform.launcher.core.EngineExecutionOrchestrator.lambda$execute$0(EngineExecutionOrchestrator.java:66)
	at org.junit.platform.launcher.core.EngineExecutionOrchestrator.withInterceptedStreams(EngineExecutionOrchestrator.java:157)
	at org.junit.platform.launcher.core.EngineExecutionOrchestrator.execute(EngineExecutionOrchestrator.java:65)
	at org.junit.platform.launcher.core.DefaultLauncher.execute(DefaultLauncher.java:125)
	at org.junit.platform.launcher.core.DefaultLauncher.execute(DefaultLauncher.java:114)
	at org.junit.platform.launcher.core.DelegatingLauncher.execute(DelegatingLauncher.java:58)
	at org.junit.platform.launcher.core.InterceptingLauncher.lambda$execute$2(InterceptingLauncher.java:57)
	at org.junit.platform.launcher.core.ClasspathAlignmentCheckingLauncherInterceptor.intercept(ClasspathAlignmentCheckingLauncherInterceptor.java:25)
	at org.junit.platform.launcher.core.InterceptingLauncher.execute(InterceptingLauncher.java:56)
	at org.junit.platform.launcher.core.DelegatingLauncher.execute(DelegatingLauncher.java:58)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at org.apache.maven.surefire.api.util.ReflectionUtils.invokeMethodWithArray(ReflectionUtils.java:125)
	at org.apache.maven.surefire.junitplatform.LauncherAdapter.executeWithCancellationToken(LauncherAdapter.java:68)
	at org.apache.maven.surefire.junitplatform.LauncherAdapter.execute(LauncherAdapter.java:54)
	at org.apache.maven.surefire.junitplatform.JUnitPlatformProvider.execute(JUnitPlatformProvider.java:203)
	at org.apache.maven.surefire.junitplatform.JUnitPlatformProvider.invokeAllTests(JUnitPlatformProvider.java:168)
	at org.apache.maven.surefire.junitplatform.JUnitPlatformProvider.invoke(JUnitPlatformProvider.java:136)
	at org.apache.maven.surefire.booter.ForkedBooter.runSuitesInProcess(ForkedBooter.java:385)
	at org.apache.maven.surefire.booter.ForkedBooter.execute(ForkedBooter.java:162)
	at org.apache.maven.surefire.booter.ForkedBooter.run(ForkedBooter.java:507)
	at org.apache.maven.surefire.booter.ForkedBooter.main(ForkedBooter.java:495)
2026-11-06T13:33:03.492+01:00 WARN  [main] c.a.core.shared.api.ApiExceptionHandler traceId=8723665f-e726-41f0-a4c2-503d1e7e7b1c clubId=planning-a accountId=planning-admin : Framework request rejected code=VALIDATION_ERROR traceId=8723665f-e726-41f0-a4c2-503d1e7e7b1c
2026-11-06T13:33:05.313+01:00 WARN  [main] c.a.core.shared.api.ApiExceptionHandler traceId=8b242ee4-7a75-473d-ab13-19995a001978 clubId=planning-a accountId=planning-admin : Framework request rejected code=VALIDATION_ERROR traceId=8b242ee4-7a75-473d-[redacted]
2026-11-06T13:33:06.920+01:00 WARN  [main] c.a.core.shared.api.ApiExceptionHandler traceId=30562c4f-c692-4aaf-8b72-8288eae6b3d5 clubId=planning-a accountId=planning-admin : Framework request rejected code=VALIDATION_ERROR traceId=30562c4f-c692-4aaf-8b72-8288eae6b3d5
2026-11-06T13:33:09.976+01:00 WARN  [main] c.networknt.schema.UnknownKeywordFactory traceId= clubId= accountId= : Unknown keyword components - you should define your own Meta Schema. If the keyword is irrelevant for validation, just use a NonValidationKeyword or if it should generate annotations AnnotationKeyword
```

### `./mvnw -q test-compile failsafe:integration-test failsafe:verify -Dit.test=DemoSeedsIT`

Exit: 0. Full output: `08-seed-final.log`.

```text
2026-11-06T16:09:55.011+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Bootstrapping Spring Data MongoDB repositories in DEFAULT mode.
2026-11-06T16:09:55.063+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Finished Spring Data repository scanning in 47 ms. Found 0 MongoDB repository interfaces.
2026-11-06T16:09:55.535+01:00 INFO  [main] org.mongodb.driver.client traceId= clubId= accountId= : MongoClient with metadata {"driver": {"name": "mongo-java-driver|spring-boot|sync", "version": "5.8.1"}, "os": {"type": "Darwin", "name": "Mac OS X", "architecture": "aarch64", "version": "26.4.1"}, "platform": "Java/Homebrew/[redacted]"} created with settings MongoClientSettings{readPreference=primary, writeConcern=WriteConcern{w=null, wTimeout=null ms, journal=null}, retryWrites=true, retryReads=true, readConcern=ReadConcern{level=null}, credential=null, transportSettings=null, commandListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener@24ae81d3], codecRegistry=ProvidersCodecRegistry{codecProviders=[ValueCodecProvider{}, BsonValueCodecProvider{}, DBRefCodecProvider{}, DBObjectCodecProvider{}, DocumentCodecProvider{}, CollectionCodecProvider{}, IterableCodecProvider{}, MapCodecProvider{}, GeoJsonCodecProvider{}, GridFSFileCodecProvider{}, Jsr310CodecProvider{}, JsonObjectCodecProvider{}, BsonCodecProvider{}, com.mongodb.client.model.mql.ExpressionCodecProvider@1039af82, com.mongodb.Jep395RecordCodecProvider@375d73bb, com.mongodb.KotlinCodecProvider@692c4be9, EnumCodecProvider{}]}, loggerSettings=LoggerSettings{maxDocumentLength=1000}, clusterSettings={hosts=[localhost:59924], srvServiceName=mongodb, mode=SINGLE, requiredClusterType=UNKNOWN, requiredReplicaSetName='null', serverSelector='null', clusterListeners='[]', serverSelectionTimeout='2000 ms', localThreshold='15 ms'}, socketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=0, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], heartbeatSocketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=2000, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], connectionPoolSettings=ConnectionPoolSettings{maxSize=100, minSize=0, maxWaitTimeMS=2000, maxConnectionLifeTimeMS=0, maxConnectionIdleTimeMS=0, maintenanceInitialDelayMS=0, maintenanceFrequencyMS=60000, connectionPoolListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener@39dcff06], maxConnecting=2}, serverSettings=ServerSettings{heartbeatFrequencyMS=10000, minHeartbeatFrequencyMS=500, serverMonitoringMode=AUTO, serverListeners='[]', serverMonitorListeners='[]'}, sslSettings=SslSettings{enabled=false, invalidHostNameAllowed=false, context=null}, applicationName='null', compressorList=[], uuidRepresentation=JAVA_LEGACY, serverApi=null, autoEncryptionSettings=null, dnsClient=null, inetAddressResolver=null, contextProvider=null, timeoutMS=null}
2026-11-06T16:09:55.542+01:00 INFO  [cluster-6aededc38842a9d7974a751b-localhost:59924] org.mongodb.driver.cluster traceId= clubId= accountId= : Monitor thread successfully connected to server with description ServerDescription{address=localhost:59924, type=REPLICA_SET_PRIMARY, cryptd=false, state=CONNECTED, ok=true, minWireVersion=0, maxWireVersion=21, maxDocumentSize=16777216, logicalSessionTimeoutMinutes=30, roundTripTimeNanos=11298667, minRoundTripTimeNanos=0, setName='docker-rs', canonicalAddress=08e56f331ca2:27017, hosts=[08e56f331ca2:27017], passives=[], arbiters=[], primary='08e56f331ca2:27017', tagSet=TagSet{[]}, electionId=7fffffff0000000000000001, setVersion=1, topologyVersion=TopologyVersion{processId=6aededc0079fcf19b5c2b74f, counter=6}, lastWriteDate=Fri Nov 06 16:09:53 CET 2026, lastUpdateTimeNanos=[redacted]}
2026-11-06T16:09:56.675+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T16:09:56.693+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T16:09:56.713+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T16:09:57.366+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T16:09:57.380+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T16:09:57.393+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T16:09:57.472+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T16:10:01.018+01:00 INFO  [main] o.s.b.t.m.w.SpringBootMockServletContext traceId= clubId= accountId= : Initializing Spring TestDispatcherServlet ''
2026-11-06T16:10:01.019+01:00 INFO  [main] o.s.t.web.servlet.TestDispatcherServlet traceId= clubId= accountId= : Initializing Servlet ''
2026-11-06T16:10:01.027+01:00 INFO  [main] o.s.t.web.servlet.TestDispatcherServlet traceId= clubId= accountId= : Completed initialization in 8 ms
2026-11-06T16:10:01.114+01:00 INFO  [main] c.a.c.c.census.application.DemoSeedsIT traceId= clubId= accountId= : Started DemoSeedsIT in 7.75 seconds (process running for 11.0)
Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK. Please add Mockito as an agent to your build as described in Mockito's documentation: https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html#0.3
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
WARNING: A Java agent has been loaded dynamically (/Users/jordib/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.11/byte-buddy-agent-1.18.11.jar)
WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading to hide this warning
WARNING: If a serviceability tool is not in use, please run with -Djdk.instrument.traceUsage for more information
WARNING: Dynamic loading of agents will be disallowed by default in a future release
{activeMembers=184, pendingMembers=3, inactiveMembers=4, leftMembers=3, members=194, dogs=246, familyGroups=12, activeDogs=242, pendingDogs=3, inactiveDogs=1, instructors=3, administrators=2, receivedDocuments=7, pendingDocuments=239}
452 changes (demo seed)
{platformAccounts=1, templateClasses=39, templates=3, weeks=3, generatedClasses=114, validatedWeeks=2, looseClasses=1, ringBlocks=1, scenarioRingBlocks=1, attendanceHistoryClasses=10, riskCancellations=1, classBookings=23, classWaitlist=3, activities=4, publishedActivities=3, activityRegistrations=44, activityWaitlist=2, activityFiles=2, scenarioActivityRegistrations=1, scenarioClassBookings=18, scenarioCancellations=3, scenarioWaitlist=5, scenarioOffers=0, scenarioRiskExempt=1, scenarioBookingBlocks=1, attendanceRenamed=6, attendanceLevelChanges=1, followupNotes=1, attendanceBookings=34, attendanceWaitlist=1, attendanceMarks=26, attendanceCancellations=2, attendanceNoShowBatches=1, followupTasks=2, followupTaskAttachments=1, followupObservations=1, scenarioTrainingOverrides=1, scenarioTrainingBookings=3, trainingHistoryOverrides=1, trainingHistoryBookings=10, messagingContacts=2, messagingProfiles=3, messagingBounces=1, pushSubscriptions=1, customTemplates=1, obstacleInventories=1, ringGeometries=2, courses=3, placements=2, ringSetups=3, buildSessions=1, billingProviders=1, pendingInactivity=1, activeInactivity=1, plannedLeave=1, expiredPacks=1}
402 changes (demo planning, week start 2026-09-14)
{activeMembers=184, pendingMembers=3, inactiveMembers=4, leftMembers=3, members=194, dogs=246, familyGroups=12, activeDogs=242, pendingDogs=3, inactiveDogs=1, instructors=3, administrators=2, receivedDocuments=7, pendingDocuments=239}
0 changes (demo seed)
{platformAccounts=1, templateClasses=39, templates=3, weeks=3, generatedClasses=114, validatedWeeks=2, looseClasses=1, ringBlocks=1, scenarioRingBlocks=1, attendanceHistoryClasses=10, riskCancellations=1, classBookings=23, classWaitlist=3, activities=4, publishedActivities=3, activityRegistrations=44, activityWaitlist=2, activityFiles=2, scenarioActivityRegistrations=1, scenarioClassBookings=18, scenarioCancellations=3, scenarioWaitlist=5, scenarioOffers=0, scenarioRiskExempt=1, scenarioBookingBlocks=1, attendanceRenamed=6, attendanceLevelChanges=1, followupNotes=1, attendanceBookings=34, attendanceWaitlist=1, attendanceMarks=26, attendanceCancellations=2, attendanceNoShowBatches=1, followupTasks=2, followupTaskAttachments=1, followupObservations=1, scenarioTrainingOverrides=1, scenarioTrainingBookings=3, trainingHistoryOverrides=1, trainingHistoryBookings=10, messagingContacts=2, messagingProfiles=3, messagingBounces=1, pushSubscriptions=1, customTemplates=1, obstacleInventories=1, ringGeometries=2, courses=3, placements=2, ringSetups=3, buildSessions=1, billingProviders=1, pendingInactivity=1, activeInactivity=1, plannedLeave=1, expiredPacks=1}
0 changes (demo planning, week start 2026-09-14)
E5-T07 outbox backlog discarded before DemoSeedsIT: 1014 PENDING records (more than one dispatch() batch)
{activeMembers=184, pendingMembers=3, inactiveMembers=4, leftMembers=3, members=194, dogs=246, familyGroups=12, activeDogs=242, pendingDogs=3, inactiveDogs=1, instructors=3, administrators=2, receivedDocuments=7, pendingDocuments=239}
452 changes (demo seed)
{platformAccounts=1, templateClasses=39, templates=3, weeks=3, generatedClasses=114, validatedWeeks=2, looseClasses=1, ringBlocks=1, scenarioRingBlocks=1, attendanceHistoryClasses=10, riskCancellations=1, classBookings=23, classWaitlist=3, activities=4, publishedActivities=3, activityRegistrations=44, activityWaitlist=2, activityFiles=2, scenarioActivityRegistrations=1, scenarioClassBookings=18, scenarioCancellations=3, scenarioWaitlist=5, scenarioOffers=0, scenarioRiskExempt=1, scenarioBookingBlocks=1, attendanceRenamed=6, attendanceLevelChanges=1, followupNotes=1, attendanceBookings=34, attendanceWaitlist=1, attendanceMarks=26, attendanceCancellations=2, attendanceNoShowBatches=1, followupTasks=2, followupTaskAttachments=1, followupObservations=1, scenarioTrainingOverrides=1, scenarioTrainingBookings=3, trainingHistoryOverrides=1, trainingHistoryBookings=10, messagingContacts=2, messagingProfiles=3, messagingBounces=1, pushSubscriptions=1, customTemplates=1, obstacleInventories=1, ringGeometries=2, courses=3, placements=2, ringSetups=3, buildSessions=1, billingProviders=1, pendingInactivity=1, activeInactivity=1, plannedLeave=1, expiredPacks=1}
402 changes (demo planning, week start 2026-09-14)
E5-T07 outbox backlog discarded before DemoSeedsIT: 1003 PENDING records (more than one dispatch() batch)
{activeMembers=184, pendingMembers=3, inactiveMembers=4, leftMembers=3, members=194, dogs=246, familyGroups=12, activeDogs=242, pendingDogs=3, inactiveDogs=1, instructors=3, administrators=2, receivedDocuments=7, pendingDocuments=239}
452 changes (demo seed)
{platformAccounts=1, templateClasses=39, templates=3, weeks=3, generatedClasses=114, validatedWeeks=2, looseClasses=1, ringBlocks=1, scenarioRingBlocks=1, attendanceHistoryClasses=10, riskCancellations=1, classBookings=23, classWaitlist=3, activities=4, publishedActivities=3, activityRegistrations=44, activityWaitlist=2, activityFiles=2, scenarioActivityRegistrations=1, scenarioClassBookings=18, scenarioCancellations=3, scenarioWaitlist=5, scenarioOffers=0, scenarioRiskExempt=1, scenarioBookingBlocks=1, attendanceRenamed=6, attendanceLevelChanges=1, followupNotes=1, attendanceBookings=34, attendanceWaitlist=1, attendanceMarks=26, attendanceCancellations=2, attendanceNoShowBatches=1, followupTasks=2, followupTaskAttachments=1, followupObservations=1, scenarioTrainingOverrides=1, scenarioTrainingBookings=3, trainingHistoryOverrides=1, trainingHistoryBookings=10, messagingContacts=2, messagingProfiles=3, messagingBounces=1, pushSubscriptions=1, customTemplates=1, obstacleInventories=1, ringGeometries=2, courses=3, placements=2, ringSetups=3, buildSessions=1, billingProviders=1, pendingInactivity=1, activeInactivity=1, plannedLeave=1, expiredPacks=1}
402 changes (demo planning, week start 2026-09-14)
E5-T07 outbox backlog discarded before DemoSeedsIT: 1003 PENDING records (more than one dispatch() batch)
```

### `./mvnw -q test-compile failsafe:integration-test failsafe:verify -Dit.test=ClubDefinitionsIT`

Exit: 0. Full output: `09-definitions-final.log`.

```text
16:15:02.661 [main] INFO org.testcontainers.utility.RyukResourceReaper -- Ryuk started - will monitor and terminate Testcontainers containers on JVM exit
16:15:02.661 [main] INFO org.testcontainers.DockerClientFactory -- Checking the system...
16:15:02.662 [main] INFO org.testcontainers.DockerClientFactory -- ✔︎ Docker server version should be at least 1.6.0
16:15:02.662 [main] INFO tc.mongo:7 -- Creating container for image: mongo:7
16:15:02.692 [main] INFO tc.mongo:7 -- Container mongo:7 is starting: 54e848c653d7cd6dbe04750670f5a45598cfd5a2d7cc3d5d2b0bb2e839207eca
16:15:03.155 [main] INFO tc.mongo:7 -- Container mongo:7 started in PT0.493475S
Integration JVM availableProcessors=18

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v4.1.1)

2026-11-06T16:15:03.826+01:00 INFO  [main] c.a.c.p.a.definition.ClubDefinitionsIT traceId= clubId= accountId= : Starting ClubDefinitionsIT using Java [redacted] with PID 832 (started by jordib in /private/tmp/canic-nov6-contract-check)
2026-11-06T16:15:03.828+01:00 INFO  [main] c.a.c.p.a.definition.ClubDefinitionsIT traceId= clubId= accountId= : The following 1 profile is active: "test"
2026-11-06T16:15:05.185+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Bootstrapping Spring Data MongoDB repositories in DEFAULT mode.
2026-11-06T16:15:05.234+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Finished Spring Data repository scanning in 45 ms. Found 0 MongoDB repository interfaces.
2026-11-06T16:15:05.677+01:00 INFO  [main] org.mongodb.driver.client traceId= clubId= accountId= : MongoClient with metadata {"driver": {"name": "mongo-java-driver|spring-boot|sync", "version": "5.8.1"}, "os": {"type": "Darwin", "name": "Mac OS X", "architecture": "aarch64", "version": "26.4.1"}, "platform": "Java/Homebrew/[redacted]"} created with settings MongoClientSettings{readPreference=primary, writeConcern=WriteConcern{w=null, wTimeout=null ms, journal=null}, retryWrites=true, retryReads=true, readConcern=ReadConcern{level=null}, credential=null, transportSettings=null, commandListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener@7403b7df], codecRegistry=ProvidersCodecRegistry{codecProviders=[ValueCodecProvider{}, BsonValueCodecProvider{}, DBRefCodecProvider{}, DBObjectCodecProvider{}, DocumentCodecProvider{}, CollectionCodecProvider{}, IterableCodecProvider{}, MapCodecProvider{}, GeoJsonCodecProvider{}, GridFSFileCodecProvider{}, Jsr310CodecProvider{}, JsonObjectCodecProvider{}, BsonCodecProvider{}, com.mongodb.client.model.mql.ExpressionCodecProvider@20984847, com.mongodb.Jep395RecordCodecProvider@595a3150, com.mongodb.KotlinCodecProvider@56d1c219, EnumCodecProvider{}]}, loggerSettings=LoggerSettings{maxDocumentLength=1000}, clusterSettings={hosts=[localhost:61330], srvServiceName=mongodb, mode=SINGLE, requiredClusterType=UNKNOWN, requiredReplicaSetName='null', serverSelector='null', clusterListeners='[]', serverSelectionTimeout='2000 ms', localThreshold='15 ms'}, socketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=0, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], heartbeatSocketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=2000, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], connectionPoolSettings=ConnectionPoolSettings{maxSize=100, minSize=0, maxWaitTimeMS=2000, maxConnectionLifeTimeMS=0, maxConnectionIdleTimeMS=0, maintenanceInitialDelayMS=0, maintenanceFrequencyMS=60000, connectionPoolListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener@3f853a53], maxConnecting=2}, serverSettings=ServerSettings{heartbeatFrequencyMS=10000, minHeartbeatFrequencyMS=500, serverMonitoringMode=AUTO, serverListeners='[]', serverMonitorListeners='[]'}, sslSettings=SslSettings{enabled=false, invalidHostNameAllowed=false, context=null}, applicationName='null', compressorList=[], uuidRepresentation=JAVA_LEGACY, serverApi=null, autoEncryptionSettings=null, dnsClient=null, inetAddressResolver=null, contextProvider=null, timeoutMS=null}
2026-11-06T16:15:05.684+01:00 INFO  [cluster-6aedeef9e9388e389fc8a1bb-localhost:61330] org.mongodb.driver.cluster traceId= clubId= accountId= : Monitor thread successfully connected to server with description ServerDescription{address=localhost:61330, type=REPLICA_SET_PRIMARY, cryptd=false, state=CONNECTED, ok=true, minWireVersion=0, maxWireVersion=21, maxDocumentSize=16777216, logicalSessionTimeoutMinutes=30, roundTripTimeNanos=10255750, minRoundTripTimeNanos=0, setName='docker-rs', canonicalAddress=54e848c653d7:27017, hosts=[54e848c653d7:27017], passives=[], arbiters=[], primary='54e848c653d7:27017', tagSet=TagSet{[]}, electionId=7fffffff0000000000000001, setVersion=1, topologyVersion=TopologyVersion{processId=6aedeef6ad305b6d647b93ee, counter=6}, lastWriteDate=Fri Nov 06 16:15:03 CET 2026, lastUpdateTimeNanos=[redacted]}
2026-11-06T16:15:06.824+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T16:15:06.841+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T16:15:06.862+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK. Please add Mockito as an agent to your build as described in Mockito's documentation: https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html#0.3
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
WARNING: A Java agent has been loaded dynamically (/Users/jordib/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.11/byte-buddy-agent-1.18.11.jar)
WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading to hide this warning
WARNING: If a serviceability tool is not in use, please run with -Djdk.instrument.traceUsage for more information
WARNING: Dynamic loading of agents will be disallowed by default in a future release
2026-11-06T16:15:09.204+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T16:15:09.225+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T16:15:09.241+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T16:15:09.356+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T16:15:13.037+01:00 INFO  [main] o.s.b.t.m.w.SpringBootMockServletContext traceId= clubId= accountId= : Initializing Spring TestDispatcherServlet ''
2026-11-06T16:15:13.037+01:00 INFO  [main] o.s.t.web.servlet.TestDispatcherServlet traceId= clubId= accountId= : Initializing Servlet ''
2026-11-06T16:15:13.044+01:00 INFO  [main] o.s.t.web.servlet.TestDispatcherServlet traceId= clubId= accountId= : Completed initialization in 7 ms
2026-11-06T16:15:13.120+01:00 INFO  [main] c.a.c.p.a.definition.ClubDefinitionsIT traceId= clubId= accountId= : Started ClubDefinitionsIT in 9.501 seconds (process running for 12.389)
```

### `./mvnw -q jacoco:report@coverage jacoco:check@coverage`

Exit: 0. Full output: `06-coverage.log`.

```text
(No output with -q.)
```

### `bin/openapi-snapshot`

Exit: 0. Full output: `03-openapi.log`.

```text
2026-11-06T12:38:44.149+01:00 INFO  [main] c.a.c.configuration.OpenApiSnapshotTest traceId= clubId= accountId= : The following 1 profile is active: "test"
2026-11-06T12:38:46.424+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Bootstrapping Spring Data MongoDB repositories in DEFAULT mode.
2026-11-06T12:38:46.522+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Finished Spring Data repository scanning in 91 ms. Found 0 MongoDB repository interfaces.
2026-11-06T12:38:47.429+01:00 INFO  [main] o.s.boot.tomcat.TomcatWebServer traceId= clubId= accountId= : Tomcat initialized with port 0 (http)
2026-11-06T12:38:47.446+01:00 INFO  [main] o.apache.catalina.core.StandardService traceId= clubId= accountId= : Starting service [Tomcat]
2026-11-06T12:38:47.447+01:00 INFO  [main] org.apache.catalina.core.StandardEngine traceId= clubId= accountId= : Starting Servlet engine: [Apache Tomcat/11.0.27]
2026-11-06T12:38:47.502+01:00 INFO  [main] o.s.b.w.c.s.WebApplicationContextInitializer traceId= clubId= accountId= : Root WebApplicationContext: initialization completed in 3338 ms
2026-11-06T12:38:47.762+01:00 INFO  [main] org.mongodb.driver.client traceId= clubId= accountId= : MongoClient with metadata {"driver": {"name": "mongo-java-driver|spring-boot|sync", "version": "5.8.1"}, "os": {"type": "Darwin", "name": "Mac OS X", "architecture": "aarch64", "version": "26.4.1"}, "platform": "Java/Homebrew/[redacted]"} created with settings MongoClientSettings{readPreference=primary, writeConcern=WriteConcern{w=null, wTimeout=null ms, journal=null}, retryWrites=true, retryReads=true, readConcern=ReadConcern{level=null}, credential=null, transportSettings=null, commandListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener@4bbe1bd1], codecRegistry=ProvidersCodecRegistry{codecProviders=[ValueCodecProvider{}, BsonValueCodecProvider{}, DBRefCodecProvider{}, DBObjectCodecProvider{}, DocumentCodecProvider{}, CollectionCodecProvider{}, IterableCodecProvider{}, MapCodecProvider{}, GeoJsonCodecProvider{}, GridFSFileCodecProvider{}, Jsr310CodecProvider{}, JsonObjectCodecProvider{}, BsonCodecProvider{}, com.mongodb.client.model.mql.ExpressionCodecProvider@3aee36c6, com.mongodb.Jep395RecordCodecProvider@713616c0, com.mongodb.KotlinCodecProvider@5feb63af, EnumCodecProvider{}]}, loggerSettings=LoggerSettings{maxDocumentLength=1000}, clusterSettings={hosts=[localhost:58338], srvServiceName=mongodb, mode=SINGLE, requiredClusterType=UNKNOWN, requiredReplicaSetName='null', serverSelector='null', clusterListeners='[]', serverSelectionTimeout='2000 ms', localThreshold='15 ms'}, socketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=0, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], heartbeatSocketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=2000, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], connectionPoolSettings=ConnectionPoolSettings{maxSize=100, minSize=0, maxWaitTimeMS=2000, maxConnectionLifeTimeMS=0, maxConnectionIdleTimeMS=0, maintenanceInitialDelayMS=0, maintenanceFrequencyMS=60000, connectionPoolListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener@64309b10], maxConnecting=2}, serverSettings=ServerSettings{heartbeatFrequencyMS=10000, minHeartbeatFrequencyMS=500, serverMonitoringMode=AUTO, serverListeners='[]', serverMonitorListeners='[]'}, sslSettings=SslSettings{enabled=false, invalidHostNameAllowed=false, context=null}, applicationName='null', compressorList=[], uuidRepresentation=JAVA_LEGACY, serverApi=null, autoEncryptionSettings=null, dnsClient=null, inetAddressResolver=null, contextProvider=null, timeoutMS=null}
2026-11-06T12:38:47.780+01:00 INFO  [cluster-6aedbc479e450b094358f095-localhost:58338] org.mongodb.driver.cluster traceId= clubId= accountId= : Monitor thread successfully connected to server with description ServerDescription{address=localhost:58338, type=REPLICA_SET_PRIMARY, cryptd=false, state=CONNECTED, ok=true, minWireVersion=0, maxWireVersion=21, maxDocumentSize=16777216, logicalSessionTimeoutMinutes=30, roundTripTimeNanos=27230375, minRoundTripTimeNanos=0, setName='docker-rs', canonicalAddress=f5ee3d3b717e:27017, hosts=[f5ee3d3b717e:27017], passives=[], arbiters=[], primary='f5ee3d3b717e:27017', tagSet=TagSet{[]}, electionId=7fffffff0000000000000001, setVersion=1, topologyVersion=TopologyVersion{processId=6aedbc410d76d9be1f2e646e, counter=6}, lastWriteDate=Fri Nov 06 12:38:43 CET 2026, lastUpdateTimeNanos=[redacted]}
2026-11-06T12:38:49.219+01:00 INFO  [main] o.s.boot.web.servlet.RegistrationBean traceId= clubId= accountId= : Filter rateLimitFilter was not registered (disabled)
2026-11-06T12:38:50.816+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T12:38:50.867+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T12:38:50.922+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T12:38:52.448+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T12:38:52.477+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T12:38:52.504+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T12:38:52.649+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-11-06T12:38:58.641+01:00 INFO  [main] o.a.c.c.C.[Tomcat].[localhost].[/] traceId= clubId= accountId= : Initializing Spring TestDispatcherServlet ''
2026-11-06T12:38:58.641+01:00 INFO  [main] o.s.t.web.servlet.TestDispatcherServlet traceId= clubId= accountId= : Initializing Servlet ''
2026-11-06T12:38:58.655+01:00 INFO  [main] o.s.t.web.servlet.TestDispatcherServlet traceId= clubId= accountId= : Completed initialization in 14 ms
2026-11-06T12:38:58.781+01:00 INFO  [main] o.s.boot.tomcat.TomcatWebServer traceId= clubId= accountId= : Tomcat started on port 58417 (http) with context path '/'
2026-11-06T12:38:58.851+01:00 INFO  [main] o.s.boot.tomcat.TomcatWebServer traceId= clubId= accountId= : Tomcat initialized with port 0 (http)
2026-11-06T12:38:58.852+01:00 INFO  [main] o.apache.catalina.core.StandardService traceId= clubId= accountId= : Starting service [Tomcat]
2026-11-06T12:38:58.853+01:00 INFO  [main] org.apache.catalina.core.StandardEngine traceId= clubId= accountId= : Starting Servlet engine: [Apache Tomcat/11.0.27]
2026-11-06T12:38:58.867+01:00 INFO  [main] o.s.b.w.c.s.WebApplicationContextInitializer traceId= clubId= accountId= : Root WebApplicationContext: initialization completed in 82 ms
2026-11-06T12:38:58.885+01:00 INFO  [main] o.s.b.a.e.web.EndpointLinksResolver traceId= clubId= accountId= : Exposing 2 endpoints beneath base path '/actuator'
2026-11-06T12:38:58.932+01:00 INFO  [main] o.s.boot.tomcat.TomcatWebServer traceId= clubId= accountId= : Tomcat started on port 58418 (http) with context path '/'
2026-11-06T12:38:58.944+01:00 INFO  [main] c.a.c.configuration.OpenApiSnapshotTest traceId= clubId= accountId= : Started OpenApiSnapshotTest in 15.215 seconds (process running for 21.463)
Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK. Please add Mockito as an agent to your build as described in Mockito's documentation: https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html#0.3
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
WARNING: A Java agent has been loaded dynamically (/Users/jordib/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.11/byte-buddy-agent-1.18.11.jar)
WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading to hide this warning
WARNING: If a serviceability tool is not in use, please run with -Djdk.instrument.traceUsage for more information
WARNING: Dynamic loading of agents will be disallowed by default in a future release
2026-11-06T12:39:05.695+01:00 INFO  [main] o.springdoc.api.AbstractOpenApiResource traceId=fd2acefa-fb7c-4833-873c-f180fbabb9d8 clubId=- accountId=user : Init duration for springdoc-openapi is: 5319 ms
2026-11-06T12:39:07.022+01:00 INFO  [tomcat-handler-0] o.a.c.c.C.[Tomcat].[localhost].[/] traceId= clubId= accountId= : Initializing Spring DispatcherServlet 'dispatcherServlet'
2026-11-06T12:39:07.022+01:00 INFO  [tomcat-handler-0] o.s.web.servlet.DispatcherServlet traceId= clubId= accountId= : Initializing Servlet 'dispatcherServlet'
2026-11-06T12:39:07.025+01:00 INFO  [tomcat-handler-0] o.s.web.servlet.DispatcherServlet traceId= clubId= accountId= : Completed initialization in 3 ms
2026-11-06T12:39:10.448+01:00 INFO  [main] o.springdoc.api.AbstractOpenApiResource traceId=febffc07-dcef-4042-ada2-bf475448de89 clubId=- accountId=- : Init duration for springdoc-openapi is: 3270 ms
Updated docs/openapi/openapi.json
```
