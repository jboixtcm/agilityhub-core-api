# Deployment recipes

## Release runbook (E11-T04; local proof now, E12-T01 executes the release)

The release topology is `deploy/compose.prod.yaml`: one authenticated MongoDB 7
replica set (`rs0`), one Core image selected by `IMAGE_TAG`, and Caddy. Only Caddy
publishes ports (80, 443 TCP/UDP). Mongo, Core, management and Caddy's admin/ask
listeners stay private. Each of the three front hosts mounts its own built `dist`
directory and proxies `/api/*`, `/oauth2/*`, `/.well-known/*` and `/connect/logout`
to the same Core process. The original Host and host-only cookies survive.

Requirements: Docker, Compose **2.24.4+** (the local override uses `!override`),
Python 3, curl and OpenSSL on the operator host; outbound access to GHCR, Docker Hub,
Debian/Ubuntu package repositories and PyPI when building the backup tool.
`deploy/.env.prod.example` is a complete placeholder inventory, not usable
credentials. Keep the populated file outside the checkout, mode 0600. Pass it
with `--env-file`; never paste `compose config` output from a populated file into
logs, tickets or evidence. It expands secrets. Core gets only its own credentials;
the backup principal and public age recipient go only to the `ops` service.
The private identity is supplied only to an explicit verification/recovery run.

### Prepare and deploy

1. At E12-T01, provision the dedicated droplet, firewall 80/443 plus restricted
   SSH, DNS for the four fixed hosts, disk/backup monitoring and private S3
   buckets. Select a free Docker subnet. Set `CADDY_IPV4_ADDRESS` inside it and
   `TRUSTED_PROXY_PATTERN` to that **exact** address, escaped as a Java regex.
   Retain the same IP across updates. Do not trust the whole Internet or forward
   arbitrary client `X-Forwarded-*` values. Confirm no other proxy is in front;
   adding one requires its own reviewed trust configuration.
2. Record the reviewed current and previous `sha-…` image tags and matching web
   artifact versions. Pull the target, copy all three built SPAs to versioned
   release directories and set `ID_DIST_DIR`, `CLUBS_DIST_DIR`, `ADMIN_DIST_DIR`.
   Verify each contains `index.html`. The checked-in `deploy/local/*/index.html`
   files are proof fixtures, never the release apps. GHCR access uses the recipe
   below; registry credentials remain in the host credential store.
