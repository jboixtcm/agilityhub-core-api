# Activity-publication email hold

Jordi requested disabling activity-publication email in Cànic production while retaining the in-app notice. The operational setting `ACTIVITY_PUBLICATION_EMAIL_DISABLED_CLUB_IDS` blocks only N-32a EMAIL for the named club IDs, at creation and before queued dispatch. APP, other codes and other tenants are unchanged; terminal skips never replay after removing the hold.

The default empty list preserves existing behavior. No API contract, business parameter or template changes. Deployment records pin the setting for activation and recovery.

Validation: 53 integration tests, including four focused regressions, passed with no failures or skips; 65 operation tests passed. The exact command and test counts are in `validation.json`. Full CI and production readback remain required before activation.

CI uncovered an existing upload-test race: urllib can report a reset while still writing an oversized request, before reading the proxy rejection. The test now uses curl to read the response during upload. All size limits, status/envelope checks and upstream-byte assertions remain unchanged. The complete 12-test deployment proof passed in a Linux Docker runner. macOS host networking cannot run this proof directly.
