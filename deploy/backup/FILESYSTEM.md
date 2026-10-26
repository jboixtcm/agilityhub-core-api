# Internal-review filesystem backup

This optional backend supports the explicitly enabled hosted-demo policy on the production host. Normal releases keep `BACKUP_STORAGE_MODE=s3`. No S3 account is needed for the internal filesystem mode. This does not turn a copy on the server into an off-host recovery copy.

## Configuration

Set these values in the private deployment environment:

```dotenv
HOSTED_DEMO_ENABLED=true
FILE_STORAGE_MODE=filesystem
BACKUP_STORAGE_MODE=filesystem
BACKUP_LOCAL_DIRECTORY=/backup
BACKUP_FILES_DIRECTORY=/storage
```

`deploy/compose.filesystem.yaml` binds the private encrypted-volume application directory to Core `/storage` and backup `/storage:ro`; a separate private encrypted-volume directory mounts at backup `/backup`. Both roots must exist without symlink ancestors and deny group/other access. The application root carries the mode-0600 `.clubs-private-storage` marker containing `clubs-private-storage-v1`. The archive root must not be inside the application root or vice versa. The normal backup environment contains the public `BACKUP_AGE_RECIPIENT`, plus the restricted Mongo backup username/password. Keep the private age identity and application encryption/signing keys in separate encrypted off-host escrow.

The runner first resolves the base Compose document privately, selects the filesystem override, then validates the final configuration. Local proof profiles cannot be combined with this production-internal mode.

## Consistency, storage and verification

Stop the Core service before capturing a filesystem backup. The runner independently refuses a backup while any Core container is running and only then passes the quiescence flag into the helper. Other application CLI writers must also be stopped for the duration. The outer deployment tool is responsible for resuming Core on either success or failure. A backup does not alter the source database or files.

The Mongo dump retains the existing streaming public-key encryption, captured oplog timestamp and source snapshot counts. App files are read into a tar stream that feeds `age` directly, without a plaintext file archive. The encrypted manifest records every relative directory/file, file length and SHA-256; inventory is checked again after capture. Symlinks, hardlinks and special files are refused. The resulting format-3 archive contains only `manifest.json.age`, `dump.archive.gz.age` and `files.tar.age`. It is stored immutably as `mongo/<UTC timestamp>-<random UUID>.tar`, mode 0600, with an atomic publish after fsync. Existing archives are never replaced or deleted.

`bin/restore-mongo --verify <exact-key>` decrypts and verifies the selected archive inside the helper's private temporary work storage. It rejects unsafe paths, duplicate entries, links, unexpected files and checksum/size/type mismatches. It restores Mongo into a disposable loopback-only instance, compares all collection counts at the recorded timestamp, and reports `FILES_OK` and `RESTORE_OK`. It never restores over the live database or application root. Filesystem mode refuses old S3 format-2 archives, because those do not contain the application files. S3 mode retains its existing format and behavior.

Before a deployment can be accepted, copy that exact verified encrypted archive over strict SSH to private off-host storage and compare its SHA-256 on both ends. Preserve the configuration-key escrow separately: the file archive intentionally excludes deployment environment secrets. The deployment tool must record the off-host copy proof. A local `BACKUP_OK`/`RESTORE_OK` alone does not prove that the backup survives loss of the host. There is no automatic local retention deletion; inspect disk capacity and retain local archives until off-host copy and recovery evidence are established.

## Local evidence

`test_backup.py` retains the S3 regression tests. `test_filesystem_backup.py` adds private-root/mode checks, immutable local archives, traversal/link/tamper refusal and a real age encrypt/decrypt round trip. `deploy/test_backup_runner_filesystem.py` proves override selection and refusal while Core runs.

Run `python3 deploy/backup/filesystem-proof.py` through the shared heavy-work wrapper. It creates an isolated internal Docker network, authenticated disposable Mongo replica set, fictional records/files and a generated age identity. It exercises the real encrypted backup and named isolated restore, then removes only its own named containers/volumes/network. It uses the already prepared `agilityhub-backup:2` helper image (override with `BACKUP_PROOF_IMAGE`) and mounts the current `backup.py`; it contacts no external provider. Evidence for 26 October is in `roadmap/evidence/2026-10-26-volume-backup/`.
