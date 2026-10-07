#### Resumed final verification

The two clean runs use different recorded random seeds. Each runs through the shared host lock. Test summaries are read from that run's Surefire/Failsafe XML before the next clean build. Full logs and exit sidecars below are retained for publication. Log whitespace is trimmed; token/hash-shaped strings are truncated before copying the literal tails.

**92-resume-fixture-regressions.log**

Exact command:
```sh
MAVEN_ARGS=-Dmaven.repo.local=/private/tmp/agilityhub-e8-t04-m2 python3 roadmap/evidence/E8-T04/run-bounded.py roadmap/evidence/E8-T04/92-resume-fixture-regressions.log ./mvnw -q -DskipTests test-compile failsafe:integration-test failsafe:verify -Dit.test=E4ContractIT,E4PersistenceIT,ListFieldsContractIT,SecurityHeadersIT,DemoScenarioSeedIT -DskipTests=false -Dfailsafe.runOrder=random -Dfailsafe.runOrder.random.seed=20261005
```
Exit code: `0`. Complete output: `roadmap/evidence/E8-T04/92-resume-fixture-regressions.log`.

Literal last 40 lines:
```text
{templateClasses=39, templates=3, weeks=3, generatedClasses=114, validatedWeeks=2, looseClasses=1, ringBlocks=1, scenarioRingBlocks=1, attendanceHistoryClasses=10, riskCancellations=1, classBookings=23, classWaitlist=3, activities=4, publishedActivities=3, activityRegistrations=44, activityWaitlist=2, activityFiles=2, scenarioActivityRegistrations=1, scenarioClassBookings=18, scenarioCancellations=3, scenarioWaitlist=5, scenarioOffers=0, scenarioRiskExempt=1, scenarioBookingBlocks=1, attendanceRenamed=6, attendanceLevelChanges=1, followupNotes=1, attendanceBookings=34, attendanceWaitlist=1, attendanceMarks=26, attendanceCancellations=2, attendanceNoShowBatches=1, followupTasks=2, followupTaskAttachments=1, followupObservations=1, scenarioTrainingOverrides=1, scenarioTrainingBookings=3, trainingHistoryOverrides=1, trainingHistoryBookings=10, messagingContacts=2, messagingProfiles=3, messagingBounces=1, pushSubscriptions=1, customTemplates=1}
384 changes (demo planning, week start 2026-09-14)
E5-T07 outbox backlog discarded before DemoScenarioSeedIT: 918 PENDING records (more than one dispatch() batch)
{activeMembers=184, pendingMembers=3, inactiveMembers=4, leftMembers=3, members=194, dogs=246, familyGroups=12, activeDogs=242, pendingDogs=3, inactiveDogs=1, instructors=3, administrators=2, receivedDocuments=7, pendingDocuments=239}
452 changes (demo seed)
{templateClasses=39, templates=3, weeks=3, generatedClasses=114, validatedWeeks=2, looseClasses=1, ringBlocks=1, scenarioRingBlocks=1, attendanceHistoryClasses=10, riskCancellations=1, classBookings=23, classWaitlist=3, activities=4, publishedActivities=3, activityRegistrations=44, activityWaitlist=2, activityFiles=2, scenarioActivityRegistrations=1, scenarioClassBookings=18, scenarioCancellations=3, scenarioWaitlist=5, scenarioOffers=0, scenarioRiskExempt=1, scenarioBookingBlocks=1, attendanceRenamed=6, attendanceLevelChanges=1, followupNotes=1, attendanceBookings=34, attendanceWaitlist=1, attendanceMarks=26, attendanceCancellations=2, attendanceNoShowBatches=1, followupTasks=2, followupTaskAttachments=1, followupObservations=1, scenarioTrainingOverrides=1, scenarioTrainingBookings=3, trainingHistoryOverrides=1, trainingHistoryBookings=10, messagingContacts=2, messagingProfiles=3, messagingBounces=1, pushSubscriptions=1, customTemplates=1}
384 changes (demo planning, week start 2026-09-14)
{activeMembers=184, pendingMembers=3, inactiveMembers=4, leftMembers=3, members=194, dogs=246, familyGroups=12, activeDogs=242, pendingDogs=3, inactiveDogs=1, instructors=3, administrators=2, receivedDocuments=7, pendingDocuments=239}
0 changes (demo seed)
{templateClasses=39, templates=3, weeks=3, generatedClasses=114, validatedWeeks=2, looseClasses=1, ringBlocks=1, scenarioRingBlocks=1, attendanceHistoryClasses=10, riskCancellations=1, classBookings=23, classWaitlist=3, activities=4, publishedActivities=3, activityRegistrations=44, activityWaitlist=2, activityFiles=2, scenarioActivityRegistrations=1, scenarioClassBookings=18, scenarioCancellations=3, scenarioWaitlist=5, scenarioOffers=0, scenarioRiskExempt=1, scenarioBookingBlocks=1, attendanceRenamed=6, attendanceLevelChanges=1, followupNotes=1, attendanceBookings=34, attendanceWaitlist=1, attendanceMarks=26, attendanceCancellations=2, attendanceNoShowBatches=1, followupTasks=2, followupTaskAttachments=1, followupObservations=1, scenarioTrainingOverrides=1, scenarioTrainingBookings=3, trainingHistoryOverrides=1, trainingHistoryBookings=10, messagingContacts=2, messagingProfiles=3, messagingBounces=1, pushSubscriptions=1, customTemplates=1}
0 changes (demo planning, week start 2026-09-14)
E5-T07 outbox backlog discarded before DemoScenarioSeedIT: 918 PENDING records (more than one dispatch() batch)
{activeMembers=330, pendingMembers=0, inactiveMembers=0, leftMembers=0, members=330, dogs=330, familyGroups=0, activeDogs=330, pendingDogs=0, inactiveDogs=0, instructors=1, administrators=1, receivedDocuments=0, pendingDocuments=330}
660 changes (demo seed)
{templateClasses=11, templates=1, weeks=2, generatedClasses=22, validatedWeeks=2, scenarioRingBlocks=0, attendanceHistoryClasses=0, classBookings=0, classWaitlist=0, activities=0, publishedActivities=0, activityRegistrations=0, activityWaitlist=0, activityFiles=0, scenarioActivityRegistrations=0, scenarioClassBookings=0, scenarioCancellations=0, scenarioWaitlist=0, scenarioOffers=0, scenarioRiskExempt=0, scenarioBookingBlocks=0, attendanceRenamed=0, attendanceLevelChanges=0, followupNotes=0, attendanceBookings=0, attendanceWaitlist=0, attendanceMarks=0, attendanceCancellations=0, attendanceNoShowBatches=0, followupTasks=0, followupTaskAttachments=0, followupObservations=0, scenarioTrainingOverrides=0, scenarioTrainingBookings=0, trainingHistoryOverrides=0, trainingHistoryBookings=0, messagingContacts=0, messagingProfiles=0, messagingBounces=0, pushSubscriptions=0, customTemplates=0}
38 changes (demo planning, week start 2026-09-14)
{activeMembers=330, pendingMembers=0, inactiveMembers=0, leftMembers=0, members=330, dogs=330, familyGroups=0, activeDogs=330, pendingDogs=0, inactiveDogs=0, instructors=1, administrators=1, receivedDocuments=0, pendingDocuments=330}
0 changes (demo seed)
{templateClasses=11, templates=1, weeks=2, generatedClasses=22, validatedWeeks=2, scenarioRingBlocks=0, attendanceHistoryClasses=0, classBookings=0, classWaitlist=0, activities=0, publishedActivities=0, activityRegistrations=0, activityWaitlist=0, activityFiles=0, scenarioActivityRegistrations=0, scenarioClassBookings=0, scenarioCancellations=0, scenarioWaitlist=0, scenarioOffers=0, scenarioRiskExempt=0, scenarioBookingBlocks=0, attendanceRenamed=0, attendanceLevelChanges=0, followupNotes=0, attendanceBookings=0, attendanceWaitlist=0, attendanceMarks=0, attendanceCancellations=0, attendanceNoShowBatches=0, followupTasks=0, followupTaskAttachments=0, followupObservations=0, scenarioTrainingOverrides=0, scenarioTrainingBookings=0, trainingHistoryOverrides=0, trainingHistoryBookings=0, messagingContacts=0, messagingProfiles=0, messagingBounces=0, pushSubscriptions=0, customTemplates=0}
0 changes (demo planning, week start 2026-09-14)
E5-T07 outbox backlog discarded before DemoScenarioSeedIT: 763 PENDING records (more than one dispatch() batch)
{activeMembers=184, pendingMembers=3, inactiveMembers=4, leftMembers=3, members=194, dogs=246, familyGroups=12, activeDogs=242, pendingDogs=3, inactiveDogs=1, instructors=3, administrators=2, receivedDocuments=7, pendingDocuments=239}
452 changes (demo seed)
{templateClasses=39, templates=3, weeks=3, generatedClasses=114, validatedWeeks=2, looseClasses=1, ringBlocks=1, scenarioRingBlocks=1, attendanceHistoryClasses=10, riskCancellations=1, classBookings=23, classWaitlist=3, activities=4, publishedActivities=3, activityRegistrations=44, activityWaitlist=2, activityFiles=2, scenarioActivityRegistrations=1, scenarioClassBookings=18, scenarioCancellations=3, scenarioWaitlist=5, scenarioOffers=0, scenarioRiskExempt=1, scenarioBookingBlocks=1, attendanceRenamed=6, attendanceLevelChanges=1, followupNotes=1, attendanceBookings=34, attendanceWaitlist=1, attendanceMarks=26, attendanceCancellations=2, attendanceNoShowBatches=1, followupTasks=2, followupTaskAttachments=1, followupObservations=1, scenarioTrainingOverrides=1, scenarioTrainingBookings=3, trainingHistoryOverrides=1, trainingHistoryBookings=10, messagingContacts=2, messagingProfiles=3, messagingBounces=1, pushSubscriptions=1, customTemplates=1}
384 changes (demo planning, week start 2026-09-14)
E5-T07 outbox backlog discarded before DemoScenarioSeedIT: 918 PENDING records (more than one dispatch() batch)
{activeMembers=8, pendingMembers=0, inactiveMembers=0, leftMembers=0, members=8, dogs=8, familyGroups=0, activeDogs=8, pendingDogs=0, inactiveDogs=0, instructors=1, administrators=1, receivedDocuments=0, pendingDocuments=8}
16 changes (demo seed)
{templateClasses=5, templates=1, weeks=1, generatedClasses=5, validatedWeeks=1, scenarioRingBlocks=0, attendanceHistoryClasses=0, classBookings=0, classWaitlist=0, activities=0, publishedActivities=0, activityRegistrations=0, activityWaitlist=0, activityFiles=0, scenarioActivityRegistrations=0, scenarioClassBookings=2, scenarioCancellations=1, scenarioWaitlist=3, scenarioOffers=1, scenarioRiskExempt=0, scenarioBookingBlocks=0, attendanceRenamed=0, attendanceLevelChanges=0, followupNotes=0, attendanceBookings=0, attendanceWaitlist=0, attendanceMarks=0, attendanceCancellations=0, attendanceNoShowBatches=0, followupTasks=0, followupTaskAttachments=0, followupObservations=0, scenarioTrainingOverrides=0, scenarioTrainingBookings=0, trainingHistoryOverrides=0, trainingHistoryBookings=0, messagingContacts=0, messagingProfiles=0, messagingBounces=0, pushSubscriptions=0, customTemplates=0}
20 changes (demo planning, week start 2026-09-14)
2026-10-05T11:37:35.090+02:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.configuration.ListFieldsContractIT]: ListFieldsContractIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-05T11:37:35.091+02:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.configuration.ListFieldsContractIT
2026-10-05T11:37:36.411+02:00 INFO  [main] o.springdoc.api.AbstractOpenApiResource traceId=568ecccc-9c28-4b92-b47c-2c002c3118a3 clubId=- accountId=- : Init duration for springdoc-openapi is: 1307 ms
{activeMembers=184, pendingMembers=3, inactiveMembers=4, leftMembers=3, members=194, dogs=246, familyGroups=12, activeDogs=242, pendingDogs=3, inactiveDogs=1, instructors=3, administrators=2, receivedDocuments=7, pendingDocuments=239}
452 changes (demo seed)
{templateClasses=39, templates=3, weeks=3, generatedClasses=114, validatedWeeks=2, looseClasses=1, ringBlocks=1, scenarioRingBlocks=1, attendanceHistoryClasses=10, riskCancellations=1, classBookings=23, classWaitlist=3, activities=4, publishedActivities=3, activityRegistrations=44, activityWaitlist=2, activityFiles=2, scenarioActivityRegistrations=1, scenarioClassBookings=18, scenarioCancellations=3, scenarioWaitlist=5, scenarioOffers=0, scenarioRiskExempt=1, scenarioBookingBlocks=1, attendanceRenamed=6, attendanceLevelChanges=1, followupNotes=1, attendanceBookings=34, attendanceWaitlist=1, attendanceMarks=26, attendanceCancellations=2, attendanceNoShowBatches=1, followupTasks=2, followupTaskAttachments=1, followupObservations=1, scenarioTrainingOverrides=1, scenarioTrainingBookings=3, trainingHistoryOverrides=1, trainingHistoryBookings=10, messagingContacts=2, messagingProfiles=3, messagingBounces=1, pushSubscriptions=1, customTemplates=1}
384 changes (demo planning, week start 2026-09-14)
2026-10-05T11:37:42.510+02:00 WARN  [main] c.networknt.schema.UnknownKeywordFactory traceId= clubId= accountId= : Unknown keyword components - you should define your own Meta Schema. If the keyword is irrelevant for validation, just use a NonValidationKeyword or if it should generate annotations AnnotationKeyword
2026-10-05T11:37:52.740+02:00 WARN  [main] c.networknt.schema.UnknownKeywordFactory traceId= clubId= accountId= : Unknown keyword example - you should define your own Meta Schema. If the keyword is irrelevant for validation, just use a NonValidationKeyword or if it should generate annotations AnnotationKeyword
E5-T07 outbox backlog discarded before ListFieldsContractIT: 918 PENDING records (more than one dispatch() batch)
```

