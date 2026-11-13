# November 13 direct feedback

Jordi explicitly requested the November 12-2/13 corrections and freely editable manual class times. No roadmap task/status or Organizer verification was changed.

## Changes

- Manual class create/edit accepts whole-minute start and independent end times, without requiring a generated-week band or a multiple of the planning grid. Opening hours, overlap, capacity and optimistic-version checks remain. Template/ring grid rules are unchanged; the club’s five-minute setting is configured separately.
- Billing-period reads return the saved invoice preview. The same mapper serves simulation creation and period reads. Reading it never creates invoices, collections or remittances.
- Document that an empty pack may have no expiry.

## Validation and publication gate

`./mvnw -q clean verify` is running locally. Surefire completed 5,021 tests with zero failures/errors/skips. Integration-test progress and changed-class results are in `local-validation-progress.json`. The running full log is retained privately in the release operations folder to avoid publishing a changing file. `clean-verify-progress.log` contains the last 40 lines at publication preparation. This report does not claim a full local pass. The exact published commit must pass the complete CI Build and tests job, both image scans, dependency/secret checks and image publication before deployment. Final local/CI evidence is recorded in SOFTWARE_CANIC/ops/evidence/2026-11-13-josep-feedback.

`bin/openapi-snapshot` completed with exit 0 (openapi-final.log); the web adopts the generated snapshot and generated TypeScript, not handwritten contract types.

Earlier attempts: the contract round-trip exposed a missing NON_NULL serialization annotation on the optional preview; corrected. A subsequent clean run hit the existing timing-sensitive password test under concurrent host load. The current complete rerun has passed all 5,021 unit tests without changing that test. Logs of both attempts are retained.

## Data investigation

The reported identity passes the current production identity check; no speculative validator change was made. The reactivation case is a rejected, never-validated signup; the UI explains the required signup/validation path rather than bypassing it. The booking render failure came from a null expiry in an empty pack, not the future leave date. Private records and credentials are not included in this repository. No receipts, remittances or applicant accounts were generated for verification.
