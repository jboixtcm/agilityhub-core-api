# Operational Flow import worker

This worker discovers authenticated Flow FCI / RSCE results and submits only uniquely licensed club dogs through the existing preview/confirmation API. It runs separately from booking schedulers. The same date-range command is used for the requested historical backfill; `--weekly` selects the previous three completed Friday–Sunday weekends, allowing delayed publication to be discovered. If the last completed import is older, the range expands to recover missed weekends.

Runtime: Python 3.12+, websocket-client 1.9.0, beautifulsoup4 4.14.2 (soupsieve and typing_extensions dependencies pinned in the deployment artifact). Run `python3 -m unittest discover -s ops/flow_imports -v` with these dependencies installed.

Configuration is a private JSON file containing `clubId`, `apiOrigin`, `apiAccountsFile`, `timeZone`, `stateDirectory`, `directoryProbeUrl`, and optional `requestDelaySeconds`. The deployment uses a dedicated unprivileged Unix user and systemd `LoadCredential=api-access:<existing private operational account file>`; no account or privilege is created in the application. The API origin must resolve to the configured club with STATS enabled. The code allows only competition routes, the club read, and authentication/revocation.

The private state directory lives on the installation's guarded encrypted data volume. It contains the renewed Flow cookie, checkpoints, and aggregate reports. These files must never be committed. The owner-entered Flow password is not retained. Revoked/invalid sessions fail closed and require a fresh owner login. Normal responses renew the cookie; the observed provider Max-Age is 60 days. This does not guarantee the provider will never revoke a session.

Each source is checked against current import history, again after preview, before confirmation. Existing/manual snapshots are never replaced. A concurrent later insertion is rejected by Core's source revision check. A lost confirmation response is recovered by checking history on the next execution. A private lock prevents overlapping workers. Unmatched results are removed before uploading the HTML; raw provider rows are not written to disk. Failed sources remain retryable. Zero-match checkpoints are invalidated when the club's licence set changes.

Only standard G1/G2/G3 AG/JP result pages are currently represented by the competition model. Other grades/disciplines are reported as unsupported. Unknown official point counts/classes are errors, never guessed. The adapter normalizes explicit `1P`, `1PA`, `1PV` to their classes. Core additionally preserves the observed combined `1PA / 1PV` as wire value `PA / PV`; both counters increase, but the run and trial count once. Existing clients display the wire string directly.

Run examples (the default is preview only):

```sh
python3 worker.py --config /private/config.json --from-date 2026-01-01 --through-date 2026-11-15
python3 worker.py --config /private/config.json --from-date 2026-01-01 --through-date 2026-11-15 --commit
python3 worker.py --config /private/config.json --weekly --commit
```

Install the timer only after the corrected Core image and real preview pass. Schedule `Mon *-*-* 06:00:00 Europe/Madrid`, `Persistent=true`, with bounded failure retries. The service must stop before a coordinated Core deployment and resume from checkpoints afterwards. Production activation and actual imported counts are recorded separately in the workspace operations evidence; source implementation does not establish deployment.
