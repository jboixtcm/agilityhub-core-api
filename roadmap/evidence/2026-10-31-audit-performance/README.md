# Audit list performance — 2026-10-31

Jordi reported a slow production `/auditoria`. Local fix on API base `ab0d69f`; no commit, push or deployment performed.

The correlated member-name lookup reads the club census for each audit entry with a missing impersonation id. Use an indexed local/foreign `_id` equality while retaining the tenant predicate. Add audit lists to the existing safe page-enrichment optimization; filters and search retain their necessary computed stages, and membership-changing pipelines retain the general path.

Validation: clean targeted build, 61 unit tests and 53 integration tests, all passing. Four added integration tests cover indexed missing/null lookups, retained snapshots, missing members, 24 optimized/original page comparisons (including member history), and bounded document reads for an impersonated page. Existing suites cover cross-tenant permissions, masking, sparse fields, exports and census lists. See `commands.txt`, the logs, final 40-line tails and `test-summary.json`. The original performance test failed with 40,804 documents examined; it passes after the fix. `git diff --check` passes. Full project CI and deployed HTTP verification remain pending release publication.

Production read-only measurements: the existing HTTP list took 23.337 seconds. Equivalent Mongo pipelines took 15,203 ms before and 62–78 ms after both optimizations, with identical first-page rows/order and total (2,475). The optimized pipeline was executed directly for diagnosis; the live HTTP API is still unchanged. Business data were not modified; ordinary authentication updates login metadata.

Reproduction script, sanitized measurements, current-runtime readback and the isolated source patch are in SOFTWARE_CANIC `ops/evidence/2026-10-31-audit-performance/`. The patch excludes the pre-existing `.gitignore` modification. No HTTP contract or database migration is required.

Jordi subsequently authorized production deployment on 2026-10-31. The scoped publication contains only this fix and its evidence; exact-commit CI and production activation are tracked in SOFTWARE_CANIC.
