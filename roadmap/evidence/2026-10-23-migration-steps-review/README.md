# Explicit migration stages and production reapply — 2026-10-23

Jordi explicitly requested both previously missing operator capabilities before the first demo. The earlier full clean verification was stopped after the scope expanded; its passing partial totals are not a final gate for these sources. No real migration, approval, provider send or deployment is performed.

Implementation and review lanes: parent CLI/orchestration/runbook; API agent census field selection and interruption integration tests; release agent execution lineage, approval validation and persistence; web agent independent read-only review.

Runbook: `docs/MIGRATION_STEPS.md`. The focused checks below will precede a new complete clean verification and new local release image. Final command logs, exits, summary and source inputs will be recorded here as they finish.

## Focused validation

All commands run under the shared `heavy.sh` lock. `run-focused.py` records exact command files, logs, exit codes, fresh selected-class XML summaries and a `*-tested-inputs.json` source/test/seed fingerprint; it refuses to replace prior evidence and detects input changes during a run.

| Evidence | Result |
|---|---|
| `01-test-compile` | Exit 0. |
| `02-units` | Exit 0; 60 tests, 0 failures/errors/skips across 7 selected classes. |
| `03-integration` | Exit 1; 23 tests with 3 test-fixture errors. Cross-tenant test opened a conflicting nested scope; terminal-failure Mockito setup called the audited proxy outside a transaction and contaminated one later test. |
| `04-test-compile`, `05-integration` | Both exit 0 after correcting the trusted test scope and stubbing the ultimate target while retaining real audited execution. 23 integration tests pass: execution 7, staged import 10, report HTTP 4, checkpoint 2. No application change was needed for those fixture errors. |
| `07-test-compile`, `08-integration` | Compile exit 0; staged suite 11 tests with 1 failure in the new alias regression. The fixture's new member number sorted before its existing principal, so the complete planner correctly refused an unsupported transition before the dependency assertion. |
| `10-test-compile`, `11-integration` | Compile exit 0; the corrected alias ordering reaches and passes both missing-step guards. The successful pack path then exposes missing required fields in the test's catalog Plan seed (1 fixture error among 11 tests); this is corrected with the complete defaults used by the existing successful pack migration tests before the next run. |
| `13-test-compile`, `14-integration` | Both exit 0; all 11 staged integration tests pass, including new-alias refusal before writes, successful receipt/pack import when step 3 is selected, partial batch recovery, terminal audit rollback, module-disabled selections and original protected transitions. |

Independent web review found a real dependency gap for a **new source alias of an already imported person**: selecting receipts/packs without step 3 could omit that known alias instead of refusing the missing person-stage update. The orchestrator now compares complete-plan aliases with the effective stored/selected source IDs before any write. The green regression first asserts a valid complete plan and the intended existing-member merge, then checks refusal without step 3 and successful import when step 3 is included.

All five focused attempts kept their recorded source/test/seed inputs unchanged during execution. After the final focused pass, the host lock and Maven target were handed to the API lane for the new complete clean verification. The green unit suite has 60 tests; the initial green integration group has 23 tests, and the affected staged class now has 11 tests (one more alias case). These are overlapping suites, not 94 distinct tests.

The initial historical full API gate was stopped on the user's scope expansion. These focused results do not replace the final full clean verification or image/Compose verification. No real source records, production approval, provider messages, registry push or deployment are exercised.
