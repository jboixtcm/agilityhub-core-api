# Publish and consume a domain event

Use only names/payloads in [CATALEG_ESDEVENIMENTS](../specs/00-transversal/CATALEG_ESDEVENIMENTS.md).
Java blocks are verbatim excerpts; linked files contain their imports and context.
The legacy Java class `ClubConfigChanged` emits the catalog name `ClubUpdated`;
do not introduce an event named `ClubConfigChanged`.

## Publish atomically

Implement `DomainEvent` in the owning context's `domain`, with type, club,
aggregate type/ID, occurrence instant, payload, actor, impersonated member and
origin. Use injected `Clock`, immutable payloads and actual request/system actor
metadata. Never include credentials. Inject the shared `EventPublisher` interface.

Call it after the aggregate write inside the same Mongo transaction. The E0
writer below is invoked through its Spring proxy inside a trusted tenant scope;
its audit annotation records the change in that transaction too:

Source: [src/main/java/com/agilityhub/core/platform/application/definition/ClubDefinitionWriter.java](../../src/main/java/com/agilityhub/core/platform/application/definition/ClubDefinitionWriter.java#L64-L83).

```java
    @Transactional
    @Audited(action = AuditAction.CLUB_UPDATED, entityType = "'Club'", entity = "#result.id",
            reason = "#result.changes() == 0 ? null : 'source: APPLY'")
    public Result apply(ObjectNode definition) {
        Plan plan = plan(definition);
        if (plan.result().changes() == 0) { return plan.result(); }
        // Saving the club also serializes concurrent parameter/admin changes through its version.
        clubs.save(plan.club());
        for (var parameter : plan.parameters()) {
            if (parameter.version() == null) { parameters.insert(parameter); } else { parameters.replace(parameter); }
            var payload = new LinkedHashMap<String, Object>();
            payload.put("key", parameter.key()); payload.put("after", parameter.value());
            payload.put("before", parameter.history().getLast().value()); payload.put("reason", "source: APPLY");
            events.publish(new ParameterChanged(plan.club().id(), clock.instant(), payload, null, null, DomainEvent.Origin.SYSTEM));
        }
        plan.admins().forEach(admins::provision);
        events.publish(new ClubConfigChanged(plan.club().id(), clock.instant(), Map.of("diff", plan.result().summary()),
                null, null, DomainEvent.Origin.SYSTEM));
        return plan.result();
    }
```

Use `@Transactional` on an externally invoked application method or an explicit
`TransactionTemplate`. Self-invocation does not activate transactional advice.
`MongoEventPublisher` rejects calls without an active transaction bound to its
Mongo database factory. Rollback removes both the aggregate change and outbox row.
Do not call consumers or external providers inline as a substitute for publishing.

## Implement a handler

Register a Spring bean implementing this contract in the consumer's `application`
layer. Give it an explicit, stable bean name: the dispatcher uses that name as a
durable consumer ID (Base64 encoded for the `processedAt` Mongo field).
For cross-context delivery, use a contract exposed through an allowed application
API or `shared.domain`; do not import another context's domain/persistence internals.
The `ClubConfigChanged` example below uses the shared domain contract.

Source: [src/main/java/com/agilityhub/core/shared/application/DomainEventHandler.java](../../src/main/java/com/agilityhub/core/shared/application/DomainEventHandler.java#L5-L10).

```java
/** External side effects must be idempotent using eventId. Bean names are durable consumer IDs. */
public interface DomainEventHandler<T extends DomainEvent> {
    String eventType();
    Class<T> eventClass();
    void handle(String eventId, T event) throws Exception;
}
```

`eventType()` must equal the catalog wire name; `eventClass()` must deserialize
the stored JSON. The E0 outbox test adapts a callback into a matching handler:

Source: [src/test/java/com/agilityhub/core/shared/persistence/OutboxIT.java](../../src/test/java/com/agilityhub/core/shared/persistence/OutboxIT.java#L63-L70).

```java
    private DomainEventHandler<ClubConfigChanged> handler(Delivery delivery) {
        return new DomainEventHandler<>() {
            public String eventType() { return "ClubUpdated"; }
            public Class<ClubConfigChanged> eventClass() { return ClubConfigChanged.class; }
            public void handle(String id, ClubConfigChanged event) throws Exception { delivery.accept(id, event); }
        };
    }
    interface Delivery { void accept(String id, ClubConfigChanged event) throws Exception; }
```

For club-scoped work, explicitly open `TenantContext.open(event.clubId())` with
try-with-resources inside `handle`, then use tenant repositories. The dispatcher
does **not** open a tenant scope. Global events need a deliberate global path;
do not open a null scope. Check module requirements explicitly for module-specific
effects and define whether disabled-module delivery is skipped or retried in the
task. Returning normally marks that consumer processed, so a skip is durable.

## Delivery, retries and recovery

- E0 dispatches every second, up to 100 claims per call. A five-minute claim lease
  and ownership token fence concurrent workers; the handler transaction locks the
  claim before delivery. Each matching handler and its checkpoint commit in one
  Mongo transaction. On failure those Mongo effects roll back.
- Successfully checkpointed consumers are skipped on retry. A later consumer's
  failure does not roll back an earlier consumer's committed work. Treat delivery
  as at least once: external effects must deduplicate using `eventId` (and consumer
  identity if a provider key space is shared), since HTTP effects cannot roll back.
- Attempts use exponential delays of 1, 2, 4, ... seconds, capped at 300 seconds.
  `SharedConfiguration` supplies 10 maximum attempts; exhausted records become
  `FAILED`, which the poller does not retry. Only the exception type is persisted.
  Do not rename consumer beans casually or promise a replay/admin API in E0.
- A record becomes `PUBLISHED` after all currently registered matching consumers
  finish, including when none match. Adding a handler later does not replay old
  published events. Register consumers with the producer change when required.
- Monitor `outbox.pending` and `outbox.failed`. The TTL index expires published
  rows after 90 days; pending/failed rows have no `publishedAt` TTL deadline.

Sources: [OutboxDispatcher](../../src/main/java/com/agilityhub/core/shared/application/OutboxDispatcher.java),
[OutboxRepository](../../src/main/java/com/agilityhub/core/shared/persistence/OutboxRepository.java),
[SharedConfiguration](../../src/main/java/com/agilityhub/core/shared/application/SharedConfiguration.java).
These are E0 infrastructure settings, not newly approved business parameters.

## Test through the dispatcher

Extend `AbstractIntegrationTest`, clean test collections, publish through a
`TransactionTemplate`, and invoke `OutboxDispatcher.dispatch()` manually. Its
scheduling is disabled in the base. Use `MockClock.advance` to reach retry times.
The following assertions are from the retry test after its first failing delivery:

Source: [src/test/java/com/agilityhub/core/shared/persistence/OutboxIT.java](../../src/test/java/com/agilityhub/core/shared/persistence/OutboxIT.java#L127-L136).

```java
        assertThat(record(id).status()).isEqualTo(DomainEventRecord.Status.PENDING);
        assertThat(record(id).processedAt()).hasSize(1);
        assertThat(record(id).nextAttemptAt()).isEqualTo(clock.instant().plusSeconds(1));
        assertThat(mongo.getCollection("outbox_effects").countDocuments()).isZero();
        dispatcher.dispatch(); assertThat(second).hasValue(1);
        clock.advance(Duration.ofSeconds(1)); dispatcher.dispatch();
        assertThat(first).hasValue(1); assertThat(second).hasValue(2);
        assertThat(record(id).status()).isEqualTo(DomainEventRecord.Status.PUBLISHED);
        assertThat(record(id).error()).isNull();
        assertThat(mongo.getCollection("outbox_effects").countDocuments()).isEqualTo(1);
```

Adapt [OutboxIT](../../src/test/java/com/agilityhub/core/shared/persistence/OutboxIT.java)
`E0_T04_*` to the current spec's `T_XX_NN_description`: committed publication,
aggregate rollback/no outbox row, failure rollback, replay without duplicate
effects, matching consumers, tenant A/B isolation and cleanup, backoff/exhaustion,
and competing workers/expired claims. Run `./mvnw -q verify`; the event catalog
contract must still pass. Finish with the [checklist](checklist.md).
