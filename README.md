# AgilityHub Core API

Spring Boot 3.5 modular monolith, Java 21, Maven 3.9.16 and MongoDB 7
(replica set). See `AGENTS.md` and `roadmap/README.md` for the task protocol.

## Start in five commands

Prerequisites: JDK 21, `curl`, and `unzip` (or PowerShell on Windows). The Maven
wrapper downloads the pinned Maven distribution on its first run. From this
repository's root:

```sh
java -version
cp .env.example .env
./mvnw -q verify
./mvnw -q spring-boot:run
curl -fsS localhost:8080/api/v1/health
```

Run the fifth command in a second terminal. Stop the API with Ctrl-C. Health
returns `status`, the Maven project `version`, and the UTC `builtAt` timestamp
from generated Spring Boot build-info. It is a public, global liveness check
and does not query a tenant or MongoDB.

`.env` is a template for deployment tools; Spring Boot does not read it
automatically. Export the variables you need in your shell or configure them
in your runtime. Do not commit credentials.

## Profiles

| Profile | MongoDB | Configuration |
| --- | --- | --- |
| `local` (default) | Auto-configuration excluded for this skeleton | Health runs without Docker; `/v3/api-docs` is available for the initial snapshot. |
| `test` | Enabled; MongoDB 7 replica set | `MONGODB_TEST_URI` has a localhost test-database default. MVC slice tests need no database. |
| `staging` | Enabled; MongoDB 7 replica set required | Set `MONGODB_HOST`, `MONGODB_DATABASE`, `MONGODB_USERNAME`, `MONGODB_PASSWORD`; see `.env.example` for port, replica-set and authentication-database defaults. |
| `prod` | Enabled; MongoDB 7 replica set required | Same required variables as staging. |

Activate one profile with `SPRING_PROFILES_ACTIVE`. Virtual threads are enabled
in every profile. Configure `SERVER_PORT` if port 8080 is occupied. The local
Mongo exclusions are temporary until E0-T02 supplies the development stack.
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

```sh
./mvnw -q test
./mvnw verify
./mvnw -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage
bin/openapi-snapshot
python3 roadmap/tools/check.py --render
```

`verify` produces Surefire results in `target/surefire-reports` and JaCoCo
HTML in `target/site/jacoco/index.html`. Each domain/application package needs
85% line and 80% branch coverage; each API package needs 70% line coverage.
Empty domain/application packages do not yet contribute executable code.
Mutation testing is opt-in and does not run during normal verification.

Dependency versions are fixed by the Spring Boot 3.5.16 parent or explicit
properties in `pom.xml`; the Maven wrapper also checks its distribution SHA-256.

`bin/openapi-snapshot` requires the local API running on port 8080 and Python 3;
it writes the generated health contract to `docs/openapi/openapi.json`. Full
OpenAPI conventions and CI diff enforcement are subsequent roadmap work.
