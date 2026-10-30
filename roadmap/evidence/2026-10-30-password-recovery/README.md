# Password recovery email — 30 October 2026

Local implementation; not committed, published or deployed. Jordi authorized enabling recovery emails. Provider key creation awaits action-time browser confirmation. The existing sender was observed as verified; the provider homepage displayed a phone-verification requirement.

## Behavior

`EMAIL_RECOVERY_ENABLED=true` with general delivery disabled instantiates a real provider behind a recovery-only wrapper. Only the identity RESET route selects it after the active account, tenant membership and client/host checks. Ordinary SYSTEM and engine deliveries stay terminal skips. Normal enabled-email and hosted allowlist behavior is preserved. Provider credentials and callback authentication are mandatory.

## Verification limits

The focused identity/delivery integration run passes 54 tests. The full clean gate ran 5,003 unit tests with one existing timing assertion failure in PasswordHasherTest while other browser/build workloads were running; integration was consequently not reached by that gate. Do not claim a clean full gate. Repeat via the shared `05-desenvolupament/roadmap-kit/mac/heavy.sh` lock after the active browser work finishes. The initial selected verification reached successful integration but its coverage report failed with malformed input; after clean rebuild the standalone report succeeded. No timing assertion or coverage threshold was relaxed.

## 01-focused.log

Command: `./mvnw -q -Dtest=EmailConfigurationTest,RecoveryOnlyEmailSenderTest,MagicLinkServiceSurvivorsTest test` · exit 0

Full log: [01-focused.log](01-focused.log). Final 40 lines:

