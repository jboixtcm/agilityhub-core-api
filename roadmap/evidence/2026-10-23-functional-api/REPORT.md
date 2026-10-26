# Functional API exploration, 23–24 October 2026

**Result: 30 real CLI invocations and 68 assertions passed across two finite batches (22/44 and 8/24).** The invocations include three fixture seed commands; these figures are separate from browser cases and Maven tests. No new migration/report/welcome functional defect was reproduced. One P3 operational improvement and the independently reproduced shared P2 consent defect are documented below.

This is an additional black-box review of the completed local release image. It does not replace the clean Maven gate or claim a deployed/provider-backed environment. Product code, configuration defaults and the image were not modified during this exploration.

## Tested artifact and isolation

- Image: `agilityhub-core-api:review-20261023`, ID `sha256:1e8060615d372dc826ea431e1cd9ef3733c440d7ce4ecc02199f708e9c6d752e`.
- Each attempt creates its own disposable Mongo replica set and an internal Docker network. No ports are published; real email, SMS, payment or cloud providers are unreachable.
- Inputs are the repository's explicitly fictional Playoff fixture, copied into a private temporary directory outside Git/Dropbox. Environment secrets are generated per attempt, never inherited from the host and never written into evidence. Temporary files are removed after the attempt.
- Every heavy invocation uses the shared `heavy.sh` with `HEAVY_WAIT_MIN=120`. No Maven verification, image build or deployment is performed here.
- Each command records exact arguments, process exit, redacted output and before/after collection counts plus digest prefixes. Assertions compare full SHA-256 document digests in memory, not only counts or the persisted prefixes. Tokens, password hashes, document contents and signed URLs are not included in snapshots.

## Batch 1: actual CLI import and welcome flows

**Passed: 22 CLI invocations, 44 assertions; exit 0.** These are CLI invocations/assertions, not browser test cases. The 22 invocations include two fixture seed commands. Evidence: `04-cli-exploration.command`, `.exit`, `.log`, and `attempt-04/`.

| Flow | Observed result |
| --- | --- |
| `--dry-run --steps=1-3` | Exact zero changes in the 12 observed collections. |
| `--apply --steps=1-3` | 187 PENDING members; zero dogs; no additional accounts or payment fields. |
| Resume with changed cut-over | Exit 1, `INPUT_SCHEMA_MISMATCH`, no observed document changes. |
| Resume the original run with steps 4–10 | Exit 0; 187 members and 189 dogs. |
| Resume an old attempt again | Exit 1, `MIGRATION_ALREADY_APPLIED`, no observed document changes. |
| Legacy full-import repeat | Exit 0; no duplicate members, dogs, family groups, accounts or memberships. |
| Production without confirmation / production reset | Exit 1 with the expected closed error code; no observed document changes. |
| Reapply without written approval / duplicate option / steps 0 and 11 | Rejected; no observed document changes. |
| Production mode with the local unavailable report store | Exit 1 before import; no observed document changes. See the operational improvement below. |
| Report command against another tenant's run | `NOT_FOUND`; no observed document changes. |
| Report command against a staging run | `INVALID_STATE`; no observed document changes. |
| Welcome dry run | `ELIGIBLE=1`; no claim, token or notification is written. |
| Welcome with local mailbox | `SENT=1`; one durable sent marker. The disposable mailbox is not evidence of external delivery. |
| Welcome repeated in a new Java process | `ALREADY_SENT=1`; exact zero observed document changes. |
| Welcome with intentionally unwritable mailbox | Exit 1, `REVIEW_REQUIRED=1`; one durable claimed-but-unsent delivery remains. |
| Retry that uncertain welcome in a new process | Exit 1, `REVIEW_REQUIRED=1`; exact zero observed document changes, no automatic resend. |
| Welcome against another tenant | Empty dry run; the other tenant's census stays empty. |
| Issued WELCOME tokens | Actual expiry minus creation time is 604800 seconds (seven days). |

Both owned containers and network were removed: `attempt-04/cleanup.json`.

Attempts 1–3 are preserved, not counted as product failures or successful functional coverage. They stopped at fixture setup: (1) missing writable local attachment/export directory variables for the non-root image, (2) a custom issuer without a matching OIDC login URL, and (3) a seed requiring the generated `SEED_PASSWORD`. The final harness takes these settings from the documented local configuration. Cleanup passed for every attempt.

## Batch 2: production report recovery and approved reapply

**Passed: 8 CLI invocations, 24 assertions; exit 0 on the first attempt.** Evidence: `06-report-exploration.command`, `.exit`, `.log`, `-tail.txt`, and `report-attempt-01/`. The harness is `run-report-exploration.py`.

The same application image ran with the strict staging profile and explicit production command mode. LocalStack 3.8.1 supplied S3, on the internal network. A temporary HTTP proxy returned 403 only for report object PUTs while the outage flag was present; normal bucket-readiness calls were forwarded to LocalStack. No application guard, bean, transaction or report writer was replaced.

