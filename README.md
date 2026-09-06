# AgilityHub Core API

Spring Boot 3.5 modular monolith, Java 21, Maven 3.9.16 and MongoDB 7
(replica set). See `AGENTS.md` and `roadmap/README.md` for the task protocol.

## Run locally

Prerequisites: Docker with Compose v2.20+ and `curl`. From the repository root:

```sh
cp .env.example .env
docker compose up -d --wait
curl -fsS localhost:8080/api/v1/health
docker compose exec mongo mongosh --quiet --eval 'rs.status().myState'
docker compose ps
```

Compose builds the API with the pinned Maven wrapper and Java 21, then runs it
as a non-root user on `eclipse-temurin:21-jre`. MongoDB 7 initializes the
single-node `rs0` replica set and must become PRIMARY before the API starts.
Expect health JSON with `status: "UP"`, replica-set state `1`, and both services
healthy. Health also returns the Maven project `version` and UTC `builtAt`
timestamp. It is a public, global liveness check and does not query a tenant or
MongoDB; the separate Mongo healthcheck checks PRIMARY readiness.

Compose reads `.env` automatically; all development values have defaults in
`compose.yaml`, so the file is optional. `.env.example` documents the variables.
Compose fixes the profile to `local` and the database host/port to `mongo:27017`;
`SERVER_PORT`, `MONGODB_PORT` (published host port), `MONGODB_DATABASE`, and
`MONGODB_REPLICA_SET` are configurable. Adjust the curl port if needed. The
unauthenticated development services are published only on `127.0.0.1`.
Credentials, `.env`, and unrelated workspace files are excluded from the
Docker build context.

`docker compose down` stops the stack and preserves the named `mongo-data`
volume. Run `docker compose up -d --build --wait` after source changes. Keep the
replica-set name stable when reusing a volume.

To run the API in your IDE or with Maven, install JDK 21, `curl`, and `unzip`
(or PowerShell on Windows), then use:

```sh
docker compose up -d --wait mongo
./mvnw -q spring-boot:run
```

Stop an already running Compose API first with `docker compose stop api` to
free port 8080. Spring Boot does not read `.env` automatically; export any
nondefault variables for host execution. The local URI uses `directConnection=true`
so the host does not need to resolve the replica member's Compose hostname.

## Profiles

| Profile | MongoDB | Configuration |
| --- | --- | --- |
| `local` (default) | Enabled; development MongoDB 7 replica set | Defaults to `localhost:27017/agilityhub`, `rs0`; Compose supplies `mongo` as the host. `/v3/api-docs` is available. |
| `test` | Enabled; MongoDB 7 replica set | Integration tests inject a Testcontainers URI for `agilityhub_test`. `MONGODB_TEST_URI` is for manual execution. MVC slice tests need no database. |
| `staging` | Enabled; MongoDB 7 replica set required | Set `MONGODB_HOST`, `MONGODB_DATABASE`, `MONGODB_USERNAME`, `MONGODB_PASSWORD`; see `.env.example` for port, replica-set and authentication-database defaults. |
| `prod` | Enabled; MongoDB 7 replica set required | Same required variables as staging. |

Activate one profile with `SPRING_PROFILES_ACTIVE`. Virtual threads are enabled
in every profile. Configure `SERVER_PORT` if port 8080 is occupied.
GET health is global and public. GET `/api/v1/branding` and
`/api/v1/manifest.webmanifest` are public per verified club host. Other routes require a validated bearer JWT; generated default users are disabled. The `local` profile alone accepts
`X-Club-Host` to override `Host`. Tenant context comes from an authenticated JWT
claim when present; a different known host returns `TENANT_MISMATCH`.

## Packages and checks

Each of the 15 bounded contexts has `api`, `application`, `domain`, and
`persistence` packages: `shared`, `platform`, `identity`, `clubs.census`,
`clubs.catalogs`, `clubs.scheduling`, `clubs.activities`, `clubs.bookings`,
`clubs.training`, `clubs.followup`, `clubs.messaging`, `clubs.common`, `courses`,
`payments`, and `migration`. `configuration` contains application wiring.

ArchUnit checks domain independence from web/data/security/servlet APIs,
context cycles (including individual `clubs.*` contexts), access to other
contexts through `application`, and independence of `shared`. Shared domain
values and the `TenantRepository` / `GlobalRepository` base classes are also
explicit cross-context contracts; other shared persistence/API internals remain
private. Mongo-mapped records live in `persistence`, keeping domain classes free
of Spring Data dependencies.

## Run tests

JDK 21 is required. `verify` also requires a running Docker engine: integration
tests fail if Docker is unavailable. The Compose stack is not required for
tests; Testcontainers creates an isolated MongoDB 7 replica set on a random
port and cleans it up after the test JVM exits. The Maven wrapper downloads its
pinned distribution on the first run.

```sh
./mvnw -q test       # Unit, MVC slice, and architecture tests; no Docker needed.
./mvnw -q verify     # Also runs *IT integration tests and checks coverage.
./mvnw -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage
bin/openapi-snapshot
python3 roadmap/tools/check.py --render
```

