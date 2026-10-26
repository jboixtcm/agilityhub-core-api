# Internal deployment on the existing encrypted volume

The internal team deployment may explicitly use the existing provider-encrypted volume for files. Public production keeps the default S3 adapters. This mode does not activate `local`/`test`, fake providers, public file serving or filesystem access through arbitrary URLs.

Required configuration:

```dotenv
SPRING_PROFILES_ACTIVE=prod
HOSTED_DEMO_ENABLED=true
EMAIL_DELIVERY_ENABLED=false
FILE_STORAGE_MODE=filesystem
FILE_STORAGE_ROOT=/storage
FILE_STORAGE_SIGNING_KEY=<base64 encoding of at least 32 cryptographically random bytes>
```

The deployment operator must verify the encrypted DigitalOcean Volume is mounted, bind its dedicated application directory to `/storage`, and provision that directory with mode `0700`, owned by the API container UID. Inside it, `.clubs-private-storage` must be a regular file with mode `0600` containing `clubs-private-storage-v1` (a trailing newline is accepted). The application requires an absolute path without symlink components and checks the directory, marker and permissions before file I/O. A marker is an explicit provisioning check, **not proof of encryption or of the host mount**; the deployment preflight must verify the actual volume.

The API creates private `exports/`, `attachments/` and `migration-reports/` directories. Export files retain their existing expiry checks and cleanup jobs; remittances retain their existing retention rules. Attachments retain the existing upload grants, size/type restrictions, immutable uploads, tenant ownership and erasure lifecycle. Signed attachment and signup URLs bind the file, HTTP method and expiry. The persistent signing key keeps valid links usable after restart; it belongs in the private deployment environment and must be retained securely for recovery.

Production migration reports use a separate volume adapter. The existing role-checked and audited report endpoint issues a signed API path valid for at most five minutes. Its file route verifies the HMAC before opening the signed club, then rechecks the persisted production run and original 30-day expiry. The existing retention job physically deletes the report file before marking it purged. Reports have no historical filesystem versions, and symlink paths are refused. Monitor the existing retention job; unlike S3 mode, there is no independent provider lifecycle rule.

Do not expose `/storage` through Caddy or any static web root. Local HTTP file routes return only valid signed files, with private/no-store download behavior and the existing sandbox content policy. No S3 credentials or SDK clients are required in this explicit mode.

The database backup alone does not cover these files. The deployment backup must include the complete private storage tree and database from a coherent stopped application state, encrypt the archive, enforce retention, and verify an isolated restore. A backup on the same volume does not survive loss of that volume/account; an independently retained encrypted copy remains necessary for that failure scenario. Backup policy and restore evidence belong to the deployment runbook.

Before public launch, explicitly switch to reviewed public production storage and restore/verify all persisted files; removing `HOSTED_DEMO_ENABLED` while leaving filesystem mode causes startup to fail.
