# E9-T01 - Round 1 executor report (archived verbatim in Round 3)
Complete. Session 1 (08-10) implemented the task; its final clean verify was cut off by the session end (`13`: only the lock wait was logged). Session 2 (09-10, this one) resumed it: it fixed the one failing test that run exposed, applied three findings of an independent self-review, and ran every Verification command to the end.

### Branch / commits
Working tree on `main`; no git-writing command run (only read-only `git status/diff/show/log`). Session 1's work is in `3142435`; the publish script commits session 2's working tree. All local dependencies were verified when selected; E9-W01's two organizer-installed schemas are used unchanged.

### Evidence (full command outputs)
Earlier attempts (one line each, logs kept):
- `01`–`10` (session 1): compile, schema, contract and Mongo attempts; each failure and its correction is listed under «Observed failing checks» below. `11`/`12`/`15`: session 1's snapshot pair, `cmp` exit 0. `13`: clean verify that never started Maven (session ended while waiting for the lock). `14`: sha256, exit 0.
- `16` (session 2) `heavy.sh ./mvnw -q clean verify` → **exit 1**: 3,758 unit tests, 1 failure, `EventCatalogContractTest.E0_T04_everyEventImplementationEmitsACatalogName` (`expected: "Parameter" but was: "Course"`): session 1 registered the `CourseEvent` sample but no branch for it, so it fell into the `Parameter` default. Fix: a `CourseEvent` branch (every `Kind` in the catalog, aggregate `Course`, payload keys). `17`: that test alone, exit 0, 2/2.
- `18` `heavy.sh ./mvnw -q clean verify` → exit 0 (green before the review fixes below; superseded by `24`).
- `19`/`20`: the review fixes. `19` = `./mvnw -q verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='E9PersistenceIT,E9ContractIT' -Djacoco.skip=true` with the fixes → exit 0, E9ContractIT 52/52, E9PersistenceIT 8/8. `20` = the same command with the four fixed main sources temporarily put back to `3142435` (new tests kept) → **exit 1**, the three new assertions fail as expected: `T_16_07_T_16_09_T_16_21_conditionalPermissionsApplyBefore501` (platform POST with `visibility: PRIVATE` → 501, not 400), `T_16_21_courseUploadUrlsCheckTheClubFileParametersBefore501` (`application/zip` → 501, not `FILE_TYPE_NOT_ALLOWED`), `T_16_03_rawMetadataAndHistoryRetainDotsDollarsAndJsonWithoutConversion` (`IllegalArgument … hexString has 24 characters`: the old converter parsed `{"$oid":"bad"}` as extended JSON). The fixed sources were then restored from `target/e9-backup/`.

**Final run.**