```text
Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK. Please add Mockito as an agent to your build as described in Mockito's documentation: https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html#0.3
WARNING: A Java agent has been loaded dynamically (/Users/jordib/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.11/byte-buddy-agent-1.18.11.jar)
WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading to hide this warning
WARNING: If a serviceability tool is not in use, please run with -Djdk.instrument.traceUsage for more information
WARNING: Dynamic loading of agents will be disallowed by default in a future release
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
12:00:52.738 [main] WARN org.springframework.context.annotation.AnnotationConfigApplicationContext -- Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'emailSender' defined in com.agilityhub.core.configuration.EmailConfiguration: Failed to instantiate [com.agilityhub.core.clubs.messaging.application.EmailSender]: Factory method 'emailSender' threw exception with message: BREVO_API_KEY is required in staging/prod
12:00:53.082 [main] WARN org.springframework.context.annotation.AnnotationConfigApplicationContext -- Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'brevoWebhookAuthentication' defined in com.agilityhub.core.configuration.EmailConfiguration: Failed to instantiate [com.agilityhub.core.clubs.messaging.application.BrevoWebhookAuthentication]: Factory method 'brevoWebhookAuthentication' threw exception with message: BREVO_WEBHOOK_TOKEN must contain 32 to 256 random URL-safe characters in staging/prod
12:00:53.101 [main] WARN org.springframework.context.annotation.AnnotationConfigApplicationContext -- Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'emailSender' defined in com.agilityhub.core.configuration.EmailConfiguration: Failed to instantiate [com.agilityhub.core.clubs.messaging.application.EmailSender]: Factory method 'emailSender' threw exception with message: EMAIL_RECOVERY_ENABLED must be true or false
12:00:53.129 [main] WARN org.springframework.context.annotation.AnnotationConfigApplicationContext -- Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'emailSender' defined in com.agilityhub.core.configuration.EmailConfiguration: Failed to instantiate [com.agilityhub.core.clubs.messaging.application.EmailSender]: Factory method 'emailSender' threw exception with message: EMAIL_DELIVERY_ENABLED=false requires HOSTED_DEMO_ENABLED=true
12:00:53.147 [main] WARN org.springframework.context.annotation.AnnotationConfigApplicationContext -- Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'emailSender' defined in com.agilityhub.core.configuration.EmailConfiguration: Failed to instantiate [com.agilityhub.core.clubs.messaging.application.EmailSender]: Factory method 'emailSender' threw exception with message: EMAIL_DELIVERY_ENABLED must be true or false
12:00:53.162 [main] WARN org.springframework.context.annotation.AnnotationConfigApplicationContext -- Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'emailSender' defined in com.agilityhub.core.configuration.EmailConfiguration: Failed to instantiate [com.agilityhub.core.clubs.messaging.application.EmailSender]: Factory method 'emailSender' threw exception with message: HOSTED_DEMO_ENABLED requires only a deployed profile, without local/test
12:00:53.410 [main] INFO com.agilityhub.core.clubs.messaging.application.LogEmailSender -- Local email accepted notificationId=fixture
12:00:53.412 [main] INFO com.agilityhub.core.clubs.messaging.application.LogEmailSender -- Local email accepted notificationId=fixture
12:00:53.550 [main] WARN org.springframework.context.annotation.AnnotationConfigApplicationContext -- Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'brevoWebhookAuthentication' defined in com.agilityhub.core.configuration.EmailConfiguration: Failed to instantiate [com.agilityhub.core.clubs.messaging.application.BrevoWebhookAuthentication]: Factory method 'brevoWebhookAuthentication' threw exception with message: BREVO_WEBHOOK_TOKEN must contain 32 to 256 random URL-safe characters in staging/prod
12:00:53.560 [main] WARN org.springframework.context.annotation.AnnotationConfigApplicationContext -- Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'emailSender' defined in com.agilityhub.core.configuration.EmailConfiguration: Failed to instantiate [com.agilityhub.core.clubs.messaging.application.EmailSender]: Factory method 'emailSender' threw exception with message: BREVO_API_KEY is required in staging/prod
12:00:53.585 [main] WARN org.springframework.context.annotation.AnnotationConfigApplicationContext -- Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'emailSender' defined in com.agilityhub.core.configuration.EmailConfiguration: Failed to instantiate [com.agilityhub.core.clubs.messaging.application.EmailSender]: Factory method 'emailSender' threw exception with message: EMAIL_PROVIDER must be sendgrid or brevo
12:00:53.597 [main] WARN org.springframework.context.annotation.AnnotationConfigApplicationContext -- Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'emailSender' defined in com.agilityhub.core.configuration.EmailConfiguration: Failed to instantiate [com.agilityhub.core.clubs.messaging.application.EmailSender]: Factory method 'emailSender' threw exception with message: MAIL_FROM_PLATFORM is required in staging/prod
12:00:53.609 [main] WARN org.springframework.context.annotation.AnnotationConfigApplicationContext -- Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'sendGridSignatures' defined in com.agilityhub.core.configuration.EmailConfiguration: Failed to instantiate [com.agilityhub.core.clubs.messaging.application.SendGridSignatureVerifier]: Factory method 'sendGridSignatures' threw exception with message: SENDGRID_WEBHOOK_PUBLIC_KEY is required in staging/prod
12:00:54.033 [main] INFO com.agilityhub.core.clubs.messaging.application.LogEmailSender -- Local email accepted notificationId=fixture
12:00:54.068 [main] WARN org.springframework.context.annotation.AnnotationConfigApplicationContext -- Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'emailSender' defined in com.agilityhub.core.configuration.EmailConfiguration: Failed to instantiate [com.agilityhub.core.clubs.messaging.application.EmailSender]: Factory method 'emailSender' threw exception with message: SENDGRID_API_KEY is required in staging/prod
12:00:54.077 [main] WARN org.springframework.context.annotation.AnnotationConfigApplicationContext -- Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'emailSender' defined in com.agilityhub.core.configuration.EmailConfiguration: Failed to instantiate [com.agilityhub.core.clubs.messaging.application.EmailSender]: Factory method 'emailSender' threw exception with message: SENDGRID_API_KEY is required in staging/prod
```

## 02-preflight.log

Command: `python3 -m unittest discover -s deploy -p 'test_release_preflight.py'` · exit 0

Full log: [02-preflight.log](02-preflight.log). Final 40 lines:

```text
...........................
----------------------------------------------------------------------
Ran 27 tests in 0.067s

OK
```

## 03-delivery-integration.log

Command: `./mvnw -q -Dtest=EmailConfigurationTest,RecoveryOnlyEmailSenderTest,MagicLinkServiceSurvivorsTest -Dit.test=SystemNotificationServiceIT verify` · exit 1

Full log: [03-delivery-integration.log](03-delivery-integration.log). Final 40 lines:

