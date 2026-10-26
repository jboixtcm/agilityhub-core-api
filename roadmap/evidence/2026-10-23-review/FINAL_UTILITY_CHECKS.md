# Final utility regression evidence

Final focused invocation evidence for the changed review utilities and the scanner policy. The original attempts remain alongside these logs.

## final-trace-regression

Command: `python3 bin/test-spec-trace.py`
Exit: 0

Last 40 lines:
```text
...
----------------------------------------------------------------------
Ran 3 tests in 0.006s

OK
```

## final-e8-smoke-regression

Command: `python3 bin/test-e8-smoke -v`
Exit: 0

Last 40 lines:
```text
test_T_12_11_checks_the_bank_header_and_rcur (__main__.E8SmokeTest.test_T_12_11_checks_the_bank_header_and_rcur) ... ok
test_T_12_15_configured_keys_use_the_real_leg (__main__.E8SmokeTest.test_T_12_15_configured_keys_use_the_real_leg) ... ok
test_T_12_15_refuses_live_keys_before_an_external_call (__main__.E8SmokeTest.test_T_12_15_refuses_live_keys_before_an_external_call) ... ok
test_T_12_21_replays_the_fetched_event_with_its_body_signature (__main__.E8SmokeTest.test_T_12_21_replays_the_fetched_event_with_its_body_signature) ... ok

----------------------------------------------------------------------
Ran 4 tests in 0.008s

OK
```

## final-secret-policy

Command: `python3 bin/security-secret-policy-test.py --gitleaks /var/folders/xn/c1yrcx212h18679n6kwf4kxh0000gn/T/agilityhub-secret-review-isl2mljr/gitleaks`
Exit: 0

Last 40 lines:
```text
PASS UUID trace/idempotency identifiers and empty example variables
PASS the exact fictional export filename argument
PASS a SHA-256 digest beside a key-named path in an evidence manifest
PASS credentials beside trace/dedup/filename, non-UUID key, populated example key, changed filename and a non-digest manifest value all fail
```
