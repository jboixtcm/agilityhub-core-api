# Dog photo visibility — 2026-10-30

Return signed photo URLs in dog list projections, member dog summaries and member overviews. Sign only the requested page; do not query each dog separately. Sparse field selection, tenant and role restrictions remain in force. The existing short-lived attachment signature and file bindings are preserved. The API change does not write census data or attachments.

The OpenAPI snapshot was regenerated with `bin/openapi-snapshot` (exit 0). Focused clean verification passed 54 unit/architecture/contract checks and 107 integration tests; see `focused-tests.json`. The new CensusIT regression checks live photo upload/binding, signed bytes, sparse fields, missing photos, nested member dogs, tenant isolation, MEMBER denial and expiry. Full clean coverage and release checks remain an exact-commit CI gate.

An earlier focal run failed only export x-fields metadata after adding the list field. Both dog export operations now advertise the same x-fields as their list; regenerated snapshot and the final contract test pass. No production migration is included.