3. Populate the environment inventory below, including stable backed-up keys.
   Generate distinct Mongo root/app/backup passwords; `MONGO_REPLICA_KEY` is
   base64 of 512 random bytes. Keep it stable on this one-node set. On a new
   volume Mongo's official entrypoint creates the root account, and the health
   bootstrap creates `rs0`, the app account (`readWrite` on its database only)
   and backup account (Mongo's built-in `backup` role only). Existing
   users are **never reset from env**; a stale app/backup password fails health.
   On a pre-round-2 installation, an operator must replace the stored backup
   user's roles with only `{role: "backup", db: "admin"}` before enabling cron;
   changing the bootstrap file alone does not revoke an existing `hostManager`.
   Mongo and the backup build share an immutable 7.0.41 digest. `pull_policy:
   never` prevents automatic replacement; explicitly pull that exact digest on
   first installation. Any Mongo/tool upgrade needs a fresh restore proof.
4. From the installed checkout, set the non-secret file path below. Validate
   without printing resolved credentials, build the ops image once, and on an
   existing deployment take and verify a pre-deploy backup. Do not run the local
   override or demo seed on the release host.

   ```sh
   export DEPLOY_ENV_FILE=/etc/agilityhub/core.env
   docker compose --env-file "$DEPLOY_ENV_FILE" -f deploy/compose.prod.yaml config --quiet
   docker compose --env-file "$DEPLOY_ENV_FILE" -f deploy/compose.prod.yaml build backup
   bin/backup-mongo
   # Supply BACKUP_AGE_IDENTITY from encrypted escrow for this command only.
   bin/restore-mongo --verify
   docker compose --env-file "$DEPLOY_ENV_FILE" -f deploy/compose.prod.yaml pull core caddy
   docker compose --env-file "$DEPLOY_ENV_FILE" -f deploy/compose.prod.yaml up -d --wait --wait-timeout 420
   ```

5. Smoke the deployed hosts: HTTPS health returns `UP`; each club host's
   `/api/v1/branding` identifies its intended tenant; all three SPAs load and
   deep links work; sign in and refresh once on a front host. Check the rotated
   `ah_refresh` has no Domain, with HttpOnly/Secure/SameSite=Strict and
   `Path=/oauth2/token`, and no refresh token is in JSON. Check the real provider
   flows listed below and one scheduler run. Keep responses containing tokens,
   cookies or personal data out of the operational log.
6. Release rollback: restore the previous `IMAGE_TAG` **and** previous three
   `*_DIST_DIR` values, then repeat `pull core`, `up -d --wait` and the smoke.
   Never use `down -v` on a release. If the new version changed persisted data
   incompatibly, an image rollback alone is unsafe: keep writers stopped and
   use the verified database recovery procedure below. Record the chosen
   recovery point and any data after it that must be reconciled.

The local proof boots Core with **prod**, fictional non-blank provider credentials
and real S3 adapters pointing at MinIO. Only the one-shot seed uses `local`.
Core's network is internal, so fictional credentials cannot initiate Internet
provider traffic. Only Caddy also joins an ingress bridge for Docker Desktop's
published loopback ports. Actual provider delivery and Stripe acceptance remain E12
prerequisites; production-profile construction and billing-key wiring are proven.

### Local rehearsal, including required failures

```sh
# Run through the host lock wrapper (heavy.sh):
heavy.sh bin/deploy-smoke
# Select an already available compatible published image tag if needed:
# heavy.sh bin/deploy-smoke --image-tag sha-<reviewed-commit>
```

`bin/deploy-smoke` creates a random Compose project, free loopback ports, a private
temporary env file and generated keys. It builds the backup helper and **local-only**
MinIO, applies the fictional Cànic/demo seed, validates Caddy, trusts the copied
Caddy root CA explicitly (no `curl -k`), checks health, branding, all three mounted
HTML fixtures/deep links with `Cache-Control: no-cache`, login/refresh rotation and rejection of the reused
cookie. It checks unauthenticated Mongo reads fail, then backs up to private MinIO
and restores latest and named archives. The live proof writes a transaction while
the dump is open, then proves snapshot counts exclude a later write. Missing
public recipient/bucket/restore identity, low work space, tampering and a restore
without `--verify` must fail. The project, volumes, plaintext and
credentials are removed even after a failed assertion. Other stacks are untouched.

When registry access is temporarily unavailable, `--skip-helper-builds` uses
already prepared helpers and refuses a backup image whose installed Python
source differs from the checkout or whose Mongo version is not 7.0.41. Keep the
pinned dependency image/build provenance; this option is a local rehearsal aid,
not evidence of a fresh dependency build. `--evidence-prefix <new-path-prefix>`
writes each verification command, exit and output separately without overwriting.

MinIO's former Docker Hub/Quay images were unavailable during this proof.
`deploy/local/Dockerfile.minio` builds the pinned
[upstream release](https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z)
from a checksum-verified source archive. This dependency is internal to the
fictional local proof, with no published MinIO ports. Production uses S3 directly.

Manual local configuration uses the same base file plus
`-f deploy/compose.prod.local.yaml`, `DEPLOY_LOCAL=1`, its own `COMPOSE_PROJECT_NAME`
and env file, generated credentials and `LOCAL_MONGODB_URI` with URL-encoded
username/password and `authSource=admin&replicaSet=rs0&directConnection=true`.
The `*.localhost` hosts use Caddy's internal CA. `local_certs`, the approval
fixture and MinIO exist only in the override; Core still uses production providers. This proves static serving, not a browser test of the
real web artifacts; the release smoke must use their actual builds.
The smoke keeps public URLs, Origin and OIDC callbacks at standard HTTPS port 443;
curl's `--connect-to` maps only the transport to the random loopback port. This
preserves production same-origin checks behind Caddy. A manual browser rehearsal
needs standard-port forwarding; a random port in Origin is a different origin.

The normal domain-admission policy rejects localhost (`HOST_RESERVED`). The
local-only `local-hosts` one-shot fixture therefore maps the two seeded
`*.example.test` domain records to `clubs.localhost`/`clubsadmin.localhost` and
activates that fictional club **before Core starts**. Only `id.localhost` is a
global CORS host in this rehearsal. This fixture is absent from production;
public club domains must pass E10 verification, with no direct database mapping.

### Daily encrypted backup, retention and recovery

`bin/backup-mongo` takes no arguments and can reach only this Compose project's
`mongo` service. `bin/restore-mongo --verify [object-key]` accepts no destination
URI and never restores over a running source. Both resolve `DEPLOY_ENV_FILE`
(default `deploy/.env.prod`) and optional `COMPOSE_PROJECT_NAME`/`DEPLOY_LOCAL`.
Missing key or bucket fails in host preflight before creating a container, dump or
object. Exit **0** means success; **2** is host configuration/usage failure;
**1** is a runtime failure (Docker may return its own nonzero startup status).
Alert on **every nonzero exit**, and on the absence of a daily `BACKUP_OK`.

The helper validates the public recipient and destination, estimates storage and
streams `mongodump --oplog --archive` through a bounded archive observer, gzip
and `age`. **No `fsync` or write lock** is used. The observer retains at most one
BSON document and reads the last timestamp in the captured oplog, which the
pinned tool copies with inclusive start/end bounds. It then counts every
collection using `aggregate` with `readConcern: snapshot` and that exact
`atClusterTime`. A later write cannot affect these counts. The count manifest and
dump are separately age-encrypted and packaged as `<timestamp>-<id>.tar`; there
is no plaintext dump or tar during backup. Mongo includes the start oplog entry
even when idle. Missing/unsupported framing, a changed collection catalog,
expired snapshot history, an oplog rollover or a pipeline failure aborts without
uploading. Avoid DDL/user/role maintenance during backups; ordinary application
writes continue. This is a count comparison, not content/index equality.

The implementation follows Mongo's [snapshot read contract](https://www.mongodb.com/docs/v7.0/reference/read-concern-snapshot/)
and the pinned tools' [inclusive oplog bounds](https://github.com/mongodb/mongo-tools/blob/100.18.0/mongodump/oplog_dump.go)
and [archive 0.1 framing](https://github.com/mongodb/mongo-tools/blob/100.18.0/common/archive/archive.go).
All counts must finish within retained snapshot history; if Mongo refuses the
snapshot, alert and retry after checking load/history capacity. Never replace a
failed snapshot count with a live count. The dump/encryption pipeline has a
15-minute watchdog that reaps both processes. SIGTERM cleans up too; no source
unlock or recovery action is ever necessary.

Generate an age key with `age-keygen` in a secure operator environment. Install
only its **public** `age1…` recipient as `BACKUP_AGE_RECIPIENT` in the cron env;
keep the `AGE-SECRET-KEY-1…` identity in off-host encrypted escrow. Supply it as
`BACKUP_AGE_IDENTITY` in the operator's environment only while running
`bin/restore-mongo --verify`; the runner passes it only to that container, never
to normal backup runs. Verification uses a separate S3 principal with
GetObject/ListBucket, supplied through a private `DEPLOY_ENV_FILE`; the normal
cron principal has only PutObject/ListBucket on its prefix. No private identity
belongs in the cron env or backup bucket.

The helper uses a **private anonymous disk volume** at `/work`, with mode-0700
per-run directories and a **1 GiB container memory limit**. `compose run --rm`
removes the container and its anonymous volume; normal exits also erase the work
directory. After a host crash, inspect and remove only that orphaned ops
container/anonymous volume before resuming; never share `/work` between runs.
Use encrypted Docker/host storage because restore creates plaintext. For backup
and restore, reserve at least `4 × sum(size + totalIndexSize) + 1 GiB` free space on
Docker's disk (minimum 1 GiB), accounting for two ciphertext copies and, on
restore, the decoded archive, Mongo data/indexes and journal. `collStats` supplies
the backup estimate across restorable collections, using only the built-in backup
role (`dbStats` is not permitted by that role). The uploaded metadata and encrypted manifest preserve it
for restore. Both refuse insufficient free space before creating the dump or
restored database. Growth can still exhaust storage mid-run: errors fail closed.
RAM does not scale with dump size: one BSON record, gzip/age buffers and an 8 MiB
single-worker S3 transfer; the isolated Mongo cache is 256 MiB. Reserve at least
1 GiB additional RAM for the helper and leave headroom for the live stack.

Provision retention as an **S3 lifecycle policy**, not cron deletion:
`deploy/backup/lifecycle.json` expires current/noncurrent objects after 30 days
and abandons incomplete multipart uploads after one day. An operator with bucket
administration privileges changes its prefix/days and applies it with
`aws s3api put-bucket-lifecycle-configuration --bucket <backup-bucket>
--lifecycle-configuration file://deploy/backup/lifecycle.json`, then reads it
back before enabling cron. The cron principal cannot change that rule or delete
objects; `deploy/backup/writer-policy.json` is its prefix-scoped IAM template
(replace the fictional bucket/prefix). Deny public access and require TLS in
production. Local MinIO proves lifecycle acceptance/readback; AWS permissions
and elapsed lifecycle expiry are release checks. Media buckets retain their own
versioning policies. Old format-1 `.tar.age` archives remain covered by prefix
retention but require the retained format-1 verifier and escrowed identity.

Daily Linux cron on a host configured to **UTC** (02:15 UTC, no DST gap; dedicated
operator, directory/log/lock writable, a monitored syslog error sink configured for failure; **A13 supersedes ADR-003's older weekly wording**):

```cron
15 2 * * * cd /opt/agilityhub/core && DEPLOY_ENV_FILE=/etc/agilityhub/core.env /usr/bin/flock -n -E 75 /var/lock/agilityhub-backup.lock bin/backup-mongo >> /var/log/agilityhub-backup.log 2>&1 || { rc=$?; /usr/bin/logger -p user.err -t agilityhub-backup "backup failed exit=$rc (75=lock busy)"; exit "$rc"; }
```

`--verify` downloads the latest completed object (or the specified key), rejects
unexpected tar members, fully authenticates/decrypts both encrypted members, and
runs `mongorestore --oplogReplay --stopOnError` in a **temporary standalone Mongo
inside the disposable ops container**, bound only to its loopback. TTL is disabled
there so expired historical rows cannot disappear during comparison. Every
restorable source collection, including admin users/roles, must have the same
count and the collection sets must match. `local` (replication) and `config`
(ephemeral sessions/transactions), `admin.system.keys` (internal cluster-time
keys regenerated by Mongo), views and `system.profile` are excluded from
the count comparison. Index definitions/data are restored by mongorestore;
count equality is not a byte-for-byte content or index equivalence claim. The
manifest is the **source at backup time**, not today's changing live database.

Disaster recovery at E12: stop Core/writers; retain the damaged volume; download
the selected archive with the read-only recovery S3 principal into private storage;
inspect the tar's two expected encrypted members, decrypt each with the escrowed
identity (`age -d -i <private-identity-file>`), then restore `dump.archive.gz` with
`mongorestore --gzip --archive=<file> --oplogReplay --stopOnError` into a **new**
isolated Mongo 7 volume. First run the supplied `--verify` on that object. Restore
the deployment secrets from escrow (especially OIDC/bank keys), initialize the
replacement replica set/authentication and verify its users before attaching the
new volume to the release Compose project. Restore media/versioned objects to the
same recovery point as needed, start Core and perform the deployment smoke.
This promotion is an operator-controlled release action; the script only verifies
in isolation. Do not clone production personal data into shared staging.

**ADR-003 §Backups deviation:** these archives contain Mongo data/users/index
metadata, **not** `dist`, Docker volumes, Caddy private keys or the environment
file, which ADR-003 included in the same package. Retain versioned web/image artifacts;
back up Caddy's persistent data/config and the env/key escrow separately into
an operator-owned encrypted off-host recovery store with separate access and
retention. Verify that escrow on D0/D+7; this script does not implement it. An archive without its OIDC/bank/backup keys is not a recovery plan.

### Keys and provider rotation

- Before any rotation: verified backup, encrypted escrow of old/new values,
  dependent clients identified, and one operator-controlled maintenance window.
  Never change two independent encryption keys at once.
- `OIDC_MASTER_KEY` encrypts the persisted signing ring, including RSA private
  signing keys. **There is no implemented master-key re-encryption procedure**:
  replacing it strands the ring. Retain it on normal deployments; a compromise
  requires a reviewed migration and forced reauthentication. Access signing-key
  rollover uses the application's ring/rotation mechanism, retaining old public
  keys through token expiry; do not delete `signing_keys` or confuse that rollover
  with changing the master key. Restore tests must retain the master key separately.
- `BILLING_BANK_KEY` is the only bank key (rulings E43 and E85):
  `BankAccountVault` encrypts the members' IBANs with it, `migration:apply`
  included, and only the SEPA remittance writer decrypts them. No online
  re-encryption tool is provided here: keep it for every retained record and
  backup, and rotate it only with a reviewed re-encryption. Never rotate it by
  just editing env.
- `MIGRATION_BANK_KEY` is retired (ruling E85): Core no longer reads it. No
  release imported bank data with it; a local database imported before needs a
  new `migration:apply` with `BILLING_BANK_KEY`. The production Compose stops
  forwarding it with E11-T04.
- `BILLING_SECRETS_KEY` encrypts the clubs' payment-provider secrets
  (`paymentProviders.STRIPE.webhookSecretEnc`, `secretKeyEnc`; ADR-009) with the
  club and field as associated data. Without it every Stripe webhook answers
  `401 WEBHOOK_SIGNATURE_INVALID`. Same rules as the bank key: escrow it, and
  rotate it only by re-entering every club's secrets.
- Changing `SIGNUP_CAPABILITY_KEY` invalidates outstanding signup capabilities
  and encrypted replay records; `BOOKING_CALENDAR_KEY` invalidates sent calendar
  links; `EMAIL_UNSUBSCRIBE_KEY` invalidates outstanding 30-day unsubscribe links.
  Schedule/reissue the affected links and restart Core/CLI together.
- VAPID keys are a pair. Changing them invalidates browser subscriptions; deploy
  the new public key, require re-subscription, and verify actual delivery. The
  public key is not a secret. Do not confuse it with SendGrid's public webhook
  verification key, which must match the provider's active event signature.
- Mongo app/backup/root passwords: change the relevant stored Mongo user with an
  authenticated administrative session, update its env value and recreate its
  consumers in the same window. Bootstrap does not rotate existing passwords.
  Root rotation needs the old root login; replica key-file rotation requires a
  coordinated Mongo restart. Smoke auth and take a new verified backup afterward.
- SendGrid/Twilio/S3 credentials: provision a replacement with the same least
  privileges, update and recreate consumers, prove a delivery/read/write, then
  revoke the old credential. Retain SendGrid webhook verification overlap only
  if supported by the provider/application. Rotate the Learn secret in concert
  with Learn (A30: it remains unconnected until the SaaS phase).
- Backup age pair: install the new **public recipient** for new backups, verify one
  with its separately supplied identity,
  keep every old identity in escrow until every archive encrypted for it has
  expired. Supply the matching identity only in the verification environment for an
  old named archive. Retention must not remove the only usable recovery point.

### Logs and D+7 support

`docker compose … logs --since 1h core` and `… mongo`/`… caddy` are the container
logs. Production services use Docker json-file rotation, **5 × 10 MB per
container** (size-based, no promised number of days). Caddy access logging is
off: query strings can contain signed capabilities. Do not enable header/body or
DEBUG logging on live identity/payment traffic. Keep the cron log outside the
repo, mode 0600; configure daily logrotate with 30 compressed files and an
alerting collector for failures before rotation. The Mongo audit/event retention
is governed by the existing product parameters, not these Docker settings.

At D0 and each day through D+7: confirm public HTTPS health and certificate dates;
check successful login and refresh on the front hosts; inspect Mongo/disk/memory
and backup duration, age of the last successful backup and nonzero cron exits;
verify a backup on D0 and D+7; check queues/scheduler failed runs and delivery
outcomes (not merely provider acceptance); exercise signup/booking/cancellation,
object upload/download/export and the enabled payment paths with approved test
accounts; review tenant/role isolation and support reports; keep the previous
release and matching artifacts ready. Record owners and evidence without personal
data, credentials or signed URLs.

### Release prerequisites and club domains

E12-T01 still owns SSH/firewall, real DNS/automatic public HTTPS, actual SPA
builds, GHCR release credentials, provider accounts/verified senders, S3 IAM/CORS,
Twilio recipients, VAPID delivery, SendGrid webhook signatures, E8 Stripe keys
and per-club webhook endpoints, bank settings/legal approval, and a real recovery
rehearsal on the droplet. This local task neither accesses nor deploys production.

**«Alta d'un domini de club» (E10-T03)** waits for E10-T02's
`GET /internal/domains/allowed?host=`. Caddy's on-demand TLS uses a private ask
bridge that translates its native `domain` query to `host` and forwards to
`http://core:8080/internal/domains/allowed`. Only a successful backend approval
allows issuance; the absent route currently fails closed. The bridge binds to
loopback inside Caddy and the core public host refuses `/internal/*`.
Register/verify ownership and the club's role-specific hosts and OIDC callbacks
before pointing CNAME records here. Add verified **admin** aliases to the
space-separated `CLUB_ADMIN_HOSTS` and recreate Caddy; other approved aliases
serve the member SPA. Check branding, SPA selection, HTTPS, cookie refresh and
revocation after each change. Do not enable an unconditional `ask` responder. Production fixes
`CADDY_ASK_UPSTREAM=core:8080`. Only the local override uses `ask-stub:8080`, which
approves exactly `approved.localhost` and records its received query. The smoke
obtains a trusted certificate for that host, checks `host=approved.localhost`
arrived at the stub, and confirms another hostname is denied.
See [Caddy's ask contract](https://caddyserver.com/docs/caddyfile/options#on-demand-tls).

### Environment inventory (source, purpose and rotation)

Every interpolated variable in the deployment examples appears below. Required
credentials are operator/provider values, never the placeholder text. Public keys
and URIs are included because they must remain consistent across recovery.

| Variables | Purpose, source and rotation |
|---|---|
| `IMAGE_TAG` | Reviewed GHCR `sha-…` tag from a green publish. Change to deploy/rollback together with web artifacts. |
| `MONGO_ROOT_USERNAME`, `MONGO_ROOT_PASSWORD` | Dedicated bootstrap/operations account, generated by the operator; update stored user and env together. Never used by Core. |
| `MONGODB_DATABASE` | Application database name chosen for this deployment; changing selects another database, it does not migrate data. |
| `MONGODB_USERNAME`, `MONGODB_PASSWORD` | Generated least-privilege application login in `admin`; rotate stored user and recreate Core together. |
| `MONGO_BACKUP_USERNAME`, `MONGO_BACKUP_PASSWORD` | Separate generated backup-only login; rotate stored user and backup env together. |
| `MONGO_REPLICA_KEY` | Base64 512 random bytes, operator-generated replica-set shared secret; Mongo recreates a private 0400 key file on tmpfs. Coordinated restart to rotate. |
| `DEPLOY_SUBNET`, `CADDY_IPV4_ADDRESS`, `TRUSTED_PROXY_PATTERN` | Operator-selected free bridge subnet, fixed proxy IP and exact escaped Java regex; update together and re-check forwarded scheme/address. |
| `AUTH_ISSUER` | Stable public ID HTTPS origin from the approved DNS plan. Changing invalidates issuer validation and client discovery. |
| `PLATFORM_PRIVACY_POLICY_VERSION`, `PLATFORM_PRIVACY_POLICY_URL` | Approved legal version and public URL; changing the version requires renewed consent. |
| `OIDC_MASTER_KEY` | Base64 32 random bytes generated once and escrowed; encrypts persisted RSA signing keys. No replacement without a reviewed re-encryption migration. |
| `OIDC_LEARN_CLIENT_SECRET` | Generated shared client secret configured in Learn and Core; rotate together. A30 leaves Learn unconnected, but prod configuration still requires a value. |
| `OIDC_LOGIN_URL` | ID SPA login URL on the issuer origin; update with the ID web build/DNS. |
| `OIDC_ID_WEB_REDIRECT_URI`, `OIDC_ID_WEB_LOGOUT_URI` | Exact registered ID callback and logout URI from the ID deployment. Coordinate URI changes with clients. |
| `OIDC_CLUBS_APP_REDIRECT_URI`, `OIDC_CLUBS_APP_LOGOUT_URI` | Exact member SPA callback/logout URI from its deployment. Coordinate URI changes with clients. |
| `OIDC_CLUBS_ADMIN_REDIRECT_URI`, `OIDC_CLUBS_ADMIN_LOGOUT_URI` | Exact admin SPA callback/logout URI from its deployment. Coordinate URI changes with clients. |
| `OIDC_AR_APP_REDIRECT_URI`, `OIDC_AR_APP_LOGOUT_URI` | Exact AR callback/logout URI from its client configuration; keep the existing defaults until that deployment is ready. |
| `OIDC_LEARN_REDIRECT_URI`, `OIDC_LEARN_LOGOUT_URI` | Exact Learn callback/logout URI; activate only when A30 permits the connection. |
| `CORS_PLATFORM_HOSTS` | Comma-separated exact platform hosts from DNS/client inventory; no wildcard. Front SPAs use same-origin proxies. |
| `SENDGRID_API_KEY` | SendGrid restricted mail-send credential; provision replacement, prove delivery, revoke old. |
| `MAIL_FROM_PLATFORM` | SendGrid-verified platform sender address; change only after sender-domain verification. |
| `SENDGRID_WEBHOOK_PUBLIC_KEY` | SendGrid Event Webhook verification public key (base64 DER/PEM); coordinate with provider signing-key change and verify a signed event. |
| `EMAIL_UNSUBSCRIBE_KEY` | Base64 32 random bytes; HMAC for 30-day unsubscribe links. Rotation invalidates outstanding links. |
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` | Twilio account/auth credentials from its console; validate replacement before revoking the old token. |
| `TWILIO_MESSAGING_SERVICE_SID` | Optional Twilio service ID; blank uses the club sender. Change with provider configuration. |
| `SMS_ALLOWED_NUMBERS` | Outside prod, allowed tester numbers only; empty means no real sends. Never populate with demo numbers. Not a prod recipient filter. |
| `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` | Generated P-256 pair in base64url; rotate as a pair and re-subscribe all browsers. Escrow private half. |
| `VAPID_SUBJECT` | Operator contact, `mailto:`/`https:`, supplied to push providers; update if contact changes. |
| `SIGNUP_CAPABILITY_KEY` | Base64 32 random bytes for signup capabilities and encrypted idempotency replays; rotation invalidates both. |
| `BOOKING_CALENDAR_KEY` | Base64 32 random bytes for booking calendar links; rotation invalidates previously sent links. |
| `MIGRATION_BANK_KEY` | Retired (ruling E85): Core no longer reads it, and `migration:apply` encrypts with `BILLING_BANK_KEY`. Leave it empty; E11-T04 removes it from the production Compose. |
| `BILLING_BANK_KEY` | Base64 32 random bytes, the only bank key (E43, E85): `BankAccountVault` encrypts the members' IBANs with it, the migration's included; required by production Compose and forwarded to Core (also the local seed); preserve and re-encrypt before rotation. |
| `BILLING_SECRETS_KEY` | Operator-generated, escrowed base64 32 random bytes, `ProviderSecretVault`'s key for the clubs' encrypted Stripe secrets (ADR-009); without it every Stripe webhook answers 401. Required and forwarded by production Compose and the local rehearsal; rotation means re-entering every club's secrets. |
| `EXPORT_S3_BUCKET`, `EXPORT_S3_REGION`, `EXPORT_S3_ENDPOINT` | Private export bucket/region from S3 provisioning; optional HTTPS endpoint (blank = AWS). Moving requires object migration. |
| `EXPORT_S3_ACCESS_KEY`, `EXPORT_S3_SECRET_KEY` | Restricted export IAM/provider key pair, Get/Put/Delete on `exports/`; overlap/revoke after a verified export. |
| `ATTACHMENT_S3_BUCKET`, `ATTACHMENT_S3_REGION`, `ATTACHMENT_S3_ENDPOINT` | Private attachment bucket/region; optional HTTPS endpoint. Preserve objects and signed upload CORS when moving. |
| `ATTACHMENT_S3_ACCESS_KEY`, `ATTACHMENT_S3_SECRET_KEY` | Restricted attachment/signup key pair (permissions below); rotate after real PUT/HEAD/GET/cleanup verification. |
| `SHARED_SCHEDULING_ENABLED` | `true` for the single release Core instance; maintenance can disable it temporarily. Restore it and check runs afterward. |
| `CORE_CONCURRENCY_LOCALLANES` | `true` for R1; infrastructure switch explained below, no secret or regular rotation. |
| `ACME_EMAIL` | Operator contact for Caddy/ACME certificate notifications; keep monitored. |
| `ID_HOST`, `CLUBS_HOST`, `ADMIN_HOST`, `CORE_HOST` | Four approved public DNS hostnames; default ADR-003 domains. DNS/certificate/client change, not a key rotation. |
| `CLUB_ADMIN_HOSTS` | Space-separated verified club admin aliases for SPA selection; default `unused.invalid`. Coordinate E10 domain verification/Caddy recreation. |
| `ID_DIST_DIR`, `CLUBS_DIST_DIR`, `ADMIN_DIST_DIR` | Absolute paths of versioned built SPA dist folders. Switch all with the matching backend tag; retain previous paths for rollback. |
| `BACKUP_AGE_RECIPIENT` | Public native age recipient from operator `age-keygen`; the only encryption material installed for cron. Change for future backups after verifying its escrowed identity. |
| `BACKUP_AGE_IDENTITY` | Private age identity, restored from encrypted off-host escrow into the operator environment only for verification/recovery; never in the cron env. Retain old identities through archive expiry. |
| `BACKUP_S3_BUCKET`, `BACKUP_S3_REGION`, `BACKUP_S3_ENDPOINT` | Pre-provisioned private backup bucket/region; optional HTTPS endpoint. Local override alone uses `http://minio:9000`. |
| `BACKUP_S3_PREFIX` | Deployment-specific nonempty relative prefix ending in `/` (default `mongo/`); restrict IAM and retention to it. |
| `BACKUP_S3_ACCESS_KEY`, `BACKUP_S3_SECRET_KEY` | Dedicated cron Put/List principal from S3 provisioning. Verification uses a separate private env file with Get/List credentials. Rotate after a backup and separate verified restore. |
| `DEPLOY_ENV_FILE`, `COMPOSE_PROJECT_NAME` | Operator host settings: private env-file path and optional isolated Compose project name. Set the same values for backup and deployment. |
| `DEPLOY_LOCAL` | Host runner switch (`1`) for the fictional override only; never set in release cron. |
| `LOCAL_MONGODB_URI`, `LOCAL_HTTP_PORT`, `LOCAL_HTTPS_PORT`, `SEED_PASSWORD` | Local-only authenticated URI, loopback ports and fictional seed credential generated by the smoke. No release values. |

Fixed wiring in the production Compose (not additional operator choices):
`SPRING_PROFILES_ACTIVE=prod`, `SERVER_PORT=8080`, `MONGODB_HOST=mongo`,
`MONGODB_PORT=27017`, `MONGODB_REPLICA_SET=rs0`, `MONGODB_AUTH_DATABASE=admin`;
Mongo's `MONGO_INITDB_ROOT_USERNAME`/`MONGO_INITDB_ROOT_PASSWORD` receive the root
values above. `CADDY_LOCAL_OPTIONS` is empty in production; `CADDY_ASK_UPSTREAM` is fixed to
`core:8080`. The local override sets `local_certs`, uses a fixture ask upstream,
sets `SPRING_DATA_MONGODB_URI`, keeps Core on `prod` and uses `local` only for
the seed (`EXPORT_LOCAL_DIRECTORY` and `ATTACHMENT_LOCAL_DIRECTORY`). It supplies MinIO's
`MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` from the generated S3 credentials.
`BUILDX_CONFIG` in the smoke is a disposable build-metadata directory, not a key.

Existing non-release options mentioned elsewhere in this document remain:
`EXPORT_SIGNING_KEY` (optional local export signatures, base64 32+ bytes; rotation
invalidates old links), `MIGRATION_ANONYMIZE_KEY` (one-use 32+ character HMAC secret
for the offline anonymizer, retain only if deterministic re-anonymization is
needed), `MONGODB_TEST_URI` (isolated test only), `CONSUMER_ENV_FILE`,
`CONSUMER_DATABASE`, `CONSUMER_PORT`, `CONSUMER_SCHEDULING_ENABLED`,
`CONSUMER_LOG_LEVEL`, `CORE_IMAGE`, `MONGO_PORT` (consumer/development tooling,
not the production compose), and `OIDC_SMOKE_BASE_URL`, `OIDC_SMOKE_EMAIL`,
`OIDC_SMOKE_PASSWORD` (fictional manual smoke only). `SMOKE_SMS_TO` is the explicit
tester destination for E7's optional real-SMS rehearsal, never a release setting.
Do not inherit any of these
consumer/test settings into release Core.

**Stripe providers (E8-T04):** Core uses each club's own account with the official
Stripe Java SDK. Store `secretKeyEnc` and `webhookSecretEnc` encrypted by
`ProviderSecretVault` using `BILLING_SECRETS_KEY` and the club/provider/field AAD;
never copy ciphertext between clubs or fields. `mode` must match the API key's
test/live prefix; configuration errors are 422. Local and test profiles resolve
`FakePaymentProvider`; staging and production resolve the SDK. There is no global
Stripe API key environment setting and no Connect account header. Key rotation
means re-encrypting the club's secrets; lookups bypass the config cache.

Register `/webhooks/stripe/{clubId}` for the Checkout, PaymentIntent, refund,
SetupIntent, detached-method and deleted-customer events listed in S12 R-12-21.
Receipts are signature-checked, stored uniquely, then processed transactionally;
failed work and durable provider commands retry internally. Stripe's default
24-hour Checkout expiration is separate from Core's shorter booking deadline;
late booking captures are refunded once per payment intent. `compose.yaml`
forwards the billing key. The administrative credential-entry UI remains a
platform-console concern; never put plaintext credentials into seed files.

`STRIPE_TEST_SECRET_KEY` / `STRIPE_TEST_WEBHOOK_SECRET` are optional inputs for an
explicit real-account rehearsal, not deployment settings consumed by Core.
Without them, fake-provider integration tests and SDK request/exception unit tests
run locally; acceptance against Stripe remains unproven. Sentry's environment
contract is still pending. RSA OIDC signing keys
live encrypted in Mongo, not in a separate env variable. Cloud/SSH/GHCR keys are
host credentials, provisioned and rotated by their respective providers and kept
out of the Core container.

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

`GET /api/v1/health` (E5-T27, INC-01 semantics) answers `200 {status: "UP"}` only
when a MongoDB `ping` answers within 1 s; otherwise `503` with the same body and
`status: "DOWN"`, plus a WARN log line with the request's `traceId`. It stays
public, global and tenant-free. The image's `HEALTHCHECK` (`curl -f` on that route)
and both compose files are unchanged, so a container whose database stops
answering turns unhealthy after the HEALTHCHECK retries, and healthy again once
MongoDB answers. A load balancer or uptime check on this route reads a `503` as
"the API cannot serve requests", not as a crash. The route never reads the
database for anything else, not even CORS (ruling E71): it allows the platform
hosts of `CORS_PLATFORM_HOSTS` only, and answers any other `Origin` with the same
envelope and no CORS headers. It never reads an `Authorization` header either
(E5-T27 round 3): decoding a bearer reads the signing key ring that
`OIDC_MASTER_KEY` persists in MongoDB, so a probe that sends one still gets its
`UP`/`DOWN` answer, and a malformed or expired bearer no longer turns it into a
`401`.

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
cookie paths or domains. Caddy preserves Host for these HTTP upstreams. The proxy
must overwrite client-supplied forwarding headers. Keep the management port and
direct upstream ports private. Register and verify each club host and its OIDC
callback before directing traffic to it.

### Client address behind Caddy (`TRUSTED_PROXY_PATTERN`, E3-T09)

The API runs with `server.forward-headers-strategy: native` (`application.yml`):
Tomcat's RemoteIpValve replaces the peer address with the client address of
`X-Forwarded-For`, and takes the scheme from `X-Forwarded-Proto`, **only** when the
peer matches `server.tomcat.remoteip.internal-proxies`, fed by the environment
variable `TRUSTED_PROXY_PATTERN` (a Java regex over the Caddy peers' IP addresses).
That client address is the key of the signup rate limits (R-04-20, per club and IP)
and the source of the consent `ipHash` (R-04-17).

- `staging` and `prod` refuse to start with an empty pattern
  (`TrustedProxyConfiguration`: «TRUSTED_PROXY_PATTERN is empty…»). Without it, every
  applicant would share Caddy's address: one limiter bucket per club (five bot
  submissions an hour would close the form for everyone) and one `ipHash`.
- Match only the proxy: the address Caddy connects from on the Compose network,
  e.g. `TRUSTED_PROXY_PATTERN=172\.18\.\d{1,3}\.\d{1,3}` for a `172.18.0.0/16`
  bridge (check it with `docker network inspect`), or `127\.0\.0\.1` when Caddy runs
  on the host. Never a pattern that matches arbitrary clients: a trusted peer can
  set any `X-Forwarded-For`.
- Caddy's `reverse_proxy` sets `X-Forwarded-For` and `X-Forwarded-Proto` itself; a
  client-sent value is only kept when Caddy's own `trusted_proxies` allows it, so
  leave `trusted_proxies` unset unless another proxy sits in front of Caddy.
- `local` and `test` keep the empty default (no proxy is trusted; the peer is the client).

The limiter matches the decoded, normalised path that Spring routes, so
percent-encoded or `//` variants of a signup route share its bucket.

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
under `attachments/` and `signup/`, plus `s3:DeleteObject` under `signup/`: the S15
cleanup job (`CleanupJob`, `jobs.retention.orphanUploadsHours`) deletes the signup
uploads that no submission claimed (R-04-08). A shared principal needs the union of
those permissions, restricted to those three prefixes. Public access must remain disabled.

Example attachment policy (replace the fictional bucket name):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {"Effect": "Allow", "Action": ["s3:GetObject", "s3:PutObject"],
     "Resource": ["arn:aws:s3:::example-attachments/attachments/*", "arn:aws:s3:::example-attachments/signup/*"]},
    {"Effect": "Allow", "Action": ["s3:DeleteObject"],
     "Resource": ["arn:aws:s3:::example-attachments/signup/*"]}
  ]
}
```

Configure the bucket's CORS allowlist for the actual HTTPS app/admin origins.
Attachment uploads use presigned `PUT` requests binding `Content-Type`,
`Content-Length` and `If-None-Match: *`; allow those headers and the `PUT` method
(the browser sets Content-Length). Every upload route, the signup ones included
(`POST /signup/upload-urls`, E3-T09), returns the signed `headers`; the client must
send them unchanged, or S3 answers 403 (and 412 to a second PUT on the same key). Allow `GET`/`HEAD` if the frontend fetches
signed downloads directly. Attachment links last five minutes. Export links expire
with the file seven days after READY; the existing worker deletes expired export
objects. Use lifecycle expiration on `exports/` as a crash-cleanup backstop with
slack beyond that seven-day READY window. Abort incomplete multipart uploads as
an additional backstop. Do not apply blanket expiry to `attachments/`: completed
attachments remain live. Orphan `signup/` uploads are deleted by the S15 cleanup
job (above); `attachments/` has no orphan-object tagging or cleanup worker yet:
reconcile unreferenced uploads against attachment metadata before deleting them. Bucket age alone does not distinguish abandoned and live uploads.

SEPA remittance files (S12 R-12-12, E8-T03) live in the export store under
`remittances/{clubId}/{period}/`: one pain.008 XML per generated remittance,
with the members' full IBANs. Extend the export principal to Get/Put/DeleteObject
under `remittances/` too. The api deletes a file only when the run that wrote it
did not commit; a rolled-back remittance keeps its file for the audit (R-12-14).
**Never apply lifecycle expiry to `remittances/`**: the files are the club's
record of what went to the bank. Downloads are presigned GETs valid for five
minutes, answering `Content-Disposition: attachment` with the stored
`application/xml` type, and each link issued is audited (`DATA_EXPORTED`).
Treat the bucket as holding bank data: private and encrypted at rest.

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

## Notifications: SMS, web push and unsubscribe links (E7-T02)

The S11 engine sends every notice through three providers, selected like the
e-mail sender (decision E13): `staging`/`prod` refuse to start without their
credentials, `test` uses in-memory doubles, `local` without credentials logs each
SMS (never the number or the text) and keeps pushes in memory. All values come
from the environment ([.env.example](../.env.example)); none is ever logged.

| Variable | Use |
|---|---|
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` | Twilio Messages API (HTTP basic). Required in staging/prod. |
| `TWILIO_MESSAGING_SERVICE_SID` | Optional: send through a Messaging Service instead of the club's `messaging.sms.senderId`. |
| `SMS_ALLOWED_NUMBERS` | Outside `prod` only: comma-separated E.164 numbers that may receive a real SMS. Any other is recorded as `SKIPPED_NOT_ALLOWED`; empty = nobody. The demo seeds use real-format Spanish mobile numbers, so keep it to the testers' phones on staging. |
| `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` | The product's P-256 key pair, base64url (uncompressed public point, 32-byte private scalar). The public key is published on `GET /branding` as `pushPublicKey`. Required in staging/prod. Generate once, e.g. `npx web-push generate-vapid-keys`; rotating it invalidates every browser subscription. |
| `VAPID_SUBJECT` | `mailto:` or `https:` contact sent to the push services. |
| `EMAIL_UNSUBSCRIBE_KEY` | HMAC key (base64, 32 bytes) of the signed 30-day «Deixar de rebre aquests comunicats» links of `CLUB_NEWS` mails (`List-Unsubscribe`). Required in staging/prod. |

The dispatcher sends right after each notice is stored and polls every 5 s for
retries (1, 5, 15, 60 min; the 5th failure is final). `SENT` is the last SMS state
(no Twilio delivery callback at R1). The real Twilio and VAPID sends are verified
on staging once the accounts exist.

**When a variable is missing (E7-T04):**

| Variables | `staging` / `prod` | `local` | `test` |
|---|---|---|---|
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` | the API refuses to start | the log sender: each SMS is recorded `SENT` with no provider call (never the number nor the text in the log) | in-memory double |
| `TWILIO_MESSAGING_SERVICE_SID` | optional: without it the club's `messaging.sms.senderId` is the sender | idem | idem |
| `SMS_ALLOWED_NUMBERS` (outside `prod`) | staging: empty = no real SMS at all; each blocked one is `SKIPPED_NOT_ALLOWED` | only matters with Twilio credentials, idem | — |
| `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` | the API refuses to start | the fake push sender keeps pushes in memory and records them `SENT`; `GET /branding.pushPublicKey` is `null` | in-memory double |
| `VAPID_SUBJECT` | required with the keys | — | — |

`bin/e7-smoke` (below) passes the Twilio credentials to its stack only when `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`
and `SMOKE_SMS_TO` are all set in the shell, and then sets `SMS_ALLOWED_NUMBERS=$SMOKE_SMS_TO`, so no demo number can
receive an SMS.

## E7 communications gate (backend)

Run from the API checkout with Docker, Compose, Python 3 and curl:

```sh
bin/e7-smoke
bin/e7-smoke     # a fresh stack per run: re-runnable
```

P4 `reminders` runs **every minute** on the single scheduler (switch `jobs.reminders.enabled`, on by default; at most one
`SKIPPED{DISABLED}` per hour while off) and reminds each booking once, at the lead its dog's owner chose
(`reminderMinutesBefore`, one of `messaging.reminderOptionsMinutes`), in instants (a DST change moves nothing). It has
no catch-up beyond its next minute: a reminder whose class has started is never sent. «Enviar comunicat» (`POST
/message-templates/{id}/send`) writes the new `announcements` collection (one document per batch, never deleted; created
on first use, index `{clubId, createdAt}` at start). **No new environment variable** besides the provider ones above.
Databases written before E7-T01 convert their notification rows once with `bin/core messaging:migrate-notifications`
(dry run first, then `--apply`; README «E7»).

Organizer-run checklist for Gate E7 (back); copy its evidence links into the organizer-owned `roadmap/ROADMAP.md`
after review:

- [ ] The channel × audience × preference matrix green for every R1 code: the counts of `NotificationMatrixTest`
  (engine, every R1 row × audience × channel × preference × modules × contact) and `NotificationActionsIT` (the real
  E5/E6 actions) in E7-T04's report.
- [ ] An announcement to 10 fictional members with log + `ANNOUNCEMENT_SENT` audit, N-08a edited at D9 reflected in
  the next notice in each recipient's language, and P4 at the configured lead: the `bin/e7-smoke` summary lines
  (`roadmap/evidence/E7-T04/`).
- [ ] Legacy SMS/PUSH intents converted to `SKIPPED_STALE` (`messaging:migrate-notifications`, E7-T02) and the
  `SMS_ALLOWED_NUMBERS` guard proven outside `prod` (E7-T02's tests; `bin/e7-smoke` with `SMOKE_SMS_TO`).
- [ ] Real SMS and real push received on devices (iOS PWA installed, Android): release items on staging with the
  Twilio account and the VAPID keys (@jordi).
- [ ] Run `./mvnw -q verify` and repeat image mode (`bin/e7-smoke --image <tag>`) with the reviewed published tag.

**E-mail sender name (E5-T28, decision E48).** Every club e-mail (system and S11
notices) is sent `From: <messaging.email.fromName> <address>`. The catalog default
of `messaging.email.fromName` is empty, which means the club's own name
(`Club.name`); no club name is a product default (white label). Set the parameter
only when a club wants another sender name; the seeds for new clubs
(`club-template-default.yaml`, `club-minim.yaml`) leave it unset. The address is
`messaging.email.fromAddress` on the club's verified domain, or the platform
address otherwise.

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
| `SPRING_PROFILES_ACTIVE` | `local`/`test` selects `FakePaymentProvider` (the compatible `FakeCheckoutGateway` subclass), which returns `https://checkout.test/<sessionId>` and makes no Stripe call. It is unavailable in staging/prod. E8-T04 resolves the real SDK there from each club's encrypted credentials; this introduces no public fake-completion route. |

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

**E6 processes (E6-T04):** the same single scheduler instance runs P8 `class-finishing` **every minute** and P3
`no-show-notices` **once a day per club**, at `messaging.noShowNoticeTime` (08:00) in the club's own `timeZone` (a club on
another time zone gets its batch at its own 08:00). Their switches are `jobs.classFinishing.enabled` and
`jobs.noShowNotices.enabled` (on by default). P3's catch-up is unlimited: after a stop it notifies every pending
no-show in one late run. P8 records at most one `SKIPPED{DISABLED}` per hour while switched off. No new environment
variable. In staging, check that `GET /jobs` lists both rows and that `job_runs` records a `SCHEDULE` run of P8 every
minute and of P3 at 08:00 local. Round 2 (ruling E65): a P3 run has at most **one** item (`NoShowNoticeBatch`, keyed
by the club's date) whose detail lists the claimed attendances; P8's waiting-list step runs only with the `WAITLIST`
module and sweeps by the class's own start; N-19's e-mail follows the member's `PERSONAL` preference. The plain
`seed:demo --club=canic --seed=42` now anchors the demo on the first Monday on or after the run date (see
`seeds/README.md`). The gate E6 (back) is rehearsed locally with the clock moved:

```sh
bin/e6-smoke
bin/e6-smoke     # a fresh stack per run: re-runnable
```

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
  that checks the ring's bookings (R-09-13): a ring block, a class moved onto the ring, a week validation (the slots of
  its future DRAFT classes) or an activity block touches each grid slot its range overlaps, and a ring deactivated or
  no longer open to free training touches every slot of the booking window. Bookings of different slots never share
  a document. The collection is created at startup (`schedulingCollections`). The S05 ring change (at most 3
  attempts) and the S06 writers (at most 6) retry a `WriteConflict` or a `DuplicateKey` with a 50–150 ms backoff
  (E5-T15), counted under the `catalogs` and `scheduling` contexts.

**`ring_slot_locks` retention (E5-T17, S15 R-15-19):** every touch of a slot sets `expiresAt` to 7 days after the
slot's `startsAt`, and `schedulingCollections` ensures the TTL index `ring_slot_lock_ttl` (`{expiresAt: 1}`,
`expireAfterSeconds: 0`) on every start. An index with that name but another key or TTL makes the start fail: drop it
(`db.ring_slot_locks.dropIndex("ring_slot_lock_ttl")`) and restart. P9 (`cleanup`) reports the expired documents that
are still waiting as `ttlPendingRingSlotLocks`.