**93-resume-clean-random-one.log**

Exact command:
```sh
MAVEN_ARGS=-Dmaven.repo.local=/private/tmp/agilityhub-e8-t04-m2 HEAVY_WAIT_MIN=25 python3 roadmap/evidence/E8-T04/run-bounded.py roadmap/evidence/E8-T04/93-resume-clean-random-one.log /Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh ./mvnw -q clean verify -Dsurefire.runOrder=random -Dfailsafe.runOrder=random -Dsurefire.runOrder.random.seed=2026100501 -Dfailsafe.runOrder.random.seed=2026100501
```
Exit code: `0`. Complete output: `roadmap/evidence/E8-T04/93-resume-clean-random-one.log`.

Literal last 40 lines:
```text
2026-10-05T11:47:52.183+02:00 WARN  [main] c.a.core.shared.api.ApiExceptionHandler traceId=ccddbdd8-f625-4900-9006-92c5d06061fe clubId=catalog-a accountId=catalog-admin : Framework request rejected code=VALIDATION_ERROR traceId=ccddbdd8-f625-4900-9006-92c5d06061fe
2026-10-05T11:47:52.208+02:00 WARN  [main] c.a.core.shared.api.ApiExceptionHandler traceId=424e5ec5-1300-4d12-a058-23b6dd2e77a5 clubId=catalog-a accountId=catalog-admin : Framework request rejected code=VALIDATION_ERROR traceId=424e5ec5-1300-4d12-a058-23b6dd2e77a5
2026-10-05T11:47:52.218+02:00 WARN  [main] c.a.core.shared.api.ApiExceptionHandler traceId=39334a4b-b588-4556-a1e3-b520a793ef88 clubId=catalog-a accountId=catalog-admin : Framework request rejected code=VALIDATION_ERROR traceId=39334a4b-b588-4556-a1e3-b520a793ef88
2026-10-05T11:47:52.219+02:00 WARN  [main] c.a.core.shared.api.ApiExceptionHandler traceId=f40c045b-cb93-4d3e-8109-f998a7ea145d clubId=catalog-a accountId=catalog-admin : Framework request rejected code=VALIDATION_ERROR traceId=f40c045b-[redacted]
2026-10-05T11:47:52.221+02:00 WARN  [main] c.a.core.shared.api.ApiExceptionHandler traceId=d8810abe-f4ce-4ccb-b687-a7555c11076f clubId=catalog-a accountId=catalog-admin : Framework request rejected code=VALIDATION_ERROR traceId=d8810abe-f4ce-4ccb-b687-a7555c11076f
2026-10-05T11:47:52.453+02:00 WARN  [main] c.a.core.shared.api.ApiExceptionHandler traceId=d517c3f5-1f52-42bf-b548-34debf4a31ff clubId=catalog-a accountId=catalog-admin : Framework request rejected code=VALIDATION_ERROR traceId=d517c3f5-1f52-42bf-b548-34debf4a31ff
2026-10-05T11:47:53.182+02:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.clubs.bookings.api.BookingJobsIT]: BookingJobsIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-05T11:47:53.182+02:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.clubs.bookings.api.BookingJobsIT
E5-T05 payment-timeouts JobRun dry  {"_id": "6f4e785b-2247-4a53-9ce0-84d31b561b5f", "clubId": "s08-a", "job": "PAYMENT_TIMEOUTS", "scheduledFor": {"$date": "2026-10-06T08:00:00Z"}, "scheduledForLocal": "2026-10-06T10:00", "timeZone": "Europe/Madrid", "trigger": "MANUAL", "dryRun": true, "status": "SUCCEEDED", "startedAt": {"$date": "2026-10-06T08:00:00Z"}, "finishedAt": {"$date": "2026-10-06T08:00:00Z"}, "durationMs": 0, "counters": [{"key": "WOULD_CANCEL", "value": 1}], "items": [{"entityType": "Booking", "entityId": "s08-pay-stale", "action": "WOULD_CANCEL", "detail": [{"key": "minutesPending", "value": 31}, {"key": "bookingId", "value": "s08-pay-stale"}]}], "errors": [], "actorAccountId": "s08-admin", "parametersSnapshot": [{"key": "jobs.paymentTimeouts.enabled", "value": true}, {"key": "bookings.paymentPendingMinutes", "value": 30}], "exclusive": false, "holder": "794510b9-36f7-4d06-8955-f8181cef67e1:2fed798b-98a2-4f0f-a8d4-f398a13fc8ea", "leaseExpired": false, "_class": "com.agilityhub.core.platform.persistence.jobs.JobRun"}
E5-T05 payment-timeouts JobRun real {"_id": "47a58203-bda0-459f-8272-efe2dd61752d", "clubId": "s08-a", "job": "PAYMENT_TIMEOUTS", "scheduledFor": {"$date": "2026-10-06T08:00:00Z"}, "scheduledForLocal": "2026-10-06T10:00", "timeZone": "Europe/Madrid", "trigger": "SCHEDULE", "dryRun": false, "status": "SUCCEEDED", "startedAt": {"$date": "2026-10-06T08:00:00Z"}, "finishedAt": {"$date": "2026-10-06T08:00:00Z"}, "durationMs": 0, "counters": [{"key": "cancelled", "value": 1}], "items": [{"entityType": "Booking", "entityId": "s08-pay-stale", "action": "CANCEL", "detail": [{"key": "minutesPending", "value": 31}, {"key": "bookingId", "value": "s08-pay-stale"}]}], "errors": [], "parametersSnapshot": [{"key": "jobs.paymentTimeouts.enabled", "value": true}, {"key": "bookings.paymentPendingMinutes", "value": 30}], "exclusive": true, "holder": "794510b9-36f7-4d06-8955-f8181cef67e1:d44ce140-8cbb-4d8c-8e6b-214fa0675765", "leaseExpired": false, "_class": "com.agilityhub.core.platform.persistence.jobs.JobRun"}
2026-10-05T11:47:53.355+02:00 INFO  [main] c.a.c.p.application.jobs.JobRunner traceId= clubId= accountId= : Job skipped jobRunId=8d7f1299-c04a-4f61-9640-8fa23da6ecea job=PAYMENT_TIMEOUTS clubId=s08-a reason=MODULE_OFF baseline=false
E5-T05 waitlist-fifo JobRun dry  {"_id": "f94c9ee6-bd0b-4b5b-b8fd-d058df23cd2e", "clubId": "s08-a", "job": "WAITLIST_FIFO", "scheduledFor": {"$date": "2026-10-06T08:30:00Z"}, "scheduledForLocal": "2026-10-06T10:30", "timeZone": "Europe/Madrid", "trigger": "MANUAL", "dryRun": true, "status": "SUCCEEDED", "startedAt": {"$date": "2026-10-06T08:30:00Z"}, "finishedAt": {"$date": "2026-10-06T08:30:00Z"}, "durationMs": 0, "counters": [{"key": "WOULD_EXPIRE", "value": 1}], "items": [{"entityType": "WaitlistEntry", "entityId": "1dd5f584-19e0-4a3f-bae1-ed3d023c785e", "action": "WOULD_EXPIRE", "detail": [{"key": "entryId", "value": "1dd5f584-19e0-4a3f-bae1-ed3d023c785e"}, {"key": "position", "value": 1}]}], "errors": [], "actorAccountId": "s08-admin", "parametersSnapshot": [{"key": "jobs.waitlistFifo.enabled", "value": true}, {"key": "waitlist.mode", "value": "FIFO"}], "exclusive": false, "holder": "794510b9-36f7-4d06-8955-f8181cef67e1:2994c9fb-8152-4a22-8c95-3115e7253704", "leaseExpired": false, "_class": "com.agilityhub.core.platform.persistence.jobs.JobRun"}
E5-T05 waitlist-fifo JobRun real {"_id": "dc15ded5-8667-41c5-8780-b2c17cf26b65", "clubId": "s08-a", "job": "WAITLIST_FIFO", "scheduledFor": {"$date": "2026-10-06T08:30:00Z"}, "scheduledForLocal": "2026-10-06T10:30", "timeZone": "Europe/Madrid", "trigger": "SCHEDULE", "dryRun": false, "status": "SUCCEEDED", "startedAt": {"$date": "2026-10-06T08:30:00Z"}, "finishedAt": {"$date": "2026-10-06T08:30:00Z"}, "durationMs": 0, "counters": [{"key": "expired", "value": 1}], "items": [{"entityType": "WaitlistEntry", "entityId": "1dd5f584-19e0-4a3f-bae1-ed3d023c785e", "action": "EXPIRE", "detail": [{"key": "entryId", "value": "1dd5f584-19e0-4a3f-bae1-ed3d023c785e"}, {"key": "position", "value": 1}]}], "errors": [], "parametersSnapshot": [{"key": "jobs.waitlistFifo.enabled", "value": true}, {"key": "waitlist.mode", "value": "FIFO"}], "exclusive": true, "holder": "794510b9-36f7-4d06-8955-f8181cef67e1:1cff2de1-cd9e-45c8-8fc4-c32a0e70550c", "leaseExpired": false, "_class": "com.agilityhub.core.platform.persistence.jobs.JobRun"}
2026-10-05T11:47:53.695+02:00 INFO  [main] c.a.c.p.application.jobs.JobRunner traceId= clubId= accountId= : Job skipped jobRunId=85977a94-61fe-42bb-aec8-2cac0d63115c job=WAITLIST_FIFO clubId=s08-a reason=MODULE_OFF baseline=false
2026-10-05T11:47:53.697+02:00 INFO  [main] c.a.c.p.application.jobs.JobRunner traceId= clubId= accountId= : Job skipped jobRunId=f38fd9fc-4496-45ed-8176-91361af8e68c job=WAITLIST_FIFO clubId=s08-a reason=MODULE_OFF baseline=false
2026-10-05T11:47:53.980+02:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.clubs.bookings.api.JobsApiIT]: JobsApiIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-05T11:47:53.981+02:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.clubs.bookings.api.JobsApiIT
E6-T04 GET /jobs [{"name":"week-opening","jobName":"WEEK_OPENING","module":null,"enabled":true,"schedule":{"kind":"WEEKLY","localTime":"20:00","dayOfWeek":"SUNDAY","dayOfMonth":null},"nextScheduledForLocal":"2026-10-11T20:00","lastRun":null},{"name":"risk-review","jobName":"RISK_REVIEW","module":null,"enabled":false,"schedule":{"kind":"DAILY","localTime":"07:30","dayOfWeek":null,"dayOfMonth":null},"nextScheduledForLocal":"2026-10-07T07:30","lastRun":{"runId":"154568b8-51d0-471b-ae86-cfc6d1b985e7","status":"SUCCEEDED","finishedAt":"2026-10-06T08:00:00Z","trigger":"MANUAL","dryRun":false,"counters":{"reviewed":4,"notifiedMembers":0,"atRisk":4}}},{"name":"no-show-notices","jobName":"NO_SHOW_NOTICES","module":null,"enabled":true,"schedule":{"kind":"DAILY","localTime":"08:00","dayOfWeek":null,"dayOfMonth":null},"nextScheduledForLocal":"2026-10-07T08:00","lastRun":null},{"name":"reminders","jobName":"REMINDERS","module":null,"enabled":false,"schedule":{"kind":"CONTINUOUS","localTime":null,"dayOfWeek":null,"dayOfMonth":null},"nextScheduledForLocal":null,"lastRun":null},{"name":"expirations","jobName":"EXPIRATIONS","module":null,"enabled":true,"schedule":{"kind":"DAILY","localTime":"06:00","dayOfWeek":null,"dayOfMonth":null},"nextScheduledForLocal":"2026-10-07T06:00","lastRun":null},{"name":"payment-timeouts","jobName":"PAYMENT_TIMEOUTS","module":"SINGLE_CLASS","enabled":true,"schedule":{"kind":"CONTINUOUS","localTime":null,"dayOfWeek":null,"dayOfMonth":null},"nextScheduledForLocal":null,"lastRun":null},{"name":"class-finishing","jobName":"CLASS_FINISHING","module":null,"enabled":true,"schedule":{"kind":"CONTINUOUS","localTime":null,"dayOfWeek":null,"dayOfMonth":null},"nextScheduledForLocal":null,"lastRun":null},{"name":"cleanup","jobName":"CLEANUP","module":null,"enabled":true,"schedule":{"kind":"DAILY","localTime":"06:00","dayOfWeek":null,"dayOfMonth":null},"nextScheduledForLocal":"2026-10-07T06:00","lastRun":null}]
2026-10-05T11:47:55.227+02:00 WARN  [main] c.a.core.shared.api.ApiExceptionHandler traceId=ab5ddbb4-ea3a-4b1a-8893-0133fce47ee2 clubId=s08-a accountId=s08-admin : Framework request rejected code=VALIDATION_ERROR traceId=ab5ddbb4-ea3a-4b1a-8893-0133fce47ee2
2026-10-05T11:47:55.266+02:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.payments.api.PaymentCurlIT]: PaymentCurlIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-05T11:47:55.268+02:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.payments.api.PaymentCurlIT
curl -X POST /api/v1/billing/runs/[id truncated]/card-charges -> 202
curl -X POST /webhooks/stripe/bill-a -> 200
curl -X GET /api/v1/invoices/[id truncated] -> 200
curl -X POST /api/v1/invoices/[id truncated]/retry -> 202
curl -X POST /webhooks/stripe/bill-a -> 200
curl -X POST /webhooks/stripe/bill-a -> 200
curl -X POST /webhooks/stripe/bill-a -> 200
curl -X GET /api/v1/invoices/[id truncated] -> 200
curl -X POST /api/v1/invoices/[id truncated]/refund -> 202
curl -X POST /webhooks/stripe/bill-a -> 200
curl -X GET /api/v1/invoices/[id truncated] -> 200
FakePaymentProvider operation=charge idempotencyKey=8dea3194...[truncated]
FakePaymentProvider operation=charge idempotencyKey=8dea3194...[truncated]:2
FakePaymentProvider operation=refund idempotencyKey=invoice-...[truncated]
StripeEvent eventId=evt_curl_failed type=payment_intent.payment_failed outcome=PROCESSED
StripeEvent eventId=evt_curl_paid type=payment_intent.succeeded outcome=PROCESSED
StripeEvent eventId=evt_curl_late_failure type=payment_intent.payment_failed outcome=IGNORED
StripeEvent eventId=evt_curl_refunded type=charge.refunded outcome=PROCESSED
PASS curl sequence: charge -> failed webhook -> invoice FAILED -> retry -> success/duplicate/late failure -> refund -> invoice PAID, refundedTotal=6000 EUR
```

