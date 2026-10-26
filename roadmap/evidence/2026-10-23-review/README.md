# Technical completion and independent review — 2026-10-23

This is local implementation and verification evidence. It does not change historical organizer verification or claim a hosted deployment. Jordi confirmed that a new DigitalOcean **demo** server comes only after the code, review and tests are finished; account access will be supplied later. Existing exports are to be anonymized for that later demo.

After explicitly reviewing the current Learn dependency, Jordi confirmed this first demo is **Clubs isolated**; integration with the existing AgilityHub API and Learn is a later phase. The current Laravel/Vue repositories were inspected read-only. They still use Learn's own login/JWT, and the Core provisioning/password-sync endpoints remain unimplemented under A30. These local results do not verify shared identity, account synchronization or SSO with Learn. That later release requires implementation and coordinated integration tests before activation.

## Starting revisions and scope

- API started at `de50be06b1c0bb3d278a4335e16378292ca6d276` with a clean worktree.
- Web started at `5502767`; existing untracked `E11-W08-org/` and `E11-W08-org2/` evidence is preserved.
- No commit, push, remote deployment, real migration, provider send or paid infrastructure operation is part of this evidence.
- The parent coordinates three agents: API regressions, web regressions, and release/migration review. Changes are independently reviewed before final validation.

## Changes

| Area | Concrete outcome |
|---|---|
| Migration welcome, S18 R-18-16/T-18-09 | Explicit `migration:welcome`, staff first, batches of up to 50, number filtering, dry-run, account-level durable claims, no automatic resend after ambiguous provider outcomes, seven-day WELCOME links and migrated N-02 copy in ca/es/en. |
| CLI isolation | Command startup disables scheduled jobs even when deployment or command-line configuration enables them. |
| Migration paths and reset | Anonymization uses the same private-directory checks as import; explicit `--env=production --reset` is rejected before reading confirmation input. |
| Selective migration/reapply, S18 R-18-03/14 | Explicit stages, immutable input continuation, independent batches/checkpoints and written production approval. `docs/MIGRATION_STEPS.md` describes the command and safety boundaries. The user-requested expansion passes focused tests and the final complete API gate. |
| Migration report, S18 R-18-15 | Separate agent implementation and evidence: manifest-bound XLSX, private encrypted storage, authorized download, fixed expiry and recovery without reapplying business data. See the release-review evidence for its final validation state. |
| Course geometry, INC-86 | Resolved placements persist tunnel control points in ring coordinates; explicit empty geometry remains empty; absent/null legacy geometry retains its compatibility fallback. API and web contracts agree. |
| API review fixes | Activity duplicate-key retry, narrow erased-census projection, concurrent TTL-index removal, and ambiguous-price signup review. |
| Web review fixes | ID logout restores a cold cookie session before revoking it and returns to login, with failure retry and abandoned/replaced-session guards. Stale authentication responses cannot overwrite newer sessions; team selection distinguishes homonyms and loads/retries the full census; production bundles omit the mock service worker; real-core harnesses use generated shapes and recipient-specific notification counts. |
| Release inputs | Read-only preflight requires reviewed immutable API image identity, actual distinct SPA artifacts, proxy and backup configuration. Unused inherited Pebble executable is removed from the Java runtime image. |
| Evidence correctness | A parameterized specification test with skipped cases is no longer reported as fully passed; billing smoke helper is executable; Python regressions run in CI. |

## Validation