**One-off backfill** for any database that ran E5-T07…E5-T16 code (staging, local stacks, dumps taken from them): the
documents written then have no `expiresAt`, so the TTL never removes them and P9 does not count them. Run once, after
deploying E5-T17 or later; a second run changes nothing:

```bash
docker compose --env-file .env.consumer -f docker-compose.consumer.yml exec -T mongo mongosh --quiet agilityhub --eval 'db.ring_slot_locks.updateMany({expiresAt: {$exists: false}}, [{$set: {expiresAt: {$add: ["$startsAt", 604800000]}}}])'
```

On another deployment, run the same `updateMany` with `mongosh` against its database. `604800000` ms is 7 days.

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


## Security hardening (E11-T03)

CORS uses exact origins, with HTTPS outside `local`. Configured
`CORS_PLATFORM_HOSTS` work on both the API and identity chains. A registered club
origin can call a global core/identity host; on a host belonging to a club, that
origin must belong to the same club. Unknown, removed, pending (outside local),
malformed and other-club origins receive no allow-origin header. Preflights have
a 300-second cache. Health only accepts configured platform origins without
looking up a club; other origins still receive its ordinary UP/DOWN response.
Cookies remain same-origin (A1); CORS never enables credentials or wildcard
origins. All SPAs receive nosniff, strict-origin-when-cross-origin, DENY and HSTS
from Caddy. The web build owns its CSP. API JSON, errors, OAuth and files keep the
Spring Security baseline and E71 download policies. API HSTS is enabled only for
HTTPS requests under `prod`.