| Flow | Observed result |
| --- | --- |
| Bucket readiness | All four public-access-block flags true; no versioning status; enabled exact `migration-reports/` prefix with 30-day expiration. |
| Production dry run | Exit 0; exact zero observed document changes. |
| Production apply with report PUT returning 403 | Exit 1; data committed as a completed migration (187 members, 189 dogs), report pointer absent. Output explicitly says to recover only the report and not reapply the migration. The actual denied PutObject event is captured. |
| `migration:report` after restoring object writes | Exit 0; existing run receives its report pointer, while all 11 observed business collections and run count remain unchanged. |
| Recovered workbook | Valid XLSX archive; sheets `Resum`, `Manifest d'entrada`, `Resultats`, with 46/7/709 rows; no formula cells or external-link parts. The original fictional workbook is saved. |
| Object contract and expiry | S3 emulator responds with `AES256`, no version ID, `private, no-store`; recorded expiry is exactly 30 days after migration completion. This validates the emulated protocol contract, not physical AWS encryption or elapsed lifecycle deletion. |
| Report command repeated | Exit 0; exact zero observed database changes. |
| Production apply repeated without approval | Exit 1, `MIGRATION_ALREADY_APPLIED`; exact zero observed document changes. |
| Reapply with an exact private approval | Exit 0; adds a completed immutable run linked to the prior run and a report pointer. One unique-NIF active fictional member's mapped name changes to `FunctionalReportName`; members, dogs, family groups, accounts and memberships are not duplicated. The 0600 approval file is owned by UID 10001 inside the command container and binds the candidate manifest, club, prior run, selected steps and cut-over. |
| Same approval reused after that reapply | Exit 1, `PRODUCTION_REQUIRES_CONFIRMATION`; exact zero observed document changes. |

The recovered workbook is `report-attempt-01/recovered-fictional-report.xlsx`; `workbook-inspection.json` records its structural inspection. `bucket-policy.json`, `report-object.json`, `fault-events.jsonl` and `approval-binding.json` record safe metadata without credentials, raw approval text or signed URLs. `cleanup.json` proves that the owned Mongo/LocalStack containers and network were removed. The temporary environment paths in every captured command no longer exist.

Combined machine-readable results are in `07-final-summary.json`. Harnesses and source fixture hashes are recorded in `07-functional-tested-inputs.json`; the candidate alteration is constructed deterministically by the second harness in private temporary storage.

## Findings and recommendations

### F-API-01 — P3: operator-facing configuration failures hide the actionable cause

Reproduced with `attempt-04/14-production-local-storage.command` and `.log`. With a valid fictional input and `--env=production --confirm-production --steps=1-10`, the local profile correctly rejects the import because its report store is unavailable. The visible error is only `Command failed (IllegalStateException)`, with no indication that the operator needs the staging/prod S3 configuration. All observed business documents remain unchanged; this is an ergonomics improvement, not a guard failure.

Owning lines: `src/main/java/com/agilityhub/core/cli/CoreCli.java:28–30`, `src/main/java/com/agilityhub/core/migration/application/MigrationReportConfiguration.java:17–23`. A related startup configuration error emits `Command startup failed (UnsatisfiedDependencyException)` (`CoreApplication.java:30–32`) and a long scrubbed stack, which required reading the owning constructor to diagnose the fixture configuration.

Recommendation: keep arbitrary persistence/provider exception messages private, but map known preflight/configuration failures to a closed safe error code and a short operator action (for example, the required profile and report-storage settings). Preserve exit 1 and the current validation-before-writes behavior. This is not a recommendation to print raw exception messages or credentials.

### Shared finding F-WEB-01 — P2: legacy image-consent projections disagree

Independently confirmed the owning API source after the web review captured the demo member #6. `DemoSeedService.java:100` writes legacy `imageRights.accepted`; `ConsentLedgerConverter.java:9` preserves this supported map representation; `CensusQuery.java:66` selects only `granted`, while `CensusConsents.java:40` treats an absent `granted` as true. `ConsentLedgers.java:20` explicitly supports the legacy `accepted` fallback elsewhere.

For member #6 (`accepted=true`), the current projection omits the required `granted` boolean and the UI presents false, while history presents true. For an odd seed member (`accepted=false`), the history calculation also produces true. The web lane subsequently reproduced both branches through actual HTTP requests (all GET 200) on the same image: [consent comparison](/Users/jordib/dev/agilityhub/agilityhub-core-web/roadmap/evidence/2026-10-23-functional-web/core-e3/consent-comparison.json). The #6 screenshot demonstrates the true legacy branch; #7 demonstrates the false branch. This should be corrected and verified before the demo, without rewriting the append-only evidence to invent consent. No product change was made in this review.

## Limits

The first batch uses fresh real Java processes and Mongo, but a local mailbox rather than a real mail provider. It does not prove provider delivery, hosted domains, production deployment, genuine exported data or any Learn identity integration. Earlier Maven integration coverage is separate from these functional results and is not included in the counts above. The second batch establishes S3 behavior against an emulator, not AWS infrastructure. It does not exercise the hosted report-download URL, real IAM, or the passage of 30 days; prior HTTP/unit tests are separate evidence. No additional functional batches were launched after this gap was closed.

All nested evidence logs are retained by a narrow `.gitignore` exception. The final complete-tree secret scan is recorded in `08-final-evidence-secret-scan.json`; no allowlist was widened.
