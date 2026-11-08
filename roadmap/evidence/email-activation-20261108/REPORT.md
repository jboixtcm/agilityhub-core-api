# Public email activation, 8 November 2026

User authorization: enable emails and deploy, including the locally validated November 8 feedback.

The new explicit EMAIL_PUBLIC_DELIVERY_ENABLED opt-in removes only the hosted email tester allowlist. Ordinary delivery must also be true. Default false and invalid/contradictory values fail startup and preflight. Brevo sender/key and webhook authentication are still required. Hosted storage, SMS, push and payment restrictions are preserved. No HTTP contract change.

Operations pins email_delivery_enabled in the immutable manifest, verifies both resolved flags, and restores the selected release setting on rollback (older releases default false). Runtime/provider/queue checks and final production evidence are in SOFTWARE_CANIC/ops/evidence/2026-11-08-email-activation.

Validation at publication: all 5,021 unit tests passed, including the new configuration regressions. The clean integration/coverage run is in progress; deployment requires its success and the exact published API/web CI. The earlier clean run failed because Docker was stopped; Docker was started and clean verify repeated without skipped tests. Prior November 8 feedback evidence remains in ../feedback-20261108/.

Commands with exit 0:

`python3 -m unittest discover -s deploy -p test_release_preflight.py`

```text
.............................
----------------------------------------------------------------------
Ran 29 tests in 0.157s

OK
```

`python3 -m unittest ops.test_deploy_clubs (SOFTWARE_CANIC)`

```text
...............................................................
----------------------------------------------------------------------
Ran 63 tests in 0.522s

OK
```
