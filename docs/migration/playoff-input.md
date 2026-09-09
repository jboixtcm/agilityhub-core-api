# Playoff census input, MappingConfig v1

This adapter follows `docs/MAPATGE_CAMPS_PLAYOFF.md` §§2–6 and S18 §§3–4.
Only fictional/anonymized fixtures belong in this repository. No live Playoff access,
receipt export, mandate export or photo download is needed by this command.

## Files and schema

Required: `socis.csv`, `tipologia.csv`, `nivell.csv`. Optional, explicitly supplied
club supplements: `family_groups.csv`, `equip.csv`. The latter two are adapter
schemas, not claimed to be native Playoff exports. The documented join columns of
Tipologia/Nivell are the v1 contract; additional source columns are reported until
an operator supplies a reviewed mapping. Every reference joins on `ID associat`;
team assignments join on `Núm. assoc.`. No family is inferred from a shared email.

CSV is UTF-8 (BOM accepted), comma or semicolon separated, with quoted multiline
cells and doubled quotes. XLSX is accepted by changing file names in a custom
mapping (`--mapping=file.yaml`); only the first sheet is read and formulas fail
validation. Dates are ISO `yyyy-MM-dd` or `d/M/yyyy`, interpreted in the target
club timezone. Headers and their positions must match. Both `Data naixement`
columns are retained: positions 11 (person) and 41 (dog). Missing/renamed columns
and malformed rows are errors with row/field/position references; additional
columns are warnings and are ignored by import. Anonymization rejects additional
columns until the mapping is reviewed, so unknown personal fields cannot leak.

The 51 exact Socis headers below come from the column mapping document. `keep`
means a structured non-identifying category/date or incident value; names, IDs,
contact details, bank details, chip/license identifiers, locations, photo URLs
and free-text notes are replaced or redacted. No source row value is printed.

### members

| Position | Exact header | Adapter field | Anonymization |
|---|---|---|---|
| 1 | ID associat | id | id |
| 2 | ID subcategoria | subcategoryId | id |
| 3 | Foto | photo | redact |
| 4 | Núm. assoc. | number | number |
| 5 | Estat | status | keep |
| 6 | Nom | firstName | name |
| 7 | Cognoms | surname | surname |
| 8 | NIF | document | document |
| 9 | Passaport | passport | passport |
| 10 | Té passaport? | hasPassport | keep |
| 11 | Data naixement | birthDate | keep |
| 12 | Edat | age | keep |
| 13 | Gènere | gender | keep |
| 14 | Estat civil | civilStatus | keep |
| 15 | Telèfon principal | phone | phone |
| 16 | Telèfon secundari | phone2 | phone |
| 17 | Codi postal | postalCode | postal |
| 18 | Domicili | street | address |
| 19 | Municipi | city | address |
| 20 | Província | province | address |
| 21 | País | country | keep |
| 22 | Nacionalitat | nationality | keep |
| 23 | Email principal | email | email |
| 24 | Email secundari | email2 | email |
| 25 | Web | web | redact |
| 26 | Data alta | joined | keep |
| 27 | Data baixa | left | keep |
| 28 | IBAN | iban | iban |
| 29 | Titular banc | holder | name |
| 30 | App+Notificacions | notifications | keep |
| 31 | Observacions | notes | redact |
| 32 | Impagats | unpaid | keep |
| 33 | Import impagats | unpaidAmount | keep |
| 34 | Data impagat | unpaidDate | keep |
| 35 | Pendents | pending | keep |
| 36 | Import pendents | pendingAmount | keep |
| 37 | Mètode pagament | payment | keep |
| 38 | Adjunts | attachments | redact |
| 39 | Nom del gos | dogName | dog |
| 40 | Sexe del gos | dogSex | keep |
| 41 | Data naixement | dogBirth | keep |
| 42 | Raça del gos | breed | keep |
| 43 | Numero de xip | chip | chip |
| 44 | Nom del guia | handler | name |
| 45 | Objectius | objectives | redact |
| 46 | Llicencia RSCE | rsce | license |
| 47 | Llicencia FCAG | fcag | license |
| 48 | Categoria RSCE | category | keep |
| 49 | Grau | grade | keep |
| 50 | Divisió | division | keep |
| 51 | Te clau | key | keep |

### plans

| Position | Exact header | Adapter field | Anonymization |
|---|---|---|---|
| 1 | ID associat | id | id |
| 2 | Tipologia | plan | keep |

### levels

| Position | Exact header | Adapter field | Anonymization |
|---|---|---|---|
| 1 | ID associat | id | id |
| 2 | Subcategoria | level | keep |
| 3 | Data assignació | assigned | keep |

### groups

| Position | Exact header | Adapter field | Anonymization |
|---|---|---|---|
| 1 | ID grup | groupId | id |
| 2 | ID associat | id | id |
| 3 | ID titular | holderId | id |

### team

| Position | Exact header | Adapter field | Anonymization |
|---|---|---|---|
| 1 | Núm. assoc. | number | number |
| 2 | Rol | role | keep |

## Commands

- `bin/core migration:anonymize <dir> <out> [--mapping=file.yaml]`
  requires `MIGRATION_ANONYMIZE_KEY` (at least 32 characters) in the environment.
  It runs before Spring/Mongo startup. Use the same one-use key for all related
  files to preserve joins, duplicate documents, numbers and shared emails; the
  same input and key produce the same cell values. Store real exports outside
  repositories and synced folders, encrypted under the operator runbook.
  Output must be a separate directory; existing files are never overwritten.