**94-resume-test-summary-one.log**

Exact command:
```sh
python3 roadmap/evidence/E8-T04/run-bounded.py roadmap/evidence/E8-T04/94-resume-test-summary-one.log python3 roadmap/evidence/E8-T04/summarize-tests.py roadmap/evidence/E8-T04/93-resume-clean-random-one.log.exit
```
Exit code: `0`. Complete output: `roadmap/evidence/E8-T04/94-resume-test-summary-one.log`.

Literal last 29 lines:
```text
clean verify exit=0
surefire totals: tests=3689, failures=0, errors=0, skipped=0
  ArchitectureTest: tests=17, failures=0, errors=0, skipped=0
  DashboardBuildersTest: tests=7, failures=0, errors=0, skipped=0
  NotificationMatrixTest: tests=2681, failures=0, errors=0, skipped=0
  NotificationCatalogContractTest: tests=4, failures=0, errors=0, skipped=0
  E8ResponseContractTest: tests=10, failures=0, errors=0, skipped=0
  S04ErrorContractTest: tests=2, failures=0, errors=0, skipped=0
  CheckoutProviderFailureTest: tests=3, failures=0, errors=0, skipped=0
  StripeCallsTest: tests=2, failures=0, errors=0, skipped=0
  StripePaymentProviderTest: tests=7, failures=0, errors=0, skipped=0
  AuditContractTest: tests=1, failures=0, errors=0, skipped=0
  DemoSeedActorTest: tests=2, failures=0, errors=0, skipped=0
  EventCatalogContractTest: tests=2, failures=0, errors=0, skipped=0
failsafe totals: tests=1490, failures=0, errors=0, skipped=0
  SignupCensusCorrectionsIT: tests=19, failures=0, errors=0, skipped=0
  SignupGateFixesIT: tests=24, failures=0, errors=0, skipped=0
  DemoScenarioSeedIT: tests=6, failures=0, errors=0, skipped=0
  NotificationEngineIT: tests=14, failures=0, errors=0, skipped=0
  TemplateVariableParityIT: tests=2, failures=0, errors=0, skipped=0
  E3ContractIT: tests=17, failures=0, errors=0, skipped=0
  E4ContractIT: tests=64, failures=0, errors=0, skipped=0
  E4PersistenceIT: tests=3, failures=0, errors=0, skipped=0
  E8ContractIT: tests=70, failures=0, errors=0, skipped=0
  ListFieldsContractIT: tests=4, failures=0, errors=0, skipped=0
  OpenApiSnapshotTest: tests=10, failures=0, errors=0, skipped=0
  SecurityHeadersIT: tests=2, failures=0, errors=0, skipped=0
  CardPaymentsIT: tests=26, failures=0, errors=0, skipped=0
  PaymentCurlIT: tests=1, failures=0, errors=0, skipped=0
```