| Command/evidence | Result |
|---|---|
| `trace-baseline-red.log` | The old trace aggregation reproduces the mixed passed/skipped false-positive. |
| `trace-regression.log` | 3 Python regression tests pass. |
| `e8-smoke-regression.log` | 4 Python smoke regression tests pass. |
| `release-preflight.log` | 20 Python preflight tests pass. |
| `welcome-and-regression-unit-final.log` | Initial focused Java run: 28 tests, no failures/errors/skips; superseded by the complete expanded verification below. |
| `focused-integration.log`, `.command`, `.exit`, `focused-integration-summary.json` | 55 integration tests, no failures/errors/skips, exit 0. Covers Welcome4, Placements9, RingSetups21, TTL6, Retention2, SignupPlans12, DemoCourses1. |
| `secret-policy-regression.log` | Initial tool availability failure: Gitleaks was not installed on PATH. No code failure is inferred. |
| `secret-policy-final.log` | Policy regressions pass with checksum-verified upstream Gitleaks 8.30.1, the CI version. |
| `changed-secret-scan.json` | Intermediate changed-file scan: 107 files, zero findings. |
| `final-source-secret-scan.json` | Reviewed changed source scan: API 78 files and web 31 files, zero findings using Gitleaks 8.30.1. Evidence logs are checked separately after final suites. |
| `expanded-source-secret-scan-retry.json` | Expanded implementation scan: API 92 files and web 31 files, zero findings using Gitleaks 8.30.1; source unchanged during scan. The preceding attempt had no API findings but could not load a nonexistent web configuration; the retry explicitly uses the API's policy for both repositories. Later test-fixture corrections are covered by the final scan. |
| `final-expanded-source-secret-scan.json` | Final frozen sources, including the corrected alias/pack regression fixture: API 92 files and web 31 files, zero findings; both source trees unchanged during scanning. |
| `final-delivery-source-secret-scan.json` | Delivery source scan after moving the raw image-scan output into release evidence: API 92 files and web 31 files, zero findings; both source trees unchanged. Evidence is scanned separately after browser logs finish. |
| `final-frozen-source-secret-scan.json` | Closing source scan after the E6 locale harness correction and final changelog: API 92 files and web 31 files, zero findings; both trees unchanged during scanning. |
| `final-delivery-evidence-secret-scan.json` | Closing scan of the dated review evidence, including raw image findings and final browser logs. Exact file counts, findings and unchanged-input checks are recorded in this JSON after all review reports are saved. |
| `final-contract-and-whitespace-check.json` | All 7 final checks pass: adopted OpenAPI and S16/S18 copies agree, both repository whitespace checks pass, the served local OpenAPI matches, and the final web source manifest matches with only the documented E6 harness delta. |
| `access-log-regression.log` | 3 Caddy access-log tests pass. |
| `../2026-10-23-migration-steps-review/02-units-summary.json` | 60 focused unit tests, zero failures/errors/skips. |
| `../2026-10-23-migration-steps-review/05-integration-summary.json` | 23 focused integration tests, zero failures/errors/skips: attempt authorization 7, staged import 10, report HTTP permissions 4, report checkpoint 2. |
| `../2026-10-23-migration-steps-review/14-integration-summary.json` | Final staged regression suite: 11 tests, zero failures/errors/skips. Added the independently discovered merged-source alias case: selected receipts/packs must require the person stage before referencing a new alias; the accepted path imports both without duplicating the person. |
| Web `66-final-combined-gate.log` | All 38 tasks pass: lint, types, builds and tests; 3,580 Vitest tests plus 20 node:test cases, locale parity and bundle limits. |
| Web `69-final-mock-docker.log` | Complete mock browser suite passes: 235 passed, 26 pre-existing skips. D17 desktop and course mobile screenshots inspected; separate real-API results follow below. |
| `../2026-10-23-api-review/13-expanded-clean-verify.command` | Final `./mvnw -q clean verify` passes, exit 0: **4,943 unit tests + 2,296 integration tests**, zero failures/errors/skips. All test reports are fresh; every added/changed test class is present; tested inputs are unchanged. Coverage and architecture gates pass. The summary lists all 26 changed test classes. |
| `../2026-10-23-api-review/14-snapshot-export.*` | Post-verification OpenAPI export passes and matches the web snapshot byte for byte. The actual local image's HTTP contract is checked separately in the real-core browser run. |
| Web `72-final-served-openapi.log` | The final image serves `/api/v1/openapi.json` successfully under the disposable local profile. Its parsed content matches the frozen snapshot recursively, including array order; only serialization differs. The inspected container uses the exact reviewed image ID. Production documentation stays disabled. |
| Web `71-final-core-e1.log` | Fresh-seed identity suite against that exact final image: 8/8 pass, including direct cold-session logout, successful token revocation, return to login and protected account access requiring a new login. Both the web reviewer and parent inspected `core-e1/id-logout-core-1280.png`; it shows the expected signed-out login screen without errors or clipping. |
| Web `74-final-core-e9.log` | Fresh-seed course suite: 3/3 pass, exit 0, against the final image. Covers library/import, placement, a build session on two phones and the planned placement flow. The web reviewer inspected desktop placement and mobile viewer screenshots. |
| Web `75-final-core-e10.log` | Fresh-seed administration suite: 6/6 pass, exit 0, against the final image. Covers console login, platform-only permissions, club creation, simulated DNS and support access. |
| Web `78-final-core-e6-locale.log` | Fresh-seed attendance suite: 14/14 pass, exit 0, against the final image. The first final attempt (`73`) found a harness assumption that anonymous localStorage would override the signed-in account locale. The corrected test selects Spanish through the real profile, verifies the PATCH and rendered language, and restores both accounts in nested `finally` blocks; all original Spanish assertions remain. Focused lint/typecheck pass (`76`, `77`). Only this E6 harness differs from the 27 frozen web inputs used by the full product/mock gates; `final-source-tested-inputs.json` records the final inputs. The parent independently reviewed the correction and checked that exact delta. |
| `../2026-10-23-release-review/15-final-image-build.*`, `16-final-image-runtime.*` | Final local arm64 image builds and starts, exit 0. UID 10001, unused Pebble absent, all 2,235 recorded inputs unchanged. Image ID `sha256:1e8060615d372dc826ea431e1cd9ef3733c440d7ce4ecc02199f708e9c6d752e`; local `review-20261023` tags only. |
| `../2026-10-23-release-review/17-final-image-scan.*` | Offline Trivy passes: 0 CRITICAL/HIGH, 54 MEDIUM, 4 LOW, 205 Java packages, no Go findings. Uses the saved advisory database updated on 22 October; does not validate amd64 or later advisories. |
| `../2026-10-23-release-review/18-final-compose-smoke.*` | All checks pass, exit 0: trusted local HTTPS, SPA fixture routes, refresh cookies, encrypted backup and exact restoration of 89 collection snapshot counts, and negative restore guards. Disposable production topology with fictional data and no provider egress; fixtures do not validate the future server's real SPA artifacts. |

