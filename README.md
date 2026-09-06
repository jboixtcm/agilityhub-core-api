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
Only GET health is public outside local; authentication is implemented in a
later roadmap task. Other routes are denied and generated default users are disabled.

## Packages and checks

Each of the 15 bounded contexts has `api`, `application`, `domain`, and
`persistence` packages: `shared`, `platform`, `identity`, `clubs.census`,
`clubs.catalogs`, `clubs.scheduling`, `clubs.activities`, `clubs.bookings`,
`clubs.training`, `clubs.followup`, `clubs.messaging`, `clubs.common`, `courses`,
`payments`, and `migration`. `configuration` contains application wiring.

ArchUnit checks domain independence from web/data/security/servlet APIs,
context cycles (including individual `clubs.*` contexts), access to other
contexts only through `application`, and independence of `shared`.

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
it writes the generated health contract to `docs/openapi/openapi.json`. Full
OpenAPI conventions and CI diff enforcement are subsequent roadmap work.
