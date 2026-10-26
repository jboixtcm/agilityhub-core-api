# Migrated-account welcome runbook

`migration:welcome` implements S18 R-18-16/T-18-09. It is an explicit operator
command, separate from the import. It selects active imported club members with
an access account, sends the N-02 `MIGRATED` email with a `WELCOME` link, and records
accepted delivery so another run does not send it again. It never runs as a
scheduled migration step.

This document prepares the operation. No real welcome email was sent during the
implementation or its tests. Production execution belongs to the approved cut-over,
after the final import, reconciliation, working club domain/app and provider checks.

## Select the reviewed executable and environment

Use the release whose source and tests include this command. `bin/core` executes
the single `target/agilityhub-core-api-*.jar` already on disk; it builds only if no
JAR exists. It does **not** rebuild a stale JAR automatically. A checkout containing
new Java files is therefore insufficient: use a fresh verified build or the
reviewed digest-pinned release image.

On an operator host with the intended database/profile and provider configuration
already provisioned privately, the exact syntax is:

```sh
bin/core migration:welcome --club=canic --dry-run
```

Use `--option=value`, as shown. `--club canic` is not equivalent: the command
rejects positional arguments. Do not append options it does not accept, such as
`--apply`, `--env`, `--force`, `--resume` or `--confirm-production`.

For the deployed Compose stack, the corresponding preview uses the configured
Core image and environment without publishing ports or starting dependency
services:

```sh
export DEPLOY_ENV_FILE=/etc/agilityhub/core.env

docker compose --env-file "$DEPLOY_ENV_FILE" -f deploy/compose.prod.yaml \
  run --rm --no-deps -T -e SHARED_SCHEDULING_ENABLED=false core \
  --core.command=migration:welcome --club=canic --dry-run
```

The Core command entry point now installs a highest-priority scheduling guard:
`shared.scheduling.enabled=false` even when the normal server's environment enables
jobs. Normal web-server startup keeps its existing scheduling configuration.
Preview performs no welcome claim, creates no token and sends no email. Spring
startup still performs its normal connection/configuration/index checks; do not
interpret this as a read-only database transaction for the entire process.

Keep the populated environment file outside version control with the permissions
specified in [DEPLOY.md](DEPLOY.md). Do not print resolved Compose configuration,
provider keys, email addresses, tokens or credential URLs into release evidence.

## Preview, then send the selected group

A preview of an explicitly selected group:

```sh
bin/core migration:welcome --club=canic --only-numbers=12,24,31 --dry-run
```

Once that group and delivery are authorized, the corresponding send command is:

```sh
bin/core migration:welcome --club=canic --only-numbers=12,24,31 --batch=50 --interval=60s
```

The complete authorized population uses the same command without `--only-numbers`:

```sh
bin/core migration:welcome --club=canic --batch=50 --interval=60s
```

For Compose execution, retain the previous `docker compose … run … core` prefix
and use these same options after `--core.command=migration:welcome`.

Run one operator process at a time. Concurrent claims prevent duplicate delivery
of one account, but the pacing budget is per process; parallel processes are not
a shared rate limiter.

| Option | Behavior |
|---|---|
| `--club=<slug>` | Required; resolves the club and scopes member/membership/notification reads to it. |
| `--dry-run` | Lists aggregate eligibility and prior-attempt outcomes; no token, claim, email or batch sleep. |
| `--batch=<n>` | Default 50; integer from 1 to 50. Upper bound on attempted deliveries between waits in one run. |
| `--interval=<duration>` | Default `60s`; positive whole seconds (`s`) or minutes (`m`), at most one hour. No zero, fractions or other units. |
| `--only-numbers=<n,n,…>` | Optional comma-separated positive member numbers. Duplicates collapse. Empty entries and zero/negative/non-numeric values fail. |

Active ADMIN/INSTRUCTOR memberships go first, then other members. Each group is
ordered by member number, with unnumbered members last within the group and a
stable member-ID tie-breaker. There is a pause between the staff group and the
ordinary-member group if the staff group attempted delivery, even if its final
batch was smaller than the limit. Larger staff groups are also capped by the
batch limit. This is automatic ordering, not a manual approval stop after staff;
use `--only-numbers` for a separately reviewed staff pilot.

With no number filter, an imported active member whose number is null is still
included. With `--only-numbers`, only matching numbers are selected; an unnumbered
member cannot match. Members without an account, non-imported members and LEFT
members are not candidates. Candidates sharing an account are deduplicated within
the run. Account and membership activity, membership-to-member linkage and email
suppression are checked again before delivery.

## Read the aggregate outcome