The per-instance token buckets below use the injected clock and reset at their
interval boundary. R1 is a single instance (ADR-003). Every refusal returns the
catalog `429 RATE_LIMITED`, `Retry-After` in seconds, and writes `SecurityEvent`.
Only trusted proxy addresses can set the effective client IP. Keys contain an
IP, an internal account ID, or a SHA-256 digest of the normalized token username;
never a plaintext e-mail. Signup keeps the existing club `signup.rateLimit`
parameter and its recipient limits. Infrastructure settings can be overridden by
Spring's environment binding, e.g. `CORE_SECURITY_RATELIMITS_ANONYMOUS_CAPACITY`.

| Configuration key (each has `.capacity` and `.period`) | Default | Key / scope |
|---|---|---|
| `core.security.rate-limits.token` | 30 / 1m | IP, shared token + magic-link entry point |
| `core.security.rate-limits.token-account` | 10 / 1m | Digest of normalized token username, across IPs |
| `core.security.rate-limits.magic-link-email` | 10 / 1h | Digest of normalized e-mail (existing magic-link rule) |
| `core.security.rate-limits.magic-link-ip` | 60 / 1h | IP (existing magic-link rule) |
| `core.security.rate-limits.branding` | 120 / 1m | IP |
| `core.security.rate-limits.public-routes` | 60 / 1m | IP for the public API |
| `core.security.rate-limits.me` | 600 / 1m | Account across IPs and `/me` children |
| `core.security.rate-limits.anonymous` | 120 / 1m | IP; discovery/authorize/logout, signup form, manifest, country/postal lookup, unsubscribe, checkout polling |
| `core.security.rate-limits.webhook` | 600 / 1m | IP; provider signature validation is still required |
| `core.security.rate-limits.signed-file` | 120 / 1m | IP; signed upload/download and calendar links |
| `core.security.rate-limits.handoff` | 30 / 1m | Both IP and authenticated account; the route still requires a bearer |