**95-resume-clean-random-two.log**

Exact command:
```sh
MAVEN_ARGS=-Dmaven.repo.local=/private/tmp/agilityhub-e8-t04-m2 HEAVY_WAIT_MIN=25 python3 roadmap/evidence/E8-T04/run-bounded.py roadmap/evidence/E8-T04/95-resume-clean-random-two.log /Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh ./mvnw -q clean verify -Dsurefire.runOrder=random -Dfailsafe.runOrder=random -Dsurefire.runOrder.random.seed=2026100502 -Dfailsafe.runOrder.random.seed=2026100502
```
Exit code: `0`. Complete output: `roadmap/evidence/E8-T04/95-resume-clean-random-two.log`.

Literal last 40 lines:
```text
2026-10-05T12:10:35.418+02:00 WARN  [main] c.a.c.c.m.a.engine.TemplateRenderer traceId= clubId= accountId= : Notification template of N-37 has no value for change
2026-10-05T12:10:35.422+02:00 WARN  [main] c.a.c.c.m.a.engine.TemplateRenderer traceId= clubId= accountId= : Notification template of N-37 has no value for change
2026-10-05T12:10:35.422+02:00 WARN  [main] c.a.c.c.m.a.engine.TemplateRenderer traceId= clubId= accountId= : Notification template of N-37 has no value for change
2026-10-05T12:10:35.426+02:00 WARN  [main] c.a.c.c.m.a.engine.TemplateRenderer traceId= clubId= accountId= : Notification template of N-37 has no value for change
2026-10-05T12:10:35.426+02:00 WARN  [main] c.a.c.c.m.a.engine.TemplateRenderer traceId= clubId= accountId= : Notification template of N-37 has no value for change
2026-10-05T12:10:35.430+02:00 WARN  [main] c.a.c.c.m.a.engine.TemplateRenderer traceId= clubId= accountId= : Notification template of N-37 has no value for change
2026-10-05T12:10:35.430+02:00 WARN  [main] c.a.c.c.m.a.engine.TemplateRenderer traceId= clubId= accountId= : Notification template of N-37 has no value for change
2026-10-05T12:10:35.434+02:00 WARN  [main] c.a.c.c.m.a.engine.TemplateRenderer traceId= clubId= accountId= : Notification template of N-37 has no value for change
2026-10-05T12:10:35.434+02:00 WARN  [main] c.a.c.c.m.a.engine.TemplateRenderer traceId= clubId= accountId= : Notification template of N-37 has no value for change
2026-10-05T12:10:35.438+02:00 WARN  [main] c.a.c.c.m.a.engine.TemplateRenderer traceId= clubId= accountId= : Notification template of N-37 has no value for change
2026-10-05T12:10:35.438+02:00 WARN  [main] c.a.c.c.m.a.engine.TemplateRenderer traceId= clubId= accountId= : Notification template of N-37 has no value for change
2026-10-05T12:10:35.442+02:00 WARN  [main] c.a.c.c.m.a.engine.TemplateRenderer traceId= clubId= accountId= : Notification template of N-37 has no value for change
2026-10-05T12:10:35.442+02:00 WARN  [main] c.a.c.c.m.a.engine.TemplateRenderer traceId= clubId= accountId= : Notification template of N-37 has no value for change
2026-10-05T12:10:35.447+02:00 WARN  [main] c.a.c.c.m.a.engine.TemplateRenderer traceId= clubId= accountId= : Notification template of N-37 has no value for change
2026-10-05T12:10:35.447+02:00 WARN  [main] c.a.c.c.m.a.engine.TemplateRenderer traceId= clubId= accountId= : Notification template of N-37 has no value for change
2026-10-05T12:10:35.452+02:00 WARN  [main] c.a.c.c.m.a.engine.TemplateRenderer traceId= clubId= accountId= : Notification template of N-37 has no value for change
2026-10-05T12:10:35.452+02:00 WARN  [main] c.a.c.c.m.a.engine.TemplateRenderer traceId= clubId= accountId= : Notification template of N-37 has no value for change
2026-10-05T12:10:35.696+02:00 WARN  [main] c.a.c.c.m.a.e.NotificationDispatcher traceId= clubId= accountId= : Notification delivery failed notificationId=9bd730c1-d210-4cb2-8df4-17f5389adcfa channel=EMAIL error=IllegalStateException
2026-10-05T12:10:36.302+02:00 WARN  [main] c.a.c.c.m.a.e.NotificationDispatcher traceId= clubId= accountId= : Notification settlement failed notificationId=7c8b2dfa-2276-40ef-[redacted]=PUSH accepted=false error=IllegalStateException
2026-10-05T12:10:36.326+02:00 INFO  [main] o.s.t.c.s.AnnotationConfigContextLoaderUtils traceId= clubId= accountId= : Could not detect default configuration classes for test class [com.agilityhub.core.payments.api.PaymentCurlIT]: PaymentCurlIT does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-10-05T12:10:36.328+02:00 INFO  [main] o.s.b.t.c.SpringBootTestContextBootstrapper traceId= clubId= accountId= : Found @SpringBootConfiguration com.agilityhub.core.CoreApplication for test class com.agilityhub.core.payments.api.PaymentCurlIT
curl -X POST /api/v1/billing/runs/[id truncated]/card-charges -> 202
curl -X POST /webhooks/stripe/bill-a -> 200
curl -X GET /api/v1/invoices/[id truncated] -> 200
curl -X POST /api/v1/invoices/[id truncated]/retry -> 202
curl -X POST /webhooks/stripe/bill-a -> 200
curl -X POST /webhooks/stripe/bill-a -> 200
curl -X POST /webhooks/stripe/bill-a -> 200
curl -X GET /api/v1/invoices/[id truncated] -> 200
curl -X POST /api/v1/invoices/[id truncated]/refund -> 202
curl -X POST /webhooks/stripe/bill-a -> 200
curl -X GET /api/v1/invoices/[id truncated] -> 200
FakePaymentProvider operation=charge idempotencyKey=1d2c5055...[truncated]
FakePaymentProvider operation=charge idempotencyKey=1d2c5055...[truncated]:2
FakePaymentProvider operation=refund idempotencyKey=invoice-...[truncated]
StripeEvent eventId=evt_curl_failed type=payment_intent.payment_failed outcome=PROCESSED
StripeEvent eventId=evt_curl_paid type=payment_intent.succeeded outcome=PROCESSED
StripeEvent eventId=evt_curl_late_failure type=payment_intent.payment_failed outcome=IGNORED
StripeEvent eventId=evt_curl_refunded type=charge.refunded outcome=PROCESSED
PASS curl sequence: charge -> failed webhook -> invoice FAILED -> retry -> success/duplicate/late failure -> refund -> invoice PAID, refundedTotal=6000 EUR
```

