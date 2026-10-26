# Selective Playoff migration and approved production reapply

S18 R-18-03/R-18-14 supports explicit stages with `--steps`, continuation with
`--resume-run`, and a separately approved production reapply. These commands are
operator tools. Use a freshly verified executable as described in
[MIGRATION_WELCOME.md](MIGRATION_WELCOME.md); an existing `target` JAR is not rebuilt
automatically by `bin/core`. No real import or approval is created by this document.

## Select only the required work

Use `--option=value` syntax. The selector accepts numbers and ascending ranges,
separated by commas; for example `--steps=1-3` or `--steps=4,6-10`. Invalid numbers,
descending ranges, empty items and whitespace are refused. Step 1 always runs.
The default command without these new options retains the accepted complete
import with its atomic census transaction and separate billing batches.

| Step | Selected effect |
|---|---|
| 1 | Validate the complete input and existing catalog references; do not migrate catalogs. |
| 2 | Family groups and their member links. |
| 3 | Person/source identifiers, member number, contact/profile fields and original legacy consent. Reserve member numbers. |
| 4 | Dog identity, ownership, source identifiers, level, licenses and descriptive fields. |
| 5 | Payment method/mandate, plan, price and next invoice date. |
| 6 | Member and dog state, booking blocks, leave dates and migrated planned leave requests. |
| 7 | Historical receipts, using the existing billing module and row-warning rules. |
| 8 | Pack balances, using the existing packs module and row-warning rules. |
| 9 | Eligible accounts and memberships; no welcome email. |
| 10 | Close the supported pipeline and reconcile the next month when forecast data is present. Future training bookings remain disabled by the current mapping/decision in S18 §13. |

When `BILLING` is disabled, stages 5/7 and reconciliation perform no billing
writes; when `PACKS` is disabled, stage 8 performs no pack writes. Those selected
stages complete as module-disabled skips. Selection never enables a module.

Selection does not bypass complete input validation or `REEXECUTION_UNSUPPORTED`.
An invalid census row still blocks the operation before business writes, even
when its field belongs to an unselected stage. Unknown or malformed auxiliary
billing rows retain the existing WARNING/SKIPPED behavior. Review those warnings.

Unselected fields on an existing imported record are preserved. A new person
created before stage 6 uses the existing `PENDING` status; a new dog created before
stage 6 uses `INACTIVE`. No account is created before stage 9. Stage 9 requires the
effective member state to match the input, including blocks and planned leave,
and the intended email to match the effective member contact. Account names use
the effective person fields. An omitted state/contact change cannot silently
grant new access. Run partial imports
while the club is closed for migration, not as an unattended live synchronization.

References must exist as imported records or be supplied by the selected earlier
stages. Family groups may refer to people selected in stage 3; their new-person
backlinks are written with those people. A selected dog/payment/state/account
cannot create a skeletal person as a side effect. Selected receipts/packs cannot
silently skip a known person just because the person stage was omitted; packs
also need the applicable dogs. A newly merged Playoff identifier needs stage 3
before receipts or packs can refer to it, even when its destination person already
exists. Stage 10 requires every preceding supported stage
to be selected or already completed in the same continuation lineage.

Example using only a private anonymized fixture directory:

```sh
bin/core migration:playoff --club=sample --in=/private/migration/fixture \
  --cut-over=2026-11-01 --steps=1-3 --dry-run

bin/core migration:playoff --club=sample --in=/private/migration/fixture \
  --cut-over=2026-11-01 --steps=1-3
```

The first command plans without creating a migration run, checkpoint, token or
email. Normal application startup still performs connection/configuration/index
checks. The second applies the selected work and prints its run ID. Neither
command creates receipts, packs, dogs or accounts for this selection.

## Resume the exact input

Retain the printed run ID. Continue with the same files, mapping and cut-over:

```sh
bin/core migration:playoff --club=sample --in=/private/migration/fixture \
  --cut-over=2026-11-01 --steps=4-10 --resume-run=PREVIOUS_RUN_ID --dry-run

bin/core migration:playoff --club=sample --in=/private/migration/fixture \
  --cut-over=2026-11-01 --steps=4-10 --resume-run=PREVIOUS_RUN_ID
```

Every invocation records selected steps, steps completed in that invocation,
completed earlier steps, cut-over, root/prior run IDs and an ordered attempt
number. `COMPLETED`/`RECONCILED` describes the invocation. `pipelineComplete` says
whether this input lineage has completed all ten supported stages; a successful
`--steps=1-3` is not a completed whole migration.