Mongo connection, server-selection and pool bounds apply to the actual client,
including URI configuration. The client-wide `timeoutMS` must remain unset
(validated at startup): the driver would otherwise apply it to an entire
transaction, breaking a legitimate seed or job (E85). A servlet filter binds
`operation-timeout` to HTTP database operations and sessions only. Each retry
opens its own bounded transaction; no deadline leaks back onto a reused thread.
Session-bound reads keep their session deadline even in after-commit callbacks;
a database override at that point is rejected by the driver.
Exports (`export`, `data-export`, export downloads) and manual job triggers
follow the background rule: neither their database operations nor their sessions
receive an HTTP deadline. A manually triggered job runs under the same timeout
policy as a scheduled one; there is no arbitrary upper runtime. Seeds, migration
CLI, scheduled jobs and other background work also have no HTTP deadline. Their socket timeout defaults to zero;
set a finite fallback only when it accommodates the longest background operation.
The independent health ping retains its stricter 1-second budget. Connection,
selection, pool and HTTP timeouts must be positive; only the background socket
fallback accepts zero. Backup/restore tools keep their own execution settings.
This uses the driver's [database and session timeout overrides](https://www.mongodb.com/docs/drivers/java/sync/current/connection/specify-connection-options/csot/).

| Configuration key | Environment variable | Default |
|---|---|---|
| `core.mongo.connect-timeout` | `MONGO_CONNECT_TIMEOUT` | 2s |
| `core.mongo.socket-timeout` | `MONGO_SOCKET_TIMEOUT` | 0s (background fallback; HTTP uses CSOT) |
| `core.mongo.server-selection-timeout` | `MONGO_SERVER_SELECTION_TIMEOUT` | 2s |
| `core.mongo.operation-timeout` | `MONGO_OPERATION_TIMEOUT` | 10s (ordinary HTTP database operations/sessions) |
| `core.mongo.max-pool-size` | `MONGO_MAX_POOL_SIZE` | 100 |
| `core.mongo.pool-wait-timeout` | `MONGO_POOL_WAIT_TIMEOUT` | 2s |
| `sentry.dsn` | `SENTRY_DSN` | Empty: disabled. Obtain the release project's DSN at E12-T01. |

