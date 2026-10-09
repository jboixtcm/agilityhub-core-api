# E11-T06 step 8 · PITest on `payments`, `clubs.bookings`, `identity`

Reviewer guide to this directory. Scripts are stdlib Python, read the XML reports only, and never run Maven.

## Configuration and batches
- `pom.xml` profile `mutation`: `targetClasses` = the three packages, no class exclusions (`bin/mutation-gate-test.py`
  checks this); `threads 4`, `-Xmx2g -XX:ActiveProcessorCount=2`, `timeoutFactor 2` + `timeoutConstant 30000`,
  `excludedTestClasses arch.*`, history in `.local/pitest/history.bin` (local, not committed).
- One run of the three packages does not fit the one-hour bound of a command on the shared host (host lock), so
  `batches.py` cuts them into **14 batches**: 1-7 `payments` (1 domain + sepa + stripe, 2-5 `application` by hand,
  6 `api`, 7 `persistence`), 8-11 `clubs.bookings` (8 api/domain/persistence/jobs/ports, 9-11 `application` split
  alphabetically by source size), 12-14 `identity` (12 api/domain/persistence, 13-14 `application`). Nested classes stay
  with their top-level class. Running `batches.py` asserts that every class of the three packages is in exactly one batch;
  `batches.py batchN` prints that batch's `targetClasses`.
- Batches 13 and 14 ran together as one PIT run over `com.agilityhub.core.identity.application.*` (`83-*.log`, report
  dir `target/pit-reports-batch13-14`). That report covers both batches, so `final-gate.py`'s per-batch presence check
  is met by it.
- Run shape (recorded for batch 8): `./mvnw -q -Pmutation -DtargetClasses=<batches.py batchN> -DtargetTests=<selection>
  -DmutationUnitSize=10 test-compile org.pitest:pitest-maven:mutationCoverage`.

## Test selections
- `payments` (1-7): the `payments` tests plus the 22 test classes elsewhere that referenced payments code or routes when the batches started (`-DtargetTests=com.agilityhub.core.payments.*,` then `clubs.bookings.api.{BookingsIT,LifecycleConcurrencyIT,LifecycleIT,SingleClassCheckoutIT}`, `clubs.census.api.{SignupCensusCorrectionsIT,SignupFollowUpFixesIT,SignupGateFixesIT,SignupIT,SignupMinorFixesIT}`, `clubs.dashboard.api.DashboardIT`, `clubs.messaging.application.engine.TemplateVariableParityIT`, `configuration.{E3ContractIT,E3ResponseContractTest,E5BackOfficeContractTest,E8ContractIT,E8PersistenceIT,E8ResponseContractTest,ListFieldsContractIT,S04ErrorContractTest}`, `migration.{PlayoffAdapterTest,PlayoffMigrationIT}`, `shared.api.AnonymousRateLimitsTest`, all under `com.agilityhub.core.`; the exact command lines are in the task report).
- `bookings` / `identity` (8-14): every test package except `arch.*` and `shared.domain.EventCatalogContractTest`, which
  fails on `main` since E9-T01 (31424351) and makes PIT refuse to start ("tests failing without mutation", `30-*.log`).

## Reports
- `batchN-mutations.xml`: **first pass** of batch N with the full selection; `batchN-not-killed.tsv` lists its non-KILLED rows.
- `rerun2-attempt1-mutations.xml`, `rerun2b-mutations.xml`: **re-runs** of batch 2 with the full selection after the
  survivor tests were added (production code unchanged).
- `targeted-*.xml`: a run with only the new survivor tests as `targetTests`.
- `final-batchN-mutations.xml`: the report a batch is scored on. Payments (1-7): the batch re-run with the full
  selection after the survivor-test edits and the export fix (below). Bookings and identity: the base report merged with
  a targeted run by `union.py`.
