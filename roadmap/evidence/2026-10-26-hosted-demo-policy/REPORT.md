# Explicit hosted demo provider policy — 26 October 2026

User scope: one definitive production host, initially serving an isolated demo/test dataset; domain names are still user-selected. This change adds an explicit restriction in the deployed profile. It neither creates a second host nor activates local/test helpers. No Git write, remote publication, provider send, server creation or deployment was performed by this lane.

## Result and limits

- Final focused Java and architecture verification: **87 tests, 13 classes, zero failures/errors/skips**, exit 0. Machine-readable counts and per-report hashes: [08-results.json](08-results.json).
- Release preflight: **22 tests**, exit 0. `git diff --check`: exit 0.
- Initial red: 9 new configuration cases failed against the previous source (7 assertion failures, 2 old required-credential errors). A first green attempt stopped at one missing test import; corrected without product-behavior changes. Later focused runs passed.
- These are local policy/architecture regressions, not a renewed complete API gate or hosted/provider evidence. The parent lane is running full `clean verify` after this freeze. Previous API/image/browser evidence predates this runtime policy; a new final image must be built/scanned/published before deployment.
- [07-policy-inputs.json](07-policy-inputs.json) retains hashes of the 23 source/test/deployment/documentation files changed by this lane. Prior unrelated corrections are preserved.

## Safety contract

`HOSTED_DEMO_ENABLED` defaults to false and accepts only explicit true/false. Enabled mode requires prod/staging with no local/test profile. It requires a nonempty, exact comma-separated `HOSTED_DEMO_EMAIL_ALLOWLIST`; empty entries, wildcard/domain patterns and malformed recipient lists fail startup/preflight. Matching is case insensitive. No list or message contents are included in refusal errors.

Real SendGrid remains required, including sender and webhook-verification configuration. Every outbound email passes through `AllowListEmailSender`; excluded recipients never reach the delegate. SYSTEM messages become FAILED immediately. Club notifications also become terminal FAILED: the dispatcher recognizes the explicit guard refusal and does not retry it. Ordinary transient SendGrid failures keep the existing bounded retry policy.

SMS returns existing NOT_ALLOWED (SKIPPED_NOT_ALLOWED); push has no public VAPID key and returns terminal FAILED. These paths construct neither Twilio nor WebPush clients. This is not a fake SENT result. Twilio/VAPID values may be absent only under the explicit policy; normal deployed behavior still validates them. Operators should disable the SMS/PUSH club modules to hide unavailable features.

Stripe configuration updates reject live/non-test mode before locks, vault writes or configuration mutation. Every outbound Stripe client, including ping and checkout expiry, rejects stored non-test mode before decryption/network. Existing key-prefix validation then enforces sk_test_. Demo webhook authentication also requires stored test mode and an explicit JSON boolean `livemode=false`, even when the supplied signature is otherwise valid. Existing production behavior is preserved with the flag false. No new public error code or parameter catalog entry was introduced.

`seed:demo`, fake payment providers, test clocks, local mail capture, local storage and other local/test helpers keep their existing deployed-profile restrictions. TLS/authentication, private storage, backup/migration requirements and domain verification are unchanged. No public capture endpoint exists. Demo addresses and allowed tester identities must be configured privately; no domains or real recipients were selected by this lane.

## Coverage

The focused cases check exact recipient delegation, excluded address/domain/display-name/list variants, propagation of real sender failures, missing/malformed allowlists, profile restrictions, retained SendGrid requirements, SMS/push unavailability, injected Spring demo properties, live Stripe configuration/outbound rejection, mode/key mismatch, successful test-client delegation, signed live/missing/invalid-mode webhook rejection, valid test-event acceptance, and terminal allowlist refusal with ordinary retry behavior preserved. Existing normal profile/provider tests and architecture rules ran alongside these cases.

## Commands and evidence

### Initial configuration red

```sh
HEAVY_WAIT_MIN=120 JAVA_HOME=/opt/homebrew/opt/openjdk@21 /Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh ./mvnw -q -Dtest=HostedDemoConfigurationTest test
```

Exit: `1`. Full output: [01-config-red.log](01-config-red.log). Last up to 40 lines:

```text
java.lang.AssertionError: 

Expecting code to raise a throwable.
	at com.agilityhub.core.configuration.HostedDemoConfigurationTest.hostedDemoRequiresANonemptyExactRecipientAllowlist(HostedDemoConfigurationTest.java:23)

[ERROR] com.agilityhub.core.configuration.HostedDemoConfigurationTest.hostedDemoSmsIsNotAllowedWithoutTwilioCredentials -- Time elapsed: 0.001 s <<< ERROR!
java.lang.IllegalStateException: TWILIO_ACCOUNT_SID and TWILIO_AUTH_TOKEN are required in staging/prod
	at com.agilityhub.core.configuration.MessagingConfiguration.smsSender(MessagingConfiguration.java:92)
	at com.agilityhub.core.configuration.HostedDemoConfigurationTest.hostedDemoSmsIsNotAllowedWithoutTwilioCredentials(HostedDemoConfigurationTest.java:32)

[ERROR] Failures: 
[ERROR]   HostedDemoConfigurationTest.hostedDemoCannotUseLocalOrTestProviderDoubles:28 
Expecting code to raise a throwable.
[ERROR]   HostedDemoConfigurationTest.hostedDemoRequiresANonemptyExactRecipientAllowlist:23 
Expecting code to raise a throwable.
[ERROR]   HostedDemoConfigurationTest.hostedDemoRequiresANonemptyExactRecipientAllowlist:23 
Expecting code to raise a throwable.
[ERROR]   HostedDemoConfigurationTest.hostedDemoRequiresANonemptyExactRecipientAllowlist:23 
Expecting code to raise a throwable.
[ERROR]   HostedDemoConfigurationTest.hostedDemoRequiresANonemptyExactRecipientAllowlist:23 
Expecting code to raise a throwable.
[ERROR]   HostedDemoConfigurationTest.hostedDemoRequiresANonemptyExactRecipientAllowlist:23 
Expecting code to raise a throwable.
[ERROR]   HostedDemoConfigurationTest.hostedDemoRequiresANonemptyExactRecipientAllowlist:23 
Expecting code to raise a throwable.
[ERROR] Errors: 
[ERROR]   HostedDemoConfigurationTest.hostedDemoPushIsUnavailableWithoutVapidCredentials:36 » IllegalState VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY and VAPID_SUBJECT are required in staging/prod
[ERROR]   HostedDemoConfigurationTest.hostedDemoSmsIsNotAllowedWithoutTwilioCredentials:32 » IllegalState TWILIO_ACCOUNT_SID and TWILIO_AUTH_TOKEN are required in staging/prod
[ERROR] Tests run: 9, Failures: 7, Errors: 2, Skipped: 0
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

### Preflight regressions

```sh
python3 -m unittest discover -s deploy -p test_release_preflight.py
```

Exit: `0`. Full output: [03-preflight-tests.log](03-preflight-tests.log). Last up to 40 lines:

```text
......................
----------------------------------------------------------------------
Ran 22 tests in 0.037s

OK
```

### Final focused and architecture

```sh
HEAVY_WAIT_MIN=120 JAVA_HOME=/opt/homebrew/opt/openjdk@21 /Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh ./mvnw -q '-Dtest=ArchitectureRulesTest,ArchitectureTest,HostedDemoConfigurationTest,AllowListEmailSenderTest,HostedDemoEmailDeliveryTest,EmailConfigurationTest,MessagingConfigurationTest,StripePaymentProviderTest,StripePaymentProviderSurvivorsTest,HostedDemoProvidersTest,HostedDemoStripeWebhooksTest,StripeWebhookSignaturesTest,StripeWebhookSignaturesSurvivorsTest' test
```

Exit: `0`. Full output: [06-focused-final.log](06-focused-final.log). Last up to 40 lines:

```text
Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK. Please add Mockito as an agent to your build as described in Mockito's documentation: https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html#0.3
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
WARNING: A Java agent has been loaded dynamically (/Users/jordib/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.11/byte-buddy-agent-1.18.11.jar)
WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading to hide this warning
WARNING: If a serviceability tool is not in use, please run with -Djdk.instrument.traceUsage for more information
WARNING: Dynamic loading of agents will be disallowed by default in a future release
17:51:46.094 [main] INFO com.agilityhub.core.clubs.messaging.application.LogEmailSender -- Local email accepted notificationId=fixture
17:51:46.096 [main] INFO com.agilityhub.core.clubs.messaging.application.LogEmailSender -- Local email accepted notificationId=fixture
17:51:46.155 [main] WARN org.springframework.context.annotation.AnnotationConfigApplicationContext -- Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'emailSender' defined in com.agilityhub.core.configuration.EmailConfiguration: Failed to instantiate [com.agilityhub.core.clubs.messaging.application.EmailSender]: Factory method 'emailSender' threw exception with message: MAIL_FROM_PLATFORM is required in staging/prod
17:51:46.257 [main] WARN org.springframework.context.annotation.AnnotationConfigApplicationContext -- Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'sendGridSignatures' defined in com.agilityhub.core.configuration.EmailConfiguration: Failed to instantiate [com.agilityhub.core.clubs.messaging.application.SendGridSignatureVerifier]: Factory method 'sendGridSignatures' threw exception with message: SENDGRID_WEBHOOK_PUBLIC_KEY is required in staging/prod
17:51:46.650 [main] INFO com.agilityhub.core.clubs.messaging.application.LogEmailSender -- Local email accepted notificationId=fixture
17:51:46.718 [main] WARN org.springframework.context.annotation.AnnotationConfigApplicationContext -- Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'emailSender' defined in com.agilityhub.core.configuration.EmailConfiguration: Failed to instantiate [com.agilityhub.core.clubs.messaging.application.EmailSender]: Factory method 'emailSender' threw exception with message: SENDGRID_API_KEY is required in staging/prod
17:51:46.731 [main] WARN org.springframework.context.annotation.AnnotationConfigApplicationContext -- Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'emailSender' defined in com.agilityhub.core.configuration.EmailConfiguration: Failed to instantiate [com.agilityhub.core.clubs.messaging.application.EmailSender]: Factory method 'emailSender' threw exception with message: SENDGRID_API_KEY is required in staging/prod
17:51:48.596 [main] INFO com.tngtech.archunit.core.PluginLoader -- Detected Java version 21.0.12.1
```

Earlier attempts remain in `02-focused-green.log` (missing test import), `04-focused-green.log` (44 passing focused tests) and `05-focused-architecture.log` (passing before the final terminal-refusal refinement). No failed attempt counts toward final passing totals.