**96-resume-test-summary-two.log**

Exact command:
```sh
python3 roadmap/evidence/E8-T04/run-bounded.py roadmap/evidence/E8-T04/96-resume-test-summary-two.log python3 roadmap/evidence/E8-T04/summarize-tests.py roadmap/evidence/E8-T04/95-resume-clean-random-two.log.exit
```
Exit code: `0`. Complete output: `roadmap/evidence/E8-T04/96-resume-test-summary-two.log`.

Literal last 29 lines:
```text
clean verify exit=0
surefire totals: tests=3689, failures=0, errors=0, skipped=0
  ArchitectureTest: tests=17, failures=0, errors=0, skipped=0
  DashboardBuildersTest: tests=7, failures=0, errors=0, skipped=0
  NotificationMatrixTest: tests=2681, failures=0, errors=0, skipped=0
  NotificationCatalogContractTest: tests=4, failures=0, errors=0, skipped=0
  E8ResponseContractTest: tests=10, failures=0, errors=0, skipped=0
  S04ErrorContractTest: tests=2, failures=0, errors=0, skipped=0
  CheckoutProviderFailureTest: tests=3, failures=0, errors=0, skipped=0
  StripeCallsTest: tests=2, failures=0, errors=0, skipped=0
  StripePaymentProviderTest: tests=7, failures=0, errors=0, skipped=0
  AuditContractTest: tests=1, failures=0, errors=0, skipped=0
  DemoSeedActorTest: tests=2, failures=0, errors=0, skipped=0
  EventCatalogContractTest: tests=2, failures=0, errors=0, skipped=0
failsafe totals: tests=1490, failures=0, errors=0, skipped=0
  SignupCensusCorrectionsIT: tests=19, failures=0, errors=0, skipped=0
  SignupGateFixesIT: tests=24, failures=0, errors=0, skipped=0
  DemoScenarioSeedIT: tests=6, failures=0, errors=0, skipped=0
  NotificationEngineIT: tests=14, failures=0, errors=0, skipped=0
  TemplateVariableParityIT: tests=2, failures=0, errors=0, skipped=0
  E3ContractIT: tests=17, failures=0, errors=0, skipped=0
  E4ContractIT: tests=64, failures=0, errors=0, skipped=0
  E4PersistenceIT: tests=3, failures=0, errors=0, skipped=0
  E8ContractIT: tests=70, failures=0, errors=0, skipped=0
  ListFieldsContractIT: tests=4, failures=0, errors=0, skipped=0
  OpenApiSnapshotTest: tests=10, failures=0, errors=0, skipped=0
  SecurityHeadersIT: tests=2, failures=0, errors=0, skipped=0
  CardPaymentsIT: tests=26, failures=0, errors=0, skipped=0
  PaymentCurlIT: tests=1, failures=0, errors=0, skipped=0
```

**98-resume-openapi.log**