`prod` and `staging` use Spring Boot structured JSON console logging; local/test
keep readable output. The `structured-logs` profile opts the local Caddy proof
into the same structured appender; a custom Logback file must explicitly select
Boot's `structured-console-appender.xml`. One request filter establishes `traceId` and clears the
MDC on every exit. Authentication and tenant resolution enrich `accountId` and
`clubId` from trusted state. Unavailable IDs are `-`; no unverified header/JWT
claim becomes an identity. UUID IDs bypass personal-data patterns in these three
trusted fields so `INTERNAL_ERROR.traceId` matches the logged ID exactly.
`LogPrivacy` is the common exclusion list for e-mails, phones, IBANs, IPs and
credential-shaped text. Exception output keeps types and code locations without
exception messages, which can contain full Mongo documents or request bodies.
The JSON field set excludes arbitrary MDC values. No filter logs request bodies,
headers or query strings. Do not enable raw HTTP/Mongo debug payload logging.

Sentry uses `send-default-pii: false`, no request-body capture, no tracing or log
streaming. Its `beforeSend` builds a closed copy: request/user/contexts,
breadcrumbs, arbitrary extras and exception messages are omitted; messages are
scrubbed with the same exclusion list, stack locations and the three IDs remain.
The local deployment leaves `SENTRY_DSN` empty. Production telemetry is enabled
only when its DSN is configured.