- `replace-class.py BATCH TARGETED PREFIX OUT`: swaps one class's rows for a targeted run of that class; refuses unless
  both hold exactly the same mutants (used before the re-runs to combine `rerun2b` with
  `targeted-billing-transactions`).
- `union.py BASE TARGETED OUT`: a non-killed BASE mutant takes TARGETED's row when TARGETED kills it. Rows are matched by
  mutation identity (class, method, descriptor, line, mutator, description, instruction indexes and blocks). Any other
  BASE row stays as is. A TARGETED mutant of a BASE class that is not in BASE makes the script refuse (code changed).
- **From history** (`summary.py`): covered rows with `numberOfTestsRun='0'`. PIT reused an earlier verdict from the
  history file because the class and its tests had not changed. Re-runs are mostly history: a re-run re-executes
  only the mutants whose class or covering tests changed.

## Scoring (`bin/mutation-gate`)
Assertion kills / all generated mutations, per package. `NO_COVERAGE`, `TIMED_OUT`, `RUN_ERROR`, `MEMORY_ERROR` and
`NON_VIABLE` stay in the denominator as not killed: a timeout or error is never proof, and it no longer fails a package
by itself (counted as `notProof`; a package fails only on its score or when it has no mutation). A mutation that
appears in two input reports, a class outside the three packages or an unknown status is an error. `final-gate.py`
refuses unless every batch has a mutation in the inputs. It then calls the gate
with bookings 70 / identity 75 / payments 80 (the report's Assumption 1).

## Triage
- `triage-batchN[a-z].md`: one row `| Class:line | Method | Mutator | Status | Decision |` per non-killed first-pass
  mutant, **plus** one per mutant the first pass killed but a re-run or final report did not (a first-pass kill not
  reproduced). Decisions: `test: Class#method`, `reason: …` (equivalent / unreachable, argued) or `rerun: …`.
- `reconcile.py` built the later tables by copying decisions from staged pre-triage tables. It matches on class, method,
  line, mutator and, where the pre-triage names one, the XML index, and prints `UNMATCHED` rows.
- `triage-check.py` (exit 1 on any problem) checks every batch with a `batchN-not-killed.tsv`: the triage rows, counted
  by (class, line, status), must equal the TSV plus the first-pass kills that a `rerunN*` or `final-batchN` report does
  not reproduce; every `test:` names an existing `void method(` in `src/test/java`; when `final-batchN` exists, each
  mutant not killed there must have a `reason:`/`rerun:` row with the same class, line and mutator (so each `test:`
  mutant is KILLED there).
- `compare.py FIRST FINAL`: status changes between two reports of one batch, plus every final non-kill.

## Shared export directory (flakiness found and fixed)
PIT's parallel minions share the working directory. The three IT methods checking that a failed run "wrote no file"
(two in `RemittancesIT`, one in `BillingFollowupsIT`) read `target/test-exports`, so they could fail on another JVM's
file and "kill" mutants without the mutant's help. It showed up as first-pass kills that re-runs did not reproduce
(6 in `rerun2-attempt1`). Fix: `support/AbstractIntegrationTest.EXPORTS` = `target/test-exports/<pid>` wired to
`exports.local-directory`, and both ITs read that root (`77-export-root-its.log`). The payments final reports are
re-run after this fix.
`killers.py SUSPECT_METHOD REPORT…` lists the kills that rest on a suspect test (`71-pit-suspect-killers.log`).

## Reproduce (from the repository root, no build)
```
python3 roadmap/evidence/E11-T06/pit/batches.py                  # partition + one-owner assertion
python3 roadmap/evidence/E11-T06/pit/summary.py <final reports>  # per-report and per-package table
python3 roadmap/evidence/E11-T06/pit/final-gate.py <out-dir> <final reports>  # out-dir: new or without summary.json/survivors.json
python3 roadmap/evidence/E11-T06/pit/triage-check.py
python3 bin/mutation-gate-test.py
```
FINAL-REPORTS: (filled at hand-off)
