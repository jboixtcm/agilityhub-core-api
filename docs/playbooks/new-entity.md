# Add an entity

Use this with the task's model, spec and [checklist](checklist.md). Java blocks are
verbatim excerpts; their linked source files supply imports and enclosing classes.

## Model and persistence

1. Take English names and ownership from [MODEL_DADES_PLATAFORMA §0](../MODEL_DADES_PLATAFORMA.md).
   The model wins over a conflicting spec; record the difference in the report.
2. Keep business rules in the context's `domain`, Mongo mappings in `persistence`,
   use cases in `application`, and HTTP DTOs in `api`.
3. A club document implements `TenantEntity` and carries `clubId`. Use `@Id`,
   `@Version Long version`, and the model's UTC `Instant` timestamps. Set timestamps
   from injected `Clock`; preserve `createdAt` on update. `Parameter` only has
   `updatedAt`; the global `Club` record demonstrates both `createdAt` and `updatedAt`.

Source: [src/main/java/com/agilityhub/core/platform/persistence/Parameter.java](../../src/main/java/com/agilityhub/core/platform/persistence/Parameter.java#L11-L18).

```java
@Document("parameters")
public record Parameter(@Id String id, String clubId, String key, Object value, String type, String scope,
                        String scopeRef, List<History> history, @Version Long version, Instant updatedAt) implements TenantEntity {
    public Parameter { value = ImmutableValues.freeze(value); history = List.copyOf(history); }
    public record History(Object value, Instant changedAt, String changedByAccountId, String reason) {
        public History { value = ImmutableValues.freeze(value); }
    }
}
```

4. Extend `TenantRepository`; custom queries start with `tenantQuery()`, including
   writes and deletes. Take the club from `TenantContext.require()`, never an
   unchecked request field. A worker opens a trusted scope with try-with-resources.
   Reserve `GlobalRepository` for model-defined global aggregates (for example
   `Club`) and explicitly privileged infrastructure; it provides no tenant guard.
5. Define named indexes in `ensureIndexes()` and wire that method into startup
   before transactions use the collection. Tenant uniqueness includes `clubId`.
   Parameters also include `scopeRef`; null is the club-wide override.

Source: [src/main/java/com/agilityhub/core/platform/persistence/ParameterRepository.java](../../src/main/java/com/agilityhub/core/platform/persistence/ParameterRepository.java#L9-L16).

```java
@Repository
public class ParameterRepository extends TenantRepository<Parameter> {
    public ParameterRepository(MongoTemplate mongo) { super(mongo, Parameter.class); }
    public void ensureIndexes() {
        mongo.indexOps(Parameter.class).ensureIndex(new Index().on("clubId", Direction.ASC).on("key", Direction.ASC)
                .on("scopeRef", Direction.ASC).unique().named("parameter_scope_key"));
    }
}
```

`TenantRepository.replace` scopes by club and ID, then upserts with
`findAndReplace`; it does not compare or increment the version. For a new
optimistic-locking use case, implement an atomic expected-version check in its
repository, disallow update upserts, and test stale writes with `STALE_VERSION`.
`ClubDefinitionWriter` currently serializes its parameter changes through the
versioned `ClubRepository.save` in the same transaction.

## DTOs and audit

Return an explicit application view mapped to an API DTO; never serialize a Mongo
document or configuration map. `BrandingResponse.from` is the public allowlist:

Source: [src/main/java/com/agilityhub/core/platform/api/BrandingResponse.java](../../src/main/java/com/agilityhub/core/platform/api/BrandingResponse.java#L11-L17).

```java
    public static BrandingResponse from(ClubConfig config) {
        var club = config.club(); var profile = config.countryProfile();
        return new BrandingResponse(new ClubSummary(club.slug(), club.name()), club.theme(), club.locales(), club.defaultLocale(),
                club.timeZone(), club.currency(), new Country(profile.code(), profile.idDocumentTypes(), profile.defaultPhonePrefix()),
                config.modules().stream().map(Enum::name).sorted().toList(), new Signup(Boolean.TRUE.equals(config.get("signup.enabled", Boolean.class))),
                club.status(), new Legal(club.privacyPolicyUrl()));
    }
```

For a spec-audited mutation, put `@Transactional` and `@Audited` on the application
method called through a Spring proxy. Use the approved `AuditAction`, `entityType`
and `entity` expressions. Register an `AuditableLoader` for before/after loading,
or supply `before` and a returned annotated snapshot. `#result.id` suits creation.
Only `@AuditField` values enter a diff; `@Sensitive` defaults to IBAN masking, with
`MASK_ID_DOCUMENT` and `HIDE` for other sensitive fields. This is a test fixture:

Source: [src/test/java/com/agilityhub/core/platform/application/audit/AuditedAspectIT.java](../../src/test/java/com/agilityhub/core/platform/application/audit/AuditedAspectIT.java#L320-L324).

```java
    @org.springframework.data.mongodb.core.mapping.Document("audit_test_parameters")
    public record AuditableParameter(@Id String id, String clubId, @AuditField String value,
                                     @AuditField @Sensitive String iban) implements TenantEntity {
        public String type() { return "Parameter"; }
    }
```

Audit storage uses `entityType`, `entityId`, and `changes[].path`. Audit and outbox
writes join the aggregate's Mongo transaction; do not defer audit until commit.
Without a transaction the audit writer inserts immediately. A no-op with no
reason writes no entry. See the [publisher example](new-event-consumer.md).

## Integration test

Extend [AbstractIntegrationTest](../../src/test/java/com/agilityhub/core/support/AbstractIntegrationTest.java):
it starts a singleton Mongo 7 replica set, selects `test`, disables scheduling,
injects `MockClock` and fixtures, and resets the clock before each test. Use fictional `@example.test` data.
Reset the touched collections and caches in setup. [PlatformIT](../../src/test/java/com/agilityhub/core/platform/persistence/PlatformIT.java)
extends this base; its `parameter(...)` helper and setup supply the excerpt below.

Source: [src/test/java/com/agilityhub/core/platform/persistence/PlatformIT.java](../../src/test/java/com/agilityhub/core/platform/persistence/PlatformIT.java#L60-L85).

```java
    @Test void T_02_06_tenantRepositoryCannotReadReplaceDeleteOrInsertAnotherClubsDocumentById() {
        var original = parameter("parameter-a", "club-a", null, 2);
        assertThatThrownBy(() -> parameters.findById(original.id())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> parameters.insert(original)).isInstanceOf(ApiException.class);
        try (var scope = TenantContext.open("club-a")) {
            parameters.insert(original); parameters.insert(parameter("ring-a", "club-a", "ring-small", 3));
            assertThat(parameters.findAll()).hasSize(2); assertThat(parameters.findById(original.id())).isPresent();
            assertThatThrownBy(() -> parameters.insert(parameter("duplicate-a", "club-a", null, 4))).isInstanceOf(DuplicateKeyException.class);
        }
        try (var scope = TenantContext.open("club-b")) {
            assertThat(parameters.findById(original.id())).isEmpty(); assertThat(parameters.findAll()).isEmpty();
            assertThat(parameters.deleteById(original.id())).isFalse();
            assertThatThrownBy(() -> parameters.insert(original)).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> parameters.replace(original)).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> parameters.replace(parameter(original.id(), "club-b", null, 99))).isInstanceOf(DuplicateKeyException.class);
            parameters.insert(parameter("parameter-b", "club-b", null, 4)); assertThat(parameters.findAll()).hasSize(1);
        }
        try (var scope = TenantContext.open("club-a")) {
            assertThat(parameters.findById(original.id()).orElseThrow().value()).isEqualTo(2);
            parameters.replace(parameter(original.id(), "club-a", null, 5));
            assertThat(parameters.findById(original.id()).orElseThrow().value()).isEqualTo(5);
            assertThat(parameters.deleteById("ring-a")).isTrue();
        }
        System.out.println("clubs indexes: " + mongo.getCollection("clubs").listIndexes().into(new java.util.ArrayList<>()));
        System.out.println("parameters indexes: " + mongo.getCollection("parameters").listIndexes().into(new java.util.ArrayList<>()));
    }
```

The test reopens club A and checks its original data. Cover
read, insert, replace, delete, forged IDs, missing tenant, scoped uniqueness,
timestamps and stale versions where required. Check actual Mongo indexes.
Use the current spec's `T_XX_NN_description` names (Java spelling of `T-xx-nn`).
Existing references: `T_02_06_*` in `PlatformIT` and `T_14_12_*` in
`AuditedAspectIT` for atomic commit, rollback and audit-insert failure.

For implementation changes run `./mvnw -q verify`; domain/application coverage is
at least 85% lines / 80% branches and API coverage at least 70% lines.
