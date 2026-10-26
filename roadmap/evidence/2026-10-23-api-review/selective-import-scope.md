> Historical gap analysis. After this review, Jordi explicitly requested both capabilities before the demo. The new implementation and validation are tracked in `../2026-10-23-migration-steps-review/` and the operator runbook `docs/MIGRATION_STEPS.md`. The analysis below records the original gap, not the current capability status.

# S18 selective import: reviewed gap

S18 §6 advertises `migration:playoff ... [--steps 1-10]`. The current CLI rejects `--steps`; the implemented command always validates and applies the complete supported import. This is an unmet operator capability, not an implemented or verified feature. The demo rehearsal can use the existing complete import and does not depend on selective execution.

## Current boundaries

- `PlayoffCommand` accepts a fixed set of options without `steps`.
- `PlayoffPlanner` produces one census plan. A member change combines the person's fields, state, payment/mandate details, plan and billing dates; dog/family references and identity ownership are computed against the same plan.
- `MigrationApplyService.apply` applies census, account/membership linking and member-number reservation within the same transaction. E2-T10's accepted implementation explicitly uses this atomic boundary.
- `PlayoffImportService` then imports receipts and packs in separate transactions of at most 100 records (E8-T06), optionally reconciles the forecast and records one run-level completion/checkpoint. The production reapply guard is run-level, not step-level.
- Future training bookings (S18 step 10) are disabled by the current mapping. Their optional selector is not a hidden implemented stage.

## Implementation required for genuine selection

1. Define the selection grammar and dependencies. Validation must always run. Dogs, family groups, payment/state writes and accounts must resolve a stored or included member. Receipts/packs need both stored/imported members and catalog references; reconciliation requires sufficient billing inputs. Reject unmet dependencies before any writes.
2. Split the census plan into explicit stages or support carefully scoped field patches. Filtering current `Change.entity` values cannot isolate person/state/payment because those fields share the same member write. Splitting must preserve existing merge, email-ownership, future-leave and family atomicity guarantees.
3. Record the selected/completed steps, input manifest and cut-over on the run. Specify how a production continuation of the same manifest differs from a prohibited second load, and how changed manifests or mappings are rejected. A new CLI flag alone would incorrectly close a production run after a partial import.
4. Execute dependency-safe stages with the required transaction boundaries and per-step failure/checkpoint evidence. Preserve the shared lease, reset/write fence and idempotent source IDs, including interrupted-run recovery.
5. Add integration cases for partial selection, missing dependencies, resume after a failed batch, manifest mismatch, dry-run zero writes, repeat idempotence, another tenant unchanged, and production completion/reapply protection. Existing full-import behavior must remain green.

This is a substantive migration-orchestration change. No selector alias or silently ignored partial range was added during the demo-readiness review. An explicit task should carry these acceptance conditions and resolve the step dependencies/production continuation semantics before implementation.

Related specification drift: R-18-14 also mentions `--allow-reapply` with written confirmation. The CLI does not implement that option; its existing one-completed-production-load guard remains enforced, while interrupted loads retain their existing recovery behavior. This production override is likewise not claimed complete and is not needed for a staging/demo rehearsal.