1. `/Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh ./mvnw -q clean verify > roadmap/evidence/E9-T01/24-clean-verify.log 2>&1; echo "exit $?"` → **exit 0** (full log `24-clean-verify.log`, 14,493 lines; with `-q` a green run prints no summary). Last 40 lines, verbatim (Testcontainers/Mongo monitor noise and PlayoffMigrationIT's own diagnostics):
```
	at java.base/sun.nio.ch.NioSocketImpl.timedFinishConnect(NioSocketImpl.java:542)
	at java.base/sun.nio.ch.NioSocketImpl.connect(NioSocketImpl.java:592)
	at java.base/java.net.SocksSocketImpl.connect(SocksSocketImpl.java:327)
	at java.base/java.net.Socket.connect(Socket.java:751)
	at com.mongodb.internal.connection.SocketStreamHelper.initialize(SocketStreamHelper.java:76)
	at com.mongodb.internal.connection.SocketStream.initializeSocket(SocketStream.java:104)
	at com.mongodb.internal.connection.SocketStream.open(SocketStream.java:79)
	at com.mongodb.internal.connection.InternalStreamConnection.open(InternalStreamConnection.java:233)
	at com.mongodb.internal.connection.DefaultServerMonitor$ServerMonitor.lookupServerDescription(DefaultServerMonitor.java:219)
	at com.mongodb.internal.connection.DefaultServerMonitor$ServerMonitor.run(DefaultServerMonitor.java:176)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 160 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 159 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 162 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 162 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 160 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 171 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 156 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 160 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 161 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 156 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 159 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 159 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 161 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 158 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 159 PENDING records (more than one dispatch() batch)
E5-T07 outbox backlog discarded before PlayoffMigrationIT: 161 PENDING records (more than one dispatch() batch)
```
   Test summary read from `target/surefire-reports` and `target/failsafe-reports` after that run (`python3 target/e9-summary.py`, exit 0, `25-test-summary.log`, complete):
```
surefire totals: tests=3758 failures=0 errors=0 skipped=0
  ArchitectureTest: tests=22 failures=0 errors=0 skipped=0
  NotificationCatalogContractTest: tests=4 failures=0 errors=0 skipped=0
  E5BackOfficeContractTest: tests=6 failures=0 errors=0 skipped=0
  E9ResponseContractTest: tests=6 failures=0 errors=0 skipped=0
  OpenApiNullableEnumContractTest: tests=3 failures=0 errors=0 skipped=0
  OpenApiRequiredContractTest: tests=3 failures=0 errors=0 skipped=0
  CourseSchemasTest: tests=3 failures=0 errors=0 skipped=0
  ParameterCatalogContractTest: tests=2 failures=0 errors=0 skipped=0
  AuditContractTest: tests=1 failures=0 errors=0 skipped=0
  ParameterValidatorTest: tests=9 failures=0 errors=0 skipped=0
  ErrorCatalogContractTest: tests=6 failures=0 errors=0 skipped=0
  EventCatalogContractTest: tests=2 failures=0 errors=0 skipped=0
failsafe totals: tests=1783 failures=0 errors=0 skipped=0
  E2ContractIT: tests=14 failures=0 errors=0 skipped=0
  E9ContractIT: tests=52 failures=0 errors=0 skipped=0
  E9PersistenceIT: tests=8 failures=0 errors=0 skipped=0
  ListFieldsContractIT: tests=4 failures=0 errors=0 skipped=0
  OpenApiSnapshotTest: tests=10 failures=0 errors=0 skipped=0
  SecurityInventoryIT: tests=1 failures=0 errors=0 skipped=0
  ClubDefinitionsIT: tests=20 failures=0 errors=0 skipped=0
```
   `verify` exit 0 includes the JaCoCo gates (domain+application ≥ 85 % lines / 80 % branches, api ≥ 70 %) and the architecture rules.

2. `heavy.sh bin/openapi-snapshot > roadmap/evidence/E9-T01/21-openapi-first.log 2>&1` → exit 0; `cp docs/openapi/openapi.json target/openapi-first.json`; `heavy.sh bin/openapi-snapshot > roadmap/evidence/E9-T01/22-openapi-second.log 2>&1` → exit 0; `cmp target/openapi-first.json docs/openapi/openapi.json` → **exit 0, no output** (`23-openapi-cmp.log`). Both snapshot logs end with `Updated docs/openapi/openapi.json` / `exit 0` (their last 40 lines are Spring/Testcontainers start-up INFO lines; complete in the files). Session 2's snapshot diff against `3142435` is three operation descriptions (upload-urls, platform POST, platform PATCH); no operation or schema change. The CHANGELOG entry states **42 operations added (359 → 401); 52 schemas added**.
3. `sha256sum src/main/resources/schemas/*.json` → exit 0 (`27-schema-sha256.log`, complete):
```
900c696fb1963939c47a90bb55b239aad636aabbf12811fab7849d0e4d23577e  src/main/resources/schemas/build-session-export.v1.schema.json
50ceb48e2dfb9be21bdfe0878978bde1a6f23167369c9f603f5e5faf1e2b3628  src/main/resources/schemas/course-data.v1.schema.json
```
   = step 8's `50ceb48e…3628` and `900c696f…577e`; the files are unchanged.
4. `git diff --check` → **exit 0, no output** (`28-diff-check.log`). The same check over the whole task (`git diff --check 3142435~1 -- src docs CHANGELOG.md roadmap/tasks roadmap/MESSAGES.md`) → exit 0. Without the pathspec it exits 2 only for trailing whitespace inside session 1's verbatim Maven evidence logs (`[ERROR] ` lines), kept literal on purpose; that output is in the same log.

`mongosh` index listing of the seven collections, printed by `E9PersistenceIT` during run `24` (`26-mongosh-indexes.log`, complete):
```
mongosh --quiet --eval const d=db.getSiblingDB('agilityhub_test'); ["build_sessions","calibration_logs","courses","obstacle_inventories","placements","ring_setups","venues"].forEach(c=>print(c+' '+EJSON.stringify(d.getCollection(c).getIndexes())))
build_sessions [{"v":2,"key":{"_id":1},"name":"_id_"},{"v":2,"key":{"clubId":1,"placementId":1},"name":"session_placement"},{"v":2,"key":{"clubId":1,"status":1,"startedAt":1},"name":"session_cleanup"},{"v":2,"key":{"clubId":1,"joinCode":1},"name":"open_join_code","unique":true,"partialFilterExpression":{"status":{"$in":["NOT_STARTED","IN_PROGRESS"]}}}]
calibration_logs [{"v":2,"key":{"_id":1},"name":"_id_"},{"v":2,"key":{"clubId":1,"ringId":1,"createdAt":-1},"name":"calibration_ring"}]
courses [{"v":2,"key":{"_id":1},"name":"_id_"},{"v":2,"key":{"clubId":1,"deletedAt":1,"updatedAt":-1},"name":"club_deleted_updated"},{"v":2,"key":{"ownerType":1,"visibility":1,"deletedAt":1},"name":"owner_visibility_deleted"},{"v":2,"key":{"ownerAccountId":1,"deletedAt":1},"name":"account_deleted"}]
obstacle_inventories [{"v":2,"key":{"_id":1},"name":"_id_"},{"v":2,"key":{"clubId":1,"scope":1,"ringId":1},"name":"inventory_scope","unique":true}]
placements [{"v":2,"key":{"_id":1},"name":"_id_"},{"v":2,"key":{"clubId":1,"ringId":1},"name":"placement_ring"},{"v":2,"key":{"clubId":1,"courseId":1},"name":"placement_course"},{"v":2,"key":{"clubId":1,"activityId":1},"name":"placement_activity"}]
ring_setups [{"v":2,"key":{"_id":1},"name":"_id_"},{"v":2,"key":{"clubId":1,"ringId":1},"name":"active_setup","unique":true,"partialFilterExpression":{"status":"ACTIVE"}},{"v":2,"key":{"clubId":1,"ringId":1,"builtAt":-1},"name":"setup_history"},{"v":2,"key":{"clubId":1,"status":1,"expiresAt":1},"name":"setup_expiry"},{"v":2,"key":{"clubId":1,"courseId":1,"status":1},"name":"setup_course"},{"v":2,"key":{"clubId":1,"placementId":1,"status":1},"name":"setup_placement"}]
venues [{"v":2,"key":{"_id":1},"name":"_id_"},{"v":2,"key":{"clubId":1},"name":"venue_club","unique":true,"partialFilterExpression":{"clubId":{"$type":2}}},{"v":2,"key":{"slug":1},"name":"venue_slug","unique":true}]
```

Catalog rows edited for the organizer to copy (in `3142435`, unchanged in session 2): `CATALEG_ESDEVENIMENTS.md` «Recorreguts» block (13 rows with §7's payloads) and Annex A's S16 row («vegeu el bloc «Recorreguts»»); `CATALEG_PARAMETRES.md` «Recorreguts» (`courses.defaultWarningThresholdM` · decimal · 1.5 · min 0 · COURSES replaces `courses.placementMarginMeters`) and Annex A's S16 row (`courses.buildSessionMaxHours` only); S14 R-14-09's «(S16; …)» group with `COURSE_FORCED_PLACEMENT` (E9-T03), `RING_GEOMETRY_CHANGED` (E9-T02), `RING_SETUP_REGISTERED` (E9-T03).

### Session 2 changes (self-review, 4b)
An independent read-only review of `3142435` against Steps 1–8 found no defect in routes, roles, modules, idempotency headers, impersonation, schemas, indexes, events, audit actions, parameters or `CourseSchemas`. It raised seven points. Fixed (each with a test seen failing on the old code, `20`):
1. `CourseJsonConverter` wrote through `Document.parse` (extended JSON): an object whose first key is `$date`, `$oid` or `$numberLong` became a BSON date, or failed with a 500. That broke A9's exact round trip for `normalizedJson`, `rawMetadata` and `progressJson`. It now copies the Jackson tree node by node (objects → `Document`, arrays → lists, int/long/double, exact decimals → `Decimal128`) and reads it back the same way. `E9PersistenceIT.T_16_03_…` adds `$date` (plain and first-key), `$numberLong`, `{"$oid":"bad"}` and an exact `0.10`.
2. `PATCH /platform/courses/{id}` required `agilityhubLevel` on every call. R-16-14 requires it when the course is created; an omitted level now keeps the stored one (501). Both library writes refuse a `visibility` other than `PUBLIC` with `400 VALIDATION_ERROR` (R-16-14: «`visibility PUBLIC`»). The platform POST fixture now sends `PUBLIC`.
3. `POST /courses/upload-urls` checked only Bean Validation before the stub. It now applies `files.allowedTypes` (`FILE_TYPE_NOT_ALLOWED`) and `0 < size ≤ files.maxSizeMb` (`FILE_TOO_LARGE {maxSizeMb}`, the existing published `FileTooLargeDetails`), the same matching as `AttachmentService`. Per-purpose MIME rules (`text/plain` only for `SMARTER_SOURCE`) stay E9-T02's, as step 1 says. New test: `E9ContractIT.T_16_21_courseUploadUrlsCheckTheClubFileParametersBefore501`.

Left as they are, for the organizer and the next tasks:
- `GET /rings/{id}/setups` takes `size` 1–1000 with `VALIDATION_ERROR`, as the existing non-universal page `GET /me/invoices` does. R-16-08's history page is not a universal list (no filter, sort or `appliedFilters`), so CONVENCIONS §4's `{20, 50, 200, 1000}` / `INVALID_FILTER` is not applied.
- `VenueRepository` is tenant-scoped, so a venue without a club (`clubId?`, partial index) cannot be read through it. No R1 route uses venues; E9-T02 creates the club's venue and decides how unattached venues are read.
- `POST /challenges/{id}/attempts` checks that `dogId` belongs to the club, not to the member. The route is an R2 reservation; ownership belongs to S19's behaviour.

### Files changed
- `courses/api/`: eight controllers (42 operations), `CourseContracts`, `CourseRequests`. Club and platform lists, staff/member projections, declared errors, module/role guards, five impersonation routes, PDF/SSE/export media types and idempotency headers.
- `courses/application/`: `CourseContractGuards`, `CourseListContract`, `CourseSchemas`, `ports/CourseReferencePort`; `configuration/CourseReferenceConfiguration` supplies scoped activity/dog existence reads. `configuration/E9ContractConfiguration` handles impersonation, nullable refs and pinned planner schemas.
- `courses/domain/`: `CourseParts`, `CourseTypes`, `AgilityHubLevel`, planner `ObstacleType`/`WarningRule`/`WarningSeverity`, `CourseEvent` (13 kinds).
- `courses/persistence/`: seven records and repositories, read-only `GlobalCourseRepository`, `CourseTenantRepository`, `CourseHistory`, lossless JSON-key and date-only converters, and field-only `RingGeometryRepository`. Unique/partial indexes created by the repositories.
- `shared/api/IdempotencyFilter`, `shared/persistence/IdempotencyRepository`: platform course creation uses account + null club scope, retaining role checks before replay and error rollback/claim release. A tenant scope cannot claim/read a null-scope record. `identity/api/CurrentUserFilter`: S16's explicit impersonation denial precedes validation of a tenant-only grant on platform course paths; other paths retain existing behavior.
- `parameters/catalog.yaml`, `fixtures/parameters-canic.json`, `CATALEG_PARAMETRES.md`: E77 warning threshold rename (decimal, 1.5, min 0), two superseded clearance parameters removed. Other defaults unchanged.
- `CATALEG_ESDEVENIMENTS.md`: 13 individual S16 payload rows, Annex A points to them. `platform/api/AuditContracts` and S14 R-14-09: three wire audit actions, with real writers explicitly deferred to E9-T02/T03.
- Tests/fixtures: `CourseSchemasTest`, `E9ContractIT`, `E9PersistenceIT`, `E9ResponseContractTest`, `E9Fixtures`, `fixtures/contracts/e9-*.json`, `fixtures/courses/*.json`; existing `EventCatalogContractTest`, `ParameterValidatorTest`, `OpenApiRequiredContractTest`, `E5BackOfficeContractTest`, `ListFieldsContractIT`, `ArchitectureRules`/`ArchitectureTest` extended.
- `docs/openapi/openapi.json`, `docs/openapi/CHANGELOG.md`, root `CHANGELOG.md`, this report, `roadmap/MESSAGES.md`, generated `roadmap/STATUS.md`, evidence logs. No other task or Organizer verification section changed.
- Session 2: `courses/persistence/CourseJsonConverter.java` (node-by-node BSON copy), `courses/api/PlatformCoursesController.java` (R-16-14 level on create, `PUBLIC` only), `courses/api/CoursesController.java` + `courses/application/CourseContractGuards.java` (`upload(contentType, size)` with `files.*`); tests `EventCatalogContractTest` (CourseEvent branch), `E9ContractIT` (upload test, platform level/visibility cases), `E9PersistenceIT` (extended-JSON keys), fixture `e9-routes.json` (platform POST `visibility: PUBLIC`); `docs/openapi/openapi.json` (three descriptions), `docs/openapi/CHANGELOG.md`, `CHANGELOG.md`; evidence `16`–`28`.

### Rules and tests implemented (R-xx-nn / T-xx-nn)
- R-16-01, T-16-03: pinned local draft-07 course/export schemas, 256 KiB byte bound, required/unknown fields and unknown obstacle rejection, unsupported version rejection, structured `schemaErrors`. Both POST/PATCH course writers call validation before 501. Java response/request shapes, explicit nulls, all enums and published schema properties are checked; planner schemas and warning shape are compared directly with the committed files.
- R-16-03/14/16, T-16-05/21: all 42 operations × five identities, host mismatch, missing membership, module/role checks, visibility/author rules, cross-tenant path/body/query references, global course isolation, real impersonation grants on exactly five routes. State comparison includes all seven new collections, rings, reference fixtures, events, audits and idempotency records.
- R-16-05/07/09, T-16-07/09/12/19: forced placement ADMIN-only, instructor publishing parameter, member setup visibility parameter, COURSES module disabled before body parsing, missing geometry/build sheet, malformed scope/body and global level validation. Optional model/references remain optional for this contract phase.
- R-16-06/07/11: seven tenant repository round trips; forged tenant writes fail, physical deletion prohibited; real Mongo partial uniqueness for active setup/open join code and uniqueness for inventory/venue, including release only on closed statuses; every declared index inspected with `mongosh`. Independent geometry/catalog versions survive edits in both directions and reject stale geometry writes. Course rawMetadata dot/dollar keys and history round-trip; JSON objects/arrays/null and DST-boundary calendar dates preserve their values.
- R-16-10/11 and T-16-10: endpoint/error/export/SSE contract only; concurrency, stream behavior and session lifecycle remain E9-T04. Catalog `BUILD_SESSION_FINISHED` stays 422.
- R-16-12/13/15/17: inventory, setup and upload forms plus catalog/parameter contract; business behavior remains E9-T02…T04. R-16-08: history page contract. T-16-13: N-31 fixture checks event, variables and OPEN_SETUP against code/catalog, without sending. T-19-01: four reserved challenge routes remain guarded 501 with no challenge collection.
- S14 R-14-09/T-02-03: three published audit action names agree with the approved row; no uncovered runtime AuditAction added. Architecture tests restrict courses dependencies and prevent clubs importing courses. Existing security, list-fields, nullable/required OpenAPI and catalog checks cover the additions.
- Observed failing checks before correction: planner export fixture rejected fewer than six markers and an empty fallback chain; snapshot check rejected absent S16 paths; route matrix rejected tenantless platform idempotency; impersonation test found the generic platform FORBIDDEN; Mongo round trips rejected explicit JSON null. These failures and the corrections are retained in separate attempt logs. Fixture-only mistakes (missing Parameter history/version semantics, missing sheet on a happy path, POST-only query fixture, nullable route body) are also recorded; no invented API mock responses are used.
- Session 2: R-16-14 / T-16-21, `T_16_07_T_16_09_T_16_21_conditionalPermissionsApplyBefore501` (platform POST without level → 400, PATCH without level → 501, `PRIVATE`/`CLUB` → 400 on both). R-16-16 / T-16-21, `T_16_21_courseUploadUrlsCheckTheClubFileParametersBefore501` (`application/zip` → `FILE_TYPE_NOT_ALLOWED`; size 0 and 25 MiB + 1 → `FILE_TOO_LARGE`, `details.maxSizeMb = 25`; `image/png` at exactly 25 MiB → 501). A9 / T-16-03, `E9PersistenceIT.T_16_03_rawMetadataAndHistoryRetainDotsDollarsAndJsonWithoutConversion` (extended-JSON keys). E0-T04's `EventCatalogContractTest` now asserts the `CourseEvent` branch. All three new assertions were seen failing on `3142435`'s sources (`20`); the event-catalog one failed in `16`.

Final diff checklist: real MockMvc/Mongo fixtures (no invented API mocks); every route has tenant/role coverage; no UI, drafts or client caches in scope. Geometry updates compare their own expected version and preserve S05 fields. Calendar dates persist as date-only strings and instants as UTC. Catalog changes are only E77's. All evidence attempts are separate files; no log ignore rule was added. Only E9-T01, its generated status and the append-only messages changed under the roadmap; Organizer verification and ROADMAP are byte-unchanged.

Error-status audit (no enum/message changes):

| Status | Codes |
|---|---|
| 400 | COURSE_MODEL_INVALID, SCHEMA_VERSION_UNSUPPORTED, FILE_TYPE_NOT_ALLOWED, FILE_TOO_LARGE, VALIDATION_ERROR |
| 403 | FORBIDDEN, IMPERSONATION_DENIED |
| 404 | COURSE_NOT_VISIBLE, NOT_FOUND, MODULE_DISABLED |
| 409 | STALE_VERSION, INVALID_STATE, COURSE_IN_USE, RING_GEOMETRY_IN_USE |
| 422 | GEOMETRY_INVALID, PLACEMENT_BLOCKED, RING_WITHOUT_GEOMETRY, PLACEMENT_RING_MISMATCH, SETUP_SOURCE_REQUIRED, INVENTORY_INVALID, BUILD_SESSION_FINISHED |
| 501 | NOT_IMPLEMENTED |

### Assumptions
- E96/task rulings applied: §6 `model` is `normalizedJson` + `schemaVersion`; the ring inventory is its separate scoped document; GET calibrations added beside POST; DELETE versions are query parameters; MEMBER visibility is limited to the contract's tenant/parameter/course checks until E9-T03; stored file keys have URL projections; marker-sheet remains a PDF route pending E9-T02.
- Task/S16 §14.2 planner spellings win where Annex B is stale: warning keys and `info/warning/critical` replace `{code, WARN/BLOCK}`; `NOT_PLACED/CURRENT/PLACED/SKIPPED/NEEDS_CHECK` replace `PENDING/PLACED/VERIFIED`; build status is `NOT_STARTED/IN_PROGRESS/COMPLETED/ABANDONED`. Warning/obstacle JSON spellings are taken from the committed schemas, while core enums use upper snake case.
- Annex B's flat placement transform names are grouped in `transform` as explicitly required by this task; storage uses `warningsJson`, wire uses `warnings`. File URLs remain projections; `Placement.buildSheetFileKey` and `BuildSession.buildSheetFileKey` are stored. Course history carries both version and schemaVersion; the ten-entry cap belongs to the future write behavior.
- Geometry keeps doors/no-go zones/AprilTag markers and its own version under `rings.geometry`; no duplicate `Ring` model or catalog-version ownership. `origin` is the planner frame, not a geometry field. `Venue` has no R1 route; its partial unique club index permits unattached venues. Audit timestamps/actors are stored in all seven records as the task requires.
- Course name writes use locale maps under CONVENCIONS_API §5; responses keep resolved `name` plus editable `nameI18n`. The existing census upload schemas already own `UploadUrl`/`UploadUrlRequest`; the S16 Java records retain those short names, but publish as `CourseUploadUrl`/`CourseUploadUrlRequest` to preserve both incompatible contracts. Snapshot-to-DTO property tests prevent this collision from silently returning the census shape.
- `levelIds` are club references (S16 §3); nonempty `levelIds` on a tenantless platform write return 404, while `agilityhubLevel` is the global enum.
- Default course list sort is `updatedAt,desc`; fields use the universal list contract. Global idempotency uses a real null tenant rather than a synthetic club and releases 501 claims. Business processing, transactions/events for actual course changes, schedulers, training adapter, notification delivery, signed URLs and SSE query tokens are outside this task.
- No error enum/message changes: all audited codes already matched the closed catalog. No new unapproved parameter/event/notification/error was invented. No local schema file was edited.
- Session 2: R-16-14's «`visibility PUBLIC`» is enforced as a body validation (`400 VALIDATION_ERROR`) on both library writes rather than silently overridden, and «`agilityhubLevel` obligatori» is checked on creation only; a PATCH that omits it keeps the stored level. `upload-urls` applies the club's `files.allowedTypes`/`files.maxSizeMb` now (R-16-16); the `text/plain`-only-for-`SMARTER_SOURCE` and image-only purposes remain E9-T02's, as step 1 writes.

### Questions / catalog proposals
Filed three nonblocking entries in `roadmap/MESSAGES.md` for @organizer:
1. Align MATRIU_PERMISOS: MEMBER collection listing is forbidden by S16/E96; visible detail reads remain allowed.
2. Align S16 §6/T-16-10 with catalog 422 for BUILD_SESSION_FINISHED, or approve an explicit §1 exception for 409. Current implementation follows 422.
3. Copy the approved E77 catalog/audit rows to source/web and align Annex B with the differences above. Resolve A8b versus marker-sheet API in E9-T02. No extra routes proposed or implemented.

Session 2 added no new proposal. The three points left as they are (see «Session 2 changes»: the setup-history page size, clubless venues, the challenge dog's owner) are notes for E9-T02/E9-T03/S19, not catalog changes. Commands denied by this session's sandbox (none needed for the task): a `python3` heredoc containing quoted `{…}` and two shell lines with `$VAR` expansion (a `for … tail` over the logs, a `B=… cp` backup) were refused by the permission rules; equivalent commands without them were used.