The command prints a summary such as `DRY RUN {ELIGIBLE=…}` or
`WELCOME {SENT=…, ALREADY_SENT=…}`. Only nonzero categories appear. It prints no
recipient names, addresses, member numbers or credential links.

| Result | Meaning and action |
|---|---|
| `ELIGIBLE` | Preview only: eligible account with no previous welcome claim. This does not prove provider delivery, public DNS or frontend readiness. |
| `SENT` | The provider adapter accepted the email and the account marker was persisted. This does not prove inbox delivery. |
| `ALREADY_SENT` | A stored sent marker exists, or this club's recorded notification is SENT/DELIVERED. No new email or token is created. |
| `INELIGIBLE` | Account/member linkage or active membership/account conditions no longer permit delivery. Review the corresponding private account data. |
| `SUPPRESSED` | The account has an email suppression status. The command does not override it. |
| `REVIEW_REQUIRED` | A durable claim already exists without confirmed accepted delivery, another process won the claim, or this attempt did not reach confirmed acceptance. No automatic resend. |

Already-sent, ineligible and suppressed accounts do not consume the attempted-send
budget. `SENT` and `REVIEW_REQUIRED` do, conservatively. A send run containing any
`REVIEW_REQUIRED` finishes with CLI exit **1** after its summary. A preview can show
`REVIEW_REQUIRED` and still return 0: its outcome is an inventory to review, not
approval to send. Invalid options, startup failures and unexpected runtime errors
also return nonzero. A summary with no candidates is not proof that every intended
member was invited; compare it with the approved migration counts and filters.

## Resume and handle ambiguous acceptance

Each account is claimed atomically before creating the link. The claim records
`welcomeDeliveryId` and `welcomeClaimedAt`; the delivery ID is deterministic for the
club/account. Once the existing notification service records provider acceptance,
`welcomeSentAt` is set. These are durable account fields, not a process-memory
counter. The sent marker is account-wide, as specified in S18.

Rerun the same authorized command after a normal completion or interruption:

- A stored `welcomeSentAt` skips the account.
- If provider status was persisted as SENT/DELIVERED but the process stopped
  before storing `welcomeSentAt`, a send rerun repairs the marker without sending
  or creating a new token. A preview recognizes acceptance but leaves the marker
  unchanged.
- A claim with no notification, a QUEUED/FAILED notification, a claim belonging
  to a different club, or another still-running claimant requires review. The
  claim does not expire into an automatic retry. A provider timeout may mean
  either refusal or acceptance; blindly retrying could duplicate the email.

For `REVIEW_REQUIRED`, retain the claim and inspect the account's delivery ID,
the tenant-scoped notification status and the provider's acceptance evidence
privately. If another operator process is still running, let it finish and
preview again. Do not clear claims, relabel a notification SENT, or fabricate a
`welcomeSentAt` to make a retry green. This command deliberately offers no force
or claim-reset switch. If a recipient still needs access after review, use the
existing approved access-resend operation as a separate, explicitly authorized
communication; keep the ambiguous migration attempt recorded. A general rerun
must not be used as proof of acceptance.

A raw exception may end the process before the final aggregate summary. The next
preview/rerun relies on persisted claims and notification states, not on the
missing terminal summary. The CLI redacts raw persistence exception details.

## Link lifetime, language and verification limits

The command uses `MagicLinkToken.Purpose.WELCOME` for `clubs-app` and the club's
verified app host, linking to `/activacio`. The lifetime comes from
`auth.welcomeLinkDays` (default **7 days**), not an independent CLI constant. A
rerun never refreshes an already-sent link; the normal access flow handles expiry.

N-02 uses the `MIGRATED` title/body in Catalan, Spanish or English. The account's
locale takes precedence; the existing notification path falls back to the club's
default locale for a missing or unsupported locale. The club name and member's
first name come from the selected tenant's records.

Automated coverage lives in:

- `MigrationWelcomeCommandTest`: argument validation, staff ordering, batch waits,
  filtering, dry-run and summary/error behavior.
- `MigrationWelcomeDeliveryTest`: eligibility, suppression, claim loss, ambiguous
  prior attempts and staff-role checks.
- `MigrationWelcomeIT`: tenant selection including unnumbered members, seven-day
  default token, rendered migrated copy, repeat/concurrent delivery, recovery after
  provider acceptance, and failed/uncertain attempts using a fake email sender.
- `CommandSchedulingGuardTest`: deployment and command-line `true` cannot enable
  scheduling in a command process.

These tests use fictional accounts and a fake sender. They do not establish real
provider acceptance, public link reachability, inbox rendering or a completed
production cut-over. Record the final test run and release commit separately;
this runbook is not a substitute for those results.