Exact command:
```sh
MAVEN_ARGS=-Dmaven.repo.local=/private/tmp/agilityhub-e8-t04-m2 python3 roadmap/evidence/E8-T04/run-bounded.py roadmap/evidence/E8-T04/98-resume-openapi.log python3 roadmap/evidence/E8-T04/verify-resume-openapi.py
```
Exit code: `0`. Complete output: `roadmap/evidence/E8-T04/98-resume-openapi.log`.

Literal last 40 lines:
```text

 :: Spring Boot ::               (v3.5.16)

2026-10-05T12:11:21.296+02:00 INFO  [main] c.a.c.configuration.OpenApiSnapshotTest traceId= clubId= accountId= : Starting OpenApiSnapshotTest using Java [redacted] with PID 33662 (started by jordib in /Users/jordib/dev/agilityhub/agilityhub-core-api)
2026-10-05T12:11:21.298+02:00 INFO  [main] c.a.c.configuration.OpenApiSnapshotTest traceId= clubId= accountId= : The following 1 profile is active: "test"
2026-10-05T12:11:22.417+02:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Bootstrapping Spring Data MongoDB repositories in DEFAULT mode.
2026-10-05T12:11:22.463+02:00 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate traceId= clubId= accountId= : Finished Spring Data repository scanning in 43 ms. Found 0 MongoDB repository interfaces.
2026-10-05T12:11:22.861+02:00 INFO  [main] o.s.b.w.embedded.tomcat.TomcatWebServer traceId= clubId= accountId= : Tomcat initialized with port 0 (http)
2026-10-05T12:11:22.869+02:00 INFO  [main] o.apache.catalina.core.StandardService traceId= clubId= accountId= : Starting service [Tomcat]
2026-10-05T12:11:22.869+02:00 INFO  [main] org.apache.catalina.core.StandardEngine traceId= clubId= accountId= : Starting Servlet engine: [Apache Tomcat/10.1.60]
2026-10-05T12:11:22.898+02:00 INFO  [main] o.a.c.c.C.[Tomcat].[localhost].[/] traceId= clubId= accountId= : Initializing Spring embedded WebApplicationContext
2026-10-05T12:11:22.899+02:00 INFO  [main] o.s.b.w.s.c.ServletWebServerApplicationContext traceId= clubId= accountId= : Root WebApplicationContext: initialization completed in 1592 ms
2026-10-05T12:11:23.012+02:00 INFO  [main] org.mongodb.driver.client traceId= clubId= accountId= : MongoClient with metadata {"driver": {"name": "mongo-java-driver|sync|spring-boot", "version": "5.5.2"}, "os": {"type": "Darwin", "name": "Mac OS X", "architecture": "aarch64", "version": "26.4.1"}, "platform": "Java/Homebrew/[redacted]"} created with settings MongoClientSettings{readPreference=primary, writeConcern=WriteConcern{w=null, wTimeout=null ms, journal=null}, retryWrites=true, retryReads=true, readConcern=ReadConcern{level=null}, credential=null, transportSettings=null, commandListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener@ca69671], codecRegistry=ProvidersCodecRegistry{codecProviders=[ValueCodecProvider{}, BsonValueCodecProvider{}, DBRefCodecProvider{}, DBObjectCodecProvider{}, DocumentCodecProvider{}, CollectionCodecProvider{}, IterableCodecProvider{}, MapCodecProvider{}, GeoJsonCodecProvider{}, GridFSFileCodecProvider{}, Jsr310CodecProvider{}, JsonObjectCodecProvider{}, BsonCodecProvider{}, EnumCodecProvider{}, com.mongodb.client.model.mql.ExpressionCodecProvider@28c8c41a, com.mongodb.Jep395RecordCodecProvider@5cc430cb, com.mongodb.KotlinCodecProvider@16f4afe9]}, loggerSettings=LoggerSettings{maxDocumentLength=1000}, clusterSettings={hosts=[localhost:64499], srvServiceName=mongodb, mode=SINGLE, requiredClusterType=UNKNOWN, requiredReplicaSetName='null', serverSelector='null', clusterListeners='[]', serverSelectionTimeout='2000 ms', localThreshold='15 ms'}, socketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=0, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], heartbeatSocketSettings=SocketSettings{connectTimeoutMS=2000, readTimeoutMS=2000, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, [redacted], connectionPoolSettings=ConnectionPoolSettings{maxSize=100, minSize=0, maxWaitTimeMS=2000, maxConnectionLifeTimeMS=0, maxConnectionIdleTimeMS=0, maintenanceInitialDelayMS=0, maintenanceFrequencyMS=60000, connectionPoolListeners=[io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener@2e59b658], maxConnecting=2}, serverSettings=ServerSettings{heartbeatFrequencyMS=10000, minHeartbeatFrequencyMS=500, serverMonitoringMode=AUTO, serverListeners='[]', serverMonitorListeners='[]'}, sslSettings=SslSettings{enabled=false, invalidHostNameAllowed=false, context=null}, applicationName='null', compressorList=[], uuidRepresentation=JAVA_LEGACY, serverApi=null, autoEncryptionSettings=null, dnsClient=null, inetAddressResolver=null, contextProvider=null, timeoutMS=null}
2026-10-05T12:11:23.019+02:00 INFO  [cluster-ClusterId{value='6ac377ca...[truncated]', description='null'}-localhost:64499] org.mongodb.driver.cluster traceId= clubId= accountId= : Monitor thread successfully connected to server with description ServerDescription{address=localhost:64499, type=REPLICA_SET_PRIMARY, cryptd=false, state=CONNECTED, ok=true, minWireVersion=0, maxWireVersion=21, maxDocumentSize=16777216, logicalSessionTimeoutMinutes=30, roundTripTimeNanos=11615125, minRoundTripTimeNanos=0, setName='docker-rs', canonicalAddress=91827452e98b:27017, hosts=[91827452e98b:27017], passives=[], arbiters=[], primary='91827452e98b:27017', tagSet=TagSet{[]}, electionId=7fffffff...[truncated], setVersion=1, topologyVersion=TopologyVersion{processId=6ac377c8...[truncated], counter=6}, lastWriteDate=Mon Oct 05 12:11:20 CEST 2026, lastUpdateTimeNanos=[redacted]}
2026-10-05T12:11:23.638+02:00 INFO  [main] o.s.boot.web.servlet.RegistrationBean traceId= clubId= accountId= : Filter rateLimitFilter was not registered (disabled)
2026-10-05T12:11:26.562+02:00 INFO  [main] o.a.c.c.C.[Tomcat].[localhost].[/] traceId= clubId= accountId= : Initializing Spring TestDispatcherServlet ''
2026-10-05T12:11:26.562+02:00 INFO  [main] o.s.t.web.servlet.TestDispatcherServlet traceId= clubId= accountId= : Initializing Servlet ''
2026-10-05T12:11:26.563+02:00 INFO  [main] o.s.t.web.servlet.TestDispatcherServlet traceId= clubId= accountId= : Completed initialization in 1 ms
2026-10-05T12:11:26.632+02:00 INFO  [main] o.s.b.w.embedded.tomcat.TomcatWebServer traceId= clubId= accountId= : Tomcat started on port 64520 (http) with context path '/'
2026-10-05T12:11:26.663+02:00 INFO  [main] o.s.b.w.embedded.tomcat.TomcatWebServer traceId= clubId= accountId= : Tomcat initialized with port 0 (http)
2026-10-05T12:11:26.664+02:00 INFO  [main] o.apache.catalina.core.StandardService traceId= clubId= accountId= : Starting service [Tomcat]
2026-10-05T12:11:26.664+02:00 INFO  [main] org.apache.catalina.core.StandardEngine traceId= clubId= accountId= : Starting Servlet engine: [Apache Tomcat/10.1.60]
2026-10-05T12:11:26.674+02:00 INFO  [main] o.a.c.c.C.[Tomcat-1].[localhost].[/] traceId= clubId= accountId= : Initializing Spring embedded WebApplicationContext
2026-10-05T12:11:26.674+02:00 INFO  [main] o.s.b.w.s.c.ServletWebServerApplicationContext traceId= clubId= accountId= : Root WebApplicationContext: initialization completed in 41 ms
2026-10-05T12:11:26.681+02:00 INFO  [main] o.s.b.a.e.web.EndpointLinksResolver traceId= clubId= accountId= : Exposing 2 endpoints beneath base path '/actuator'
2026-10-05T12:11:26.702+02:00 INFO  [main] o.s.b.w.embedded.tomcat.TomcatWebServer traceId= clubId= accountId= : Tomcat started on port 64521 (http) with context path '/'
2026-10-05T12:11:26.710+02:00 INFO  [main] c.a.c.configuration.OpenApiSnapshotTest traceId= clubId= accountId= : Started OpenApiSnapshotTest in 5.655 seconds (process running for 8.269)
Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK. Please add Mockito as an agent to your build as described in Mockito's documentation: https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html#0.3
WARNING: A Java agent has been loaded dynamically (/private/tmp/agilityhub-e8-t04-m2/net/bytebuddy/byte-buddy-agent/1.17.8/byte-buddy-agent-1.17.8.jar)
WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading to hide this warning
WARNING: If a serviceability tool is not in use, please run with -Djdk.instrument.traceUsage for more information
WARNING: Dynamic loading of agents will be disallowed by default in a future release
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
2026-10-05T12:11:29.390+02:00 INFO  [main] o.springdoc.api.AbstractOpenApiResource traceId=debf798e-f07d-42b2-8300-02bbcaad24fe clubId=- accountId=user : Init duration for springdoc-openapi is: 1947 ms
2026-10-05T12:11:29.960+02:00 INFO  [tomcat-handler-0] o.a.c.c.C.[Tomcat].[localhost].[/] traceId= clubId= accountId= : Initializing Spring DispatcherServlet 'dispatcherServlet'
2026-10-05T12:11:29.960+02:00 INFO  [tomcat-handler-0] o.s.web.servlet.DispatcherServlet traceId= clubId= accountId= : Initializing Servlet 'dispatcherServlet'
2026-10-05T12:11:29.961+02:00 INFO  [tomcat-handler-0] o.s.web.servlet.DispatcherServlet traceId= clubId= accountId= : Completed initialization in 1 ms
2026-10-05T12:11:31.515+02:00 INFO  [main] o.springdoc.api.AbstractOpenApiResource traceId=6d3226a3-2075-4ee0-9d09-3f42ee9aba15 clubId=- accountId=- : Init duration for springdoc-openapi is: 1474 ms
Updated docs/openapi/openapi.json
PASS bin/openapi-snapshot: fresh snapshot is byte-identical to the reviewed contract
```