```text
12:02:06.460 [main] INFO tc.mongo:7 -- Container mongo:7 is starting: aa7fc840857a083cc87c658c5ad9fa7a07aaf63610b623aa61395dc3cfd9562a
12:02:07.270 [main] INFO tc.mongo:7 -- Container mongo:7 started in PT0.863677S
Integration JVM availableProcessors=18

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v4.1.1)

2026-10-30T12:02:08.483+01:00 INFO  [main] c.a.c.c.m.a.SystemNotificationServiceIT traceId= clubId= accountId= : Starting SystemNotificationServiceIT using Java [redacted] with PID 25160 (started by jordib in /Users/jordib/dev/agilityhub/agilityhub-core-api)
2026-10-30T12:02:08.488+01:00 INFO  [main] c.a.c.c.m.a.SystemNotificationServiceIT traceId= clubId= accountId= : The following 1 profile is active: "test"
2026-10-30T12:02:10.205+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Bootstrapping Spring Data MongoDB repositories in DEFAULT mode.
2026-10-30T12:02:10.276+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Finished Spring Data repository scanning in 66 ms. Found 0 MongoDB repository interfaces.
2026-10-30T12:02:10.856+01:00 INFO  [main] org.mongodb.driver.client traceId= clubId= accountId= : MongoClient with metadata {"driver": {"name": "mongo-java-driver|spring-boot|sync", "version": "5.8.1"}, "os": {"type": "Darwin", "name": "Mac OS X", "architecture": "aarch64", "version": "26.4.1"}, "platform": "Java/Homebrew/[redacted]"} created with settings MongoClientSettings{readPreference=primary, writeConcern=WriteConcern{w=null, wTimeout=null ms, journal=null}, retryWrites=true, retryReads=true, readConcern=ReadConcern{level=null}, credential=null, transportSettings=null, commandListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener@77feef20], codecRegistry=ProvidersCodecRegistry{codecProviders=[ValueCodecProvider{}, BsonValueCodecProvider{}, DBRefCodecProvider{}, DBObjectCodecProvider{}, DocumentCodecProvider{}, CollectionCodecProvider{}, IterableCodecProvider{}, MapCodecProvider{}, GeoJsonCodecProvider{}, GridFSFileCodecProvider{}, Jsr310CodecProvider{}, JsonObjectCodecProvider{}, BsonCodecProvider{}, com.mongodb.client.model.mql.ExpressionCodecProvider@481dfca7, com.mongodb.Jep395RecordCodecProvider@f09a29b, com.mongodb.KotlinCodecProvider@1afb8449, EnumCodecProvider{}]}, loggerSettings=LoggerSettings{maxDocumentLength=1000}, clusterSettings={hosts=[localhost:65007], srvServiceName=mongodb, mode=SINGLE, requiredClusterType=UNKNOWN, requiredReplicaSetName='null', serverSelector='null', clusterListeners='[]', serverSelectionTimeout='2000 ms', localThreshold='15 ms'}, socketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=0, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], heartbeatSocketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=2000, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], connectionPoolSettings=ConnectionPoolSettings{maxSize=100, minSize=0, maxWaitTimeMS=2000, maxConnectionLifeTimeMS=0, maxConnectionIdleTimeMS=0, maintenanceInitialDelayMS=0, maintenanceFrequencyMS=60000, connectionPoolListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener@1a7cefd9], maxConnecting=2}, serverSettings=ServerSettings{heartbeatFrequencyMS=10000, minHeartbeatFrequencyMS=500, serverMonitoringMode=AUTO, serverListeners='[]', serverMonitorListeners='[]'}, sslSettings=SslSettings{enabled=false, invalidHostNameAllowed=false, context=null}, applicationName='null', compressorList=[], uuidRepresentation=JAVA_LEGACY, serverApi=null, autoEncryptionSettings=null, dnsClient=null, inetAddressResolver=null, contextProvider=null, timeoutMS=null}
2026-10-30T12:02:10.865+01:00 INFO  [cluster-6ae47932d815f88923e0ddcb-localhost:65007] org.mongodb.driver.cluster traceId= clubId= accountId= : Monitor thread successfully connected to server with description ServerDescription{address=localhost:65007, type=REPLICA_SET_PRIMARY, cryptd=false, state=CONNECTED, ok=true, minWireVersion=0, maxWireVersion=21, maxDocumentSize=16777216, logicalSessionTimeoutMinutes=30, roundTripTimeNanos=12825708, minRoundTripTimeNanos=0, setName='docker-rs', canonicalAddress=aa7fc840857a:27017, hosts=[aa7fc840857a:27017], passives=[], arbiters=[], primary='aa7fc840857a:27017', tagSet=TagSet{[]}, electionId=7fffffff0000000000000001, setVersion=1, topologyVersion=TopologyVersion{processId=6ae4792ed16324dc5f8eed87, counter=6}, lastWriteDate=Fri Oct 30 12:02:07 CET 2026, lastUpdateTimeNanos=[redacted]}
Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK. Please add Mockito as an agent to your build as described in Mockito's documentation: https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html#0.3
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
WARNING: A Java agent has been loaded dynamically (/Users/jordib/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.11/byte-buddy-agent-1.18.11.jar)
WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading to hide this warning
WARNING: If a serviceability tool is not in use, please run with -Djdk.instrument.traceUsage for more information
WARNING: Dynamic loading of agents will be disallowed by default in a future release
2026-10-30T12:02:13.078+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-10-30T12:02:13.116+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-10-30T12:02:13.163+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-10-30T12:02:15.097+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-10-30T12:02:15.118+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-10-30T12:02:15.135+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-10-30T12:02:15.236+01:00 WARN  [main] o.s.aop.framework.CglibAopProxy traceId= clubId= accountId= : Public final method [public final boolean com.agilityhub.core.courses.persistence.CourseTenantRepository.deleteById(java.lang.String)] cannot get proxied via CGLIB, consider removing the final marker or using interface-based JDK proxies.
2026-10-30T12:02:20.554+01:00 INFO  [main] c.a.c.c.m.a.SystemNotificationServiceIT traceId= clubId= accountId= : Started SystemNotificationServiceIT in 12.472 seconds (process running for 17.389)
[ERROR] Failed to execute goal org.jacoco:jacoco-maven-plugin:0.8.14:report (coverage) on project agilityhub-core-api: An error has occurred in JaCoCo report generation.: Error while creating report: malformed input around byte 37 -> [Help 1]
[ERROR] 
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR] 
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoExecutionException
```

