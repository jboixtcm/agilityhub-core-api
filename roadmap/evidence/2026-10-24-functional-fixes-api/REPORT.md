# Local functional audit fixes — API and demo definition

Scope: INC-91/F01 and INC-94/F04, authorized on 2026-10-24. Existing uncommitted work is preserved. This is local implementation and verification, with fictional fixtures and test providers; no commit, push, deployment or real provider operation. Historical audit reports are unchanged.

## Changes

- `ConsentGrant` gives canonical `granted` precedence over legacy `accepted`, including when the canonical field is null or malformed. Image use is granted only by an explicit boolean true; missing or malformed evidence becomes false in the read projection, never an affirmative consent.
- S03 §3 defines old privacy acceptance as `{acceptedAt, version}` without a boolean. `legacyPrivacy` preserves that representation only for the legacy privacy object; an explicit false/null/malformed flag still wins. An incomplete canonical ledger row does not inherit that exception.
- Detail, consent history, universal list/filter/export projection and dashboard signup warnings now use the same rules. The converter projects the latest image entry while preserving the original append-only ledger. Read regressions verify stored documents are unchanged.
- `DemoSeedService` writes canonical `granted` for new fictional census records.
- `seeds/club-canic-demo.yaml` is a separate definition with visible fictional identity, reserved test hosts, ca/es/en nonoperative manual-payment instructions, and the CANIC modules except LEARN_LINK. It keeps the `canic` dataset binding. The production seed remains byte-identical; the before hash is in `00-production-seed-tested-inputs.json`.
- Docker includes the new definition. The web coordinator owns switching the functional stack to this seed and preserving the exact applied configuration. Signup and N-01 applicant facts read the configured instructions; complete `seed:demo` must preserve them.

## Verification ledger

Each targeted command has `.command`, `.exit`, complete `.log`, `-tail.txt`, `-result.json` and a source `-tested-inputs.json`. The full gate adds fresh per-class XML summaries, changed-class coverage and a before/after input manifest. All heavy execution uses the shared host lock with `HEAVY_WAIT_MIN=120`.

- `01-consent-red`: original production code plus the new HTTP regression; exit 1, 10 tests, 8 failures, 0 errors/skips. This reproduced the missing/wrong consent values; inputs unchanged.
- `02-focused`: exit 1 at test compilation: the new definition-test constructor omitted the checked IOException declaration. Corrected only the fixture constructor; no tests executed in that attempt.
- `03-focused`: exit 1; 5 unit tests passed, 12 integration tests had 6 failures and 1 error. The new warning fixture lacked the pending-dog condition; the demo fixture had not activated the ONBOARDING definition before requesting signup methods. Both corrections were confined to the fixtures.
- `04-focused`: exit 0; 5 unit tests and 12 integration tests passed, no failures/errors/skips; tested inputs unchanged.
- `05-expanded-clean-verify`: `./mvnw -q clean verify`, exit 0. All 4,948 unit tests (474 classes) and 2,308 integration tests (174 classes) passed, with zero failures/errors/skips. Every report is fresh, no changed test class is missing, and all 2241 tested inputs are unchanged. Exact command, complete log, last 40 lines, counts and source digests are retained.
- Coverage: 98.47% lines and 88.43% branches overall; all 77 gated packages meet the configured thresholds. `05-coverage-summary.json` contains per-package and new-helper details. No threshold was relaxed.
- `05-openapi-comparison.json`: fresh generated OpenAPI is byte-identical to the reviewed snapshot; no snapshot rewrite. `05-seeds-tested-inputs.json` proves the production seed is unchanged.
- `06-image-build` and `07-image-runtime`: exit 0. Local `agilityhub-core-api:review-fixes-20261024` (plus local ghcr alias), exact image ID `sha256:9f8a73041597a46df54ec22789dba793e9d6d20b7454fae61a9238d1824753a9`; arm64 Linux. UID 10001, Java execution, absent unused Pebble binary, readable demo definition and exact demo/production seed hashes were checked in a container with `--network none`. Build inputs are unchanged from the successful clean gate. No push or deployment.
- `08-cli-exploration`: exit 0; 22 real CLI invocations (including 2 fixture seed commands), 44/44 assertions passed in 153.86 seconds. The exact new image and safe demo seed were used; source inputs unchanged. Fresh private fictional inputs, local mailbox and an internal Docker network prevented real-provider calls. Before/after snapshots verify zero-write refusal/dry-run paths, selective import/resume, durable welcome repeat handling and tenant isolation. `attempt-01/cleanup.json` confirms the owned Mongo container and network were removed.
- `09-report-exploration`: exit 0; 8 real CLI invocations and 24/24 assertions passed in 97.79 seconds, with unchanged source inputs. A locally injected report-store outage leaves the completed census import recoverable by report-only publication, without changing business documents. Exact approved reapply succeeds; stale or missing approval is refused. `report-attempt-01/cleanup.json` confirms owned containers/network removed. Together the CLI groups comprise 30 invocations and 68 assertions, not 68 independent workflows.
- Continuation on 2026-10-26: root independently compared all 2,241 source inputs with the successful clean verification manifest; none changed. The local image ID remains identical. Evidence: web `functional-fixes-root/10-api-resume-integrity.json`.

The targeted regression covers legacy booleans, absent/malformed/contradictory flags, explicit withdrawal in an append-only ledger, history/detail/list/filter/dashboard agreement, cross-tenant and role denial, legitimate legacy privacy acceptance, and the safe demo definition after the complete seed including signup configuration and applicant-notification facts in all three languages.