**99-resume-secret-scan.log**

Exact command:
```sh
python3 roadmap/evidence/E8-T04/run-bounded.py roadmap/evidence/E8-T04/99-resume-secret-scan.log python3 roadmap/evidence/E8-T04/check-stripe-secrets.py
```
Exit code: `0`. Complete output: `roadmap/evidence/E8-T04/99-resume-secret-scan.log`.

Literal last 40 lines:
```text
./roadmap/evidence/E8-T04/56-final-secret-scan.log:13: bare sk_live documentation mention
./roadmap/evidence/E8-T04/56-final-secret-scan.log:14: bare sk_live documentation mention
./roadmap/evidence/E8-T04/56-final-secret-scan.log:15: bare sk_live documentation mention
./roadmap/evidence/E8-T04/56-final-secret-scan.log:16: bare sk_live documentation mention
./roadmap/evidence/E8-T04/56-final-secret-scan.log:17: bare sk_live documentation mention
./roadmap/evidence/E8-T04/56-final-secret-scan.log:18: bare sk_live documentation mention
./roadmap/evidence/E8-T04/56-final-secret-scan.log:19: bare sk_live documentation mention
./roadmap/evidence/E8-T04/56-final-secret-scan.log:20: bare sk_live documentation mention
./roadmap/evidence/E8-T04/56-final-secret-scan.log:21: bare sk_live documentation mention
./roadmap/evidence/E8-T04/56-final-secret-scan.log:22: bare sk_live documentation mention
./roadmap/evidence/E8-T04/56-final-secret-scan.log:23: bare sk_live documentation mention
./roadmap/evidence/E8-T04/56-final-secret-scan.log:24: bare sk_live documentation mention
./roadmap/evidence/E8-T04/56-final-secret-scan.log:25: bare sk_live documentation mention
./roadmap/evidence/E8-T04/56-final-secret-scan.log:26: bare sk_live documentation mention
./roadmap/evidence/E8-T04/56-final-secret-scan.log:27: bare sk_live documentation mention
./roadmap/evidence/E8-T04/56-final-secret-scan.log:28: bare sk_live documentation mention
./roadmap/evidence/E8-T04/56-final-secret-scan.log:29: bare sk_live documentation mention
./roadmap/evidence/E8-T04/56-final-secret-scan.log:30: bare sk_live documentation mention
./roadmap/evidence/E8-T04/56-final-secret-scan.log:31: bare sk_live documentation mention
./roadmap/evidence/E8-T04/56-final-secret-scan.log:32: bare sk_live documentation mention
./roadmap/evidence/E8-T04/56-final-secret-scan.log:33: bare sk_live documentation mention
./roadmap/evidence/E8-T04/check-stripe-secrets.py:8: bare sk_live documentation mention
./roadmap/evidence/E8-T04/check-stripe-secrets.py:22: bare sk_live documentation mention
./roadmap/evidence/E8-T04/check-stripe-secrets.py:23: bare sk_live documentation mention
./roadmap/evidence/E8-T04/42-secret-scan.log:1: bare sk_live documentation mention
./roadmap/evidence/E8-T04/42-secret-scan.log:2: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:1: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:2: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:3: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:4: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:5: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:6: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:7: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:8: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:9: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:10: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:11: bare sk_live documentation mention
./roadmap/evidence/E8-T04/33-secret-grep.log:1: bare sk_live documentation mention
./roadmap/evidence/E8-T04/33-secret-grep.log:2: bare sk_live documentation mention
rg exit=0; bare documentation mentions=55; secret-shaped matches=0
```

**100-resume-payment-proofs.log**

Exact command:
```sh
python3 roadmap/evidence/E8-T04/run-bounded.py roadmap/evidence/E8-T04/100-resume-payment-proofs.log python3 roadmap/evidence/E8-T04/extract-resume-proofs.py roadmap/evidence/E8-T04/95-resume-clean-random-two.log
```
Exit code: `0`. Complete output: `roadmap/evidence/E8-T04/100-resume-payment-proofs.log`.

