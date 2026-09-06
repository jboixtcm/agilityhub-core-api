# Add a scheduler

E0 provides clocks, tenant scopes, module guards and the outbox poller. S15 will
extend these into business jobs; E0 does not yet provide a per-club job runner,
run ledger or distributed business-job lock. The steps below describe that future
structure, while every Java block is copied from existing code.

## Per-club work

1. Keep a thin scheduling trigger in `application`, calling a separate application
   worker so proxy-based transactions/audit apply. A trusted platform application
   service should enumerate eligible club IDs under the task's lifecycle rules;
   do not let another context reach into platform persistence.
2. Process one club at a time, closing its `TenantContext` before opening the next.
   `TenantContext` is thread-local; establish a scope inside each worker thread.
   Do not change a request's tenant or pass a club ID supplied by an untrusted caller.
   This existing configuration method shows the scope lifecycle:

Source: [src/main/java/com/agilityhub/core/platform/application/ClubConfigService.java](../../src/main/java/com/agilityhub/core/platform/application/ClubConfigService.java#L34-L36).

```java
    public ClubConfig get(String clubId) {
        try (var scope = TenantContext.open(clubId)) { return cache.get(clubId, this::load); }
    }
```

3. Resolve configuration through `ClubConfigService` and call
   `ModuleGuard.require(clubId, module)` before module-specific work. An MVC
   `@RequiresModule` annotation does not guard a background method. Skip disabled
   modules deliberately; do not swallow other failures as successful runs.
4. Inject both `Clock` for stored UTC instants and `ClubClock` for the club-local
   due date/time. Use the club's `timeZone`, never the server zone or a club literal.
   The existing conversion reads the instant and the zone provider:

Source: [src/main/java/com/agilityhub/core/shared/application/DefaultClubClock.java](../../src/main/java/com/agilityhub/core/shared/application/DefaultClubClock.java#L16-L20).

```java
    @Override
    public LocalDate today(String clubId) { return now(clubId).toLocalDate(); }

    @Override
    public ZonedDateTime now(String clubId) { return clock.instant().atZone(zones.timeZone(clubId)); }
```

5. Read schedule/configuration keys only from
   [CATALEG_PARAMETRES](../specs/00-transversal/CATALEG_PARAMETRES.md). Take job IDs,
   eligibility, missed-run and DST rules from S15/the current task. If a required
   definition is missing, record a proposal instead of creating a key or schedule.

## Idempotency and failure

For the S15 implementation, define a durable uniqueness boundary from the job,
club and business occurrence (a local date only for a once-per-local-day job).
Claim it atomically so overlapping triggers or two instances cannot repeat work.
Persist completion together with business changes in the same Mongo transaction;
rollback must leave the occurrence retryable. A read-then-write check, JVM lock,
or `@Scheduled` alone is insufficient. Follow the model for the eventual ledger.

Publish [outbox events](new-event-consumer.md) in that transaction and apply
[audit](new-entity.md) where specified. External effects require their own stable
idempotency key. Define retries/recovery in the S15 task; E0 outbox retries cover
event delivery, not arbitrary scheduler executions. Isolate a failed club's work
so later clubs can proceed, and record failure using approved observability data.

The existing [OutboxDispatcher](../../src/main/java/com/agilityhub/core/shared/application/OutboxDispatcher.java)
polls with `@Scheduled(fixedDelay = 1000)`, claims up to 100 events and uses fenced
Mongo claims. It is global infrastructure, not a club-local cron example. The
`shared.scheduling.enabled=false` setting disables scheduling in integration tests;
tests invoke workers/dispatch explicitly rather than waiting for timers.

## Deterministic tests

Use [MockClock](../../src/test/java/com/agilityhub/core/support/MockClock.java)
directly in unit tests or the clock injected by `AbstractIntegrationTest` for
Mongo tests. Advance/set it instead of sleeping. This existing DST test is the
pattern for club-local date assertions (imports are in the source):

Source: [src/test/java/com/agilityhub/core/shared/application/ClubClockTest.java](../../src/test/java/com/agilityhub/core/shared/application/ClubClockTest.java#L14-L25).

```java
    @Test void E0_T04_clubDatesUseTenantZoneAndInjectedClockAcrossDst() {
        var clock = new MockClock(Instant.parse("2030-03-31T00:30:00Z"));
        var clubs = new DefaultClubClock(clock, new MapTimeZoneProvider(Map.of(
                "club-a", ZoneId.of("Europe/Madrid"), "club-b", ZoneId.of("America/New_York"))));
        assertThat(clubs.today("club-a")).hasToString("2030-03-31");
        assertThat(clubs.today("club-b")).hasToString("2030-03-30");
        assertThat(clubs.now("club-a").getHour()).isEqualTo(1);
        clock.advance(Duration.ofHours(1));
        assertThat(clubs.now("club-a").getHour()).isEqualTo(3);
        assertThatThrownBy(() -> clubs.today("unknown")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ErrorCode.CLUB_NOT_FOUND));
    }
```

Add the current spec's `T_XX_NN_description` tests for not-yet-due/due work, two
zones on different dates, spring DST gap and autumn repeated hour, missed runs,
disabled module, duplicate ticks, concurrent instances, rollback/retry, one club
failing while another succeeds, and tenant cleanup after exceptions. Verify one
committed business effect and the expected audit/outbox entries per occurrence.
Existing references are `E0_T04_clubDatesUseTenantZoneAndInjectedClockAcrossDst`
and `T_02_09_serviceAndSchedulerGuardSupportsEveryModuleAndRejectsCrossTenantCalls`
in [RequiresModuleIT](../../src/test/java/com/agilityhub/core/platform/api/RequiresModuleIT.java).

Run `./mvnw -q verify` when implementing a job, and follow the [checklist](checklist.md).