`--resume-run` must name the latest attempt for that club and environment. It
refuses changed input bytes, filenames, mapping, cut-over, another tenant's run,
already completed write stages and an empty continuation. Step 1 may repeat.
Advanced attempts use their persisted ordinal; duplicate highest ordinals, or
legacy attempts tied at the newest timestamp, are refused rather than ordered by
an arbitrary record ID.
Resume is explicit; running the old complete-import command over an unfinished
advanced lineage is refused. At staging, a deliberate reset remains available
under its existing rules.

Each stage uses transactions of at most 100 change records. A whole step is marked
complete only after all its batches commit. If the process fails, committed earlier
batches remain and the unfinished step can be selected again; existing source IDs
prevent duplicate receipts, packs, people or access accounts. The final selected
step's completion marker and the terminal run audit/event commit together, so a
failure recording completion does not leave an unresumable fully marked run.

For example, if stage 7 fails after committing its first 100 receipts, use the
failed/latest run ID with `--steps=7-10`. Do not select already completed stages
2–6. Inspect the saved execution metadata/report rather than guessing from an
interrupted terminal output. Every continuation creates a new attempt; it does
not overwrite the prior report or extend its original 30-day deadline.

Production apply also requires `--env=production --confirm-production` and an
explicit `--cut-over`. Production continuation checks the shared write checkpoint
under the same fence as other application writers before every batch. An operator
edit after the previous batch/import, including a genuine report-download audit,
blocks continuation; it is not absorbed into a clean checkpoint. Report pointer
publication alone preserves an otherwise clean checkpoint. Review changed data
and use the separate approved reapply procedure when that is the intended action.

## Approve a second production load

The normal one-completed-production-load guard remains. `--allow-reapply` alone is
refused. A reapply requires a private written confirmation file, bound to the
latest prior run, club ID, exact new input manifest, cut-over and selected steps.
It never permits production reset, unsupported identity/state merges, or writes
to manually created records without migration source IDs.

First preview the candidate without changing data:

```sh
bin/core migration:playoff --club=sample --in=/private/migration/reviewed-candidate \
  --cut-over=2026-11-01 --steps=1-10 --env=production --dry-run
```

The CLI prints a non-personal manifest SHA-256, cut-over and normalized selected
steps. A preview without a resume/approval validates the candidate; it does not
grant production execution. An explicit resume or approval is also checked for
binding during preview, without creating an attempt.

Record the actual approval in the private operational runbook, then create this
JSON outside Git and Dropbox/synchronized source directories, with owner-only
permissions (`chmod 600`). Replace every example value with the reviewed facts;
the club field is the internal club ID, not its slug. The timestamp must be no
earlier than the prior run's completion/start and no later than the server clock.

```json
{
  "version": 1,
  "clubId": "INTERNAL_CLUB_ID",
  "priorRunId": "LATEST_RUN_ID",
  "inputManifestSha256": "SHA256_FROM_CANDIDATE_PREVIEW",
  "cutover": "2026-11-01",
  "steps": [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
  "approvedBy": "OPERATOR_REFERENCE",
  "approvedAt": "APPROVAL_INSTANT_IN_UTC",
  "reason": "REVIEWED_REASON",
  "runbookReference": "PRIVATE_RUNBOOK_ENTRY"
}
```

The parser rejects symlinks, public/group permissions, duplicate/unknown keys,
trailing JSON, invalid selectors and files over 16 KiB. The database stores the
approval file's SHA-256 and prior run link, not its free-text contents.

```sh
bin/core migration:playoff --club=sample --in=/private/migration/reviewed-candidate \
  --cut-over=2026-11-01 --steps=1-10 --env=production --confirm-production \
  --allow-reapply --reapply-confirmation=/private/migration/approval.json
```

`--allow-reapply` and `--resume-run` are mutually exclusive. Omitting `--steps` on
an approved reapply selects all ten stages. A reapply starts a new input lineage;
an approved limited selection is only complete for that selection. The same
approval cannot be reused after a newer attempt exists. An interrupted approved
attempt continues through its exact-input `--resume-run`, rather than reusing the
original approval.

A committed import whose report upload fails is still committed. Recover with
`migration:report` as documented in [MIGRATION_REPORTS.md](MIGRATION_REPORTS.md),
without reapplying the business data. A successful local test does not constitute
approval for any real import, provider send or production cut-over.
