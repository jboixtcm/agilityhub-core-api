# Club definitions

Build the current source with `./mvnw -q package`, then run:

```sh
export SEED_PASSWORD='<choose a local test password>'
bin/core club:apply seeds/club-canic.yaml --dry-run
bin/core club:apply seeds/club-canic.yaml
bin/core club:export canic
bin/core club:apply seeds/club-minim.yaml
bin/core club:apply seeds/club-template-default.yaml
bin/core identity:seed-test-accounts --club=canic
```

`bin/core` builds the jar if it is missing. Rebuild explicitly after changing Java or
resources. Commands use the application's MongoDB environment variables and exit
with status 0 or 1. They start no HTTP server and disable background scheduling.
The Cànic definition contains 2 admins, 3 instructors and 10 members; the minimal
definition contains 1 admin and 2 members. All are fictional `@example.test`
accounts. Their `${SEED_PASSWORD}` references require that environment variable,
which has no default and is never printed. Omit `accounts[].password` to create
passwordless accounts. Passwords are accepted in local/test; staging/prod require
both an environment reference and `--allow-seed-passwords`. Literal passwords are
local/test only and must never be committed.

`identity:seed-test-accounts --club=canic` applies only the accounts section of
`seeds/club-canic.yaml` to an existing club. A definition path can replace the
`--club` option. The alias supports `--dry-run` and `--allow-seed-passwords` and
preserves club configuration.

The schema is JSON Schema 2020-12 and is also packaged in the application jar.
Localization is inside `club`, as specified by E0-T10. Use either `preset` or
`modules`. Only declared parameter overrides are changed; omitted overrides,
scoped overrides, and existing accounts are preserved. Account names, locales,
passwords and onboarding flags initialize new global accounts only; applying a
second club never resets them. `accounts[].roles` sets the exact tenant membership
roles, preserving status and member/instructor links. The legacy `admins[]`
section creates passwordless accounts and adds ADMIN while preserving other roles.
An email cannot appear twice across these sections. Provider names
are stored as configuration slots; existing configuration remains intact. This E0
format accepts provider names only, so credential values cannot enter a seed.

Diffs show added/changed/unchanged sections and the before/after fields. A dry run
performs no aggregate, parameter, identity, audit, or outbox writes. Infrastructure
startup may create collections and indexes. Reapplying unchanged input writes
nothing. Changed applies persist the club, parameters, accounts, memberships, `CLUB_UPDATED`
audit summary (`source: APPLY`), and outbox events in one Mongo transaction.

Local/test definitions trust their domains so local branding can be exercised.
Staging/prod domains are PENDING until the domain-verification vertical is
implemented. A template cannot declare domains. The template seed is not an
operational club.

Catalogs are applied transactionally with the club definition. The schema closes each
catalog shape. Levels and plans match by `code`, rings by `shortName`, FAQ by a stable
seed `code` (stored separately from its editable question), and prices by
`planCode` + `concept` + `validFrom`. Omitted entries and fields are preserved; exports
include current catalogs and all price history. Editing an existing price obeys the
normal immutable-history rules. Only the first price of an unused plan can bootstrap
historical validity (the seed uses 2026-01-01); subsequent prices use ordinary date
and overlap validation. Team rows require census members and belong to `seed:demo`;
`catalogs.instructors` must be empty. Message templates remain deferred to E7.

The Cànic has nine levels (including TER), five rings, five plans/current prices,
and seven active FAQ entries. All FAQ answers retain the provisional marker. D8
supplies the amounts; the EUR 10 therapy maintenance price and zero tax are the
provisional S05 §12 assumptions. Only enabled ca/es locales are stored in catalogs;
pages keep their existing ca/es/en placeholder translations. The consumer variant
has identical content with local routing hosts.

```sh
bin/core seed:demo --club=canic --seed=42
```

Demo generation requires local/test, an applied club definition and empty census
collections. `seeds/demo-canic.yaml` supplies all club-specific counts, names, catalog
codes and a fixed business reference date. The default seed is 42. It creates 184
ACTIVE, 3 PENDING, 4 INACTIVE and 3 LEFT members (194 total), 242 ACTIVE dogs,
12 two-person families, 3 instructors and 2 admins. Six dogs have downloadable
fictional PDF documents; the remaining vaccination documents are pending. The first
15 members link to the existing seed login accounts, preserving passwords and roles;
new `@example.test` accounts are passwordless. IBANs use fictional bank/branch 0000
and valid domestic/mod-97 check digits. No real data is read and no welcome mail is sent.
Member numbers are reserved for later signups.

Generation is deterministic for the same seed/specification/tenant; census IDs stay
stable. Infrastructure timestamps and account/attachment IDs are allocated when
persisting. A completed run makes subsequent runs no-ops and preserves manual demo
edits. A changed seed/specification or preexisting census is rejected with
`CLUB_NOT_EMPTY`; use a fresh disposable database for another dataset. Do not remove
the completion record to reset a live demo. Local document files use the configured
attachment directory; as with normal uploads, a failed transaction can leave an
unclaimed local file. Use a disposable attachment directory for test rehearsals.

To reproduce D1's active-member count, use
`GET /api/v1/members?size=20&filter=status:eq:ACTIVE` and inspect `totalItems` (184).
The universal list contract rejects size=1; an unfiltered list includes all 194
members. D1's occupancy and training counts (142/163, 56/38) need the later
scheduling/training fixtures and are not fabricated in this census seed.

Export includes account metadata and membership roles, excluding passwords and hashes.

The Cànic parameter catalog values are all product defaults, so its seed has an
empty override map. Its theme comes from the approved `01-acces.html` tokens.
The generic AgilityHub theme is a neutral blue/light seed preset; all example
administrators and contact addresses are fictional `@example.test` addresses.