| Telemetry configuration key | Value / scope |
|---|---|
| `sentry.send-default-pii` | `false` in every profile |
| `sentry.max-request-body-size` | `none` in every profile |
| `sentry.traces-sample-rate` | `0.0` (no transaction tracing) |
| `sentry.enable-logs` | `false` (no separate log streaming) |
| `logging.structured.format.console` | `com.agilityhub.core.configuration.PrivacyLogFormatter` in `prod` / `staging`; unset locally |

CI audits packaged dependencies with pinned Trivy `rootfs`, which inspects nested
JARs; `fs` mode instead targets build manifests and silently ignores JARs. The
scan fails if its Java inventory lacks Spring Web, Spring Security or the Mongo
driver. This covers packaged dependencies without a separate NVD API key. Both architecture images are built,
scanned for every secret and fixable CRITICAL vulnerability, then those exact
images are pushed. Lower severities and unfixed findings appear in the job
summary; secret matches never do. Only after both succeed is `:main` / `:sha-*`
published as a multi-architecture manifest. The amd64 job runs the authenticated
Compose topology through `bin/deploy-smoke --security-only` and proves that the
same smoke rejects an image whose entry point immediately fails. This mode uses
E11-T04's local hosts and CA, skips backup helpers, and still cleans up on failure.
The full E11-T04 backup rehearsal remains `bin/deploy-smoke`.

Gitleaks scans history with its default rules. Evidence exceptions match only
UUID trace/idempotency identifiers, notification deduplication keys and the
literal pair of empty attachment-key assignments; they are scoped to individual
rules so directory scans still read those files. `python3
bin/security-secret-policy-test.py` proves a populated example key and a
credential beside a trace still fail. The scanner's official configuration
reference is https://github.com/gitleaks/gitleaks#configuration.

Run mutation analysis under the shared host lock with `./mvnw -q -Pmutation
test-compile org.pitest:pitest-maven:mutationCoverage`. It targets bookings and
identity (payments joins at E11-T06), uses four workers (each capped at a 2 GiB
heap and two active JVM processors) and keeps incremental history in `.local/pitest/history.bin`. The history and downloaded scanners are
ignored local build caches; publish score summaries and sanitized evidence,
not the caches. Six historical Testcontainers unsubscribe-link findings use exact
commit/file/rule/line entries in `.gitleaksignore`; no future value is exempted.
The profile allows 30 seconds of fixed fixture headroom plus twice the baseline
test duration (PIT `timeoutConstant` / `timeoutFactor`). A timed-out mutant is reported explicitly; it is not a passing
ordinary test run.