## 04-clean-verify.log

Command: `./mvnw -q clean verify` · exit 1

Full log: [04-clean-verify.log](04-clean-verify.log). Final 40 lines:

```text
familyGroups: created=0 updated=0 skipped=0 errors=0 proposed=0
accounts: created=0 updated=0 skipped=0 errors=0
invoices: created=0 updated=0 skipped=0 errors=0
packBalances: created=0 updated=0 skipped=0 errors=0
leaves: created=0 updated=0 skipped=0 errors=0
Incidents: {}
Playoff DRY_RUN
members: created=0 updated=0 skipped=0 errors=0
dogs: created=0 updated=0 skipped=0 errors=0
familyGroups: created=0 updated=0 skipped=0 errors=0 proposed=0
accounts: created=0 updated=0 skipped=0 errors=0
invoices: created=0 updated=0 skipped=0 errors=0
packBalances: created=0 updated=0 skipped=0 errors=0
leaves: created=0 updated=0 skipped=0 errors=0
Incidents: {}
WELCOME {ALREADY_SENT=2}
WELCOME {REVIEW_REQUIRED=1}
WELCOME {REVIEW_REQUIRED=1}
Command failed (IllegalStateException)
DRY RUN {ELIGIBLE=2}
WELCOME {SENT=2}
[ERROR] Failures: 
[ERROR]   PasswordHasherTest.T_01_02_INC_37_everyVerificationTakesTheSameTimeWithinTwentyPercent:54->lambda$T_01_02_INC_37_everyVerificationTakesTheSameTimeWithinTwentyPercent$8:55 [bcrypt 12, wrong, fastest of 5 runs, vs the floor (319 ms)] 
Expecting actual:
  4.32752417E8
to be between:
  [3.19018593E8, 3.8282231159999996E8]

[ERROR] Tests run: 5003, Failures: 1, Errors: 0, Skipped: 0
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test (default-test) on project agilityhub-core-api: There are test failures.
[ERROR] 
[ERROR] See /Users/jordib/dev/agilityhub/agilityhub-core-api/target/surefire-reports for the individual test results.
[ERROR] See dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.
[ERROR] -> [Help 1]
[ERROR] 
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR] 
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
```

