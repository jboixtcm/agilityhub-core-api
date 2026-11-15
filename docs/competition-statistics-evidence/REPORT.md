# Competition statistics — API validation

Status: implementation complete; final clean verification is running. This is an executor report, not organizer verification or production acceptance.

## Changes

Added tenant-scoped source adapters, strict FCAG/Flow parsers, exact-licence preview, explicit assignments and atomic confirmation, stale/replayed request protection, member statistics, ownership/module/role guards and privacy export/erasure. The OpenAPI snapshot adds five routes and ten uniquely named schemas; the semantic comparison found no changed or removed existing values. Frontend and backend snapshots are identical.

## Request and boundaries

User-requested implementation on separate `feature/competition-statistics` branches. The isolated checkouts are under `implementation/competition-statistics/`. Existing organizer tasks and verification sections remain unchanged. No production deployment, STATS activation, provider mutation, scheduled synchronization, or live result import was performed.

See [behavior, configuration and limitations](../COMPETITION_STATISTICS.md). FCAG HTTP fetch plus parsing returned 57 rows for 2026–2027. Flow's authenticated browser print layout and `_facom_key` cookie were observed, but a real authenticated server request remains unverified: automatic approval rejected Terminal access for session transfer. No provider session was provisioned or committed. Positive Flow point labels still need a live example; the checked run contained `-`.

## Assumptions and review scope

- This feature uses STATS; SOCIAL_LEAGUE remains separate. New screen copy is proposed in Catalan, Spanish and English because no approved import-screen mockup exists.
- FCAG official net totals are preserved. Flow official points are explicit and never inferred from speed or a clear run.
- Each Flow print page represents one run. Its trial identity uses title plus organizer/date because no trial UUID is exposed; consistent reimport is needed after a provider rename.
- The CSV download is our reviewed interchange template, not a native Flow export claim.
- Task-local test identifiers are T-STATS-01 through T-STATS-32. No existing catalog error, audit or notification code was added.

## Validation

Final command and coverage/test totals will be inserted after the running clean build completes. The complete source fixture tests use fictional identities. The live FCAG probe prints aggregate counts only; no real result rows or browser session values are included in this evidence directory.