Literal last 40 lines:
```text
CardPaymentsIT.java: T_12_16_manualAuditAndExpiredSignupStillRecordsCapturedRemainder
CardPaymentsIT.java: T_12_16_expiredBookingRefundsExactlyOnceAndPrivacyDeletionIsRecoverable
CardPaymentsIT.java: T_12_15_storedUnknownPaymentIsDeferredAndReprocessed
CardPaymentsIT.java: T_12_17_cancellationPoliciesRefundCreditOrLeaveThePaymentAndRefundTargetIsExact
CardPaymentsIT.java: T_12_15_lostResponseReusesRunAndRetryCommandsDespiteChangedVersion
CardPaymentsIT.java: T_12_16_signupCommitFailureReusesOneProviderSetupSession
CardPaymentsIT.java: T_12_16_rejectedSignupCompletionRefundsOnceWithoutRevivingCancelledRows
CardPaymentsIT.java: T_12_17_lateCheckoutCannotOverwriteTheCheckoutThatPaidItsRows
CardPaymentsIT.java: T_12_17_upfrontRefundBeforeCompletionWaitsAndSettlesOnce
CardPaymentsIT.java: T_12_17_refundMetadataTargetsSecondRowBeforeProviderResultAndKeepsAdminProvenance
CardPaymentsIT.java: T_12_16_unpaidCheckoutNeverSettlesSignupOrBooking
CardPaymentsIT.java: T_12_15_recoveryIsFairBackedOffAndDeadLettersAfterConfiguredAttempts
CardPaymentsIT.java: T_12_31_cardInvalidIsPublishedOnMemberAndMeOnlyForCards
CardPaymentsIT.java: T_12_15_chargingRunAcceptsAnotherCommandWithoutResubmittingInvoices
CardPaymentsIT.java: T_12_17_pendingRefundKeepsOnlyAllowListedMetadata
CardPaymentsIT.java: T_12_15_recoveryUsesTheClubsAttemptLimitAndRetainsItsDeadline
PaymentCurlIT.java: T_12_15_T_12_17_curlChargeFailureRetrySuccessDuplicateAndRefund
StripePaymentProviderTest.java: T_12_32_modeMismatchAndMissingKeysFailBeforeAnyNetworkCall
StripePaymentProviderTest.java: T_12_15_offSessionAndRefundUseExplicitDomainKeysAndPerClubClient
StripePaymentProviderTest.java: T_12_16_checkoutParametersAndSavedProviderReferenceAreStable
StripePaymentProviderTest.java: T_12_31_setupAndExpandedCardUseTheClubAccount
StripePaymentProviderTest.java: T_12_16_disabledProviderStillExpiresAnExistingCheckoutButCannotCreateOne
StripePaymentProviderTest.java: T_12_30_aDeclinedIntentKeepsItsReferenceAndOtherErrorsMapSafely
StripePaymentProviderTest.java: T_12_32_disabledTenantAndAlreadyDeletedCustomerAreHandledWithoutLeakingProviderErrors
StripeCallsTest.java: T_12_15_rateLimitRetriesTwiceWithBoundedExponentialBackoff
StripeCallsTest.java: T_12_32_providerErrorsNeverExposeMessagesAndAreNotRetried
E8ContractIT.java: T_12_21_T_13_24_everyRouteEnforcesRolesTenantAndResourceIsolationBeforeItsAnswerOrItsStub
E8ContractIT.java: T_12_21_T_13_24_impersonationReachesTheMemberRoutesOnly
E8ContractIT.java: T_12_22_billingOffHidesEveryS12RouteAndTheLeaveRoutesStillAnswer
E8ContractIT.java: T_12_22_packsAndSingleClassOffHideTheirRoutesOnly
E8ContractIT.java: T_12_15_theStripeWebhookAuthenticatesTheBodyBeforeAnythingElse
E8ContractIT.java: T_12_12_T_13_02_inputsAreValidatedBeforeTheStubAndAnInvoiceHasNoPatch
E8ContractIT.java: T_12_16_theCheckoutExtensionsRejectPaidOrMissingRowsWithoutWrites
E8ContractIT.java: T_12_21_T_13_24_theCheckoutExtensionsAuthorizeAndCheckTheirReferencesBeforeTheStub
E8ContractIT.java: T_12_21_T_13_24_everyStubResolvesItsCallerAndItsReferencesBeforeTheStub
E8ContractIT.java: T_12_21_T_13_24_theStubsWriteNothing

External credentials (presence only):
STRIPE_TEST_SECRET_KEY: absent
STRIPE_TEST_WEBHOOK_SECRET: absent
```

**101-resume-diff-check.log**

Exact command:
```sh
python3 roadmap/evidence/E8-T04/run-bounded.py roadmap/evidence/E8-T04/101-resume-diff-check.log git diff --check
```
Exit code: `0`. Complete output: `roadmap/evidence/E8-T04/101-resume-diff-check.log`.

Output is empty (zero lines).

**107-resume-verified-package-smoke.log**

Exact command:
```sh
HEAVY_WAIT_MIN=25 python3 roadmap/evidence/E8-T04/run-bounded.py roadmap/evidence/E8-T04/107-resume-verified-package-smoke.log /Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh python3 roadmap/evidence/E8-T04/smoke-verified-package.py
```
Exit code: `0`. Complete output: `roadmap/evidence/E8-T04/107-resume-verified-package-smoke.log`.

Literal last 40 lines:
```text
curl PATCH /api/v1/dogs/3fa59e3a-6f17-3d8d-ab35-1ecbf39b26ff -> 200
curl GET /api/v1/members/1413baee-fd92-34db-9154-76e7fdb6b475/signup -> 200
curl GET /api/v1/attachments/files/be98b6e1-a635-4281-8ee3-e2d81be93200 -> 200
curl GET /api/v1/attachments/files/be98b6e1-a635-4281-8ee3-e2d81be93200 -> 200
PASS D2 adds a file with the ADMIN's DOG_DOCUMENT upload; the signed upload and download URLs need no bearer; the download is application/pdf, attachment, with the stored name
curl POST /api/v1/members/1413baee-fd92-34db-9154-76e7fdb6b475/rejection -> 200
PASS D1 dashboard + counters: pending=3, olderThanWarn=1, activeMembers=185
PASS rejected readmission restores the LEFT member and its INACTIVE dog; D1 counters restored
curl POST /api/v1/signup/upload-urls -> 200
curl GET /api/v1/parameters/signup.enabled -> 200
curl PUT /api/v1/parameters/signup.enabled -> 200
curl POST /api/v1/signup -> 422 SIGNUP_CLOSED
PASS signup.enabled=false closes the form; canonical catalog status is 422 SIGNUP_CLOSED
curl POST /api/v1/signup/upload-urls -> 200
curl OPTIONS /api/v1/signup/uploads -> 200
curl PUT /api/v1/signup/uploads -> 204
curl PUT /api/v1/signup/uploads -> 422
PASS signup closed: the member's signed upload passes the CORS preflight and the PUT with its headers only, without a bearer or the club's host; an anonymous one answers 422 SIGNUP_CLOSED
curl POST /api/v1/me/dogs/signup -> 201
curl GET /api/v1/members/c72bc068-9624-4f3b-888b-f3aeaacbce1b/signup -> 200
curl GET /api/v1/signup/files -> 200
PASS the member's add-dog claims the file; its download from the web's origin is image/png, inline, with the stored name
PASS T-04-34 / T-14-11: E3 backend gate completed; credentials and capabilities withheld
curl GET /api/v1/signup -> 200
curl POST /api/v1/signup -> 201
curl POST /api/v1/checkout-sessions -> 201
PASS a card signup on the Stripe club opens its checkout (1 rows CHECKOUT_PENDING); the key replays the same checkoutUrl
Fake provider completion delivered: checkoutSessionId=215d1aa4…
PASS the provider's completion pays every row (PAID, STRIPE) and keeps the card on the member
curl POST /api/v1/signup -> 201
curl POST /api/v1/checkout-sessions -> 201
Fake provider expiry delivered: checkoutSessionId=09a35705…
PASS the provider's expiry gives every row back (DUE, no session)
curl POST /api/v1/signup -> 201
curl POST /api/v1/checkout-sessions -> 201
PASS P7 payment-timeouts ran past the stranded checkout's expiresAt and left it to P5 (still PENDING)
POST /test/clock to P5's daily time 2026-10-07 06:00 Europe/Madrid + 1 min (server now 2026-10-07T04:01Z): the first P5 occurrence past the stranded checkout's expiresAt
PASS P5 expirations step h (SCHEDULE 2026-10-07T06:00) expires the checkout that never heard from the provider past its expiresAt: EXPIRE_CHECKOUT, counters {'expiredCheckouts': 1}, rows DUE, no refund mark
PASS T-04-22: the signup checkout on the fake provider (create, complete, expire, stranded release)
Removed disposable Compose services, volumes and private mailbox; existing stacks preserved
```

**108-resume-final-secret-scan.log**

Exact command:
```sh
python3 roadmap/evidence/E8-T04/run-bounded.py roadmap/evidence/E8-T04/108-resume-final-secret-scan.log python3 roadmap/evidence/E8-T04/check-stripe-secrets.py
```
Exit code: `0`. Complete output: `roadmap/evidence/E8-T04/108-resume-final-secret-scan.log`.

Literal last 40 lines:
```text
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:33: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:34: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:35: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:36: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:37: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:38: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:39: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:40: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:41: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:42: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:43: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:44: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:45: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:46: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:47: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:48: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:49: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:50: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:51: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:52: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:53: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:54: bare sk_live documentation mention
./roadmap/evidence/E8-T04/99-resume-secret-scan.log:55: bare sk_live documentation mention
./roadmap/evidence/E8-T04/21-secret-grep.log:1: bare sk_live documentation mention
./roadmap/evidence/E8-T04/21-secret-grep.log:2: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:1: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:2: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:3: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:4: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:5: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:6: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:7: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:8: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:9: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:10: bare sk_live documentation mention
./roadmap/evidence/E8-T04/44-secret-scan-all.log:11: bare sk_live documentation mention
./roadmap/evidence/E8-T04/check-stripe-secrets.py:8: bare sk_live documentation mention
./roadmap/evidence/E8-T04/check-stripe-secrets.py:22: bare sk_live documentation mention
./roadmap/evidence/E8-T04/check-stripe-secrets.py:23: bare sk_live documentation mention
rg exit=0; bare documentation mentions=110; secret-shaped matches=0
```