`verify` produces Surefire results in `target/surefire-reports`, Failsafe
integration results in `target/failsafe-reports`, and combined JaCoCo HTML in
`target/site/jacoco/index.html`. Each domain/application package needs
85% line and 80% branch coverage; each API package needs 70% line coverage.
Empty domain/application packages do not yet contribute executable code.
The gates include nested packages and fail `verify` below either threshold.
Persistence coverage is reported without a minimum. Report and gate analysis
exclude `*Config`, `*Configuration`, `*Application` (including nested classes),
and generated code under a `generated` package. Put generated DTOs there;
handwritten DTOs remain included.
Mutation testing is opt-in and does not run during normal verification.

Extend `com.agilityhub.core.support.AbstractIntegrationTest` for full Spring
tests with the `test` profile. All subclasses share one static MongoDB container
and can reuse the Spring context. The container uses the
[Testcontainers singleton lifecycle](https://java.testcontainers.org/test_framework_integration/manual_lifecycle_control/#singleton-containers),
so it is not stopped between test classes. Tests must clean up their own
collections; the base does not drop databases used by other tests.

An injected `Clock` resolves to the primary `MockClock`, reset to
`2026-01-01T00:00:00Z` before each test. Use `clock.setInstant(...)` or
`clock.advance(Duration...)` to control time. Keep tests sequential when sharing
the clock and database. The injected `Fixtures` helper reads typed objects or
lists from JSON resources with `fixtures.read(path, Type.class)` and
`fixtures.readList(path, Type.class)`. Fixtures belong in `src/test/resources/fixtures`
and contain fictional data only. `MongoTransactionSmokeIT` exercises commit and
rollback using the application's `MongoTransactionManager`.

Dependency versions are fixed by the Spring Boot 3.5.16 parent or explicit
properties in `pom.xml`; the Maven wrapper also checks its distribution SHA-256.

`bin/openapi-snapshot` requires the local API running on port 8080 and Python 3;
it writes the generated API contract to `docs/openapi/openapi.json`. The CI
diff hook runs when the snapshot exists; automatic contract generation before
that hook and full OpenAPI conventions belong to E0-T12.

## Continuous integration and branch protection

`.github/workflows/ci.yml` runs on every push to `main`, pull request targeting
`main`, and manual dispatch. **Build and tests** uses Ubuntu, Temurin 21,
Maven caching, and the runner's Docker engine for Testcontainers. It runs
`./mvnw -B verify`, including architecture checks and coverage gates, and
uploads `jacoco-report` for 14 days whenever HTML exists, including on coverage
failure. **Secret scan** checks Git history with Gitleaks independently of the
build. Actions are pinned to commit SHAs; Dependabot checks Maven dependencies
and GitHub Actions weekly.

To reproduce the secret scan locally, install Gitleaks 8.30.1 and run
`gitleaks detect --redact` from the repository root. Add
`gitleaks detect --no-git --redact` to scan uncommitted working-tree files.
The action receives GitHub's automatic `GITHUB_TOKEN` with read-only repository
permissions and has PR comments disabled. For an organization-owned repository,
Jordi must add the `GITLEAKS_LICENSE` Actions secret; personal repositories do
not require it. See the [Gitleaks Action configuration](https://github.com/gitleaks/gitleaks-action#environment-variables).

After the first published CI run, Jordi must configure a branch protection
rule for `main` in **Settings → Branches** (see
[GitHub's branch protection instructions](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/managing-a-branch-protection-rule)):

1. Require a pull request before merging and **1 approving review**.
2. Require status checks to pass before merging; select **Build and tests**
   and **Secret scan** from the CI workflow.

The executor does not change repository settings. The current publish script
pushes directly to `main`; Jordi must reconcile its access with this protection
rule (PR publishing or an explicitly managed bypass).

## Club configuration and country data

`ParameterCatalog` loads `src/main/resources/parameters/catalog.yaml`. Its 146
parameter keys include the expanded grouped rows and ten named jobs from Annex A.
The document's three Club-backed display rows are retained as `clubBindings`;
`leave.reasons` and `files.allowedTypes` Annex amendments replace/extend their
original entries. `T_02_03` independently parses the Markdown and compares the
keys, types, source defaults, and resolved typed defaults. Unspecified prose
references have `null` defaults pending catalog clarification (E0-T05 report).

`ClubConfigService` returns deeply immutable values and a Club view containing no
payment secrets. Configuration and host caches expire after five minutes and
are invalidated by outbox handlers for `ClubUpdated`, `ClubCreated`,
`ClubStatusChanged`, `ClubModulesChanged`, and `ParameterChanged`. Invalid stored
parameter overrides are ignored and logged as `ParameterInvalidOverride`, without
logging their potentially private values. Public branding/manifest responses
have a content ETag and a 60-second HTTP cache lifetime.

Tenant repositories require `TenantContext`, including reads/writes by ID.
Trusted background work opens a scope with `try (var scope =
TenantContext.open(clubId))`. The outbox dispatcher/publisher use global
infrastructure access because they handle records across tenants (and global
events). Parameter uniqueness is `(clubId, key, scopeRef)`: a null scope is the
club override, while ring/level overrides can coexist. Club slug and domain host
indexes are globally unique and created at startup.

The Spanish postal lookup uses the complete [GeoNames ES postal-code dataset](https://download.geonames.org/export/zip/ES.zip),
provided by [GeoNames](https://www.geonames.org/) under
[Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/).
Downloaded 2026-09-06; source archive SHA-256:
`90f4771d26e5956834e9eb49bf48acad2302d4a44a2a32c6eda23d9d3b826b35`.
`src/main/resources/country/es/postal-codes.csv` retains postal code, town and
region, sorted and deduplicated (37,867 rows); coordinates and other fields are
omitted. Display converts interior `De`/`Del` particles to `de`/`del`, including
`08349` → `Cabrera de Mar`. Source spelling is otherwise preserved. `GENERIC`
has no postal lookup or national document/IBAN validation; phones must be E.164.
The ES profile uses Apache Commons Validator 1.11.0 for country-specific IBAN
length/pattern and mod-97 checks, based on the SWIFT registry; see its
[IBANValidator documentation](https://commons.apache.org/proper/commons-validator/apidocs/org/apache/commons/validator/routines/IBANValidator.html).

## Auth for local development

E0 supports `POST /oauth2/token` with `password` and `refresh_token`, public
`GET /oauth2/jwks` (also `/.well-known/jwks.json`), and authenticated
`GET /api/v1/me`. Spring Authorization Server supplies the token endpoint,
grant converter/provider integration, response handler, and JWKS filters.
The first-party public clients are `clubs-app` and `clubs-admin`; omitted
`client_id` defaults to `clubs-app` for the local/task curl contract. Other
clients and scopes are rejected until the E1 client-registration/OIDC work.

First create a club with a verified host (club-as-code arrives in E0-T10).
Then seed accounts for its slug; the temporary ApplicationRunner runs during
startup and leaves the local API running:

```sh
export SEED_PASSWORD='<choose a local test password>'
./mvnw -q spring-boot:run -Dspring-boot.run.arguments='identity:seed-test-accounts --club=<existing-slug>'
```

Only `local`/`test` expose this command. It creates `admin@example.test`,
`instructor@example.test`, and `member@example.test` with argon2id passwords;
repeat runs preserve existing credentials and memberships. Admin/instructor
memberships also have `MEMBER`. Member IDs remain empty pending census setup.
`SEED_PASSWORD` has no default and is never printed. The CLI dispatcher will
replace this temporary runner in E0-T10.

```sh
curl -s localhost:8080/oauth2/token \
  -H 'X-Club-Host: app.example.test' \
  -d grant_type=password -d client_id=clubs-app \
  -d username=admin@example.test --data-urlencode "password=$SEED_PASSWORD"
curl -s localhost:8080/api/v1/me \
  -H 'X-Club-Host: app.example.test' -H "Authorization: Bearer $ACCESS_TOKEN"
curl -s localhost:8080/oauth2/token \
  -H 'X-Club-Host: app.example.test' \
  -d grant_type=refresh_token -d client_id=clubs-app \
  --data-urlencode "refresh_token=$REFRESH_TOKEN"
```

Set `ACCESS_TOKEN` and `REFRESH_TOKEN` from the sign-in response. Access JWTs
expire after 15 minutes, use RS256 with a public-key thumbprint `kid`, and
carry account/tenant/role/profile/locale claims. `/me` selects explicit public
fields and rechecks the current account and membership. A known different
host returns `TENANT_MISMATCH`; an unknown gateway host retains the existing
JWT tenant fallback. `X-Club-Host` is accepted only under `local`.

Refresh tokens contain 32 random bytes; Mongo stores only SHA-256 hashes.
Each use atomically consumes and replaces the token within a Mongo transaction.
Reusing a rotated token revokes its family. The initial expiry uses the catalog
parameter `auth.sessionDays`; rotations retain that expiry. Sliding sessions,
lockout policy, full OIDC and impersonation remain E1 work.

`AUTH_JWK_PEM` supplies the RSA private key through the deployment environment.
The key must be at least 2048 bits; production/staging startup fails without it.
Local/test generate an ephemeral key when unset, so restarting invalidates old
access JWTs. `AUTH_ISSUER` defaults to the S01 issuer. JWKS exposes public fields
only. OAuth failures use the same localized `{code,message,details,traceId}`
contract as API errors. Missing/invalid access tokens return 401.

Password verification accepts Learn bcrypt `$2y$`, `$2a$`, `$2b$` hashes.
New hashes use the [OWASP argon2id minimum](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
(19 MiB, two iterations, one lane, 16-byte salt). Missing/passwordless accounts
perform a dummy argon2id verification; legacy bcrypt and argon2id costs differ,
so this removes the missing-account fast path without promising identical
wall-clock timing across algorithms. The PHP fixture was generated with
`password_hash("Learn-fixture-password", PASSWORD_BCRYPT, ["cost" => 12])`
using the local `laravelsail/php83-composer` PHP runtime; it contains fictional
test credentials only.
