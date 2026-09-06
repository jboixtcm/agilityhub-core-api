# Club definitions

Build the current source with `./mvnw -q package`, then run:

```sh
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
`identity:seed-test-accounts` requires `SEED_PASSWORD` and is available only in
local/test profiles, with staging/prod explicitly excluded. Club admins created by
`club:apply` have no password; invitation/login onboarding belongs to E1.

The schema is JSON Schema 2020-12 and is also packaged in the application jar.
Localization is inside `club`, as specified by E0-T10. Use either `preset` or
`modules`. Only declared parameter overrides are changed; omitted overrides,
scoped overrides, and existing accounts are preserved. Existing memberships gain
ADMIN without resetting their state, member link, or other roles. Provider names
are stored as configuration slots; existing configuration remains intact. This E0
format accepts provider names only, so credential values cannot enter a seed.

Diffs show added/changed/unchanged sections and the before/after fields. A dry run
performs no aggregate, parameter, identity, audit, or outbox writes. Infrastructure
startup may create collections and indexes. Reapplying unchanged input writes
nothing. Changed applies persist the club, parameters, admins, `CLUB_UPDATED`
audit summary (`source: APPLY`), and outbox events in one Mongo transaction.

Local/test definitions trust their domains so local branding can be exercised.
Staging/prod domains are PENDING until the domain-verification vertical is
implemented. A template cannot declare domains. The template seed is not an
operational club.

Catalog entries use stable `code` identifiers. Their vertical-specific fields
remain extensible in the schema. Catalogs and message templates are validated but
are not persisted in E0; command output explicitly reports their deferred status.
They will be applied in E2 and E7. Export consequently returns empty sections for
those parts. Keep their source YAML until those stages implement persistence.

The Cànic parameter catalog values are all product defaults, so its seed has an
empty override map. Its theme comes from the approved `01-acces.html` tokens.
The generic AgilityHub theme is a neutral blue/light seed preset; all example
administrators and contact addresses are fictional `@example.test` addresses.
