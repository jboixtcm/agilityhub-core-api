# Deployment recipes

## Run the published image (E1-T14)

Requirements: Docker with Compose v2.20+, `curl`, and access to the private GHCR
package. No host Java or Maven is required. CI publishes
`ghcr.io/jboixtcm/agilityhub-core-api:main` and `:sha-<seven-character-commit>` for
both `linux/amd64` and `linux/arm64` after tests, coverage, OpenAPI and the secret
scan pass on a `main` push. PRs and manual runs only validate; GitHub's existing
`[skip ci]` push behavior remains in force. Actions are pinned to commits.
The build stage uses the builder's native architecture for the portable Java jar;
the runtime uses the requested target architecture. See the
[Docker multi-platform workflow](https://docs.docker.com/build/ci/github-actions/multi-platform/).

The first package publication is private by default and the workflow associates
it with this private repository; keep its inherited repository access. Local
pulls need a credential with package read access. If the GitHub CLI's stored
credential has that access, pipe it directly to Docker:

```sh
gh auth token | docker login ghcr.io -u "$(gh api user --jq .login)" --password-stdin
docker pull ghcr.io/jboixtcm/agilityhub-core-api:main
cp .env.consumer.example .env.consumer
chmod 600 .env.consumer
# Edit .env.consumer: set a local SEED_PASSWORD of at least 12 characters.
bin/consumer-up
curl -4 -fsS http://127.0.0.1:8080/api/v1/health
docker compose --env-file .env.consumer -f docker-compose.consumer.yml ps -a
```

If the stored CLI credential cannot pull packages, use a classic PAT with
`read:packages` and repository/package access via `docker login --password-stdin`.
Do not print the token or save it in the consumer env file. GitHub documents
[GHCR authentication and visibility](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).
The CI publisher uses only `GITHUB_TOKEN` with job-scoped `packages: write`.

`bin/consumer-up` wraps `docker compose --env-file .env.consumer
-f docker-compose.consumer.yml up -d --wait`; `CONSUMER_ENV_FILE` can select another
env file. From a different repo, copy `docker-compose.consumer.yml`,
`.env.consumer.example` and the two `bin/consumer-*` helpers with the same relative
layout, or run the Compose command directly. No source checkout mounts are used.
The image contains both seed files. Its local Cànic seed is
`seeds/club-canic-consumer.yaml`, installed as `/app/seeds/club-canic.yaml`; it
differs from the canonical seed only in the two domain hosts. Keep those fixture
datasets aligned when changing seeds.

Startup waits for Mongo's single-node `rs0` PRIMARY, then the one-shot `seed`
service applies `/app/seeds/club-canic.yaml` and `/app/seeds/club-minim.yaml`, then
the non-root `core` becomes healthy. An unsuccessful seed prevents API startup.
Account examples: `admin@example.test`, `instructor@example.test`,
`member@example.test`, and `minim.admin@example.test`. All passwords come from
`SEED_PASSWORD`; repeated applies preserve existing credentials. To change this
local password for existing fixtures, reset the disposable volumes and reseed.

The API is published at `127.0.0.1:8080` (`CONSUMER_PORT` overrides it), and
Mongo at `127.0.0.1:27017` (`MONGO_PORT` overrides it). The management listener
stays private. Use `MONGO_PORT=27018 bin/consumer-up` alongside a local Mongo;
containers still connect to `mongo:27017`. For the Mac gate, use `127.0.0.1`
or `curl -4`: `localhost` can reach an unrelated native IPv6 listener. Mongo data and `MAIL_LOCAL_DIRECTORY=/app/mailbox`
use named volumes. The mailbox is owned by the image's non-root user with private
permissions; messages go to the local sink. Copy it locally when inspecting a
fictional magic link:

```sh
mailbox_dir="$(mktemp -d)"
docker compose --env-file .env.consumer -f docker-compose.consumer.yml cp core:/app/mailbox/. "$mailbox_dir/"
```

Treat the mailbox contents as local credentials; keep them out of version control
and delete the copied directory after use. The local profile uses an ephemeral
OIDC signing ring (`OIDC_MASTER_KEY` empty); restarting core invalidates existing
access tokens, which can be refreshed. Deployment mail/key variables are not
inherited by this stack.

For browser development, map these names in `/etc/hosts` and serve each SPA on its
matching host (including Vite's port):

```text
127.0.0.1 app.example.test admin.example.test id.example.test minim.example.test
```

Use the Vite proxy below with `changeOrigin: false` to preserve Host. Alternatively,
when using `localhost` without a hosts-file edit, set a separate upstream Host for
each SPA, for example this app proxy (use `admin.example.test` / `id.example.test`
in the other SPAs):

```typescript
const backend = {
  target: 'http://127.0.0.1:8080',
  changeOrigin: false,
  configure(proxy) {
    proxy.on('proxyReq', (request) => {
      request.setHeader('Host', 'app.example.test')
      if (request.getHeader('Origin')) request.setHeader('Origin', 'http://app.example.test')
      if (request.getHeader('Referer')) request.setHeader('Referer', 'http://app.example.test/')
    })
  },
}
// Assign backend to /api, /oauth2, /.well-known and /connect/logout.
```

The header rewrite is for a loopback development proxy only; bind Vite to loopback
and keep its host allowlist. Each SPA needs its own browser host for independent
cookies. Handoff/magic-link URLs still use seeded hosts; full browser redirects
and the Secure OIDC browser cookie require the HTTPS hosts/Caddy recipe below.
The HTTP smoke simulates these hosts explicitly and carries the Secure cookie.

To update, `docker compose --env-file .env.consumer -f docker-compose.consumer.yml
pull` then `bin/consumer-up`. To stop while retaining data, use
`bin/consumer-down`; to delete this consumer project's data and mailbox, use
`bin/consumer-down -v`. Choose a different `COMPOSE_PROJECT_NAME` and
`CONSUMER_PORT` for simultaneous independent consumers.

The same E1 assertions can run against any compatible image:

```sh
bin/e1-smoke --image ghcr.io/jboixtcm/agilityhub-core-api:main
# Before CI's first publication, build the exact same Dockerfile locally:
docker build -t ghcr.io/jboixtcm/agilityhub-core-api:local .
CORE_IMAGE=ghcr.io/jboixtcm/agilityhub-core-api:local bin/consumer-up
bin/e1-smoke --image ghcr.io/jboixtcm/agilityhub-core-api:local
```

Smoke additionally needs Python 3, `bin/e1-smoke`, the consumer Compose file and
`src/test/resources/fixtures/learn-users.csv`. It creates a random project/port,
password, database and mailbox, runs CLI commands from the image, and removes its
own containers/volumes even on failure. It never changes an existing consumer
stack. The no-argument smoke still uses a locally built jar.

**Staging handoff (E0-T13):** pull a reviewed `:sha-…` tag (or pin its digest),
rather than building a checkout or following mutable `:main`. The consumer file
is local-only. E0-T13 must provide its staging profile, authenticated Mongo,
persistent backed-up `OIDC_MASTER_KEY`, real secrets and verified HTTPS hosts;
fictional auto-seeding and ephemeral signing stay in the local stack. The organizer
checks the first real publish run, package access and both architectures in Actions.

## Browser hosts and refresh cookies (E1-T13 / A1)

Serve every club app, club admin and identity SPA through its own HTTPS host.
The browser calls relative URLs and keeps the access token in memory. The API
sets `ah_refresh` as a host-only cookie with `HttpOnly; Secure; SameSite=Strict;
Path=/oauth2/token`; its lifetime is the effective `auth.sessionDays` in seconds.
Each successful refresh rotates both the persisted token and the cookie.
`clubs-app`, `clubs-admin` and `id-web` use `token-delivery: COOKIE` in
`core.oidc.clients`; `learn` and `ar-app` retain `BODY` delivery. This is client
configuration, not a catalog parameter. Missing or unknown delivery values fail
startup.

Caddy template for each app host (replace the fictional host and SPA directory):

```caddyfile
app.example.test {
    handle /api/* {
        reverse_proxy core:8080
    }
    @identity path /oauth2/* /.well-known/* /connect/logout
    handle @identity {
        reverse_proxy id:8080
    }
    handle {
        root * /srv/app
        try_files {path} /index.html
        file_server
    }
}
```

`core:8080` and `id:8080` denote private upstream services. With the current modular
monolith both routes can use `core:8080`; `id` is the identity upstream name when
separately routed. Preserve the original `Host`, `Origin`, `Referer`, `Cookie` and
all `Set-Cookie` response headers, including the two logout headers. Do not rewrite
cookie paths or domains. Caddy preserves Host for these HTTP upstreams. On an API
reachable only through the trusted proxy, enable Spring's
`server.forward-headers-strategy=framework` to recognize HTTPS forwarded by Caddy.
The proxy must overwrite client-supplied forwarding headers. Keep the management
port and direct upstream ports private. Register and verify each club host and its
OIDC callback before directing traffic to it.

A browser refresh posts form data `grant_type=refresh_token&client_id=clubs-app`
to `/oauth2/token`, with `credentials: 'same-origin'`. The browser sends the cookie;
it does not read or copy it into the body. The request must supply a same-host
`Origin`, or a same-host `Referer` when Origin is absent. An invalid supplied
Origin cannot fall back to Referer. Tokens remain bound to their registered client
and tenant. Reuse/expiry clears the cookie and returns the existing catalog error.
Magic-link and handoff redemption run on the destination host to create its own
first-party cookie. OIDC code exchange for a COOKIE client likewise runs through
that client's app proxy.

The cookie path intentionally excludes `/oauth2/revoke`. For a COOKIE client,
post `{}` with its bearer access token to that route to revoke the bearer session
and expire the cookie. Explicit `{token}` revocation remains supported for BODY
clients and impersonation. Deleting the current `/api/v1/me/sessions/{id}` clears
the cookie too; deleting another device does not. `/connect/logout` validates the
existing RP logout request and expires both identity and refresh cookies. A server
cannot delete a cookie on another host/device: it rejects that revoked family's
next refresh and clears the cookie on that response.

No credentialed cross-origin CORS is needed for SPAs. The existing CORS policy for
other consumers is retained. This section is a recipe for E0-T13/E10; it does not
publish or change a live Caddy installation.

## Local Vite proxy (E1-W07)

Use the same relative paths in development. An example `vite.config.ts` fragment:

```typescript
const backend = { target: 'http://127.0.0.1:8080', changeOrigin: false }
export default {
  server: {
    host: '127.0.0.1',
    allowedHosts: ['app.example.test'],
    proxy: {
      '/api': backend,
      '/oauth2': backend,
      '/.well-known': backend,
      '/connect/logout': backend,
    },
  },
}
```

Map `app.example.test` to loopback locally and register that host on the fictional
dev club; choose the corresponding admin/identity host for each SPA. Preserve the
browser Host instead of substituting the backend address. Run the API with the
`local` profile. Only plain HTTP under `local` relaxes `Secure`; HTTPS and all
other profiles retain it, and `prod`/`staging` take precedence over `local`.

## Bootstrap and manage platform admins (E1-T13 / A3)

After the intended person's account exists, run on the deployment host:

```sh
bin/core identity:grant-platform-admin operator@example.test
```

Replace the fictional example email with the existing account email. The command
normalizes it, grants `AGILITYHUB_ADMIN` idempotently and audits as SYSTEM; it never
creates an account or prints credentials. Use the deployment's normal environment,
including its Mongo connection and `OIDC_MASTER_KEY`. No real person or platform
admin credential belongs in a seed. Start a fresh login/refresh after a grant to
obtain the updated platform-role claim.

An existing platform admin can use `GET` and `PUT`
`/api/v1/platform/accounts/{id}/platform-roles` with `{ "platformRoles": [...] }`.
Only `AGILITYHUB_ADMIN` is supported. These global routes check the caller's live
stored role, require no club membership and audit changes atomically without a
domain event. Concurrent removals preserve one ACTIVE platform admin; removing
the last returns `409 LAST_PLATFORM_ADMIN`.

## Object storage (exports and attachments)

Use a private S3-compatible bucket with separate `exports/` and `attachments/`
prefixes, or separate private buckets. Set `EXPORT_S3_BUCKET`, `EXPORT_S3_REGION`,
`EXPORT_S3_ACCESS_KEY`, `EXPORT_S3_SECRET_KEY` and the corresponding
`ATTACHMENT_S3_*` variables from [.env.example](../.env.example). Both adapters
accept an optional `*_S3_ENDPOINT` for a compatible provider. Keep credentials in
the deployment environment. Limit the export principal to Get/Put/DeleteObject
under `exports/`; limit the attachment principal to Get/PutObject (including HEAD)
under `attachments/` and `signup/`. A shared principal needs the union of those
permissions, restricted to those three prefixes. Public access must remain disabled.

Configure the bucket's CORS allowlist for the actual HTTPS app/admin origins.
Attachment uploads use presigned `PUT` requests binding `Content-Type`,
`Content-Length` and `If-None-Match: *`; allow those headers and the `PUT` method
(the browser sets Content-Length). Allow `GET`/`HEAD` if the frontend fetches
signed downloads directly. Attachment links last five minutes. Export links expire
with the file seven days after READY; the existing worker deletes expired export
objects. Use lifecycle expiration on `exports/` as a crash-cleanup backstop with
slack beyond that seven-day READY window. Abort incomplete multipart uploads as
an additional backstop. Do not apply blanket expiry to `attachments/`: completed
attachments remain live. The current adapter has no orphan-object tagging or
cleanup worker; reconcile unreferenced uploads against attachment metadata before
deleting them. Bucket age alone does not distinguish abandoned and live uploads.

`staging`/`prod` require both sets of bucket, region and credentials; missing
values fail startup with a missing-configuration error, even when `local` is also
active. Startup checks configuration presence, not remote bucket access: verify
an upload, completion/HEAD, signed download and export cleanup on deployment.

`local` uses `EXPORT_LOCAL_DIRECTORY=./.local/exports` and
`ATTACHMENT_LOCAL_DIRECTORY=./.local/attachments`; tests use `target/test-exports`
and `target/test-attachments`. Both development Compose files mount named
`export-files` and `attachment-files` volumes at `/app/exports` and
`/app/attachments`, writable by the image's UID 10001. Files use private
permissions (directories 0700, files 0600). Local signed URLs also require bearer
authentication. Optional `EXPORT_SIGNING_KEY` (base64, at least 32 bytes) preserves
export signatures over restarts; otherwise it is ephemeral. Attachment signatures
are always ephemeral, so obtain fresh links after a restart. Retain these volumes
when recreating containers; removing volumes deletes their stored files.

## E3 signup and dashboard gate (backend)

Run from the API checkout with Docker, Compose, Python 3 and curl:

```sh
bin/e3-smoke
bin/e3-smoke
# Reuse a compatible published image without a local Java/Maven build:
bin/e3-smoke --image ghcr.io/jboixtcm/agilityhub-core-api:main
# Or exercise consumer Compose with the image built by the first command:
bin/e3-smoke --image agilityhub-e3-smoke:local
```

The default builds the current Dockerfile and runs `compose.yaml`. Image mode
runs `docker-compose.consumer.yml`; the image must include E3-T03/T04/T05 and
`seeds/demo-canic.yaml`. Both modes use a new random Compose project, free
loopback ports, private generated credentials, a Mongo replica set, the same
Cànic catalogs/demo seed and the E1 local mailbox. Each seed is applied twice
inside the same database and its second apply must report zero changes. The
smoke activates only its disposable club, executes HTTP requests using curl,
checks exact statuses and removes its containers, volumes and temporary files
on success or failure. The built image remains cached. Existing local stacks
are unaffected. A failed assertion exits nonzero; credentials, signed upload
URLs and welcome capabilities never appear in the output.

E3 runtime settings (all secrets remain environment-only):

| Setting | Purpose |
|---|---|
| `SIGNUP_CAPABILITY_KEY` | Base64-encoded **32 bytes**, required in staging/prod. Signs 24-hour tenant/member signup capabilities and encrypts anonymous idempotency replays. Keep the key stable across API replicas and restarts; changing it invalidates outstanding capabilities/replays. Both local Compose files accept it; an empty local/test value uses an ephemeral key. |
| `BOOKING_CALENDAR_KEY` | Base64-encoded **32 bytes**, required in staging/prod. Signs the `.ics` links of class bookings (S08 R-08-08, valid until the class ends). Keep it stable across replicas and restarts; changing it invalidates the calendar links already sent. An empty local/test value uses an ephemeral key. |
| `MAIL_LOCAL_DIRECTORY` | Local/test mailbox JSON directory; Compose uses `/app/mailbox` on a private named volume. N-01/N-02/N-03 mail can be correlated by `tags.notificationId` with the notification log. N-37 is APP-only. Copy messages using the mailbox recipe above, then remove the private copy. |
| `ATTACHMENT_LOCAL_DIRECTORY` | Local uploaded-file directory (`/app/attachments` in Compose). Signup keys use `signup/<clubId>/<yyyyMM>/<uuid>/<filename>`; the month is club-local and the prefix is fixed by the adapter, not an environment setting. |
| `ATTACHMENT_S3_BUCKET`, `ATTACHMENT_S3_REGION`, `ATTACHMENT_S3_ACCESS_KEY`, `ATTACHMENT_S3_SECRET_KEY`, optional `ATTACHMENT_S3_ENDPOINT` | Existing staging/prod private storage settings. Include `signup/` as well as `attachments/` in IAM/CORS verification. Signed signup PUT grants last 15 minutes; claimed documents must not be expired by a blanket signup-prefix lifecycle. |
| `SPRING_PROFILES_ACTIVE` | `local`/`test` selects `FakeCheckoutGateway`, which returns `https://checkout.test/<sessionId>` and makes no Stripe call. It is unavailable in staging/prod. Real Stripe credentials, webhook wiring and checkout completion are E8; no new Stripe environment variable or public fake-completion route is introduced by E3. |

The Cànic gate seed enables SEPA and manual payments; it does not enable CARD.
The smoke verifies SEPA without an IBAN produces `ACCOUNT_NOT_PROVIDED`, then
records the upfront amount through D2 validation. Fake checkout lifecycle
coverage remains in `SignupIT`; this gate does not claim a real Stripe payment.

For E3-W03 browser work, use the same published image and the existing consumer
host/proxy/mailbox setup above. On a **fresh disposable consumer project**:

```sh
bin/consumer-up
docker compose --env-file .env.consumer -f docker-compose.consumer.yml run --rm --no-deps -T --entrypoint java seed -jar /app/app.jar --core.command=seed:demo --club=canic --seed=42
# Repeat: reports 0 changes, preserving subsequent reviewer edits and validation.
docker compose --env-file .env.consumer -f docker-compose.consumer.yml run --rm --no-deps -T --entrypoint java seed -jar /app/app.jar --core.command=seed:demo --club=canic --seed=42
# Local rehearsal only: committed club definitions deliberately remain ONBOARDING.
docker compose --env-file .env.consumer -f docker-compose.consumer.yml exec -T mongo mongosh --quiet agilityhub --eval 'db.clubs.updateOne({slug:"canic"},{$set:{status:"ACTIVE"}})'
docker compose --env-file .env.consumer -f docker-compose.consumer.yml restart core
```

Use the configured `CONSUMER_DATABASE` instead of `agilityhub` if overridden.
Restart after fixture activation clears the existing configuration caches.
Login with `admin@example.test` and the local seed password. D1 starts with
184 ACTIVE members, 242 ACTIVE dogs and three PENDING public applications
(two recent and one aged three days with `ACCOUNT_NOT_PROVIDED`). All three
have pending dogs, requested plans, consent evidence and upfront lines for D2;
they have no membership/account or member number until validation. Total census
counts are 194 members and 245 dogs. Ages use the club-local day of the first
demo apply and then age naturally; the default warning threshold is two days.
The demo seed is insert-only: reapply preserves dates, attachments, edits and
validated/rejected records. An older demo specification fails with
`CLUB_NOT_EMPTY`; use a fresh disposable project to adopt this fixture revision.
Occupancy is `{percent:null, booked:0, capacity:0, waitingTotal:0}` and training
is `{value:0, distinctMembers:0}` until E4/E5 supply scheduling/booking data.

Organizer-run checklist for Gate E3 (back); copy its evidence links into the
organizer-owned `roadmap/ROADMAP.md` after review:

- [ ] Run `bin/e3-smoke` twice successfully and retain both complete outputs.
- [ ] Confirm GET signup is enabled with four steps, offered plans/methods,
  identity `NEW`, a real tiny PDF uploaded and claimed, and seeded family lookup
  `FOUND`.
- [ ] Confirm public signup with family, anonymous replay, D1 pending count and
  overdue warning, D2 missing-IBAN warning, effect-free dry run, level/invoice-date
  selection, collected upfront amount, consecutive member number and family link.
- [ ] Confirm N-02 in the private mailbox and exchange that actual welcome link;
  `/me` must identify the validated member.
- [ ] Confirm an ACTIVE member adds a dog, receives N-01, appears in D1, is
  validated without changing member/account/number, and receives N-37 in the
  APP inbox.
- [ ] Confirm signup without family can be rejected, N-03 reaches its applicant,
  and dashboard/menu counters return to the baseline pending count.
- [ ] Set `signup.enabled=false`: GET returns a closed configuration and POST
  returns **422 `SIGNUP_CLOSED`**, the approved catalog status (the older task
  example says 409).
- [ ] Run `./mvnw -q verify`, review coverage/architecture/catalog checks, and
  repeat image mode with the reviewed published tag. Record that tag through the
  organizer/publish workflow; the executor never commits or pushes.
- [ ] E3-W03: repeat the scenario through the browser with the same seed/mailbox,
  capture D1/D2 screens, and retain the E4/E5 zero/null KPI boundary.

Backend evidence: `roadmap/evidence/E3-T05/` and the E3-T05 Executor report.
Browser/staging and remote publication checks remain organizer-run gate items.

## E4 planning and activities gate (backend)

Run from the API checkout with Docker, Compose, Python 3 and curl:

```sh
bin/e4-smoke
bin/e4-smoke
# Consumer Compose with an image that already contains E4-T02…T05 and seeds/demo-canic.yaml:
bin/e4-smoke --image agilityhub-e4-smoke:local
```

Same isolation as `bin/e3-smoke`: a new random Compose project, free loopback
ports, generated `SEED_PASSWORD`, the local mailbox volume, exact HTTP statuses,
and `down --volumes` on success or failure. It applies `seeds/club-canic.yaml` and
`seed:demo --seed=42` twice (second runs: 0 changes), activates only its
disposable club and provisions a **runtime-only random public API key** by storing
its SHA-256 in that club's `publicApiKeyHash` (no key provisioning API exists yet;
the key is never printed). It then runs: templates (B inconsistent) → generation
of week +3 from «Setmana B» `422 TEMPLATE_INCONSISTENT` → candidates propose
week +3 → generation from A + Dissabtes → DRAFT calendar → member grid without
drafts → validation → member/instructor grids → cancellation of the seeded
Wednesday 18:50 B+C class (`422 ADMIN_TEXT_REQUIRED`, then 4 bookings + 2 waitlist)
→ `ClassCancelledByClub` in the outbox and N-08a APP/EMAIL (local mailbox)/SMS
`QUEUED` rows for the 6 registrants → ring block over a class `409`, on a free slot
`201` (OCCUPIED/BLOCK) → an activity with a run suffix that conflicts, publishes with
`cancelClasses` and blocks Central → member `/me/activities` + registration →
public API `403`/`200` without registrant data → activity cancellation with N-32c →
both `finish-ended` commands → summary table. Any failed assertion exits nonzero.

E4 adds **no environment variable**: planning and activities use the existing Mongo,
mailbox, attachment and profile settings. Since E5-T02 the demo class registrants are
real S08 bookings (the E4-T05 `demo_class_bookings` adapter is gone); the pack and
inactivity stand-ins the seed and tests use are active only in `local`/`test`, and the
only new variable is `BOOKING_CALENDAR_KEY` (see the table above).

For browser work (E4-W…), seed a fresh disposable consumer project exactly as in
the E3 recipe above: the same `seed:demo --club=canic --seed=42` command now adds
the E4 planning and activities dated relative to the run week
(`seeds/README.md` → «E4 planning and activities»).

Organizer-run checklist for Gate E4 (back); copy its evidence links into the
organizer-owned `roadmap/ROADMAP.md` after review:

- [ ] Run `bin/e4-smoke` twice successfully and retain both complete outputs
  (`roadmap/evidence/E4-T05/`).
- [ ] «L'admin genera i valida una setmana des de les plantilles (A/B + dissabtes)»:
  smoke lines `POST /weeks/{week+3}/generation (Setmana B) · 422`, `… (A + Dissabtes) · 200`
  and `POST /weeks/{week+3}/validation · 200`.
- [ ] «D4c anul·la una classe amb inscrits ficticis (transacció + esdeveniments a
  l'outbox)»: smoke lines for the cancellation (4/2), `outbox ClassCancelledByClub`
  and `notifications N-08a`.
- [ ] «Una activitat publicada bloqueja la pista»: smoke lines for `ring-conflicts`,
  `publication · 409`, `publication {cancelClasses} · 200 PUBLISHED` and the calendar block.
- [ ] Front (organizer-run, E4-W…): «alumne i instructor veuen 10/23» — log in as
  `member@example.test` and `instructor@example.test` on the seeded stack and open
  mobile screens 10 and 23 on a week +2 day; «apareix a 04» — the published
  activities («Lliga social — 3a jornada» is open to members) appear in screen 04.
- [ ] Run `./mvnw -q verify` (includes `DemoPlanningSeedIT`) and repeat image mode
  with the reviewed published tag.

## E5 bookings, waiting lists, free training and processes gate (backend)

Run from the API checkout with Docker, Compose, Python 3 and curl (k6 optional: `bin/e5-perf` falls back to the
`grafana/k6` image):

```sh
bin/e5-smoke
bin/e5-smoke
bin/e5-smoke --image <tag>     # consumer Compose with an image that contains E5-T06 and seeds/club-fifo.yaml, demo-fifo.yaml
bin/e5-perf                    # k6 (ruling E28): peak 300 distinct members in 5 s on 10 empty 5-seat classes + last seat
                               # 50 at once + burst; zero-overbooking check; load-test club seeds/club-perf.yaml (perf/README.md)
```

Same isolation as `bin/e4-smoke`: each run uses a new random Compose project, free loopback ports and a generated
`SEED_PASSWORD`, checks exact HTTP statuses and codes, and runs `down --volumes` on success or failure. It applies
`seeds/club-canic.yaml` and `seeds/club-fifo.yaml` (the fictional FIFO + `SINGLE_CLASS` club), and runs
`seed:demo --seed=42` for each club twice with `--week-start` on the Monday at least 8 days ahead, so nothing seeded
is due in real time. The second run of every command prints 0 changes. The smoke activates both disposable clubs and
starts the API **with the scheduler on**. It switches P2 off until P2's own step. Then it moves the test clock
(`POST /api/v1/test/clock`, local/test only) through the anchor week and signs in again after every move, because
access tokens follow the moved clock:

1. **FIFO club, before the jump:** only entry 1 is `NOTIFIED`; one booking is `PAYMENT_PENDING`.
2. **Monday 07:00:** the scheduler's P6 run expires entry 1 and notifies entry 2 (N-15 with `confirm_by`). Its P7
   run cancels the stale booking with `CANCELLED{PAYMENT_TIMEOUT}` and N-40.
3. **Screens 03 and 04:** `GET /me/home` returns the chips and the chronological rows of the four sources.
   `GET /me/bookable-classes` for four members shows the six live row states.
4. **Booking cycle:** hold, then the same hold refreshed; confirm (201 with `calendarLinks`); the same
   `Idempotency-Key` again (identical 201); `GET /bookings/{id}`; cancellation in time (`late = false`,
   `SeatReleased{notifyWaitlist}`).
5. **Monday 08:10:** a cancellation 20 min before the class gives `CANCELLED_LATE`, `late = true`,
   `notifyWaitlist = false`.
6. **Swap at the limit:** the hold returns `limit.swappable`; confirming with `swapBookingId` gives old
   `CANCELLED{SWAP}` + new `ACTIVE`.
7. **ALL_AT_ONCE waiting list:** a member joins a `WAITLIST_OPEN` class (`WaitlistJoined`). Another member frees a
   seat in time: every waiting member is `NOTIFIED` with N-15. Hold + claim gives `CONSOLIDATED`, and the others go
   back to `ACTIVE` with N-46.
8. **Free training:** the counter goes from 2/3 to 3/3 with a «Qualsevol» booking (first free ring in catalog order);
   a fourth booking gets `409 TRAINING_LIMIT_REACHED{cancellableBookings}`. An admin ring block over a live training
   booking gets `422 RING_HAS_BOOKINGS`; with `cancelBookings` it gets 201, the booking is `CANCELLED_BY_CLUB`, and
   N-47 is sent.
9. **Tuesday 07:20:** P2 is switched on and dry-run (`WOULD_CANCEL` / `WOULD_NOTIFY`). At 07:30 the scheduler's P2
   cancels today's 1-registrant class (`CANCELLED{RISK_REVIEW}`, N-17 + N-08a), warns tomorrow's (N-16) and keeps
   the exempt class.
10. **Minimum:** an in-time cancellation that leaves one dog of `classes.minDogs` gives exactly one
    `ClassBelowMinimum` and N-54.
11. **Sunday 20:00:** the admin first switches `messaging.notifyWeekOpening` on (`PUT /parameters/…`; the
    catalog default is off). Then the scheduler's P1 opens W+2 (validated), with `WeekOpened{notified}` and the
    N-33 rows.
12. **CLI:** `bin/core jobs:run cleanup --club=canic`, then `waitlist-fifo` and `payment-timeouts --club=fifo`, each
    exits 0 with its counters.

It ends with a summary table (step · status · key values). Any failed assertion exits nonzero. Ids are truncated;
no token, password or key is printed.

**Scheduler in staging (E5):** run **one** API instance with `SHARED_SCHEDULING_ENABLED=true` at R1. The `tick`
lease in `job_locks` makes extra instances skip, not double-run. Each process has a `jobs.<name>.enabled` club
parameter: `weekOpening`, `riskReview`, `waitlistFifo` (FIFO clubs), `paymentTimeouts` (`SINGLE_CLASS` clubs) and
`cleanup`. Admins switch them with `PUT /api/v1/jobs/{name}/switch`; the defaults are on. Staging has no test clock.
The gate's «processos actius a staging amb el rellotge avançat» is therefore demonstrated locally by `bin/e5-smoke`,
which runs the same `Job` beans under the same scheduler with the clock moved. In staging, check that `GET /jobs`
lists the processes and that `job_runs` records a `SCHEDULE` run of `cleanup` (daily at `jobs.dailyTime`) and of P1
at the Sunday opening. **E5-T06 adds no environment variable**: the aggregates, the demo scenario, the smoke and k6
use the existing settings. E5's only new variable remains `BOOKING_CALENDAR_KEY` (E5-T02, table above). k6 needs no
secret: the harness mints short-lived impersonation tokens on the disposable stack.

**One API instance and the local lanes (E5-T07):** R1 runs a single API instance (ADR-003). The local lanes only
bound contention inside one process; with more than one instance they protect nothing and the Mongo mechanisms below
are the guarantee. `core.concurrency.local-lanes` (default `true` in `application.yml`; Spring's relaxed binding also reads the
environment variable `CORE_CONCURRENCY_LOCALLANES`) switches them; it is an infrastructure setting, not a club
parameter, and no deployment needs to set it at R1. The lanes are fair in-process locks, one per aggregate
(an activity, a class, a dog, a member or a training slot), held around each retried booking transaction, so a
burst on one last seat queues instead of exhausting its retries. The guarantees that hold across instances are:

- S07 registrations: the `$inc registrationSeq` on the activity, `WriteConflict` → at most 3 retries (R-07-08).
- S08 holds, bookings and waiting lists: the `$inc` on `seat_locks` per class, at most 3 attempts (R-08-07).
- S09 free training: the partial unique index `training_active_seat` and the `$inc` of `Dog.trainingSeq` (always) and
  `Member.trainingSeq` (unit `MEMBER`), at most 3 attempts (R-09-06). The ring-slot sequences in `ring_slot_locks`
  (one document per ring and training grid slot) are written by a training booking (its own slot) and by every write
  that checks the ring's bookings (R-09-13): a ring block, a class moved onto the ring or an activity block touches
  each grid slot its range overlaps, and a ring deactivated or no longer open to free training touches every slot
  of the booking window. Bookings of different slots never share a document.

With the lanes off, a burst on one aggregate ends partly in `409 STALE_VERSION` (see the E5-T07 report for the
measured numbers). `core.transactions.retries{context,cause}` and `core.transactions.exhausted{context}` in the
Prometheus metrics show how often the Mongo mechanisms are reached. Do not scale the API out before a decision on
a shared lock or larger retry budgets.

Organizer-run checklist for Gate E5 (back); copy its evidence links into the organizer-owned `roadmap/ROADMAP.md`
after review:

- [ ] Run `bin/e5-smoke` twice successfully and retain both complete outputs (`roadmap/evidence/E5-T06/`).
- [ ] Book / cancel / waiting list in both modes / free training: summary lines for steps 4–8 and the FIFO lines of
  steps 1–2.
- [ ] P1/P2/P6/P7/P9 active with the clock advanced: the `(scheduler, SCHEDULE|CATCH_UP)` lines and the three
  `jobs:run` lines.
- [ ] k6 within the targets (ruling E28: 300 distinct members within 5 s, all 50 seats booked, hold p95 < 500 ms, flow
  p95 < 800 ms), and no overbooking with 50 simultaneous requests for the last seat: `bin/e5-perf` `RESULT` lines
  (distinct members, seats filled, answer histogram), plus the k6 threshold summaries. The load-test club
  (`seeds/club-perf.yaml`) exists only on the disposable stack; never apply it to staging or production.
- [ ] Front (organizer-run, E5-W…): screens 03/04/06/29/07/08/24 and the web E2E T-08-40 against the same seed
  (`seeds/README.md` → «E5 bookings…», with the test clock at `demoNow`).
- [ ] Run `./mvnw -q verify` (includes `MemberAggregatesIT`, `DemoScenarioSeedIT`) and repeat image mode with the
  reviewed published tag.