- `bin/core migration:playoff <dir> [--dry-run] [--club=slug] [--mapping=file.yaml]`
  defaults to the YAML's `defaultClub` (the supplied mapping uses `canic`).
  The target club and its catalogs must already exist. The importer only reads
  plans/levels/prices; missing or ambiguous mappings remain warnings with null
  links. It never creates seed catalogs or assigns a guessed current price.
- Apply with valid bank accounts requires `MIGRATION_BANK_KEY`, a base64-encoded
  32-byte AES key. Retain this key for S12 bank access. Stored IBANs use AES-GCM
  with fresh nonces and club/member authenticated context, plus `ibanLast4` for
  census displays. Plaintext IBANs are never persisted or included in reports.
- `--env=production` (also enforced by the prod/production Spring profile)
  requires `--confirm-production` for apply. A completed production run prevents
  another production apply with `MIGRATION_ALREADY_APPLIED`. No reset, overwrite
  or allow-reapply switch is offered in this stage.

## Mapping and incident handling

Case, leading/trailing whitespace and repeated internal spaces are normalized for
plan and level matching. `Quota reduïda` and `Familiar Abonat/curs` remain
`PLAN_UNMAPPED` (B30/B31); unknown old plans use `LEGACY_PLAN`. `Pendent` stays
unassigned with `LEVEL_PENDING` (B32). `Llicencia` is a marker, never a level;
`Terapies` produces a plan review warning. Photos remain untouched with a
`MAPPING_INVALID` warning (B29). Shared emails give the first eligible person
(by ACTIVE first, then earliest join date) the account; others retain contact
email but have no account (`EMAIL_SHARED`, B33). Explicit family files alone
create groups and select their payer; no group is guessed from typology alone.

Duplicate normalized identity documents create one member and a dog per source
record. Without a document, normalized email is the fallback key; without either,
the source id identifies the person. Source aliases persist in
`Member.externalIds.playoff[]`; dogs/groups store `externalIds.playoff`. The
existing census model also keeps the S18 `sourceIds` projection. IDs are stable
and tenant-specific. Reapply changes mapped fields and preserves unrelated fields,
existing account profiles/passwords/consents and completed onboarding. Existing
manual records that collide with imported identities are reported, never adopted.

ACTIVE records win number collisions. Old or undated LEFT rows are skipped while
their numbers contribute to `Club.nextMemberNumber`. A24a's calendar-year reading
includes leave dates from January 1 of (current club year minus the catalog's
`migration.leftMaxYears`), i.e. 2021 onward in 2026. The same catalog parameter
controls other target years. Person ages below the mapping's warning threshold
are accepted with `AGE_SUSPECT`; very young person dates with no dog birth date
are cleared with `BIRTHDATE_SUSPECT`. Missing dogs/chips/bank/contact data remain
importable and are listed by source file and row, using S18 incident codes or the
closest approved validation code. Incident labels are report values, not new API
error enum entries.

The supplied exports contain no image consent evidence, so the importer creates
only a `LEGACY` privacy provenance marker and never invents image consent or
current legal acceptance. New accounts are created with `onboardingPending=true`,
no password, and the club's default locale; existing accounts remain intact.
Memberships get MEMBER plus explicit team/instructor roles, with LEFT memberships
suspended. Loading sends no notifications or welcome links.

Every SEPA record receives a new cutover mandate reference using club slug and
member number (stable member id if the number is absent), with the club-local
cutover date, preserved on reapply. These exports contain no mandate fields;
S12 owns remittance generation and reads the existing `billing.sepa.useFrst=false`
product default for RCUR. RSCE category/grade and FCAG division populate only their respective licenses.
Card methods become MANUAL with `CARD_NOT_MIGRATED` and no guessed cash channel;
invalid/absent IBANs stay absent. Future leave dates are retained on the member;
S13 owns the eventual leave workflow. No auxiliary inactivity-period input is
invented where the known three exports provide none.

## Transactions, report and fixtures

Dry-run performs reads only, including no run, audit, outbox, identity or sequence
writes. Any input error prevents apply. Census-only apply uses one transaction
under the existing tenant census/catalog locks so a failed row cannot leave a
partial person/dog/group graph. This stage does not implement S18's later billing
batch checkpoints. A failed apply rolls back the complete graph and stores a FAILED run plus a
MigrationRunFailed event in a separate transaction, so the operator can retry.
A successful run emits cataloged MigrationRunStarted/Completed
and insert-only AccountCreated events; record notifications are suppressed. One
`@Audited CATALOG_CHANGED` targets MigrationRun with counters: the closed audit
action list has no MIGRATION_APPLIED, which is proposed to the organizer.

The report contains adapter file keys, one-based source row numbers (header is
row 1), outcomes and fixed incident/field identifiers. It does not contain names,
source document numbers, emails, account IDs, IBANs or encrypted values. Reports
are printed to stdout; dry-run creates no report file.

`src/test/resources/fixtures/playoff/` contains 184 fictional ACTIVE source rows
plus 7 LEFT examples: 26 missing IBANs, 9 inferred dogs, 16 missing chips, 7 missing
emails, 20 shared-email second owners, 4 number conflicts, 2 suspicious ages and
one duplicated document (one person/two dogs). Additional rows cover invalid IBAN,
card fallback, old/undated leave and explicit family/team joins. Anonymizer tests
exercise a 50-row synthetic export and verify deterministic replacement and
relationship preservation. No production data was accessed to build this fixture.
