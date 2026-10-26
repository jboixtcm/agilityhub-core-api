# Migration reports (S18 R-18-15)

Demo/staging rehearsals use anonymized source files and the existing CLI report,
which contains adapter keys, source row ordinals, fixed codes and internal IDs.
They do not create a named XLSX or contact report storage. A dry run writes no
migration run, object or report pointer. Keep real exports outside Git and Dropbox;
the importer and report recovery command enforce the same input-path guards.

A successful production apply now records `inputManifest` (mapping-approved
filenames, SHA-256 and physical data-row counts, plus a canonical fingerprint of
the full mapping configuration), safe report rows, counters and a
fixed report expiry in its tenant-scoped `MigrationRun`. It then creates
`report.xlsx` and publishes its private object key. The workbook includes the run
status, totals/reconciliation, manifest and every result/warning/proposal with its
original source reference and available source member name. It does not add
email, tax ID or bank-account columns. Spreadsheet values are string cells, never
evaluated formulas. The temporary workbook is mode 0600 and removed on either
success or failure; incident rows and the CLI never acquire the names.
Workbook headings and explanations are Catalan (S18 §10); identifiers and fixed
incident codes retain their stable values. A reconciliation entry that carries
only an internal member ID retains that ID; it has no guessed source name.

For a `FAILED` run the sheet is explicitly **Resultats previstos**. Earlier batches
may have committed: planned `CREATED`/`UPDATED` outcomes must never be read as an
inventory of committed changes. Failed runs have no fabricated completion
counters. Reconcile their actual records before deciding any recovery.

## Before a production apply

Public production defaults to S3. The explicitly configured internal team
deployment may instead use the existing encrypted volume; see
[Internal volume storage](INTERNAL_VOLUME_STORAGE.md) for its hosted-demo guard,
private mount marker, signed file route, retention and backup requirements.
It still treats an active `prod` profile as production and enforces the original
30-day report deadline. It does not activate the unavailable local/test report
store or bypass production migration safeguards.

In default S3 mode, the existing `exports.s3.*` deployment configuration supplies the client, private
bucket and signer. The report path is
`migration-reports/<clubId>/<runId>/report.xlsx`. No report configuration is
created or repaired by the application. Before its first migration write, the
production importer requires:

- All four S3 bucket public-access-block flags enabled.
- An unversioned bucket. A versioned or suspended bucket is refused because
  ordinary deletion could retain a named historical object.
- An enabled lifecycle rule with the exact `migration-reports/` prefix and a
  30-day expiration, without tag/AND filters.
- The current club's configured `migration.exportRetentionDays` is 30. This
  implementation supports that S18 policy only: an override (including zero)
  is refused before any production import write; it is never silently ignored.

The deployment principal needs read access to public-access-block, versioning and
lifecycle configuration, plus object Put/Get/Delete under the report prefix.
Each upload explicitly requests SSE-S3 `AES256`; the service checks the S3
response confirms that encryption and has no version ID. A provider that does
not support these guarantees fails closed. Ordinary exports retain their
existing behavior. No provider credentials, bucket policy or lifecycle was
created or changed while implementing/testing this feature.

These checks do not prove that the principal can successfully upload: IAM,
network or provider failures can still happen after business data has committed.
The failure procedure below is part of the production runbook. For a demo,
stay in staging migration mode and use fictional/anonymized data; production
report storage is unnecessary.
The importer also treats an active `prod`/`production` profile as production,
regardless of `--env=staging`. Use a separately configured, disposable demo import
process with a non-production profile; changing only the flag on the deployed
production-profile web process does not bypass these safeguards.

## Publish or recover without importing again

The importer prints the run ID. If business data commits but workbook publication
fails, the command exits unsuccessfully and prints that the data is committed.
The run remains `COMPLETED`/`RECONCILED`, preserving the production reapply guard;
it is **not** changed to `FAILED`. Do not reapply the migration to repair a report.

After resolving the storage failure, use the exact original private inputs and
mapping. The recovery command compares their manifest and mapping fingerprint against that run and only
publishes the workbook. It neither replans nor applies census, identities,
billing or welcome messages:

```sh
bin/core migration:report --club=canic --run=<recorded-run-id> --in=/private/operator/playoff-export
# If the original run used an explicit mapping:
bin/core migration:report --club=canic --run=<recorded-run-id> --in=/private/operator/playoff-export --mapping=/private/operator/reviewed-mapping.yaml
```

Use `--name=value` syntax. `bin/core` runs the current packaged JAR; rebuild it
after source changes before running commands. CLI startup disables scheduled
jobs even when deployment settings enable them. Recovery is not available after
the original report expiry. It never resets that expiry and never prints the
signed URL or names. If original inputs are unavailable or differ, recovery
refuses rather than reconstructing an unverified report from current business
records.

Publishing the report pointer preserves an otherwise clean import checkpoint
inside the shared write fence. It cannot hide an application edit or an export
audit that occurred after the import. Downloading the report is therefore a real
subsequent write for the destructive-reset guard; retrying publication does not
make that reset safe again. Production CLI resets remain prohibited.

## Download and retention

An authenticated `GET
/api/v1/platform/clubs/{clubId}/migrations/{runId}/report` returns
`{downloadUrl, expiresAt}`. Only that club's ADMIN or AGILITYHUB_ADMIN can receive
the link. Another club cannot resolve the run; MEMBER, INSTRUCTOR, impersonation
and support access cannot export it. The URL is a bearer capability: keep it
private and do not put it in logs, evidence or chat. It expires within five
minutes, bounded by the report's original expiry. The D19 UI remains optional;
this API is usable from the administration tooling/runbook.
Issuing an authorized link records `DATA_EXPORTED` for its `MigrationRun`; the
audit contains the filename, never the URL, signature or workbook names.

Access ends exactly 30 days after the run finishes, including before cleanup
runs. P9 `cleanup` plans expired production reports by the stored deadline,
deletes the object (including an upload that never acquired its database pointer),
and then marks the report purged while retaining the run/manifest/counters.
Storage failure leaves it retryable. The job's dry run only lists the proposed
deletions. Monitor failures and `migrationReportsPurged`/`WOULD_DELETE_migrationReports`.

Physical S3 deletion is asynchronous: P9 removes overdue bytes at its next
successful execution, and the bucket lifecycle is an orphan backstop measured
from **object upload time**. Republishing on day 29 does not change application
expiry or P9's deadline, but S3's independent 30-day lifecycle clock restarts for
that object. Therefore a stopped/broken cleanup job is an operational failure,
not evidence of exact physical deletion at the 30-day instant. Do not claim a
real bucket retention proof until its configured lifecycle and a scheduled
cleanup have been checked on the actual environment.

Automated tests use only fictional inputs, mocked S3 responses and private
temporary directories for the explicit internal volume adapter. They check
privacy/encryption/lifecycle refusals, immutable manifest matching, workbook
content and spool cleanup, post-commit upload failure recovery, tenant/role
access and expiry/purge behavior, plus volume marker/permission guards and
symlink refusal. They do not establish real cloud access or
production data reconciliation.