## 05-coverage-diagnostic.log

Command: `./mvnw -q -e jacoco:report` · exit 0

Full log: [05-coverage-diagnostic.log](05-coverage-diagnostic.log). Final 40 lines:

```text

```

## 06-identity-integration.log

Command: `./mvnw -q -Dit.test=IdentityCoreIT,DisabledEmailIdentityIT,SystemNotificationServiceIT failsafe:integration-test failsafe:verify` · exit 0

Full log: [06-identity-integration.log](06-identity-integration.log). Final 40 lines:

```text
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v4.1.1)

2026-10-30T12:09:26.378+01:00 INFO  [main] c.a.core.identity.api.IdentityCoreIT traceId= clubId= accountId= : Starting IdentityCoreIT using Java [redacted] with PID 27935 (started by jordib in /Users/jordib/dev/agilityhub/agilityhub-core-api)
2026-10-30T12:09:26.378+01:00 INFO  [main] c.a.core.identity.api.IdentityCoreIT traceId= clubId= accountId= : The following 1 profile is active: "test"
2026-10-30T12:09:27.096+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Bootstrapping Spring Data MongoDB repositories in DEFAULT mode.
2026-10-30T12:09:27.166+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Finished Spring Data repository scanning in 69 ms. Found 0 MongoDB repository interfaces.
2026-10-30T12:09:27.504+01:00 INFO  [main] org.mongodb.driver.client traceId= clubId= accountId= : MongoClient with metadata {"driver": {"name": "mongo-java-driver|spring-boot|sync", "version": "5.8.1"}, "os": {"type": "Darwin", "name": "Mac OS X", "architecture": "aarch64", "version": "26.4.1"}, "platform": "Java/Homebrew/[redacted]"} created with settings MongoClientSettings{readPreference=primary, writeConcern=WriteConcern{w=null, wTimeout=null ms, journal=null}, retryWrites=true, retryReads=true, readConcern=ReadConcern{level=null}, credential=null, transportSettings=null, commandListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener@13a7d728], codecRegistry=ProvidersCodecRegistry{codecProviders=[ValueCodecProvider{}, BsonValueCodecProvider{}, DBRefCodecProvider{}, DBObjectCodecProvider{}, DocumentCodecProvider{}, CollectionCodecProvider{}, IterableCodecProvider{}, MapCodecProvider{}, GeoJsonCodecProvider{}, GridFSFileCodecProvider{}, Jsr310CodecProvider{}, JsonObjectCodecProvider{}, BsonCodecProvider{}, com.mongodb.client.model.mql.ExpressionCodecProvider@4c364a9d, com.mongodb.Jep395RecordCodecProvider@597084b7, com.mongodb.KotlinCodecProvider@59ebe484, EnumCodecProvider{}]}, loggerSettings=LoggerSettings{maxDocumentLength=1000}, clusterSettings={hosts=[localhost:50848], srvServiceName=mongodb, mode=SINGLE, requiredClusterType=UNKNOWN, requiredReplicaSetName='null', serverSelector='null', clusterListeners='[]', serverSelectionTimeout='2000 ms', localThreshold='15 ms'}, socketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=0, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], heartbeatSocketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=2000, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], connectionPoolSettings=ConnectionPoolSettings{maxSize=100, minSize=0, maxWaitTimeMS=2000, maxConnectionLifeTimeMS=0, maxConnectionIdleTimeMS=0, maintenanceInitialDelayMS=0, maintenanceFrequencyMS=60000, connectionPoolListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener@4578b8cb], maxConnecting=2}, serverSettings=ServerSettings{heartbeatFrequencyMS=10000, minHeartbeatFrequencyMS=500, serverMonitoringMode=AUTO, serverListeners='[]', serverMonitorListeners='[]'}, sslSettings=SslSettings{enabled=false, invalidHostNameAllowed=false, context=null}, applicationName='null', compressorList=[], uuidRepresentation=JAVA_LEGACY, serverApi=null, autoEncryptionSettings=null, dnsClient=null, inetAddressResolver=null, contextProvider=null, timeoutMS=null}
2026-10-30T12:09:27.509+01:00 INFO  [cluster-6ae47ae74d6a9493ba7265a7-localhost:50848] org.mongodb.driver.cluster traceId= clubId= accountId= : Monitor thread successfully connected to server with description ServerDescription{address=localhost:50848, type=REPLICA_SET_PRIMARY, cryptd=false, state=CONNECTED, ok=true, minWireVersion=0, maxWireVersion=21, maxDocumentSize=16777216, logicalSessionTimeoutMinutes=30, roundTripTimeNanos=2859750, minRoundTripTimeNanos=0, setName='docker-rs', canonicalAddress=abe038cb26f9:27017, hosts=[abe038cb26f9:27017], passives=[], arbiters=[], primary='abe038cb26f9:27017', tagSet=TagSet{[]}, electionId=7fffffff0000000000000001, setVersion=1, topologyVersion=TopologyVersion{processId=6ae47ad336d70748dbe6691b, counter=6}, lastWriteDate=Fri Oct 30 12:09:26 CET 2026, lastUpdateTimeNanos=[redacted]}
2026-10-30T12:09:32.172+01:00 INFO  [main] o.s.b.t.m.w.SpringBootMockServletContext traceId= clubId= accountId= : Initializing Spring TestDispatcherServlet ''
2026-10-30T12:09:32.172+01:00 INFO  [main] o.s.t.web.servlet.TestDispatcherServlet traceId= clubId= accountId= : Initializing Servlet ''
2026-10-30T12:09:32.179+01:00 INFO  [main] o.s.t.web.servlet.TestDispatcherServlet traceId= clubId= accountId= : Completed initialization in 7 ms
2026-10-30T12:09:32.238+01:00 INFO  [main] c.a.core.identity.api.IdentityCoreIT traceId= clubId= accountId= : Started IdentityCoreIT in 5.897 seconds (process running for 28.471)
2026-10-30T12:10:22.258+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.identity.api.DisabledEmailIdentityIT]: DisabledEmailIdentityIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-30T12:10:22.259+01:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.identity.api.DisabledEmailIdentityIT
2026-10-30T12:10:22.260+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.identity.api.DisabledEmailIdentityIT]: DisabledEmailIdentityIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-30T12:10:22.261+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.identity.api.IdentityIntegrationSupport]: IdentityIntegrationSupport does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-30T12:10:22.261+01:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.support.AbstractIntegrationTest]: AbstractIntegrationTest does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-30T12:10:22.263+01:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.identity.api.DisabledEmailIdentityIT

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v4.1.1)

2026-10-30T12:10:22.307+01:00 INFO  [main] c.a.c.i.api.DisabledEmailIdentityIT traceId= clubId= accountId= : Starting DisabledEmailIdentityIT using Java [redacted] with PID 27935 (started by jordib in /Users/jordib/dev/agilityhub/agilityhub-core-api)
2026-10-30T12:10:22.307+01:00 INFO  [main] c.a.c.i.api.DisabledEmailIdentityIT traceId= clubId= accountId= : The following 1 profile is active: "test"
2026-10-30T12:10:23.623+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Bootstrapping Spring Data MongoDB repositories in DEFAULT mode.
2026-10-30T12:10:23.697+01:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Finished Spring Data repository scanning in 71 ms. Found 0 MongoDB repository interfaces.
2026-10-30T12:10:23.930+01:00 INFO  [main] org.mongodb.driver.client traceId= clubId= accountId= : MongoClient with metadata {"driver": {"name": "mongo-java-driver|spring-boot|sync", "version": "5.8.1"}, "os": {"type": "Darwin", "name": "Mac OS X", "architecture": "aarch64", "version": "26.4.1"}, "platform": "Java/Homebrew/[redacted]"} created with settings MongoClientSettings{readPreference=primary, writeConcern=WriteConcern{w=null, wTimeout=null ms, journal=null}, retryWrites=true, retryReads=true, readConcern=ReadConcern{level=null}, credential=null, transportSettings=null, commandListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener@5ae71c23], codecRegistry=ProvidersCodecRegistry{codecProviders=[ValueCodecProvider{}, BsonValueCodecProvider{}, DBRefCodecProvider{}, DBObjectCodecProvider{}, DocumentCodecProvider{}, CollectionCodecProvider{}, IterableCodecProvider{}, MapCodecProvider{}, GeoJsonCodecProvider{}, GridFSFileCodecProvider{}, Jsr310CodecProvider{}, JsonObjectCodecProvider{}, BsonCodecProvider{}, com.mongodb.client.model.mql.ExpressionCodecProvider@4c364a9d, com.mongodb.Jep395RecordCodecProvider@597084b7, com.mongodb.KotlinCodecProvider@59ebe484, EnumCodecProvider{}]}, loggerSettings=LoggerSettings{maxDocumentLength=1000}, clusterSettings={hosts=[localhost:50848], srvServiceName=mongodb, mode=SINGLE, requiredClusterType=UNKNOWN, requiredReplicaSetName='null', serverSelector='null', clusterListeners='[]', serverSelectionTimeout='2000 ms', localThreshold='15 ms'}, socketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=0, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], heartbeatSocketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=2000, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], connectionPoolSettings=ConnectionPoolSettings{maxSize=100, minSize=0, maxWaitTimeMS=2000, maxConnectionLifeTimeMS=0, maxConnectionIdleTimeMS=0, maintenanceInitialDelayMS=0, maintenanceFrequencyMS=60000, connectionPoolListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener@444e839e], maxConnecting=2}, serverSettings=ServerSettings{heartbeatFrequencyMS=10000, minHeartbeatFrequencyMS=500, serverMonitoringMode=AUTO, serverListeners='[]', serverMonitorListeners='[]'}, sslSettings=SslSettings{enabled=false, invalidHostNameAllowed=false, context=null}, applicationName='null', compressorList=[], uuidRepresentation=JAVA_LEGACY, serverApi=null, autoEncryptionSettings=null, dnsClient=null, inetAddressResolver=null, contextProvider=null, timeoutMS=null}
2026-10-30T12:10:23.945+01:00 INFO  [cluster-6ae47b1f4d6a9493ba7265a8-localhost:50848] org.mongodb.driver.cluster traceId= clubId= accountId= : Monitor thread successfully connected to server with description ServerDescription{address=localhost:50848, type=REPLICA_SET_PRIMARY, cryptd=false, state=CONNECTED, ok=true, minWireVersion=0, maxWireVersion=21, maxDocumentSize=16777216, logicalSessionTimeoutMinutes=30, roundTripTimeNanos=13732042, minRoundTripTimeNanos=0, setName='docker-rs', canonicalAddress=abe038cb26f9:27017, hosts=[abe038cb26f9:27017], passives=[], arbiters=[], primary='abe038cb26f9:27017', tagSet=TagSet{[]}, electionId=7fffffff0000000000000001, setVersion=1, topologyVersion=TopologyVersion{processId=6ae47ad336d70748dbe6691b, counter=6}, lastWriteDate=Fri Oct 30 12:10:22 CET 2026, lastUpdateTimeNanos=[redacted]}
2026-10-30T12:10:28.390+01:00 INFO  [main] o.s.b.t.m.w.SpringBootMockServletContext traceId= clubId= accountId= : Initializing Spring TestDispatcherServlet ''
2026-10-30T12:10:28.390+01:00 INFO  [main] o.s.t.web.servlet.TestDispatcherServlet traceId= clubId= accountId= : Initializing Servlet ''
2026-10-30T12:10:28.397+01:00 INFO  [main] o.s.t.web.servlet.TestDispatcherServlet traceId= clubId= accountId= : Completed initialization in 7 ms
2026-10-30T12:10:28.454+01:00 INFO  [main] c.a.c.i.api.DisabledEmailIdentityIT traceId= clubId= accountId= : Started DisabledEmailIdentityIT in 6.185 seconds (process running for 84.686)
```

## Provider setup and serial gate

The approved restricted Brevo key has been created and installed privately. The transactional relay is enabled, the sender is verified in the provider UI, and the authenticated webhook has been configured. Its public endpoint returns 200 with the dedicated bearer secret and 401 without it. Ordinary email remains disabled, and the application release has not been deployed. No key or webhook secret is in this evidence.

The serial clean verification has completed all 5,003 unit tests with zero failures/errors; the full integration and coverage gate is still running. Publication starts image CI; production activation must wait for the final gate and reviewed immutable image.