## Scope distinctions and later hosted verification

- Final clean Maven verification, image build/scan, Compose proof and all four selected real-API browser suites are complete. The latter total **31/31 passed with no skips**, each group using a new seed against the same final image. This is separate from the full mock browser suite's 235 passes and 26 pre-existing skips.
- Preliminary evidence scanning found only source-file SHA-256 fingerprints under two API manifest filenames and one web manifest filename. Their values were verified and filenames aligned with the existing narrow `*-tested-inputs.json` convention, preserving their bytes; no scanner exception was broadened. The closing evidence scan is recorded separately after the final reports and logs are stable.
- The exact historical PIT baseline selection is a diagnostic for INC-89, not a mutation-score run. See `../2026-10-23-api-review/README.md` for the actual result and any unreproduced historical failure.
- Jordi explicitly requested `--steps` and approved production reapply before the demo after the initial scope review found them missing. They are implemented and pass the final complete verification, with explicit lineage continuation, private approval binding and per-stage checkpoints; focused validation is recorded in `../2026-10-23-migration-steps-review/` and the complete gate in `../2026-10-23-api-review/13-expanded-clean-verify.summary`. The earlier `selective-import-scope.md` is the initial gap analysis, not the current implementation status.
- Learn integration and the optional Jackson 3 migration retain their existing roadmap scope; no new completion claim is made for them.
- Hosted demo, external provider delivery, real-export reconciliation, bank/legal approval and eventual production cutover remain separate from local code verification.

## Related evidence

- `../2026-10-23-api-review/README.md`
- `../2026-10-23-release-review/`
- `../2026-10-23-migration-steps-review/`
- Web: `/Users/jordib/dev/agilityhub/agilityhub-core-web/roadmap/evidence/2026-10-23-web-review/REPORT.md`
- Source release audit: `/Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/REVISIO_LLANÇAMENT_2026-10-23.md`
